package com.fytbt.media

import android.graphics.Bitmap
import android.media.session.PlaybackState

/** Colors extracted from the current album art (ARGB ints), for theming Now Playing. */
data class ArtColors(
    val accent: Int,
    val onAccent: Int,
    val background: Int,
)

data class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: Bitmap?,
    val durationMs: Long,
    val sourcePackage: String?,
) {
    val hasAnything: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()
}

data class PlaybackInfo(
    val state: Int,
    val positionMs: Long,
    val updatedAtElapsedMs: Long,
    val playbackSpeed: Float,
    val actions: Long,
) {
    val isPlaying: Boolean
        get() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    val canPlay: Boolean get() = actions and PlaybackState.ACTION_PLAY != 0L
    val canPause: Boolean get() = actions and PlaybackState.ACTION_PAUSE != 0L
    val canSkipNext: Boolean get() = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
    val canSkipPrev: Boolean get() = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
    val canSeek: Boolean get() = actions and PlaybackState.ACTION_SEEK_TO != 0L
}
