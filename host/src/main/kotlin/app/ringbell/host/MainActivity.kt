package app.ringbell.host

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.AudioManager
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ListenerService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { HostScreen() }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault())
private fun fmt(ms: Long) = timeFmt.format(Date(ms))

@Composable
private fun HostScreen() {
    val entries by History.entries.collectAsStateWithLifecycle()
    val conn by Conn.state.collectAsStateWithLifecycle()
    val active = entries.filter { it.state == RingState.RINGING }
    val past = entries.filter { it.state != RingState.RINGING }

    // Re-evaluate permission state whenever we come back from a settings screen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Ring Host", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (conn.connected) Color(0xFF2E7D32) else Color(0xFFB71C1C))
                )
                Spacer(Modifier.width(8.dp))
                Text(conn.text, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { SetupCard(refresh) }
        item { VolumeCard(refresh, active.isNotEmpty()) }

        if (active.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ringing now", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (active.size > 1) TextButton(onClick = { Actions.dismissAll() }) { Text("Dismiss all") }
                }
            }
            items(active, key = { "a" + it.id }) { e ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Rang at ${fmt(e.at)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = { Actions.dismiss(e.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Dismiss") }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (past.isNotEmpty()) TextButton(onClick = { History.clearResolved() }) { Text("Clear") }
            }
        }
        if (past.isEmpty()) {
            item { Text("No rings yet.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(past, key = { "h" + it.id }) { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Text("Rang ${fmt(e.at)}", style = MaterialTheme.typography.bodySmall)
                    Text(describe(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun describe(e: Entry): String {
    val at = e.resolvedAt?.let { fmt(it) }.orEmpty()
    return when (e.state) {
        RingState.RINGING -> "Ringing"
        RingState.DISMISSED -> "Dismissed $at"
        RingState.CANCELLED -> "Cancelled by sender $at"
        RingState.MISSED -> "Missed (received late; host was offline)"
    }
}

/** Shows only the things that still need to be allowed so alarms reliably fire. */
@Composable
private fun SetupCard(refreshKey: Int) {
    val ctx = LocalContext.current

    // refreshKey changes on every ON_RESUME, which forces these checks to be re-read.
    val notificationsOk = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val fullScreenOk = Build.VERSION.SDK_INT < 34 ||
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    val batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (notificationsOk && fullScreenOk && batteryOk) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setup so rings can't be missed", style = MaterialTheme.typography.titleSmall)

            if (!notificationsOk) {
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notifications") }
            }
            if (!fullScreenOk) {
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow full-screen alerts (lock screen)") }
            }
            if (!batteryOk) {
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Don't restrict battery usage") }
            }
        }
    }
}

/** Rings use the ALARM volume stream: show it, let the user set it, and let them hear the sound. */
@Composable
private fun VolumeCard(refreshKey: Int, anyRinging: Boolean) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1) }
    var volume by remember(refreshKey) { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_ALARM)) }
    var testing by remember { mutableStateOf(false) }
    val player = remember { AlarmPlayer(ctx.applicationContext) }

    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(testing) {
        if (testing) {
            player.start(vibrate = false)
            volume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            delay(8_000)
            testing = false
        } else {
            player.stop()
        }
    }
    LaunchedEffect(anyRinging) { if (anyRinging) testing = false }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Alarm volume", style = MaterialTheme.typography.titleSmall)
            Text(
                "Rings play on the Alarm volume, which is separate from the ringer and media volume. " +
                    "Turn it up so a ring can't go unheard.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = volume.toFloat(),
                onValueChange = {
                    volume = it.roundToInt()
                    try {
                        am.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
                    } catch (_: SecurityException) {
                    }
                },
                valueRange = 0f..max.toFloat(),
                steps = max - 1,
            )
            if (volume == 0) {
                Text(
                    "Alarm volume is at zero. Ring Host raises it to about 70% when a ring arrives, " +
                        "but set it here so you know it's right.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { testing = !testing }, enabled = !anyRinging) {
                    Text(if (testing) "Stop test" else "Test ring sound")
                }
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) {
                    Text("Sound settings")
                }
            }
        }
    }
}
