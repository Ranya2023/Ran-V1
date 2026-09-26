package iq.uor.ran.ui

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.distinctUntilChangedBy
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import iq.uor.ran.feature.setup.Countries
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private var pendingVideo: Peer? = null
    private val camLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val p = pendingVideo; pendingVideo = null
            if (p != null) placeCall(p, ok)
        }

    private var startTab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Identity.init(); Messenger.init(this)
        if (Store.settings.value.registered) startUp()
        startTab = intent.getIntExtra("tab", 0)

        setContent {
            UoRTheme {
                val s by Store.settings.collectAsState()
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (!s.registered) OnboardingScreen { startUp() }
                    else MainScreen(startTab, { placeCall(it, false) }, { placeCall(it, true) }, ::openChat) { placeCall(it, false, support = true) }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Hub.call.distinctUntilChangedBy { it.phase }.collect {
                    if (it.phase == Phase.INCOMING) startActivity(Intent(this@MainActivity, CallActivity::class.java))
                }
            }
        }
    }

    private fun startUp() {
        if (!Store.settings.value.autoStart) Store.save(Store.settings.value.copy(autoStart = true))
        CallService.start(this)
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab = intent.getIntExtra("tab", startTab)
    }

    override fun onResume() {
        super.onResume()
        if (Store.settings.value.registered) CallService.instance?.announceNow() ?: CallService.start(this)
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    fun placeCall(peer: Peer, video: Boolean, support: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); toast(L.t("allow_mic")); return
        }
        if (video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingVideo = peer; camLauncher.launch(Manifest.permission.CAMERA); return
        }
        val svc = CallService.instance
        if (svc == null) { CallService.start(this); toast(L.t("starting")); return }
        if (!Hub.isBusy()) svc.dial(peer, video)
        startActivity(Intent(this, CallActivity::class.java))
    }

    fun openChat(code: String, name: String) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra("code", code).putExtra("name", name))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/* ============================== Onboarding ============================== */

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val s by Store.settings.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var country by remember { mutableStateOf(Countries.IRAQ) }
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(s.name) }
    var avatar by remember { mutableStateOf(s.avatar) }
    var obVisible by remember { mutableStateOf(s.visible) }
    var pickCountry by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val problem = Countries.problem(country, number)

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) avatar = Profile.saveAvatar(ctx, uri) ?: avatar
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Call, null, tint = Gold, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(tr("app_name"), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(tr("tagline"), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))

        if (step == 0) {
            LanguagePicker()
            Spacer(Modifier.height(18.dp))
            Text(tr("enter_your_number"), fontWeight = FontWeight.SemiBold)
            Text(tr("number_is_id"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickCountry = true }) { Text("${country.flag} +${country.dial}") }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    number, { number = it.filter(Char::isDigit).take(15) },
                    label = { Text(tr("phone")) }, singleLine = true, isError = number.isNotEmpty() && problem != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f)
                )
            }
            if (number.isNotEmpty() && problem != null)
                Text("⚠ " + tr(problem), color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            else if (problem == null)
                Text("✅ " + Countries.pretty(Countries.e164(country, number)), color = Green, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Button(onClick = { if (problem == null) step = 1 else Toast.makeText(ctx, L.t(problem), Toast.LENGTH_LONG).show() },
                enabled = problem == null, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(tr("next"), fontSize = 17.sp) }
        } else {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (avatar.isNotEmpty()) AvatarImage(avatar, 104)
                else Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.Person, null, Modifier.size(56.dp)) }
                FilledIconButton(onClick = { photo.launch("image/*") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                }
            }
            Text(tr("photo_optional"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(tr("full_name")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(tr("your_number"), fontWeight = FontWeight.Bold)
                    Text(Countries.pretty(Countries.e164(country, number)), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { step = 0 }) { Text(tr("change")) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(tr("show_available"), tr("show_available_sub"), obVisible) { obVisible = it }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (name.trim().length < 2) { Toast.makeText(ctx, L.t("err_name"), Toast.LENGTH_LONG).show() }
                else {
                    val e164 = Countries.e164(country, number)
                    Store.save(Store.settings.value.copy(registered = true, code = e164, phone = e164,
                        name = name.trim(), avatar = avatar, avatarHash = if (avatar.isBlank()) "" else Profile.hash(avatar),
                        visible = obVisible))
                    onDone()
                }
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(tr("start"), fontSize = 17.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Text("🔒 " + tr("privacy_note"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
    if (pickCountry) CountryDialog(onPick = { country = it; pickCountry = false }) { pickCountry = false }
}

@Composable
fun CountryDialog(onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = Countries.all.filter { q.isBlank() || it.name.lowercase().contains(q.lowercase()) || it.dial.contains(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("choose_country")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text(tr("search")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(list, key = { it.iso + it.dial }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(c.flag, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, Modifier.weight(1f))
                            Text("+" + c.dial, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("close")) } }
    )
}

@Composable
fun LanguagePicker() {
    val s by Store.settings.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("en" to "English", "ckb" to "کوردی", "ar" to "العربية").forEach { (code, label) ->
            FilterChip(selected = s.lang == code, onClick = { Store.save(s.copy(lang = code)) }, label = { Text(label) })
        }
    }
}

/* ============================== Main ============================== */

private data class NavTab(val key: String, val icon: ImageVector)

@Composable
fun MainScreen(startTab: Int, onCall: (Peer) -> Unit, onVideo: (Peer) -> Unit, onChat: (String, String) -> Unit,
               onSupport: (Peer) -> Unit = {}) {
    var tab by remember(startTab) { mutableIntStateOf(startTab) }
    val tabs = listOf(
        NavTab("chats", Icons.Filled.Chat),
        NavTab("contacts", Icons.Filled.People),
        NavTab("calls", Icons.Filled.Call),
        NavTab("team", Icons.Filled.HealthAndSafety),
        NavTab("settings", Icons.Filled.Settings)
    )
    val call by Hub.call.collectAsState()
    val version by Hub.chatVersion.collectAsState()
    val unread = remember(version) { runCatching { Messenger.totalUnread() }.getOrDefault(0) }
    val ctx = LocalContext.current
    var showQr by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = {
                            if (i == 0 && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Icon(t.icon, null) }
                            else Icon(t.icon, null)
                        },
                        label = { Text(tr(t.key), fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Header(onQr = { showQr = true })
            DiagnosticsBanner()
            if (call.phase == Phase.ACTIVE || call.phase == Phase.OUTGOING || call.phase == Phase.INCOMING) {
                Surface(color = Green, modifier = Modifier.fillMaxWidth()
                    .clickable { ctx.startActivity(Intent(ctx, CallActivity::class.java)) }) {
                    Text(tr("return_to_call").format(call.peer?.name ?: ""), color = Color.White,
                        modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
            }
            val room by Rooms.active.collectAsState()
            room?.let { r ->
                Surface(color = Color(0xFF6A1B9A), modifier = Modifier.fillMaxWidth().clickable {
                    ctx.startActivity(Intent(ctx, RoomActivity::class.java).putExtra("room", r.id))
                }) {
                    Text("🎙 " + tr("return_to_room").format(r.name), color = Color.White,
                        modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatsTab(onChat)
                    1 -> ContactsTab(onCall, onVideo, onChat, onSupport)
                    2 -> CallsTab(onCall, onVideo, onChat)
                    3 -> TeamTab(onCall, onChat)
                    else -> SettingsTab()
                }
            }
        }
    }
    if (showQr) MyQrDialog { showQr = false }
}

@Composable
fun Header(onQr: () -> Unit) {
    val s by Store.settings.collectAsState()
    val ip by Hub.myIp.collectAsState()
    val peers by Hub.peers.collectAsState()
    val running by Hub.serviceRunning.collectAsState()
    val conflict by Hub.codeConflict.collectAsState()

    Column(Modifier.fillMaxWidth().background(Brand).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("my_code").uppercase(), color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(Store.formatCode(s.code), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(s.name + if (s.org.isNotBlank()) " • ${s.org}" else "", color = Color.White.copy(alpha = .85f), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(when { !running -> Red; s.dnd -> Gold; else -> Green })
                    Spacer(Modifier.width(6.dp))
                    Text(when { !running -> tr("offline"); s.dnd -> tr("dnd"); else -> tr("ready") }, color = Color.White, fontSize = 13.sp)
                }
                Text(if (ip.isEmpty()) tr("no_wifi") else ip, color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                val cloud by Hub.cloud.collectAsState()
                Text((if (s.visible) "👁 " + tr("visible") else "🕶 " + tr("hidden")) + " • ${peers.size} " + tr("online") +
                        when (cloud) { 2 -> " • 🌐"; 1 -> " • 🌐…"; else -> "" },
                    color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = onQr, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)) {
                    Icon(Icons.Filled.QrCode, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text(tr("my_qr"), fontSize = 12.sp)
                }
            }
        }
        if (conflict) {
            Spacer(Modifier.height(6.dp))
            Text("⚠ " + tr("code_conflict"), color = Gold, fontSize = 13.sp)
        }
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 12) { Box(Modifier.size(size.dp).clip(CircleShape).background(color)) }

@Composable
fun Avatar(name: String, key: String = name, size: Int = 46) {
    val me by Store.settings.collectAsState()
    val contacts by Store.contacts.collectAsState()
    val path = when {
        key == me.code -> me.avatar
        else -> contacts.firstOrNull { it.code == key }?.avatar ?: ""
    }
    if (path.isNotEmpty() && java.io.File(path).exists()) { AvatarImage(path, size); return }
    val c = avatarColor(key)
    Box(Modifier.size(size.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
    }
}

/** Profile picture loaded from the phone's own storage. */
@Composable
fun AvatarImage(path: String, size: Int = 46) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(CircleShape))
    else Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
}

/* ---------------- QR: show mine, scan others ---------------- */

@Composable
fun rememberQrScanner(onResult: (Any?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> onResult(Qr.parse(r.contents)) }
    val prompt = tr("scan_prompt")
    return {
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt)
                .setBeepEnabled(false).setOrientationLocked(false)
        )
    }
}

@Composable
fun ScanResultHandler(result: Any?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    when (result) {
        is Contact -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(tr("add_contact")) },
            text = {
                Column {
                    Text(result.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(tr("code") + ": " + Store.formatCode(result.code))
                    if (result.phone.isNotBlank()) Text(tr("phone") + ": " + result.phone)
                    if (result.key.isNotBlank()) Text("🔒 " + tr("verified_by_qr") + "\n" +
                            Identity.fingerprint(unb64(result.key)), fontSize = 13.sp, color = Green)
                }
            },
            confirmButton = { TextButton(onClick = { Store.upsertContact(result); onDone()
                Toast.makeText(ctx, L.t("saved"), Toast.LENGTH_SHORT).show() }) { Text(tr("save")) } },
            dismissButton = { TextButton(onClick = onDone) { Text(tr("cancel")) } }
        )
        is SetupProfile -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(tr("join_org")) },
            text = { Text(tr("join_org_q").format(result.org, result.contacts.size)) },
            confirmButton = { TextButton(onClick = { Qr.applySetup(result); CallService.instance?.announceNow(); onDone() }) { Text(tr("join")) } },
            dismissButton = { TextButton(onClick = onDone) { Text(tr("cancel")) } }
        )
        is Room -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("🎙 " + tr("join_room")) },
            text = { Text(result.name) },
            confirmButton = { TextButton(onClick = {
                Rooms.add(result); onDone()
                ctx.startActivity(Intent(ctx, RoomActivity::class.java).putExtra("room", result.id))
            }) { Text(tr("join")) } },
            dismissButton = { TextButton(onClick = onDone) { Text(tr("cancel")) } }
        )
        else -> LaunchedEffect(Unit) { Toast.makeText(ctx, L.t("qr_invalid"), Toast.LENGTH_SHORT).show(); onDone() }
    }
}

@Composable
fun MyQrDialog(onDismiss: () -> Unit) {
    val s by Store.settings.collectAsState()
    val ip by Hub.myIp.collectAsState()
    val bmp = remember(s.code, s.name, s.phone, ip) { Qr.bitmap(Qr.myCard()) }
    var scanned by remember { mutableStateOf<Any?>(null) }
    var hasScan by remember { mutableStateOf(false) }
    val scan = rememberQrScanner { scanned = it; hasScan = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("my_qr")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(bmp.asImageBitmap(), null, modifier = Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
                Spacer(Modifier.height(8.dp))
                Text(s.name, fontWeight = FontWeight.Bold)
                Text(Store.formatCode(s.code), fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(tr("safety_number") + ": " + Identity.fingerprint(), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("qr_explain"), fontSize = 12.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = scan) { Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text(tr("scan_qr")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("close")) } }
    )
    if (hasScan) ScanResultHandler(scanned) { hasScan = false; scanned = null }
}

/* ---------------- Chats ---------------- */

@Composable
fun ChatsTab(onChat: (String, String) -> Unit) {
    val version by Hub.chatVersion.collectAsState()
    val online by Hub.online.collectAsState()
    val convs = remember(version) { runCatching { Messenger.conversations() }.getOrDefault(emptyList()) }
    if (convs.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Forum, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(tr("no_chats"), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(convs, key = { it.peer }) { c ->
            Row(Modifier.fillMaxWidth().clickable { onChat(c.peer, c.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(c.name, c.peer)
                    if (online.containsKey(c.peer)) Box(Modifier.align(Alignment.BottomEnd)) { StatusDot(Green) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val preview = Messenger.preview(c.last)
                    Text((if (c.last.out) "✓ " else "") + preview, maxLines = 1, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(DateUtils.getRelativeTimeSpanString(c.last.time).toString(), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.unread > 0) Badge(containerColor = Green) { Text("${c.unread}", color = Color.White) }
                }
            }
        }
    }
}

/* ---------------- Contacts ---------------- */

@Composable
fun ContactsTab(onCall: (Peer) -> Unit, onVideo: (Peer) -> Unit, onChat: (String, String) -> Unit, onSupport: (Peer) -> Unit = {}) {
    val peers by Hub.peers.collectAsState()
    val online by Hub.online.collectAsState()
    val saved by Store.contacts.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf<Any?>(null) }
    var hasScan by remember { mutableStateOf(false) }
    val scan = rememberQrScanner { scanned = it; hasScan = true }

    val q = query.trim().lowercase()
    fun match(name: String, code: String, phone: String) =
        q.isEmpty() || name.lowercase().contains(q) || code.contains(q) || (phone.isNotEmpty() && phone.contains(q))
    val myContacts = saved.filter { !it.blocked && match(it.name, it.code, it.phone) }
    val savedCodes = saved.map { it.code }.toSet()
    val available = peers.filter { it.code !in savedCodes && match(it.name, it.code, it.phone) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
            item {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(tr("search_hint")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            item { SectionTitle(tr("my_contacts") + " (${myContacts.size})") }
            if (myContacts.isEmpty()) item {
                Text(tr("no_contacts"), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            items(myContacts, key = { "c" + it.code }) { c ->
                val live = online[c.code]
                PersonRow(
                    name = c.name, key = c.code,
                    sub = Store.formatCode(c.code) + (if (c.phone.isNotBlank()) " • ${c.phone}" else ""),
                    online = live != null, status = live?.status ?: 0, verified = c.verified,
                    onCall = { onCall(live ?: Peer(c.code, c.name, c.ip, c.phone)) },
                    onChat = { onChat(c.code, c.name) },
                    menu = { close ->
                        DropdownMenuItem(text = { Text("🎥 " + tr("video_call")) },
                            onClick = { onVideo(live ?: Peer(c.code, c.name, c.ip, c.phone)); close() })
                        DropdownMenuItem(text = { Text(if (c.team) "➖ " + tr("remove_team") else "🛡 " + tr("add_team")) },
                            onClick = { Store.setTeam(c.code, !c.team); close() })
                        DropdownMenuItem(text = { Text(tr("block")) }, onClick = { Store.setBlocked(c.code, c.name, true); close() })
                        DropdownMenuItem(text = { Text(tr("delete")) }, onClick = { Store.removeContact(c.code); close() })
                    }
                )
            }
            item { SectionTitle(tr("available_now") + " (${available.size})") }
            if (available.isEmpty()) item {
                Text(tr("no_available"), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            items(available, key = { "p" + it.code }) { p ->
                PersonRow(
                    name = p.name, key = p.code,
                    sub = Store.formatCode(p.code) + (if (p.phone.isNotBlank()) " • ${p.phone}" else ""),
                    online = true, status = p.status, verified = false,
                    onCall = { onCall(p) }, onChat = { onChat(p.code, p.name) },
                    menu = { close ->
                        DropdownMenuItem(text = { Text("🎥 " + tr("video_call")) }, onClick = { onVideo(p); close() })
                        DropdownMenuItem(text = { Text(tr("save_contact")) },
                            onClick = { Store.upsertContact(Contact(p.code, p.name, p.phone, p.ip)); close() })
                        DropdownMenuItem(text = { Text(tr("block")) }, onClick = { Store.setBlocked(p.code, p.name, true); close() })
                    }
                )
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
            SmallFloatingActionButton(onClick = scan) { Icon(Icons.Filled.QrCodeScanner, tr("scan_qr")) }
            Spacer(Modifier.height(10.dp))
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.PersonAdd, tr("add_contact")) }
        }
    }
    if (showAdd) AddContactDialog { showAdd = false }
    if (hasScan) ScanResultHandler(scanned) { hasScan = false; scanned = null }
}

@Composable
fun PersonRow(
    name: String, key: String, sub: String, online: Boolean, status: Int, verified: Boolean,
    onCall: () -> Unit, onChat: () -> Unit, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onChat() }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(name, key)
            Box(Modifier.align(Alignment.BottomEnd)) {
                StatusDot(when { !online -> Color.Gray; status == 1 -> Red; status == 2 -> Gold; else -> Green })
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, null, tint = Green, modifier = Modifier.size(16.dp)) }
            }
            Text(sub + when { !online -> ""; status == 1 -> " • " + tr("in_a_call"); status == 2 -> " • " + tr("dnd"); else -> " • " + tr("online") },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onChat) { Icon(Icons.Filled.Chat, tr("message"), tint = MaterialTheme.colorScheme.primary) }
        FilledIconButton(onClick = onCall, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green)) {
            Icon(Icons.Filled.Call, tr("call"), tint = Color.White)
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu(this) { open = false } }
        }
    }
}

@Composable
fun AddContactDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var acCountry by remember { mutableStateOf(Countries.byNumber(Store.settings.value.code) ?: Countries.IRAQ) }
    var pickAcCountry by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("add_contact")) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(tr("full_name")) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickAcCountry = true }) { Text("${acCountry.flag} +${acCountry.dial}") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(id, { id = it.filter(Char::isDigit).take(15) },
                        label = { Text(tr("phone")) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val prob = Countries.problem(acCountry, id)
                if (prob != null) { Toast.makeText(ctx, L.t(prob), Toast.LENGTH_LONG).show(); return@TextButton }
                val e = Countries.e164(acCountry, id)
                Store.upsertContact(Contact(e, name.ifBlank { Countries.pretty(e) }, e))
                onDismiss()
            }) { Text(tr("save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } }
    )
    if (pickAcCountry) CountryDialog(onPick = { acCountry = it; pickAcCountry = false }) { pickAcCountry = false }
}

/* ---------------- Keypad ---------------- */

@Composable
fun KeypadTab(onCall: (Peer) -> Unit, onVideo: (Peer) -> Unit, onChat: (String, String) -> Unit) {
    var number by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val online by Hub.online.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var country by remember { mutableStateOf(Countries.byNumber(Store.settings.value.code) ?: Countries.IRAQ) }
    var pickCountry by remember { mutableStateOf(false) }
    val e164 = Countries.e164(country, number)
    val problem = Countries.problem(country, number)
    val match = online[e164] ?: Store.contact(e164)?.let { Peer(it.code, it.name, it.ip) }

    fun go(chat: Boolean, video: Boolean = false) {
        if (problem != null) { Toast.makeText(ctx, L.t(problem), Toast.LENGTH_LONG).show(); return }
        busy = true
        scope.launch {
            val p = online[e164] ?: Discovery.resolve(e164, 2500)
            busy = false
            when {
                p == null -> Toast.makeText(ctx, L.t("not_found"), Toast.LENGTH_LONG).show()
                chat -> onChat(p.code, p.name)
                video -> onVideo(p)
                else -> onCall(p)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (number.isEmpty()) tr("enter_number") else Countries.pretty(e164),
            fontSize = if (number.isEmpty()) 22.sp else 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Text(when {
            busy -> tr("searching")
            number.isEmpty() -> tr("keypad_hint")
            problem != null -> "⚠ " + tr(problem)
            match != null -> match.name + if (online.containsKey(e164)) " • " + tr("online") else ""
            else -> ""
        }, color = if (problem != null) Red else if (match != null) Green else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp), fontSize = 14.sp)
        OutlinedButton(onClick = { pickCountry = true }) { Text("${country.flag} +${country.dial}  ▾") }
        Spacer(Modifier.height(6.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                row.forEach { k ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(70.dp).clip(CircleShape).clickable {
                            number = if (k == "⌫") number.dropLast(1) else if (number.length < 15) number + k else number
                        }) {
                        Box(contentAlignment = Alignment.Center) { Text(k, fontSize = 26.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            FloatingActionButton(onClick = { go(true) }, shape = CircleShape, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Chat, tr("message"))
            }
            FloatingActionButton(onClick = { go(false) }, containerColor = Green, shape = CircleShape,
                modifier = Modifier.size(70.dp)) { Icon(Icons.Filled.Call, tr("call"), tint = Color.White, modifier = Modifier.size(32.dp)) }
            FloatingActionButton(onClick = { go(false, video = true) }, shape = CircleShape, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Videocam, tr("video_call"))
            }
        }
        if (pickCountry) CountryDialog(onPick = { country = it; pickCountry = false }) { pickCountry = false }
    }
}

/* ---------------- Calls (history + keypad) ---------------- */

@Composable
fun CallsTab(onCall: (Peer) -> Unit, onVideo: (Peer) -> Unit, onChat: (String, String) -> Unit) {
    var keypad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !keypad, onClick = { keypad = false }, label = { Text(tr("call_history")) },
                leadingIcon = { Icon(Icons.Filled.History, null, Modifier.size(18.dp)) })
            FilterChip(selected = keypad, onClick = { keypad = true }, label = { Text(tr("keypad")) },
                leadingIcon = { Icon(Icons.Filled.Dialpad, null, Modifier.size(18.dp)) })
        }
        Box(Modifier.weight(1f)) { if (keypad) KeypadTab(onCall, onVideo, onChat) else RecentsTab(onCall) }
    }
}

/* ---------------- Recents ---------------- */

@Composable
fun RecentsTab(onCall: (Peer) -> Unit) {
    val history by Store.history.collectAsState()
    val online by Hub.online.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(tr("call_history"))
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(tr("clear")) }
        }
        if (history.isEmpty()) Text(tr("no_calls"), modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(history) { h ->
                val (icon, color, label) = when (h.type) {
                    "out" -> Triple(Icons.Filled.CallMade, Green, tr("outgoing"))
                    "in" -> Triple(Icons.Filled.CallReceived, MaterialTheme.colorScheme.primary, tr("incoming_l"))
                    "declined" -> Triple(Icons.Filled.CallEnd, Gold, tr("declined"))
                    else -> Triple(Icons.Filled.CallMissed, Red, tr("missed"))
                }
                val name = Store.contact(h.code)?.name ?: h.name
                Row(Modifier.fillMaxWidth().clickable { onCall(online[h.code] ?: Peer(h.code, name, h.ip)) }
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = color)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.SemiBold, color = if (h.type == "missed") Red else Color.Unspecified)
                        Text("$label • ${Store.formatCode(h.code)}" + if (h.duration > 0) " • ${DateUtils.formatElapsedTime(h.duration)}" else "",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(DateUtils.getRelativeTimeSpanString(h.time).toString(), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(tr("clear_history_q")) },
        confirmButton = { TextButton(onClick = { Store.clearHistory(); confirmClear = false }) { Text(tr("clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("cancel")) } }
    )
}

/* ---------------- Settings ---------------- */

@SuppressLint("BatteryLife")
@Composable
fun SettingsTab() {
    val s by Store.settings.collectAsState()
    val contacts by Store.contacts.collectAsState()
    val ctx = LocalContext.current
    var name by remember(s.name) { mutableStateOf(s.name) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Profile.saveAvatar(ctx, uri)?.let {
            Store.save(Store.settings.value.copy(avatar = it, avatarHash = Profile.hash(it)))
            CallService.instance?.announceNow()
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    var showSetup by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<Any?>(null) }
    var hasScan by remember { mutableStateOf(false) }
    val scan = rememberQrScanner { scanned = it; hasScan = true }

    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val batteryOk = remember(refresh) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val fullScreenOk = remember(refresh) { if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true }
    val notifOk = remember(refresh) { nm.areNotificationsEnabled() }
    val micOk = remember(refresh) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val blocked = contacts.filter { it.blocked }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SectionTitle(tr("profile")) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    if (s.avatar.isNotEmpty()) AvatarImage(s.avatar, 72) else Avatar(s.name, s.code, 72)
                    FilledIconButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.PhotoCamera, null, Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Store.formatCode(s.code), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(tr("number_cannot_change"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(tr("full_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.avatar.isNotEmpty()) TextButton(onClick = { Store.save(s.copy(avatar = "", avatarHash = "")) }) { Text(tr("remove_photo")) }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (name.trim().length < 2) Toast.makeText(ctx, L.t("err_name"), Toast.LENGTH_SHORT).show()
                    else { Store.save(s.copy(name = name.trim())); CallService.instance?.announceNow()
                        Toast.makeText(ctx, L.t("saved"), Toast.LENGTH_SHORT).show() }
                }) { Text(tr("save")) }
            }
            Text(tr("safety_number") + ": " + Identity.fingerprint(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { SectionTitle(tr("privacy")) }
        item {
            SwitchRow(tr("show_available"), tr("show_available_sub"), s.visible) { Store.save(s.copy(visible = it)); CallService.instance?.announceNow() }
            SwitchRow(tr("share_phone"), tr("share_phone_sub"), s.sharePhone) { Store.save(s.copy(sharePhone = it)) }
            Text(tr("who_can_call"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Row {
                FilterChip(selected = s.whoCanCall == "everyone", onClick = { Store.save(s.copy(whoCanCall = "everyone")) }, label = { Text(tr("everyone")) })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = s.whoCanCall == "contacts", onClick = { Store.save(s.copy(whoCanCall = "contacts")) }, label = { Text(tr("contacts_only")) })
            }
            if (blocked.isNotEmpty()) {
                Text(tr("blocked") + " (${blocked.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                blocked.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.name + " • " + Store.formatCode(b.code), Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = { Store.setBlocked(b.code, b.name, false) }) { Text(tr("unblock")) }
                    }
                }
            }
        }

        item { SectionTitle(tr("media")) }
        item {
            SwitchRow(tr("auto_save"), tr("auto_save_sub"), s.autoSaveMedia) { Store.save(s.copy(autoSaveMedia = it)) }
        }

        item { SectionTitle(tr("appearance")) }
        item {
            Text(tr("language"), fontWeight = FontWeight.SemiBold)
            LanguagePicker()
            Text(tr("theme"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system", "light", "dark").forEach { t ->
                    FilterChip(selected = s.theme == t, onClick = { Store.save(s.copy(theme = t)) }, label = { Text(tr("theme_$t")) })
                }
            }
        }

        item { SectionTitle(tr("calls")) }
        item {
            SwitchRow(tr("dnd"), tr("dnd_sub"), s.dnd) { Store.save(s.copy(dnd = it)); CallService.instance?.announceNow() }
            SwitchRow(tr("auto_start"), tr("auto_start_sub"), s.autoStart) { Store.save(s.copy(autoStart = it)) }
            SwitchRow(tr("never_sleep"), tr("never_sleep_sub"), s.alwaysAwake) { Store.save(s.copy(alwaysAwake = it)) }
        }

        item { SectionTitle(tr("organization")) }
        item {
            if (s.org.isNotBlank()) Text(tr("member_of").format(s.org), fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showSetup = true }) { Text(tr("create_setup_qr")) }
                OutlinedButton(onClick = scan) { Text(tr("scan_qr")) }
            }
        }

        item { SectionTitle(tr("connection_check")) }
        item {
            Text(tr("connection_check_sub"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("📶 " + tr("check_now"))
            }
        }

        item { SectionTitle(tr("about")) }
        item {
            OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, AboutActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()) { Text("ℹ️ " + tr("about_app")) }
            Spacer(Modifier.height(8.dp))
            if (s.paused) Button(onClick = {
                Store.save(s.copy(paused = false, autoStart = true)); CallService.start(ctx)
                Toast.makeText(ctx, L.t("resumed"), Toast.LENGTH_LONG).show()
            }, colors = ButtonDefaults.buttonColors(containerColor = Green), modifier = Modifier.fillMaxWidth()) {
                Text("▶ " + tr("resume_service"))
            } else OutlinedButton(onClick = {
                Store.save(s.copy(paused = true))
                ctx.startService(Intent(ctx, CallService::class.java).setAction(CallService.ACTION_STOP))
                Toast.makeText(ctx, L.t("paused_note"), Toast.LENGTH_LONG).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("⏸ " + tr("pause_service")) }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showSetup) SetupQrDialog { showSetup = false }
    if (showDiagnostics) DiagnosticsDialog { showDiagnostics = false }
    if (hasScan) ScanResultHandler(scanned) { hasScan = false; scanned = null }
}

@Composable
fun SetupQrDialog(onDismiss: () -> Unit) {
    val s by Store.settings.collectAsState()
    val contacts by Store.contacts.collectAsState()
    var org by remember { mutableStateOf(s.org) }
    var visible by remember { mutableStateOf(true) }
    var qr by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("create_setup_qr")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (qr == null) {
                    Text(tr("setup_explain"), fontSize = 13.sp)
                    OutlinedTextField(org, { org = it.take(40) }, label = { Text(tr("org_name")) }, singleLine = true)
                    SwitchRow(tr("show_available"), tr("setup_visible_sub"), visible) { visible = it }
                } else {
                    val bmp = remember(qr) { Qr.bitmap(qr!!, 900) }
                    Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                    Text(tr("setup_scan_this"), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            if (qr == null) TextButton(onClick = {
                if (org.isNotBlank()) {
                    Store.save(s.copy(org = org.trim()))
                    qr = Qr.setupProfile(org.trim(), s.lang, visible, s.whoCanCall, contacts.filter { !it.blocked })
                }
            }) { Text(tr("create")) }
            else TextButton(onClick = onDismiss) { Text(tr("close")) }
        },
        dismissButton = { if (qr == null) TextButton(onClick = onDismiss) { Text(tr("cancel")) } }
    )
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun FixRow(title: String, ok: Boolean?, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onFix() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Warning; null -> Icons.Filled.Settings }, null,
            tint = when (ok) { true -> Green; false -> Red; null -> MaterialTheme.colorScheme.primary })
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f))
        if (ok != true) Text(tr("open"), color = MaterialTheme.colorScheme.primary)
    }
}
