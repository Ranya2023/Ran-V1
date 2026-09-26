package iq.uor.ran.feature.rooms

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class RoomActivity : ComponentActivity() {
    private var room: Room? = null

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) room?.let { Rooms.join(this, it) } else { Toast.makeText(this, L.t("allow_mic"), Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Identity.init()
        val id = intent.getStringExtra("room")
        room = Rooms.saved.value.firstOrNull { it.id == id } ?: Rooms.active.value
        val r = room ?: run { finish(); return }
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Hub.isBusy()) { Toast.makeText(this, L.t("busy"), Toast.LENGTH_SHORT).show(); finish(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Rooms.join(this, r)
        else mic.launch(Manifest.permission.RECORD_AUDIO)
        setContent { UoRTheme { Surface(color = MaterialTheme.colorScheme.background) { RoomScreen(r) { Rooms.leave(); finish() } } } }
    }
}

@Composable
fun RoomScreen(room: Room, onLeave: () -> Unit) {
    val members by Rooms.members.collectAsState()
    val talking by Rooms.talking.collectAsState()
    val openMic by Rooms.openMic.collectAsState()
    val speaker by Rooms.speaker.collectAsState()
    val active by Rooms.active.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var showInvite by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(200); tick++ } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Brand).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RecordVoiceOver, null, tint = Gold, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(room.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("🔒 " + tr("room_note").format(members.size + 1), color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
            }
            IconButton(onClick = { showInvite = true }) { Icon(Icons.Filled.PersonAdd, tr("invite"), tint = Color.White) }
        }
        if (active == null) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f).padding(8.dp)) {
            item(key = "me") { MemberTile(Store.settings.value.name + " (" + tr("me") + ")", Store.settings.value.code, talking || openMic) }
            items(members, key = { it.session }) { m ->
                val t = tick
                MemberTile(m.name, m.code, t >= 0 && Rooms.isTalking(m.session))
            }
        }
        if (members.isEmpty()) Text(tr("room_empty"), textAlign = TextAlign.Center, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(8.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("open_mic"), fontWeight = FontWeight.SemiBold)
                Text(tr("open_mic_sub"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(openMic, { Rooms.openMic.value = it })
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { Rooms.setSpeaker(!speaker) }, modifier = Modifier.size(56.dp)) {
                Icon(if (speaker) Icons.Filled.VolumeUp else Icons.Filled.Hearing, tr("speaker"))
            }
            // Push-to-talk: hold to speak
            Box(Modifier.size(128.dp).clip(CircleShape).background(if (talking) Red else Green)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        Rooms.talking.value = true
                        tryAwaitRelease()
                        Rooms.talking.value = false
                    })
                }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(44.dp))
                    Text(if (talking) tr("talking") else tr("hold_to_talk"), color = Color.White, fontSize = 12.sp,
                        textAlign = TextAlign.Center)
                }
            }
            FilledIconButton(onClick = onLeave, modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Red)) {
                Icon(Icons.Filled.CallEnd, tr("leave"), tint = Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showInvite) RoomInviteDialog(room) { showInvite = false }
}

@Composable
fun MemberTile(name: String, code: String, talking: Boolean) {
    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).border(if (talking) 4.dp else 0.dp, if (talking) Green else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center) { Avatar(name, code, 62) }
        Text(name, maxLines = 1, fontSize = 13.sp, fontWeight = if (talking) FontWeight.Bold else FontWeight.Normal)
        if (talking) Text("🔊", fontSize = 12.sp)
    }
}

@Composable
fun RoomInviteDialog(room: Room, onDismiss: () -> Unit) {
    val contacts by Store.contacts.collectAsState()
    val bmp = remember(room.id) { Qr.bitmap(Qr.roomQr(room)) }
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("invite") + " – " + room.name) },
        text = {
            if (!pick) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(bmp.asImageBitmap(), null, modifier = Modifier.size(230.dp).background(Color.White))
                Text(tr("room_qr_explain"), fontSize = 12.sp, textAlign = TextAlign.Center)
            } else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(contacts.filter { !it.blocked }, key = { it.code }) { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        Messenger.sendControl(c.code, c.name, "room", Rooms.json(room).toString())
                        Toast.makeText(ctx, L.t("sent"), Toast.LENGTH_SHORT).show()
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, c.code, 36); Spacer(Modifier.width(10.dp)); Text(c.name, Modifier.weight(1f))
                        Icon(Icons.Filled.Send, null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (pick) onDismiss() else pick = true }) { Text(if (pick) tr("close") else tr("send_to_contact")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("close")) } }
    )
}
