package com.megu.phone.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** The meguanime.com account linked to this phone, as the Profile screen shows it. */
data class LinkedAccount(val id: String, val name: String, val avatar: String?)

/** One sign-in attempt: the code to type on the website, and how often to check. */
data class LinkSession(
    val userCode: String,
    val deviceCode: String,
    val verifyUrl: String,
    val expiresAt: Long,
    val intervalMs: Long
)

/** What meguanime.com says when we ask whether the code was approved yet. */
sealed interface LinkPoll {
    data object Pending : LinkPoll
    data object SlowDown : LinkPoll
    data object Expired : LinkPoll
    data class Approved(
        val account: LinkedAccount,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long
    ) : LinkPoll
}

/**
 * Signing in uses the same "link a device" flow as the TV app:
 *  1. POST /api/tv/link/start  -> a short code to type on meguanime.com/tv
 *  2. POST /api/tv/link/poll   -> repeat until the site says "approved"
 * The site then hands back login tokens, which are kept on the phone (and excluded
 * from Android backups) so Sync can read and write your lists as you.
 */
object Account {
    private const val TAG = "MeguAccount"
    private const val SITE = "https://meguanime.com"
    const val DEFAULT_VERIFY_URL = "https://meguanime.com/tv"
    const val SUPABASE = "https://xcnpjrsvlzwruempnwxm.supabase.co"

    /** The public client key of meguanime.com's backend (the same one the TV app ships with). */
    const val SUPABASE_KEY = "sb_publishable_dgmcGYwvKETM0QCkGE-FWQ_vpBtEoPl"

    private const val PREFS = "megu_account"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private var prefs: SharedPreferences? = null
    private var access: String? = null
    private var refresh: String? = null
    private var expiresAt: Long = 0L
    private val refreshLock = Mutex()

    /** Read this inside a composable to be redrawn when you sign in or out. */
    var current by mutableStateOf<LinkedAccount?>(null)
        private set

    val isLinked: Boolean get() = current != null && access != null

    /** The id meguanime.com knows you by. */
    val userId: String?
        get() {
            val c = current ?: return null
            return c.id.ifBlank { jwtSubject(access) }
        }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        access = p.getString("access", null)
        refresh = p.getString("refresh", null)
        expiresAt = p.getLong("expires", 0L)
        val id = p.getString("id", null)
        if (id != null && access != null) {
            current = LinkedAccount(
                id = id,
                name = p.getString("name", "") ?: "",
                avatar = p.getString("avatar", null)
            )
        }
    }

    fun signIn(a: LinkPoll.Approved) {
        prefs?.edit()
            ?.putString("id", a.account.id)
            ?.putString("name", a.account.name)
            ?.putString("avatar", a.account.avatar)
            ?.apply()
        saveTokens(a.accessToken, a.refreshToken, a.expiresAt)
        current = a.account
        Sync.onSignedIn()
    }

    fun signOut() {
        prefs?.edit()?.clear()?.apply()
        access = null
        refresh = null
        expiresAt = 0L
        current = null
        Sync.onSignedOut()
    }

    private fun saveTokens(newAccess: String, newRefresh: String, newExpiresAt: Long) {
        access = newAccess
        refresh = newRefresh
        expiresAt = newExpiresAt
        prefs?.edit()
            ?.putString("access", newAccess)
            ?.putString("refresh", newRefresh)
            ?.putLong("expires", newExpiresAt)
            ?.apply()
    }

    // ---------------------------------------------------------------- tokens

    /**
     * A login token that is still valid, renewing it first when it is about to run out.
     * Returns null when you are signed out or the site can't be reached.
     */
    suspend fun validToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val token = access ?: return@withLock null
        val fresh = System.currentTimeMillis() < expiresAt - 60_000L
        if (fresh && !forceRefresh) token else refreshTokens()
    }

    private suspend fun refreshTokens(): String? = withContext(Dispatchers.IO) {
        val rt = refresh ?: return@withContext null
        try {
            val body = JSONObject().put("refresh_token", rt).toString().toRequestBody(JSON_TYPE)
            val req = Request.Builder()
                .url("$SUPABASE/auth/v1/token?grant_type=refresh_token")
                .header("apikey", SUPABASE_KEY)
                .header("User-Agent", Repo.UA)
                .post(body)
                .build()
            val (code, text) = Repo.client.newCall(req).execute().use {
                it.code to it.body?.string().orEmpty()
            }
            when {
                code in 400..403 -> {
                    // The site no longer accepts this login (revoked or too old).
                    Log.w(TAG, "session ended: HTTP $code")
                    signOut()
                    null
                }
                code !in 200..299 -> null
                else -> {
                    val j = JSONObject(text)
                    val newAccess = j.getString("access_token")
                    val newRefresh = j.optString("refresh_token").ifBlank { rt }
                    val exp = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
                    saveTokens(newAccess, newRefresh, exp)
                    newAccess
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "token refresh failed", e)
            null
        }
    }

    private fun jwtSubject(token: String?): String? {
        val part = token?.split('.')?.getOrNull(1) ?: return null
        return try {
            val json = String(
                Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            JSONObject(json).optString("sub").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- sign-in

    /** Asks meguanime.com for a new code. Throws if the site can't be reached. */
    suspend fun startLink(): LinkSession = withContext(Dispatchers.IO) {
        val (_, json) = post("/api/tv/link/start", JSONObject().put("device", deviceName()))
        val userCode = json.optString("user_code")
        val deviceCode = json.optString("device_code")
        if (userCode.isBlank() || deviceCode.isBlank()) {
            throw IOException("meguanime.com did not return a code")
        }
        LinkSession(
            userCode = userCode,
            deviceCode = deviceCode,
            verifyUrl = json.optString("verify_url", DEFAULT_VERIFY_URL)
                .ifBlank { DEFAULT_VERIFY_URL },
            expiresAt = System.currentTimeMillis() +
                json.optLong("expires_in", 600L).coerceIn(30L, 3600L) * 1000L,
            intervalMs = json.optLong("interval", 4L).coerceIn(2L, 30L) * 1000L
        )
    }

    /** One check. Throws on network trouble; the caller just tries again later. */
    suspend fun poll(session: LinkSession): LinkPoll = withContext(Dispatchers.IO) {
        val (_, json) = post("/api/tv/link/poll", JSONObject().put("device_code", session.deviceCode))
        when (json.optString("status")) {
            "approved" -> {
                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token")
                if (accessToken.isBlank() || refreshToken.isBlank()) {
                    LinkPoll.Pending
                } else {
                    val user = json.optJSONObject("user")
                    LinkPoll.Approved(
                        account = LinkedAccount(
                            id = user?.optString("id").orEmpty(),
                            name = user?.optString("name").orEmpty(),
                            avatar = user?.optString("avatar")
                                ?.takeIf { it.isNotBlank() && it != "null" }
                        ),
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresAt = System.currentTimeMillis() +
                            json.optLong("expires_in", 3600L) * 1000L
                    )
                }
            }
            "expired" -> LinkPoll.Expired
            "slow_down" -> LinkPoll.SlowDown
            else -> LinkPoll.Pending
        }
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
        return listOf(maker, Build.MODEL.orEmpty())
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { "Android phone" }
            .take(40)
    }

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val req = Request.Builder()
            .url(SITE + path)
            .header("User-Agent", Repo.UA)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        Repo.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            return resp.code to json
        }
    }
}
