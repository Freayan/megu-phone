package com.megu.phone.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * All network access lives here.
 *  - Browse + details metadata: AniList public GraphQL API
 *  - Sub/dub episode counts:    https://meguanime.com/api/langinfo?al=<anilist id>
 *  - Episode titles/thumbnails: https://api.ani.zip/mappings?anilist_id=<anilist id>
 *  - Video links:               https://meguanime.com/api/stream?ani=<id>&ep=<n>[&lang=dub]
 *    (links are signed and expire quickly, so they are never cached)
 */
object Repo {
    private const val ANILIST = "https://graphql.anilist.co"
    private const val SITE = "https://meguanime.com"
    private const val ANIZIP = "https://api.ani.zip/mappings?anilist_id="

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36 MeguAnimes/0.2"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val pageCache = ConcurrentHashMap<String, List<Anime>>()
    private val detailsCache = ConcurrentHashMap<Int, Anime>()
    private val langCache = ConcurrentHashMap<Int, LangInfo>()
    private val episodeCache = ConcurrentHashMap<Int, Map<Int, EpisodeMeta>>()
    private val searchCache = ConcurrentHashMap<String, List<Anime>>()

    private const val LIST_FIELDS =
        "id idMal title { romaji english native } coverImage { large extraLarge } bannerImage " +
            "episodes duration averageScore format seasonYear genres status " +
            "nextAiringEpisode { episode } isAdult"

    private const val DETAIL_FIELDS =
        "id idMal title { romaji english native } coverImage { large extraLarge } bannerImage " +
            "episodes duration averageScore format seasonYear genres status " +
            "description(asHtml: false) nextAiringEpisode { episode } isAdult " +
            "relations { edges { relationType node { id type format " +
            "title { romaji english native } coverImage { large } } } }"

    // ---------------------------------------------------------------- browse

    /** One row of anime, e.g. page("TRENDING_DESC") or page("POPULARITY_DESC", "RELEASING"). */
    suspend fun page(sort: String, status: String? = null, perPage: Int = 24): List<Anime> {
        val key = "$sort:$status:$perPage"
        pageCache[key]?.let { return it }

        val statusArg = if (status != null) ", status: $status" else ""
        val query = "query { Page(page: 1, perPage: $perPage) { " +
            "media(type: ANIME, sort: [$sort]$statusArg, isAdult: false) { $LIST_FIELDS } } }"
        val data = graphql(query)
        val arr = data.getJSONObject("Page").getJSONArray("media")
        val list = (0 until arr.length()).map { parseAnime(arr.getJSONObject(it)) }
        pageCache[key] = list
        return list
    }

    // ---------------------------------------------------------------- search

    /** Results already fetched for this text, or null. Lets the screen skip the wait. */
    fun cachedSearch(text: String): List<Anime>? = searchCache[text.trim().lowercase()]

    /** Title search on AniList. The text goes in as a GraphQL variable, so any characters are safe. */
    suspend fun search(text: String, perPage: Int = 30): List<Anime> {
        val clean = text.trim()
        val key = clean.lowercase()
        searchCache[key]?.let { return it }

        val query = "query(\$q: String) { Page(page: 1, perPage: $perPage) { " +
            "media(type: ANIME, search: \$q, sort: [SEARCH_MATCH], isAdult: false) { $LIST_FIELDS } } }"
        val data = graphql(query, JSONObject().put("q", clean))
        val arr = data.getJSONObject("Page").getJSONArray("media")
        val list = (0 until arr.length())
            .map { parseAnime(arr.getJSONObject(it)) }
            .distinctBy { it.id }
        searchCache[key] = list
        return list
    }

    // --------------------------------------------------------------- details

    suspend fun details(id: Int): Anime {
        detailsCache[id]?.let { return it }

        val query = "query { Media(id: $id, type: ANIME) { $DETAIL_FIELDS } }"
        val media = graphql(query).getJSONObject("Media")
        val anime = parseAnime(media)
        detailsCache[id] = anime
        return anime
    }

    /** Several shows in one go (50 per request), for titles and covers of your synced lists. */
    suspend fun byIds(ids: List<Int>): List<Anime> {
        val out = ArrayList<Anime>()
        for (chunk in ids.distinct().chunked(50)) {
            val query = "query { Page(page: 1, perPage: ${chunk.size}) { " +
                "media(type: ANIME, id_in: [${chunk.joinToString(",")}]) { $LIST_FIELDS } } }"
            val arr = graphql(query).getJSONObject("Page").getJSONArray("media")
            for (i in 0 until arr.length()) out.add(parseAnime(arr.getJSONObject(i)))
        }
        return out
    }

    suspend fun langInfo(id: Int): LangInfo? {
        langCache[id]?.let { return it }
        return try {
            val o = JSONObject(httpGet("$SITE/api/langinfo?al=$id"))
            val arr = o.optJSONArray("dubEps")
            val eps = ArrayList<Int>()
            if (arr != null) {
                for (i in 0 until arr.length()) eps.add(arr.getInt(i))
            }
            val info = LangInfo(
                sub = o.optInt("sub", 0),
                dub = o.optInt("dub", 0),
                dubEps = eps,
                hasDub = o.optBoolean("hasDub", false)
            )
            langCache[id] = info
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Episode titles and thumbnails. Empty map when ani.zip has nothing. */
    suspend fun episodeMeta(id: Int): Map<Int, EpisodeMeta> {
        episodeCache[id]?.let { return it }
        return try {
            val root = JSONObject(httpGet(ANIZIP + id))
            val eps = root.optJSONObject("episodes")
            val map = HashMap<Int, EpisodeMeta>()
            if (eps != null) {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val n = k.toIntOrNull() ?: continue
                    val e = eps.optJSONObject(k) ?: continue
                    val t = e.optJSONObject("title")
                    val title = t?.str("en") ?: t?.str("x-jat") ?: t?.str("ja")
                    map[n] = EpisodeMeta(
                        title = title?.replace('`', '\''),
                        image = e.str("image"),
                        overview = e.str("overview")?.replace('`', '\''),
                        runtime = e.intOrNull("runtime")
                    )
                }
            }
            episodeCache[id] = map
            map
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---------------------------------------------------------------- stream

    /**
     * Finds playable servers for one episode. Tries the main /api/stream route first,
     * then a fallback source. Returns an empty list when nothing is available.
     */
    suspend fun resolveStream(id: Int, episode: Int, dub: Boolean): List<StreamServer> {
        val urls = ArrayList<String>()
        urls.add("$SITE/api/stream?ani=$id&ep=$episode" + (if (dub) "&lang=dub" else ""))
        if (dub) {
            urls.add("$SITE/api/dub?al=$id&ep=$episode")
        } else {
            urls.add("$SITE/api/anikoto?al=$id&ep=$episode")
        }
        for (u in urls) {
            try {
                val servers = parseStream(httpGet(u))
                if (servers.isNotEmpty()) return servers
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // try the next source
            }
        }
        return emptyList()
    }

    private fun parseStream(text: String): List<StreamServer> {
        val root = JSONObject(text)
        val out = ArrayList<StreamServer>()

        // Multi-server answer: { providers: [ { id, label, hard, source, tracks, intro, outro } ] }
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val p = providers.optJSONObject(i) ?: continue
                val src = p.str("source") ?: continue
                if (!src.startsWith("http")) continue
                out.add(
                    StreamServer(
                        id = p.str("id") ?: "s$i",
                        label = p.str("label") ?: p.str("id") ?: "Server ${i + 1}",
                        url = src,
                        subtitles = parseTracks(p.optJSONArray("tracks")),
                        intro = parseRange(p.optJSONObject("intro")),
                        outro = parseRange(p.optJSONObject("outro")),
                        hardsub = p.optBoolean("hard", false)
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }

        // Single-server answer: { source, tracks, intro, outro, server? }
        val src = root.str("source")
        if (src != null && src.startsWith("http")) {
            out.add(
                StreamServer(
                    id = "main",
                    label = root.str("server") ?: "Default",
                    url = src,
                    subtitles = parseTracks(root.optJSONArray("tracks")),
                    intro = parseRange(root.optJSONObject("intro")),
                    outro = parseRange(root.optJSONObject("outro")),
                    hardsub = false
                )
            )
        }
        return out
    }

    private fun parseTracks(arr: JSONArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<SubtitleTrack>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val file = t.str("file") ?: continue
            val kind = (t.str("kind") ?: "subtitles").lowercase()
            if (kind != "subtitles" && kind != "captions") continue
            val decoded = try {
                URLDecoder.decode(file, "UTF-8")
            } catch (e: Exception) {
                file
            }
            // Some servers list the seek-preview sprite sheet as a "subtitle". Skip it.
            if (decoded.contains("thumbnails", ignoreCase = true)) continue
            if (!seen.add(file)) continue
            out.add(
                SubtitleTrack(
                    url = file,
                    label = t.str("label") ?: "Subtitles ${out.size + 1}",
                    isDefault = t.optBoolean("default", false)
                )
            )
        }
        return out
    }

    private fun parseRange(o: JSONObject?): TimeRange? {
        if (o == null) return null
        val start = o.optDouble("start", -1.0)
        val end = o.optDouble("end", -1.0)
        return if (start >= 0 && end > start) TimeRange(start, end) else null
    }

    // --------------------------------------------------------------- parsing

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun parseAnime(o: JSONObject): Anime {
        val t = o.optJSONObject("title")
        val romaji = t?.str("romaji")
        val title = t?.str("english") ?: romaji ?: t?.str("native") ?: "Untitled"
        val cov = o.optJSONObject("coverImage")
        val genresArr = o.optJSONArray("genres")
        val genres = ArrayList<String>()
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) genres.add(genresArr.getString(i))
        }

        val related = ArrayList<Related>()
        val edges = o.optJSONObject("relations")?.optJSONArray("edges")
        if (edges != null) {
            for (i in 0 until edges.length()) {
                val edge = edges.getJSONObject(i)
                val node = edge.optJSONObject("node") ?: continue
                if (node.str("type") != "ANIME") continue
                val nt = node.optJSONObject("title")
                related.add(
                    Related(
                        id = node.getInt("id"),
                        title = nt?.str("english") ?: nt?.str("romaji") ?: nt?.str("native") ?: "Untitled",
                        cover = node.optJSONObject("coverImage")?.str("large"),
                        relation = (edge.str("relationType") ?: "RELATED")
                            .lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                        format = node.str("format")
                    )
                )
            }
        }

        return Anime(
            id = o.getInt("id"),
            idMal = o.intOrNull("idMal"),
            title = title,
            romaji = romaji ?: title,
            cover = cov?.str("extraLarge") ?: cov?.str("large"),
            banner = o.str("bannerImage"),
            episodes = o.intOrNull("episodes"),
            duration = o.intOrNull("duration"),
            score = o.intOrNull("averageScore"),
            format = o.str("format"),
            year = o.intOrNull("seasonYear"),
            genres = genres,
            description = cleanDescription(o.str("description") ?: ""),
            status = o.str("status"),
            nextEpisode = o.optJSONObject("nextAiringEpisode")?.intOrNull("episode"),
            isAdult = o.optBoolean("isAdult", false),
            related = related.distinctBy { it.id }
        )
    }

    private fun cleanDescription(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    // ------------------------------------------------------------------ http

    suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from ${req.url.host}")
            resp.body?.string() ?: throw IOException("Empty response from ${req.url.host}")
        }
    }

    private suspend fun graphql(query: String, variables: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
        val payload = JSONObject().put("query", query)
        if (variables != null) payload.put("variables", variables)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(ANILIST)
            .post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("AniList error (HTTP ${resp.code})")
            val root = JSONObject(text)
            if (!root.has("data") || root.isNull("data")) throw IOException("AniList returned no data")
            root.getJSONObject("data")
        }
    }
}
