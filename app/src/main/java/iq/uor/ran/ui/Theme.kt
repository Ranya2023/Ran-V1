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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val Brand = Color(0xFF1A3C6E)
val Gold = Color(0xFFFFC83D)
val Green = Color(0xFF1DB954)
val Red = Color(0xFFE53935)

val LocalLang = staticCompositionLocalOf { "en" }

@Composable
fun tr(key: String): String = L.get(LocalLang.current, key)

/** Theme (system / light / dark) + language + right-to-left for Kurdish and Arabic. */
@Composable
fun UoRTheme(content: @Composable () -> Unit) {
    val s by Store.settings.collectAsState()
    val dark = when (s.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8FB4FF), onPrimary = Color(0xFF0B1B33), secondary = Gold, tertiary = Green,
            background = Color(0xFF0E1420), surface = Color(0xFF0E1420),
            surfaceVariant = Color(0xFF1D2636), primaryContainer = Color(0xFF223556)
        )
    } else {
        lightColorScheme(
            primary = Brand, secondary = Color(0xFFB8860B), tertiary = Green,
            surfaceVariant = Color(0xFFEDF1F7), primaryContainer = Color(0xFFDCE6F6)
        )
    }
    val dir = if (s.lang == "en") LayoutDirection.Ltr else LayoutDirection.Rtl
    CompositionLocalProvider(LocalLang provides s.lang, LocalLayoutDirection provides dir) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

fun initials(name: String): String =
    name.trim().split(" ").map { w -> w.dropWhile { !it.isLetterOrDigit() } }.filter { it.isNotBlank() }
        .take(2).joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** Stable colour per person for avatars. */
fun avatarColor(key: String): Color {
    val colors = listOf(0xFF1A73E8, 0xFF0F9D58, 0xFFE8710A, 0xFF9334E6, 0xFFD93025, 0xFF12A4AF, 0xFFB5179E, 0xFF5F6368)
    return Color(colors[(key.hashCode() and 0x7fffffff) % colors.size])
}
