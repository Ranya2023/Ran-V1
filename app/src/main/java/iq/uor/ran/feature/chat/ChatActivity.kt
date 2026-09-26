package iq.uor.ran.feature.chat

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChatActivity : ComponentActivity() {
    private lateinit var code: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Identity.init(); Messenger.init(this)
        code = intent.getStringExtra("code") ?: run { finish(); return }
        val name = intent.getStringExtra("name") ?: code
        setContent { UoRTheme { Surface(color = MaterialTheme.colorScheme.background) { ChatScreen(code, name, ::finish) } } }
    }

    override fun onResume() { super.onResume(); Hub.openChat = code; Messenger.markRead(code) }
    override fun onPause() { super.onPause(); if (Hub.openChat == code) Hub.openChat = null }
}

@Composable
fun ChatScreen(code: String, fallbackName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by Hub.chatVersion.collectAsState()
    val online by Hub.online.collectAsState()
    val contacts by Store.contacts.collectAsState()
    val contact = contacts.firstOrNull { it.code == code }
    val name = contact?.name ?: fallbackName
    val msgs = remember(version) { runCatching { Messenger.messages(code) }.getOrDefault(emptyList()) }
    val list = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val live = online[code]

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1)
        if (Hub.openChat == code) runCatching { Messenger.markRead(code) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val err = Messenger.sendFile(code, name, uri)
            if (err != null) Toast.makeText(ctx, L.t(err), Toast.LENGTH_LONG).show()
        }
    }

    fun call(video: Boolean) {
        if (CallService.instance == null) { CallService.start(ctx); return }
        if (!Hub.isBusy()) CallService.instance?.dial(live ?: Peer(code, name, contact?.ip ?: ""), video)
        ctx.startActivity(Intent(ctx, CallActivity::class.java))
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> call(ok) }

    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(Modifier.fillMaxWidth().background(Brand).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
            Avatar(name, code, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (contact?.verified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, null, tint = Gold, modifier = Modifier.size(16.dp)) }
                }
                Text(Store.formatCode(code) + " • " + (if (live != null) tr("online") else tr("offline")),
                    color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
            }
            IconButton(onClick = {
                if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) call(true)
                else camPerm.launch(android.Manifest.permission.CAMERA)
            }) { Icon(Icons.Filled.Videocam, tr("video_call"), tint = Color.White) }
            IconButton(onClick = { call(false) }) { Icon(Icons.Filled.Call, tr("call"), tint = Color.White) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (contact == null) DropdownMenuItem(text = { Text(tr("save_contact")) }, onClick = {
                        Store.upsertContact(Contact(code, name, live?.phone ?: "", live?.ip ?: "")); menu = false })
                    DropdownMenuItem(text = { Text(tr("delete_chat")) }, onClick = { confirmDelete = true; menu = false })
                    DropdownMenuItem(text = { Text(tr("block")) }, onClick = { Store.setBlocked(code, name, true); menu = false; onBack() })
                }
            }
        }
        Text("🔒 " + tr("e2e_note"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(8.dp)) {
            items(msgs, key = { it.id }) { m -> Bubble(m) }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch("*/*") }) { Icon(Icons.Filled.AttachFile, tr("attach")) }
            IconButton(onClick = { picker.launch("image/*") }) { Icon(Icons.Filled.Image, tr("photo")) }
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text(tr("type_message")) },
                maxLines = 5, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { Messenger.sendText(code, name, text); text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.Filled.Send, tr("send"))
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("delete_chat")) },
        confirmButton = { TextButton(onClick = { Messenger.deleteChat(code); confirmDelete = false }) { Text(tr("delete")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("cancel")) } }
    )
}

@Composable
fun Bubble(m: Msg) {
    val ctx = LocalContext.current
    val mine = m.out
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = m.path.isNotEmpty() || m.status == 2) {
                if (m.status == 2 && mine) Messenger.retryNow(m.id) else openFile(ctx, m)
            }.padding(8.dp)) {
            when (m.kind) {
                "image", "video" -> {
                    val thumb by produceState<Bitmap?>(null, m.path) { value = loadThumb(m.path, m.kind == "video") }
                    Box(contentAlignment = Alignment.Center) {
                        thumb?.let {
                            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                                modifier = Modifier.width(260.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)))
                        } ?: Box(Modifier.size(200.dp, 140.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .15f)))
                        if (m.kind == "video") Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)),
                            contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                    }
                    if (m.path.isNotEmpty()) TextButton(onClick = {
                        val ok = MediaSaver.save(ctx, m)
                        Toast.makeText(ctx, L.t(if (ok) "saved_to_gallery" else "no_app"), Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.Download, null, Modifier.size(16.dp)); Text(" " + tr("save_to_gallery"), fontSize = 12.sp) }
                }
                "text" -> {}
                "rollcall", "checkin", "room" -> SpecialBubble(m)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.InsertDriveFile, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(m.fileName, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(Formatter.formatShortFileSize(ctx, m.size), fontSize = 12.sp)
                    }
                }
            }
            if (m.body.isNotBlank() && (m.kind == "text" || Messenger.isFile(m.kind))) Text(m.body, fontSize = 16.sp, modifier = Modifier.padding(top = if (m.kind == "text") 0.dp else 6.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(DateUtils.formatDateTime(ctx, m.time, DateUtils.FORMAT_SHOW_TIME), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    when (m.status) {
                        0 -> Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(13.dp))
                        1 -> Icon(Icons.Filled.DoneAll, null, modifier = Modifier.size(14.dp), tint = Green)
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, modifier = Modifier.size(13.dp), tint = Red)
                            Text(" " + tr("tap_retry"), fontSize = 10.sp, color = Red)
                        }
                    }
                }
            }
        }
    }
}

/** Roll calls, check-ins and voice-room invitations inside a chat. */
@Composable
fun SpecialBubble(m: Msg) {
    val ctx = LocalContext.current
    val o = Safety.parse(m.body)
    when (m.kind) {
        "rollcall" -> Column {
            Text("📋 " + tr("rollcall"), fontWeight = FontWeight.Bold)
            Text(o.optString("title").ifBlank { tr("are_you_safe") })
            if (!m.out) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = {
                    Safety.checkIn(ctx, "safe", rid = o.optString("rid"), to = listOf(Store.contact(m.peer) ?: Contact(m.peer, m.peerName)))
                }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("✅ " + tr("im_safe")) }
                Button(onClick = {
                    Safety.checkIn(ctx, "help", rid = o.optString("rid"), to = listOf(Store.contact(m.peer) ?: Contact(m.peer, m.peerName)))
                }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("🆘") }
            }
        }
        "checkin" -> Column {
            val help = o.optString("status") == "help"
            Text(if (help) "🆘 " + tr("need_help") else "✅ " + tr("im_safe"), fontWeight = FontWeight.Bold,
                color = if (help) Red else Green, fontSize = 17.sp)
            if (o.optString("note").isNotBlank()) Text(o.optString("note"))
            Safety.mapLink(o)?.let { link ->
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) } }) {
                    Icon(Icons.Filled.Place, null); Text(" " + tr("location") + " (±${o.optInt("acc")} m)")
                }
            }
        }
        "room" -> Column {
            val r = Rooms.fromJson(m.body)
            Text("🎙 " + tr("room_invite"), fontWeight = FontWeight.Bold)
            Text(r?.name ?: "")
            if (r != null) Button(onClick = {
                Rooms.add(r)
                ctx.startActivity(Intent(ctx, RoomActivity::class.java).putExtra("room", r.id))
            }, modifier = Modifier.padding(top = 6.dp)) { Text(tr("join")) }
        }
    }
}

private fun openFile(ctx: android.content.Context, m: Msg) {
    if (m.path.isEmpty()) return
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(m.path))
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, m.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, L.t("no_app"), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { }
}

private suspend fun loadThumb(path: String, video: Boolean): Bitmap? = withContext(Dispatchers.IO) {
    try {
        if (video) {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(path); r.frameAtTime } finally { runCatching { r.release() } }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 600 && o.outHeight / (sample * 2) >= 400) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) { null }
}
