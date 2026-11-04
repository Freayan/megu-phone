package com.megu.phone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class WatchEntry(
    val animeId: Int,
    val title: String,
    val cover: String?,
    val episode: Int,
    val dub: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

/** How far you got in one episode, as reported by meguanime.com. */
data class EpisodeRow(
    val animeId: Int,
    val episode: Int,
    val dub: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * Watch progress and the "Continue watching" list, stored on the phone.
 * Everything is filed under [profile]; profiles arrive in a later stage.
 */
object Progress {
    private var prefs: SharedPreferences? = null

    var profile: String = "default"

    /** Goes up whenever "Continue watching" changes, so screens reading it redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("megu_progress", Context.MODE_PRIVATE)
        }
        // Every screen that records progress starts here, so the account is ready too.
        Account.init(context)
        Sync.init(context)
    }

    private fun store(): SharedPreferences = prefs ?: error("Progress.init() was not called")

    private fun lang(dub: Boolean) = if (dub) "dub" else "sub"
    private fun posKey(id: Int, ep: Int, dub: Boolean) = "pos:$profile:$id:$ep:${lang(dub)}"
    private fun doneKey(id: Int, ep: Int) = "done:$profile:$id:$ep"
    private fun contKey() = "continue:$profile"

    fun positionFor(id: Int, ep: Int, dub: Boolean): Long =
        store().getString(posKey(id, ep, dub), null)
            ?.substringBefore(',')?.toLongOrNull() ?: 0L

    fun fraction(id: Int, ep: Int, dub: Boolean): Float {
        val raw = store().getString(posKey(id, ep, dub), null) ?: return 0f
        val parts = raw.split(',')
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return 0f
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: return 0f
        if (dur <= 0) return 0f
        return (pos.toFloat() / dur).coerceIn(0f, 1f)
    }

    fun isWatched(id: Int, ep: Int): Boolean = store().getBoolean(doneKey(id, ep), false)

    /**
     * Called by the player every few seconds and when it pauses or closes.
     * [nextEpisode] is the episode that follows, or null for the last one.
     */
    fun record(
        animeId: Int,
        title: String,
        cover: String?,
        episode: Int,
        dub: Boolean,
        positionMs: Long,
        durationMs: Long,
        nextEpisode: Int?
    ) {
        val finished = durationMs > 0 && positionMs >= durationMs * 0.93
        if (finished) {
            store().edit()
                .putBoolean(doneKey(animeId, episode), true)
                .remove(posKey(animeId, episode, true))
                .remove(posKey(animeId, episode, false))
                .apply()
            if (nextEpisode != null) {
                upsert(
                    WatchEntry(animeId, title, cover, nextEpisode, dub, 0L, 0L, System.currentTimeMillis())
                )
            } else {
                removeEntry(animeId)
            }
            Sync.pushProgress(animeId, title, cover, episode, dub, positionMs, durationMs, true, nextEpisode)
            return
        }
        if (positionMs < 5_000) return
        store().edit().putString(posKey(animeId, episode, dub), "$positionMs,$durationMs").apply()
        upsert(
            WatchEntry(animeId, title, cover, episode, dub, positionMs, durationMs, System.currentTimeMillis())
        )
        Sync.pushProgress(animeId, title, cover, episode, dub, positionMs, durationMs, false, nextEpisode)
    }

    /**
     * Merges what meguanime.com has into "Continue watching": for each show the newer
     * of the two wins, and the resume position is filled in so playback picks up there.
     */
    fun importRemote(entries: List<WatchEntry>) {
        if (entries.isEmpty()) return
        val merged = HashMap<Int, WatchEntry>()
        for (e in continueWatching()) merged[e.animeId] = e
        val edit = store().edit()
        for (r in entries) {
            val local = merged[r.animeId]
            if (local == null || r.updatedAt > local.updatedAt) {
                merged[r.animeId] = r
                if (r.positionMs >= 5_000L) {
                    edit.putString(posKey(r.animeId, r.episode, r.dub), "${r.positionMs},${r.durationMs}")
                }
            }
        }
        edit.apply()
        saveEntries(merged.values.sortedByDescending { it.updatedAt })
    }

    /** Marks episodes finished on the site as watched here, and keeps partial progress. */
    fun importEpisodeRows(rows: List<EpisodeRow>) {
        if (rows.isEmpty()) return
        val edit = store().edit()
        for (r in rows) {
            if (r.episode <= 0 || r.durationMs <= 0L) continue
            if (r.positionMs >= r.durationMs * 0.93) {
                edit.putBoolean(doneKey(r.animeId, r.episode), true)
            } else if (r.positionMs >= 5_000L && positionFor(r.animeId, r.episode, r.dub) == 0L) {
                edit.putString(posKey(r.animeId, r.episode, r.dub), "${r.positionMs},${r.durationMs}")
            }
        }
        edit.apply()
        version++
    }

    fun continueWatching(): List<WatchEntry> {
        val raw = store().getString(contKey(), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WatchEntry(
                    animeId = o.getInt("id"),
                    title = o.getString("title"),
                    cover = o.optString("cover").takeIf { c -> c.isNotBlank() },
                    episode = o.getInt("ep"),
                    dub = o.optBoolean("dub", false),
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    updatedAt = o.optLong("ts", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun removeEntry(animeId: Int) {
        saveEntries(continueWatching().filter { it.animeId != animeId })
        Sync.pushHistoryDelete(animeId)
    }

    private fun upsert(entry: WatchEntry) {
        saveEntries(listOf(entry) + continueWatching().filter { it.animeId != entry.animeId })
    }

    private fun saveEntries(list: List<WatchEntry>) {
        val arr = JSONArray()
        for (e in list.take(20)) {
            arr.put(
                JSONObject()
                    .put("id", e.animeId)
                    .put("title", e.title)
                    .put("cover", e.cover ?: "")
                    .put("ep", e.episode)
                    .put("dub", e.dub)
                    .put("pos", e.positionMs)
                    .put("dur", e.durationMs)
                    .put("ts", e.updatedAt)
            )
        }
        store().edit().putString(contKey(), arr.toString()).apply()
        version++
    }
}
