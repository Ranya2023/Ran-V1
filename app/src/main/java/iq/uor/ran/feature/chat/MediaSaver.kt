package iq.uor.ran.feature.chat

import iq.uor.ran.core.data.*
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Saves received photos and videos into the phone's gallery (like WhatsApp's "Media visibility"). */
object MediaSaver {
    fun save(ctx: Context, m: Msg): Boolean {
        if (m.path.isEmpty() || !(m.kind == "image" || m.kind == "video")) return false
        val src = File(m.path)
        if (!src.exists()) return false
        val mime = m.mime.ifBlank { if (m.kind == "image") "image/jpeg" else "video/mp4" }
        val name = m.fileName.ifBlank { "Ran_" + m.time + if (m.kind == "image") ".jpg" else ".mp4" }
        val folder = (if (m.kind == "image") Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/Ran"
        return try {
            val cr = ctx.contentResolver
            val collection = if (m.kind == "image") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = cr.insert(collection, values) ?: return false
            cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return false
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) { false }
    }

    /** Called for every received photo/video when "save automatically" is on. */
    fun autoSave(ctx: Context, m: Msg) {
        if (Store.settings.value.autoSaveMedia) runCatching { save(ctx, m) }
    }
}
