package iq.uor.ran.feature.setup

import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Optional profile picture: stored on the phone, shared only with people you talk to. */
object Profile {
    private const val SIZE = 512

    fun file(ctx: Context) = File(ctx.filesDir, "avatar.jpg")
    fun contactAvatar(ctx: Context, code: String) = File(File(ctx.filesDir, "avatars").apply { mkdirs() }, code.filter { it.isDigit() } + ".jpg")

    /** Copies the picked image, shrinks it and returns the stored path (or null). */
    fun saveAvatar(ctx: Context, uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) null else {
            val square = crop(bmp)
            FileOutputStream(file(ctx)).use { square.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            square.recycle(); bmp.recycle()
            file(ctx).absolutePath
        }
    } catch (e: Exception) { null }

    private fun crop(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        val x = (b.width - side) / 2; val y = (b.height - side) / 2
        val sq = Bitmap.createBitmap(b, x, y, side, side)
        return if (side > SIZE) Bitmap.createScaledBitmap(sq, SIZE, SIZE, true) else sq
    }

    fun bytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()

    fun hash(path: String): String {
        val b = bytes(path) ?: return ""
        return b64(MessageDigest.getInstance("SHA-256").digest(b)).take(16)
    }

    fun myHash(): String = Store.settings.value.avatar.let { if (it.isBlank()) "" else hash(it) }

    /** Stores an avatar received from a contact. */
    fun saveContactAvatar(ctx: Context, code: String, data: ByteArray): String? = try {
        val f = contactAvatar(ctx, code)
        f.writeBytes(data)
        f.absolutePath
    } catch (e: Exception) { null }
}
