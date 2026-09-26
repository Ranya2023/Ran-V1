package iq.uor.ran.ui

import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Identity.init()
        setContent { UoRTheme { Surface(color = MaterialTheme.colorScheme.background) { AboutScreen { finish() } } } }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().background(Brand).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
            Text(tr("about_app"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Call, null, tint = Gold, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(AppInfo.APP_NAME, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(tr("version") + " " + AppInfo.VERSION, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(tr("tagline"), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("developer"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(AppInfo.DEVELOPER, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(AppInfo.ORGANISATION, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Email, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(AppInfo.EMAIL, Modifier.weight(1f))
                        TextButton(onClick = { open("mailto:" + AppInfo.EMAIL) }) { Text(tr("write")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(AppInfo.ADDRESS, Modifier.weight(1f))
                    }
                    if (AppInfo.WHATSAPP.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Chat, null, tint = Green)
                        Spacer(Modifier.width(10.dp))
                        Text("WhatsApp", Modifier.weight(1f))
                        Button(onClick = { open(AppInfo.whatsappLink) },
                            colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text(tr("open")) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("🔒 " + tr("privacy"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(tr("about_privacy"), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(tr("safety_number") + ": " + Identity.fingerprint(), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("📶 " + tr("how_it_works"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(tr("about_how"), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(tr("about_free"), fontSize = 13.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(30.dp))
        }
    }
}
