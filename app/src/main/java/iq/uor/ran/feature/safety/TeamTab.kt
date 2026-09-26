package iq.uor.ran.feature.safety

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Team tab: safety check-in, coordinator roll call, team status, voice rooms. */
@Composable
fun TeamTab(onCall: (Peer) -> Unit, onChat: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    val contacts by Store.contacts.collectAsState()
    val version by Hub.chatVersion.collectAsState()
    val online by Hub.online.collectAsState()
    val rollCalls by Safety.rollCalls.collectAsState()
    val rooms by Rooms.saved.collectAsState()
    val team = contacts.filter { it.team && !it.blocked }
    val latest = remember(version) { runCatching { Safety.responses() }.getOrDefault(emptyMap()) }

    var confirmHelp by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var newRollCall by remember { mutableStateOf(false) }
    var openRollCall by remember { mutableStateOf<RollCall?>(null) }
    var newRoom by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<Any?>(null) }
    var hasScan by remember { mutableStateOf(false) }
    val scan = rememberQrScanner { scanned = it; hasScan = true }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        Store.save(Store.settings.value.copy(shareLocation = r.values.any { it }))
    }

    fun send(status: String) {
        if (team.isEmpty()) { Toast.makeText(ctx, L.t("team_empty"), Toast.LENGTH_LONG).show(); return }
        val n = Safety.checkIn(ctx, status, note.trim())
        note = ""
        Toast.makeText(ctx, L.t("sent_to_n").format(n), Toast.LENGTH_SHORT).show()
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        /* ---- my status ---- */
        item { SectionTitle(tr("safety_check")) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(tr("safety_explain").format(team.size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text(tr("note_optional")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { send("safe") }, modifier = Modifier.weight(1f).height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("✅ " + tr("im_safe"), fontSize = 16.sp) }
                        Button(onClick = { confirmHelp = true }, modifier = Modifier.weight(1f).height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("🆘 " + tr("need_help"), fontSize = 16.sp) }
                    }
                    SwitchRow(tr("share_location"), tr("share_location_sub"), s.shareLocation) { on ->
                        if (on && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                            locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else Store.save(s.copy(shareLocation = on))
                    }
                }
            }
        }

        /* ---- roll call ---- */
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("rollcalls"))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (team.isEmpty()) Toast.makeText(ctx, L.t("team_empty"), Toast.LENGTH_LONG).show() else newRollCall = true }) {
                    Icon(Icons.Filled.Campaign, null); Spacer(Modifier.width(4.dp)); Text(tr("start_rollcall"))
                }
            }
        }
        if (rollCalls.isEmpty()) item {
            Text(tr("rollcall_explain"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        items(rollCalls.take(5), key = { "rc" + it.id }) { rc ->
            val resp = remember(version, rc.id) { runCatching { Safety.responses(rc.id) }.getOrDefault(emptyMap()) }
            val safe = resp.values.count { Safety.parse(it.body).optString("status") == "safe" }
            val help = resp.values.count { Safety.parse(it.body).optString("status") == "help" }
            val waiting = rc.members.size - resp.size
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openRollCall = rc }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rc.title, fontWeight = FontWeight.SemiBold)
                        Text(DateUtils.getRelativeTimeSpanString(rc.time).toString(), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("✅ $safe  🆘 $help  ⏳ $waiting", fontWeight = FontWeight.Bold,
                        color = if (help > 0) Red else MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        /* ---- team members ---- */
        item { SectionTitle(tr("my_team") + " (${team.size})") }
        if (team.isEmpty()) item {
            Text(tr("team_how"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        items(team, key = { "t" + it.code }) { c ->
            val st = latest[c.code]
            val o = st?.let { Safety.parse(it.body) }
            val help = o?.optString("status") == "help"
            Row(Modifier.fillMaxWidth().clickable { onChat(c.code, c.name) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(c.name, c.code, 42)
                    if (online.containsKey(c.code)) Box(Modifier.align(Alignment.BottomEnd)) { StatusDot(Green, 10) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (st == null) tr("no_status")
                        else (if (help) "🆘 " + tr("need_help") else "✅ " + tr("im_safe")) + " • " +
                                DateUtils.getRelativeTimeSpanString(st.time) +
                                (if (o?.optString("note").isNullOrBlank()) "" else " • " + o?.optString("note")),
                        fontSize = 12.sp, color = if (help) Red else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                o?.let { Safety.mapLink(it) }?.let { link ->
                    IconButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
                        Icon(Icons.Filled.Place, tr("location"), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = { onCall(online[c.code] ?: Peer(c.code, c.name, c.ip, c.phone)) }) {
                    Icon(Icons.Filled.Call, tr("call"), tint = Green)
                }
            }
        }

        /* ---- voice rooms ---- */
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("voice_rooms"))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = scan) { Icon(Icons.Filled.QrCodeScanner, tr("scan_qr")) }
                TextButton(onClick = { newRoom = true }) { Icon(Icons.Filled.Add, null); Text(tr("create")) }
            }
        }
        if (rooms.isEmpty()) item {
            Text(tr("rooms_explain"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        items(rooms, key = { "r" + it.id }) { r ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable {
                ctx.startActivity(Intent(ctx, RoomActivity::class.java).putExtra("room", r.id))
            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RecordVoiceOver, null, tint = Color(0xFF6A1B9A), modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Text(r.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                FilledTonalButton(onClick = { ctx.startActivity(Intent(ctx, RoomActivity::class.java).putExtra("room", r.id)) }) { Text(tr("join")) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("delete")) }, onClick = { Rooms.remove(r.id); menu = false })
                    }
                }
            }
        }
    }

    if (confirmHelp) AlertDialog(
        onDismissRequest = { confirmHelp = false },
        title = { Text("🆘 " + tr("need_help")) },
        text = { Text(tr("help_confirm").format(team.size)) },
        confirmButton = { TextButton(onClick = { confirmHelp = false; send("help") }) { Text(tr("send"), color = Red) } },
        dismissButton = { TextButton(onClick = { confirmHelp = false }) { Text(tr("cancel")) } }
    )
    if (newRollCall) {
        var title by remember { mutableStateOf(L.t("are_you_safe")) }
        AlertDialog(
            onDismissRequest = { newRollCall = false },
            title = { Text(tr("start_rollcall")) },
            text = {
                Column {
                    Text(tr("rollcall_to").format(team.size), fontSize = 13.sp)
                    OutlinedTextField(title, { title = it.take(120) }, label = { Text(tr("question")) })
                }
            },
            confirmButton = { TextButton(onClick = {
                Safety.startRollCall(title.ifBlank { L.t("are_you_safe") })?.let { openRollCall = it }
                newRollCall = false
            }) { Text(tr("send")) } },
            dismissButton = { TextButton(onClick = { newRollCall = false }) { Text(tr("cancel")) } }
        )
    }
    openRollCall?.let { rc -> RollCallDialog(rc, onCall) { openRollCall = null } }
    if (newRoom) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newRoom = false },
            title = { Text(tr("create_room")) },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text(tr("room_name")) }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (name.isNotBlank()) {
                    val r = Rooms.create(name)
                    newRoom = false
                    ctx.startActivity(Intent(ctx, RoomActivity::class.java).putExtra("room", r.id))
                }
            }) { Text(tr("create")) } },
            dismissButton = { TextButton(onClick = { newRoom = false }) { Text(tr("cancel")) } }
        )
    }
    if (hasScan) ScanResultHandler(scanned) { hasScan = false; scanned = null }
}

@Composable
fun RollCallDialog(rc: RollCall, onCall: (Peer) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version by Hub.chatVersion.collectAsState()
    val online by Hub.online.collectAsState()
    val resp = remember(version) { runCatching { Safety.responses(rc.id) }.getOrDefault(emptyMap()) }
    // people who need help first, then no answer, then safe
    val order = rc.members.sortedBy { code ->
        when (resp[code]?.let { Safety.parse(it.body).optString("status") }) { "help" -> 0; null -> 1; else -> 2 }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📋 " + rc.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(order) { code ->
                    val c = Store.contact(code)
                    val m = resp[code]
                    val o = m?.let { Safety.parse(it.body) }
                    val status = o?.optString("status")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (status) { "help" -> "🆘"; "safe" -> "✅"; else -> "⏳" }, fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c?.name ?: code, fontWeight = FontWeight.SemiBold)
                            Text(if (m == null) tr("no_answer_yet") else DateUtils.getRelativeTimeSpanString(m.time).toString() +
                                    (if (o?.optString("note").isNullOrBlank()) "" else " • " + o?.optString("note")),
                                fontSize = 12.sp, color = if (status == "help") Red else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        o?.let { Safety.mapLink(it) }?.let { link ->
                            IconButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
                                Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { onCall(online[code] ?: Peer(code, c?.name ?: code, c?.ip ?: "")) }) {
                            Icon(Icons.Filled.Call, null, tint = Green)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("close")) } },
        dismissButton = { TextButton(onClick = { Safety.deleteRollCall(rc.id); onDismiss() }) { Text(tr("delete")) } }
    )
}
