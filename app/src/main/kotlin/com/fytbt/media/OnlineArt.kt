package com.fytbt.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up album art online when the phone doesn't send it over Bluetooth (Spotify, iPhones —
 * they transmit only title/artist text over AVRCP). Uses Apple's free iTunes Search API: no key,
 * no auth, and the artwork URL is upgradeable to a high resolution.
 *
 * Everything here blocks on the network, so callers must run it off the main thread. Results are
 * cached per "artist|title" so repeats / the same track don't re-hit the network.
 */
object OnlineArt {
    private const val TAG = "FytBt"
    private val urlCache = HashMap<String, String?>()  // key -> artwork URL (null = looked up, no match)

    /** Returns a hi-res artwork URL for the track, or null if none found / no query possible. */
    fun lookupArtworkUrl(artist: String?, title: String?): String? {
        val query = buildQuery(artist, title) ?: return null
        synchronized(urlCache) { if (urlCache.containsKey(query)) return urlCache[query] }
        val result = runCatching {
            val endpoint = "https://itunes.apple.com/search?term=" +
                URLEncoder.encode(query, "UTF-8") + "&entity=song&limit=1"
            val body = httpGet(endpoint) ?: return@runCatching null
            val results = JSONObject(body).optJSONArray("results")
            if (results == null || results.length() == 0) return@runCatching null
            val art100 = results.getJSONObject(0).optString("artworkUrl100", "")
            if (art100.isBlank()) null
            // 100x100 thumbnail URL → ask for a big version instead.
            else art100.replace("100x100bb", "600x600bb")
        }.getOrElse {
            Log.w(TAG, "iTunes art lookup failed: ${it.message}")
            null
        }
        synchronized(urlCache) { urlCache[query] = result }
        if (result != null) Log.i(TAG, "online art match for \"$query\"")
        return result
    }

    fun download(urlString: String): Bitmap? = runCatching {
        (URL(urlString).openConnection() as HttpURLConnection).run {
            connectTimeout = 6000
            readTimeout = 6000
            doInput = true
            inputStream.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrElse {
        Log.w(TAG, "online art download failed: ${it.message}")
        null
    }

    private fun httpGet(endpoint: String): String? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
        }
        return try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Strip the noise that throws off matching: "(feat. …)", "[Official Video]", "- Topic", etc. */
    private fun buildQuery(artist: String?, title: String?): String? {
        val t = clean(title)
        val a = clean(artist?.substringBefore(',')) // first/primary artist
        val combined = listOf(a, t).filter { it.isNotBlank() }.joinToString(" ")
        return combined.ifBlank { null }
    }

    private fun clean(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s
            .replace(Regex("\\(.*?\\)"), " ")       // (feat. X), (Official Audio)
            .replace(Regex("\\[.*?]"), " ")          // [Official Video]
            .replace(Regex("(?i)\\bofficial\\b|\\bvideo\\b|\\baudio\\b|\\blyrics?\\b|\\bhd\\b"), " ")
            .replace(Regex("(?i)\\s*-\\s*topic\\s*$"), " ")
            .replace(Regex("(?i)\\bfeat\\.?\\b.*$"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
