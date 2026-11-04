package com.megu.phone.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class SyncPhase { Idle, Syncing, Done, Failed }

data class SyncState(
    val phase: SyncPhase = SyncPhase.Idle,
    val at: Long = 0L,
    val message: String = ""
)

/**
 * Keeps this phone and your meguanime.com account in step, using the same tables as the TV app:
 *  - watch_history    -> "Continue watching"
 *  - watchlist        -> "My List" (every status: watching, completed, ...)
 *  - episode_progress -> how far you got in each episode, and which ones you finished
 *
 * Pulling (site -> phone) happens when the app opens, right after signing in, and when you tap
 * "Sync now". Pushing (phone -> site) happens as you watch and when you add or remove a show.
 * A push that fails (no internet) is not retried; the next pull simply brings the phone back
 * in line with the site.
 */
object Sync {
    private const val TAG = "MeguSync"
    private const val REST = Account.SUPABASE + "/rest/v1/"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullLock = Mutex()
    private var prefs: SharedPreferences? = null

    var state by mutableStateOf(SyncState())
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("megu_sync", Context.MODE_PRIVATE)
        }
    }

    fun pullInBackground() {
        if (Account.isLinked) scope.launch { pull() }
    }

    fun onSignedIn() {
        scope.launch { pull() }
    }

    fun onSignedOut() {
        prefs?.edit()?.clear()?.apply()
        state = SyncState()
    }

    // ---------------------------------------------------------------- http

    /** Returns the response text, or null when you are signed out. Throws on any HTTP/network error. */
    private suspend fun rest(
        method: String,
        path: String,
        body: JSONObject? = null,
        prefer: String? = null
    ): String? {
        var token = Account.validToken() ?: return null
        var result = send(method, path, body, prefer, token)
        if (result.first == 401) {
            token = Account.validToken(forceRefresh = true) ?: return null
            result = send(method, path, body, prefer, token)
        }
        if (result.first !in 200..299) throw IOException("HTTP ${result.first} for $path")
        return result.second
    }

    private suspend fun send(
        method: String,
        path: String,
        body: JSONObject?,
        prefer: String?,
        token: String
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(REST + path)
            .header("apikey", Account.SUPABASE_KEY)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", Repo.UA)
        if (prefer != null) b.header("Prefer", prefer)
        when (method) {
            "POST" -> b.post((body ?: JSONObject()).toString().toRequestBody(JSON_TYPE))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        Repo.client.newCall(b.build()).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    // ---------------------------------------------------------------- pull

    /** Site -> phone. Returns true when it worked. */
    suspend fun pull(): Boolean {
        if (!Account.isLinked) return false
        if (!pullLock.tryLock()) return false
        try {
            state = SyncState(SyncPhase.Syncing, state.at)
            doPull()
            state = SyncState(SyncPhase.Done, System.currentTimeMillis())
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pull failed", e)
            state = SyncState(
                SyncPhase.Failed,
                state.at,
                "Couldn't sync with meguanime.com. Check your connection and try again."
            )
            return false
        } finally {
            pullLock.unlock()
        }
    }

    private class Hist(
        val id: Int,
        val ep: Int,
        val dub: Boolean,
        val durSec: Long,
        val resumeSec: Long,
        val updated: Long
    )

    private suspend fun doPull() {
        val notSignedIn = "not signed in"
        val historyRows = JSONArray(
            rest(
                "GET",
                "watch_history?select=anime_id,ep_number,lang,duration,total_eps,resume_time," +
                    "updated_at&order=updated_at.desc&limit=30"
            ) ?: throw IOException(notSignedIn)
        )
        val listRows = JSONArray(
            rest("GET", "watchlist?select=anime_id,status") ?: throw IOException(notSignedIn)
        )

        val hist = ArrayList<Hist>()
        for (i in 0 until historyRows.length()) {
            val o = historyRows.getJSONObject(i)
            val id = o.optString("anime_id").toIntOrNull() ?: continue
            hist.add(
                Hist(
                    id = id,
                    ep = o.optInt("ep_number", 1).coerceAtLeast(1),
                    dub = o.optString("lang") == "dub",
                    durSec = o.optLong("duration", 0L),
                    resumeSec = o.optLong("resume_time", 0L),
                    updated = parseTime(o.optString("updated_at"))
                )
            )
        }
        val historyIds = hist.map { it.id }.distinct()

        val progressRows = if (historyIds.isEmpty()) {
            JSONArray()
        } else {
            JSONArray(
                rest(
                    "GET",
                    "episode_progress?select=anime_id,ep_number,progress_sec,duration_sec" +
                        "&anime_id=in.(${historyIds.joinToString(",")})"
                ) ?: throw IOException(notSignedIn)
            )
        }

        val statuses = LinkedHashMap<Int, String>()
        for (i in 0 until listRows.length()) {
            val o = listRows.getJSONObject(i)
            val id = o.optString("anime_id").toIntOrNull() ?: continue
            statuses[id] = o.optString("status").ifBlank { "plan-to-watch" }
        }
        val listIds = statuses.keys.take(300)

        // Titles and covers come from AniList, 50 shows per request.
        val info = Repo.byIds((historyIds + listIds).distinct()).associateBy { it.id }

        // Continue watching
        val entries = hist.mapNotNull { h ->
            val a = info[h.id] ?: return@mapNotNull null
            WatchEntry(h.id, a.title, a.cover, h.ep, h.dub, h.resumeSec * 1000L, h.durSec * 1000L, h.updated)
        }
        Progress.importRemote(entries)

        // Episode progress / watched marks
        val dubById = hist.associate { it.id to it.dub }
        val rows = ArrayList<EpisodeRow>()
        for (i in 0 until progressRows.length()) {
            val o = progressRows.getJSONObject(i)
            val id = o.optString("anime_id").toIntOrNull() ?: continue
            rows.add(
                EpisodeRow(
                    animeId = id,
                    episode = o.optInt("ep_number", 0),
                    dub = dubById[id] ?: false,
                    positionMs = o.optLong("progress_sec", 0L) * 1000L,
                    durationMs = o.optLong("duration_sec", 0L) * 1000L
                )
            )
        }
        Progress.importEpisodeRows(rows)

        // My List
        val now = System.currentTimeMillis()
        val saved = listIds.mapIndexedNotNull { index, id ->
            val a = info[id] ?: return@mapIndexedNotNull null
            val meta = listOf(statusLabel(statuses[id]), a.meta())
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            SavedAnime(id, a.title, a.cover, a.score, meta, now - index)
        }
        val remoteIds = statuses.keys.toSet()
        val removedOnSite = syncedList() - remoteIds
        MyList.importRemote(saved, removedOnSite)
        saveSyncedList(remoteIds)
    }

    // ---------------------------------------------------------------- push: My List

    fun pushListAdd(anime: Anime) {
        if (!Account.isLinked) return
        scope.launch {
            try {
                val uid = Account.userId ?: return@launch
                val row = JSONObject()
                    .put("user_id", uid)
                    .put("anime_id", anime.id.toString())
                    .put("title", anime.title)
                    .put("title_jp", anime.romaji.ifBlank { null } ?: JSONObject.NULL)
                    .put("image", anime.cover ?: JSONObject.NULL)
                    .put("status", "plan-to-watch")
                // "ignore-duplicates": if the site already has it (say as "Watching"), leave it as is.
                rest("POST", "watchlist", row, "resolution=ignore-duplicates,return=minimal")
                markSynced(anime.id, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "list add not synced", e)
            }
        }
    }

    fun pushListRemove(id: Int) {
        if (!Account.isLinked) return
        scope.launch {
            try {
                val uid = Account.userId ?: return@launch
                rest("DELETE", "watchlist?user_id=eq.$uid&anime_id=eq.$id", null, "return=minimal")
                markSynced(id, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "list remove not synced", e)
            }
        }
    }

    // ---------------------------------------------------------------- push: watching

    private class Snap(
        val animeId: Int,
        val title: String,
        val cover: String?,
        val episode: Int,
        val dub: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val finished: Boolean,
        val next: Int?
    )

    private val lastSent = HashMap<Int, Long>()
    private val trailing = HashMap<Int, Job>()

    /**
     * Called while you watch. Sends at most once every 30 seconds per show, plus one last
     * update shortly after you stop, so the site always ends up with your final position.
     */
    fun pushProgress(
        animeId: Int,
        title: String,
        cover: String?,
        episode: Int,
        dub: Boolean,
        positionMs: Long,
        durationMs: Long,
        finished: Boolean,
        nextEpisode: Int?
    ) {
        if (!Account.isLinked) return
        val snap = Snap(animeId, title, cover, episode, dub, positionMs, durationMs, finished, nextEpisode)
        val now = System.currentTimeMillis()
        var sendNow = false
        synchronized(this) {
            trailing.remove(animeId)?.cancel()
            val last = lastSent[animeId] ?: 0L
            if (finished || now - last >= 30_000L) {
                lastSent[animeId] = now
                sendNow = true
            }
        }
        if (sendNow) {
            scope.launch { sendProgress(snap) }
            return
        }
        val job = scope.launch {
            delay(10_000L)
            synchronized(this@Sync) {
                lastSent[animeId] = System.currentTimeMillis()
                trailing.remove(animeId)
            }
            sendProgress(snap)
        }
        synchronized(this) {
            trailing[animeId] = job
        }
    }

    /** "Remove from continue watching" (or finishing the last episode). */
    fun pushHistoryDelete(animeId: Int) {
        if (!Account.isLinked) return
        scope.launch {
            try {
                val uid = Account.userId ?: return@launch
                rest("DELETE", "watch_history?user_id=eq.$uid&anime_id=eq.$animeId", null, "return=minimal")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "history delete not synced", e)
            }
        }
    }

    private suspend fun sendProgress(s: Snap) {
        try {
            val uid = Account.userId ?: return
            val merge = "resolution=merge-duplicates,return=minimal"

            // watch_history: where to continue. After a finished episode it points at the next one.
            if (!(s.finished && s.next == null)) {
                val ep = if (s.finished) (s.next ?: s.episode) else s.episode
                val dur = if (s.finished) 0L else s.durationMs / 1000L
                val pos = if (s.finished) 0L else s.positionMs / 1000L
                val row = JSONObject()
                    .put("user_id", uid)
                    .put("anime_id", s.animeId.toString())
                    .put("mal_id", JSONObject.NULL)
                    .put("title", s.title)
                    .put("title_jp", JSONObject.NULL)
                    .put("image", s.cover ?: JSONObject.NULL)
                    .put("ep_idx", ep - 1)
                    .put("ep_number", ep)
                    .put("lang", if (s.dub) "dub" else "sub")
                    .put("duration", dur)
                    .put("total_eps", JSONObject.NULL)
                    .put("resume_time", pos)
                    .put("updated_at", isoNow())
                rest("POST", "watch_history", row, merge)
            }

            // episode_progress: how far you got in this episode.
            if (s.durationMs > 0L && s.positionMs > 0L) {
                val dur = s.durationMs / 1000L
                val pos = if (s.finished) dur else s.positionMs / 1000L
                val pct = if (dur > 0L) (pos * 100L / dur).coerceIn(0L, 100L) else 0L
                val row = JSONObject()
                    .put("user_id", uid)
                    .put("anime_id", s.animeId.toString())
                    .put("ep_number", s.episode)
                    .put("progress_sec", pos)
                    .put("duration_sec", dur)
                    .put("progress_pct", pct)
                rest("POST", "episode_progress", row, merge)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "progress not synced", e)
        }
    }

    // ---------------------------------------------------------------- bookkeeping

    /** Ids that were on the site's list the last time we looked, so site-side removals can be mirrored. */
    private fun syncedList(): Set<Int> =
        prefs?.getStringSet("list", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    private fun saveSyncedList(ids: Set<Int>) {
        prefs?.edit()?.putStringSet("list", ids.map { it.toString() }.toSet())?.apply()
    }

    private fun markSynced(id: Int, present: Boolean) {
        synchronized(this) {
            val now = syncedList().toMutableSet()
            if (present) now.add(id) else now.remove(id)
            saveSyncedList(now)
        }
    }

    private fun statusLabel(s: String?): String = when (s) {
        "watching" -> "Watching"
        "completed" -> "Completed"
        "on-hold" -> "On hold"
        "dropped" -> "Dropped"
        "plan-to-watch" -> "Plan to watch"
        else -> ""
    }

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(System.currentTimeMillis())
    }

    /** Reads times like 2026-01-02T03:04:05.123+00:00 (the site stores UTC). */
    private fun parseTime(s: String?): Long {
        if (s == null || s.length < 19) return 0L
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            var ms = f.parse(s.substring(0, 19))?.time ?: return 0L
            if (s.length > 20 && s[19] == '.') {
                val digits = s.substring(20).takeWhile { it.isDigit() }.take(3).padEnd(3, '0')
                ms += digits.toLong()
            }
            ms
        } catch (e: Exception) {
            0L
        }
    }
}
