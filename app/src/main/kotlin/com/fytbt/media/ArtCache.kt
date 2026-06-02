package com.fytbt.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * On-disk cache of album art keyed by "artist|title". Lets art that was fetched online (while on
 * WiFi or tethered) persist and show OFFLINE on later drives — the common case, since people replay
 * the same albums/playlists. Stored in filesDir (survives reboots/cache-clears).
 */
object ArtCache {
    private const val TAG = "FytBt"

    // Bumped to v2 when the matching logic changed — abandons any wrongly-matched art cached by the
    // old (take-first-result) lookup so those tracks re-fetch with the verified matcher.
    private fun dir(context: Context): File =
        File(context.filesDir, "artcache_v2").apply {
            if (!exists()) {
                mkdirs()
                runCatching { File(context.filesDir, "artcache").deleteRecursively() }
            }
        }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), Integer.toHexString(key.lowercase().hashCode()) + ".jpg")

    fun get(context: Context, key: String): Bitmap? = runCatching {
        val f = fileFor(context, key)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()

    fun put(context: Context, key: String, bmp: Bitmap) {
        runCatching {
            fileFor(context, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.onFailure { Log.w(TAG, "art cache write failed: ${it.message}") }
    }
}
