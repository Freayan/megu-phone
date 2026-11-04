package com.megu.phone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One show saved to My List. Only what the grid needs, so it opens instantly. */
data class SavedAnime(
    val id: Int,
    val title: String,
    val cover: String?,
    val score: Int?,
    val meta: String,
    val addedAt: Long
)

private const val LIB_PREFS = "megu_library"

private fun libPrefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

/**
 * "My List", stored on the phone and filed under the current profile.
 * [version] goes up on every change, so screens that read it redraw by themselves.
 */
object MyList {
    private var prefs: SharedPreferences? = null

    /** Read this inside a composable to be redrawn whenever the list changes. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) prefs = libPrefs(context)
    }

    private fun store(): SharedPreferences = prefs ?: error("MyList.init() was not called")

    private fun key() = "mylist:${Progress.profile}"

    fun all(): List<SavedAnime> {
        val raw = store().getString(key(), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedAnime(
                    id = o.getInt("id"),
                    title = o.getString("title"),
                    cover = o.optString("cover").takeIf { c -> c.isNotBlank() },
                    score = if (o.has("score") && !o.isNull("score")) o.optInt("score") else null,
                    meta = o.optString("meta"),
                    addedAt = o.optLong("ts", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contains(id: Int): Boolean = all().any { it.id == id }

    fun add(anime: Anime) {
        val entry = SavedAnime(
            id = anime.id,
            title = anime.title,
            cover = anime.cover,
            score = anime.score,
            meta = anime.meta(),
            addedAt = System.currentTimeMillis()
        )
        save(listOf(entry) + all().filter { it.id != anime.id })
        Sync.pushListAdd(anime)
    }

    fun remove(id: Int) {
        save(all().filter { it.id != id })
        Sync.pushListRemove(id)
    }

    /**
     * Brings in what is on meguanime.com. Shows already here are kept as they are,
     * new ones are added after them, and shows removed on the site are dropped.
     */
    fun importRemote(remote: List<SavedAnime>, removedOnSite: Set<Int>) {
        val before = all()
        val kept = before.filter { it.id !in removedOnSite }
        val have = kept.map { it.id }.toSet()
        val fresh = remote.filter { it.id !in have }
        if (fresh.isEmpty() && kept.size == before.size) return
        save(kept + fresh)
    }

    /** Adds the show if it is not saved, removes it if it is. Returns true when it is saved now. */
    fun toggle(anime: Anime): Boolean {
        return if (contains(anime.id)) {
            remove(anime.id)
            false
        } else {
            add(anime)
            true
        }
    }

    private fun save(list: List<SavedAnime>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
                .put("id", e.id)
                .put("title", e.title)
                .put("cover", e.cover ?: "")
                .put("meta", e.meta)
                .put("ts", e.addedAt)
            if (e.score != null) o.put("score", e.score)
            arr.put(o)
        }
        store().edit().putString(key(), arr.toString()).apply()
        version++
    }
}

/** The last few things typed into Search, newest first. */
object SearchHistory {
    private var prefs: SharedPreferences? = null
    private const val MAX = 8

    fun init(context: Context) {
        if (prefs == null) prefs = libPrefs(context)
    }

    private fun store(): SharedPreferences = prefs ?: error("SearchHistory.init() was not called")

    private fun key() = "searches:${Progress.profile}"

    fun all(): List<String> {
        val raw = store().getString(key(), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(text: String) {
        val t = text.trim()
        if (t.length < 2) return
        val list = listOf(t) + all().filter { !it.equals(t, ignoreCase = true) }
        save(list.take(MAX))
    }

    fun remove(text: String) {
        save(all().filter { it != text })
    }

    fun clear() {
        save(emptyList())
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        store().edit().putString(key(), arr.toString()).apply()
    }
}
