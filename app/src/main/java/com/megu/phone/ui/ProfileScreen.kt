package com.megu.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.megu.phone.data.Account
import com.megu.phone.data.LinkPoll
import com.megu.phone.data.LinkSession
import com.megu.phone.data.LinkedAccount
import com.megu.phone.data.Sync
import com.megu.phone.data.SyncPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val account = Account.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Profile",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(28.dp))
        if (account != null) SignedIn(account) else SignedOut()
    }
}

// ------------------------------------------------------------------ signed in

@Composable
private fun SignedIn(account: LinkedAccount) {
    var confirm by remember { mutableStateOf(false) }
    val shown = account.name.ifBlank { "meguanime.com account" }

    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            shown.first().uppercase(),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        if (account.avatar != null) {
            AsyncImage(
                model = account.avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(shown, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
        "Signed in to meguanime.com",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    val sync = Sync.state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text("Synced with meguanime.com", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Continue Watching, My List and episode progress follow your account, " +
                "in both directions.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sync.phase == SyncPhase.Syncing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when (sync.phase) {
                    SyncPhase.Syncing -> "Syncing…"
                    SyncPhase.Failed -> sync.message
                    SyncPhase.Done -> "Synced " + ago(sync.at)
                    SyncPhase.Idle -> if (sync.at > 0L) "Synced " + ago(sync.at) else "Not synced yet"
                },
                color = if (sync.phase == SyncPhase.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { Sync.pullInBackground() },
            enabled = sync.phase != SyncPhase.Syncing
        ) { Text("Sync now") }
    }
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Sign out")
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out?") },
            text = { Text("Syncing stops. Your My List and progress already on this phone are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Account.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ----------------------------------------------------------------- signed out

@Composable
private fun SignedOut() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<LinkSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val active = session
    if (active != null) {
        // Every few seconds, asks meguanime.com whether you approved the code.
        // Leaving this screen or tapping Cancel stops it automatically.
        LaunchedEffect(active.deviceCode) {
            var wait = active.intervalMs
            while (true) {
                if (System.currentTimeMillis() >= active.expiresAt) {
                    session = null
                    problem = "That code expired. Tap the button to get a new one."
                    break
                }
                delay(wait)
                val result: LinkPoll? = try {
                    Account.poll(active)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                when (result) {
                    is LinkPoll.Approved -> {
                        Account.signIn(result)
                        break
                    }
                    LinkPoll.Expired -> {
                        session = null
                        problem = "That code expired. Tap the button to get a new one."
                        break
                    }
                    LinkPoll.SlowDown -> {
                        wait += 5_000L
                        offline = false
                    }
                    LinkPoll.Pending -> offline = false
                    null -> offline = true
                }
            }
        }
        // Ticks once a second so the countdown moves.
        LaunchedEffect(active.deviceCode) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    if (active == null) {
        Text(
            "Sign in with your meguanime.com account to link it to this app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                problem = null
                scope.launch {
                    try {
                        session = Account.startLink()
                        copied = false
                        offline = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem = "Couldn't reach meguanime.com. Check your connection and try again."
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Contacting meguanime.com…" else "Sign in with meguanime.com")
        }
    } else {
        Text(
            "1. Open meguanime.com and log in",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openSite(context, active.verifyUrl) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open meguanime.com") }

        Spacer(Modifier.height(24.dp))
        Text(
            "2. Type this code on that page",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                active.userCode,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = {
            copyText(context, active.userCode)
            copied = true
        }) { Text(if (copied) "Copied" else "Copy code") }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (offline) "Can't reach meguanime.com. Still trying…"
                else "Waiting for you to approve it…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Code expires in ${timeLeft(active.expiresAt - now)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { session = null }) { Text("Cancel") }
    }
}

// -------------------------------------------------------------------- helpers

/** Opens the site in the browser. Only ever opens meguanime.com addresses. */
private fun openSite(context: Context, url: String) {
    val parsed = Uri.parse(url)
    val host = parsed.host.orEmpty()
    val ok = parsed.scheme == "https" && (host == "meguanime.com" || host.endsWith(".meguanime.com"))
    val target = if (ok) url else Account.DEFAULT_VERIFY_URL
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        // No browser available: nothing to do.
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("meguanime code", text))
}

private fun ago(at: Long): String {
    val s = ((System.currentTimeMillis() - at) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60L -> "just now"
        s < 3_600L -> "${s / 60L} min ago"
        s < 86_400L -> "${s / 3_600L} h ago"
        else -> "${s / 86_400L} d ago"
    }
}

private fun timeLeft(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60L, s % 60L)
}
