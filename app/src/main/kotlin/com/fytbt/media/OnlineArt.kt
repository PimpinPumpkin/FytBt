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

    /**
     * Returns a hi-res artwork URL for the track, or null if none found / no confident match.
     * Fetches several candidates and verifies the result's artist + title actually match what we
     * asked for — taking iTunes' first result blindly was pulling wrong albums. A weak match
     * returns null (placeholder) rather than showing the wrong cover.
     */
    fun lookupArtworkUrl(artist: String?, title: String?): String? {
        val query = buildQuery(artist, title) ?: return null
        synchronized(urlCache) { if (urlCache.containsKey(query)) return urlCache[query] }
        val wantTitle = norm(title)
        val wantArtist = norm(artist?.substringBefore(','))
        val result = runCatching {
            val endpoint = "https://itunes.apple.com/search?term=" +
                URLEncoder.encode(query, "UTF-8") + "&entity=song&limit=12"
            val body = httpGet(endpoint) ?: return@runCatching null
            val results = JSONObject(body).optJSONArray("results") ?: return@runCatching null

            var bestUrl: String? = null
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val art = r.optString("artworkUrl100", "")
                if (art.isBlank()) continue
                val titleScore = tokenOverlap(wantTitle, norm(r.optString("trackName")))
                val artistScore = tokenOverlap(wantArtist, norm(r.optString("artistName")))
                // Title carries most of the weight; artist confirms it's the right release.
                val score = titleScore * 0.65 + artistScore * 0.35
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = art.replace("100x100bb", "600x600bb")
                }
            }
            // Confident match required: most of the title words present AND the artist at least
            // partially matches (or, if we had no artist to check, a strong title match alone).
            val confident = bestScore >= 0.5 &&
                (wantArtist.isBlank() || tokenOverlapAtBest(results, wantArtist) >= 0.34)
            if (confident) bestUrl else null
        }.getOrElse {
            Log.w(TAG, "iTunes art lookup failed: ${it.message}")
            null
        }
        synchronized(urlCache) { urlCache[query] = result }
        Log.i(TAG, if (result != null) "online art match for \"$query\"" else "no confident art match for \"$query\"")
        return result
    }

    /** Best artist-token overlap across all results (used as a sanity gate). */
    private fun tokenOverlapAtBest(results: org.json.JSONArray, wantArtist: String): Double {
        var best = 0.0
        for (i in 0 until results.length()) {
            val a = norm(results.getJSONObject(i).optString("artistName"))
            best = maxOf(best, tokenOverlap(wantArtist, a))
        }
        return best
    }

    /** Fraction of [want]'s word-tokens that appear in [have]. 1.0 = all present. */
    private fun tokenOverlap(want: String, have: String): Double {
        val w = want.split(' ').filter { it.isNotBlank() }
        if (w.isEmpty()) return 0.0
        val h = have.split(' ').filter { it.isNotBlank() }.toSet()
        return w.count { it in h }.toDouble() / w.size
    }

    /** Lowercase, drop the noise clean() leaves, strip to alphanumerics+spaces for comparison. */
    private fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return clean(s).lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
