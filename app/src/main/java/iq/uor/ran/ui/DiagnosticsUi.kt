package iq.uor.ran.ui

import iq.uor.ran.core.data.*
import iq.uor.ran.feature.call.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Red/orange strip on the home screen: tells the user exactly what stops calls from working. */
@Composable
fun DiagnosticsBanner() {
    val ctx = LocalContext.current
    val ip by Hub.myIp.collectAsState()
    val running by Hub.serviceRunning.collectAsState()
    val settings by Store.settings.collectAsState()
    var issues by remember { mutableStateOf(emptyList<Issue>()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(ip, running, settings) {
        while (true) { issues = NetworkDoctor.check(ctx); delay(5000) }
    }
    val top = issues.maxByOrNull { it.severity } ?: return
    Surface(color = if (top.severity == 2) Red else Gold, modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (top.severity == 2) Icons.Filled.ErrorOutline else Icons.Filled.WarningAmber, null,
                tint = if (top.severity == 2) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black)
            Spacer(Modifier.width(8.dp))
            Text(tr(top.titleKey) + (if (issues.size > 1) "  (+${issues.size - 1})" else ""),
                color = if (top.severity == 2) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(tr("fix_it"), color = if (top.severity == 2) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black)
        }
    }
    if (open) DiagnosticsDialog { open = false }
}

@SuppressLint("BatteryLife")
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val issues = remember(refresh) { NetworkDoctor.check(ctx) }

    fun fix(what: String) {
        runCatching {
            when (what) {
                "wifi" -> ctx.startActivity(Intent(AndroidSettings.ACTION_WIFI_SETTINGS))
                "app" -> ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                "notif" -> ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                "battery" -> ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                "fullscreen" -> if (Build.VERSION.SDK_INT >= 34)
                    ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                "resume" -> { Store.save(Store.settings.value.copy(paused = false, autoStart = true)); CallService.start(ctx) }
                else -> {}
            }
        }
        refresh++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📶 " + tr("connection_check")) },
        text = {
            if (issues.isEmpty()) Text("✅ " + tr("all_good"), color = Green, fontWeight = FontWeight.Bold)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(issues) { i ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text((if (i.severity == 2) "⛔ " else "⚠ ") + tr(i.titleKey), fontWeight = FontWeight.Bold,
                            color = if (i.severity == 2) Red else MaterialTheme.colorScheme.onSurface)
                        Text(tr(i.bodyKey), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (i.fix != "help") TextButton(onClick = { fix(i.fix) }) { Text(tr("fix_it")) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh++ }) { Text(tr("recheck")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("close")) } }
    )
}
