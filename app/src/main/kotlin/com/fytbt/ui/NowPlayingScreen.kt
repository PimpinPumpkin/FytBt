package com.fytbt.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fytbt.media.ArtColors
import com.fytbt.media.PlaybackInfo
import com.fytbt.media.TrackMetadata
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    hasNotificationAccess: Boolean,
    hasSession: Boolean,
    metadata: TrackMetadata?,
    playback: PlaybackInfo?,
    artColors: ArtColors?,
    onGrantNotificationAccess: () -> Unit,
    onRefreshAccess: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
) {
    if (!hasNotificationAccess) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            NotificationAccessGate(
                onGrant = onGrantNotificationAccess,
                onRecheck = onRefreshAccess,
            )
        }
        return
    }

    // Accent for the controls/slider pulled from the album art (cross-fades on track change).
    val accent by animateColorAsState(
        targetValue = artColors?.let { Color(it.accent) } ?: MaterialTheme.colorScheme.primary,
        animationSpec = tween(600), label = "accent",
    )
    val onAccent by animateColorAsState(
        targetValue = artColors?.let { Color(it.onAccent) } ?: MaterialTheme.colorScheme.onPrimary,
        animationSpec = tween(600), label = "onAccent",
    )

    val artBitmap = metadata?.art?.asImageBitmap()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Background = the album art, zoomed (so blur edges aren't visible) + heavily blurred,
        // with a dark scrim for legibility. Updates with the track automatically.
        if (artBitmap != null) {
            Image(
                bitmap = artBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(45.dp),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Artwork takes all leftover vertical space and sizes itself square to fit it,
            // so the info + playhead + controls below always stay on-screen (no scroll).
            Box(
                modifier = Modifier.weight(1f, fill = true).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Artwork(art = metadata?.art?.asImageBitmap())
            }
            Spacer(Modifier.height(16.dp))
            TrackInfo(metadata = metadata, hasSession = hasSession)
            Spacer(Modifier.height(14.dp))
            ProgressRow(
                metadata = metadata,
                playback = playback,
                accent = accent,
            )
            Spacer(Modifier.height(16.dp))
            TransportControls(
                playback = playback,
                enabled = hasSession,
                accent = accent,
                onAccent = onAccent,
                onPlayPause = onPlayPause,
                onSkipNext = onSkipNext,
                onSkipPrev = onSkipPrev,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Artwork(art: ImageBitmap?) {
    // Square, sized to whatever the parent weighted Box leaves — fills its height, capped
    // by width. Centerpiece of the screen, soft shadow.
    Card(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(1f),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "♪",
                    fontSize = 140.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

@Composable
private fun TrackInfo(metadata: TrackMetadata?, hasSession: Boolean) {
    val title = metadata?.title?.takeIf { it.isNotBlank() }
        ?: if (hasSession) "Unknown track" else "Nothing playing"
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    val sub = listOfNotNull(
        metadata?.artist?.takeIf { it.isNotBlank() },
        metadata?.album?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
    if (sub != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            sub,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (!hasSession) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Pair or connect a phone in the Bluetooth tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProgressRow(
    metadata: TrackMetadata?,
    playback: PlaybackInfo?,
    accent: Color,
) {
    val durationMs = (metadata?.durationMs ?: 0L).coerceAtLeast(0L)

    // Tick every 500 ms so the playhead glides between MediaController updates.
    val live by produceState(initialValue = SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val livePos = if (playback != null) {
        val base = playback.positionMs
        if (playback.isPlaying && playback.updatedAtElapsedMs > 0) {
            base + ((live - playback.updatedAtElapsedMs) * playback.playbackSpeed).toLong()
        } else base
    } else 0L
    val displayedPos = livePos.coerceIn(0L, if (durationMs > 0) durationMs else livePos.coerceAtLeast(0L))
    val sliderValue = if (durationMs > 0) {
        (displayedPos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // Display-only: this is an A2DP-sink relay, and AVRCP seek-to-position isn't honored by the
    // source (dragging just snapped back). So the playhead shows position but isn't a scrubber.
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = {},
            valueRange = 0f..1f,
            enabled = false,
            colors = SliderDefaults.colors(
                disabledThumbColor = accent,
                disabledActiveTrackColor = accent,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(
                formatTime(displayedPos),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (durationMs > 0) formatTime(durationMs) else "—:—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private enum class Glyph { PREV, PLAY, PAUSE, NEXT }

@Composable
private fun TransportControls(
    playback: PlaybackInfo?,
    enabled: Boolean,
    accent: Color,
    onAccent: Color,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(
            glyph = Glyph.PREV,
            size = 72.dp,
            enabled = enabled && (playback?.canSkipPrev != false),
            prominent = false,
            accent = accent,
            onAccent = onAccent,
            onClick = onSkipPrev,
        )
        CircleButton(
            glyph = if (playback?.isPlaying == true) Glyph.PAUSE else Glyph.PLAY,
            size = 96.dp,
            enabled = enabled,
            prominent = true,
            accent = accent,
            onAccent = onAccent,
            onClick = onPlayPause,
        )
        CircleButton(
            glyph = Glyph.NEXT,
            size = 72.dp,
            enabled = enabled && (playback?.canSkipNext != false),
            prominent = false,
            accent = accent,
            onAccent = onAccent,
            onClick = onSkipNext,
        )
    }
}

@Composable
private fun CircleButton(
    glyph: Glyph,
    size: Dp,
    enabled: Boolean,
    prominent: Boolean,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
) {
    val colors = if (prominent) {
        // Big play/pause uses the album-art accent.
        IconButtonDefaults.filledIconButtonColors(
            containerColor = accent,
            contentColor = onAccent,
            disabledContainerColor = accent.copy(alpha = 0.25f),
            disabledContentColor = onAccent.copy(alpha = 0.4f),
        )
    } else {
        IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
    }
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(percent = 50),
        colors = colors,
    ) {
        val tint = LocalContentColor.current
        // Glyph occupies ~38% of the button so it reads cleanly at car-screen distance.
        Canvas(modifier = Modifier.size(size * 0.38f)) {
            drawTransportGlyph(glyph, tint)
        }
    }
}

private fun DrawScope.drawTransportGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    when (glyph) {
        Glyph.PLAY -> {
            // Right-pointing triangle, optically centered (nudged right a hair).
            val inset = w * 0.06f
            val path = Path().apply {
                moveTo(inset, 0f)
                lineTo(w - inset * 0.5f, h / 2f)
                lineTo(inset, h)
                close()
            }
            drawPath(path, color)
        }
        Glyph.PAUSE -> {
            val barW = w * 0.30f
            val r = CornerRadius(barW * 0.25f, barW * 0.25f)
            drawRoundRect(color, topLeft = Offset(0f, 0f), size = Size(barW, h), cornerRadius = r)
            drawRoundRect(color, topLeft = Offset(w - barW, 0f), size = Size(barW, h), cornerRadius = r)
        }
        Glyph.NEXT -> {
            // Two right triangles + a trailing bar.
            val triW = w * 0.42f
            val barW = w * 0.14f
            drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(triW, h / 2f); lineTo(0f, h); close()
            }, color)
            drawPath(Path().apply {
                moveTo(triW * 0.95f, 0f); lineTo(triW * 0.95f + triW, h / 2f); lineTo(triW * 0.95f, h); close()
            }, color)
            drawRoundRect(
                color,
                topLeft = Offset(w - barW, 0f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.3f, barW * 0.3f),
            )
        }
        Glyph.PREV -> {
            // Leading bar + two left triangles (mirror of NEXT).
            val triW = w * 0.42f
            val barW = w * 0.14f
            drawRoundRect(
                color,
                topLeft = Offset(0f, 0f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.3f, barW * 0.3f),
            )
            drawPath(Path().apply {
                moveTo(w, 0f); lineTo(w - triW, h / 2f); lineTo(w, h); close()
            }, color)
            drawPath(Path().apply {
                moveTo(w - triW * 0.95f, 0f); lineTo(w - triW * 0.95f - triW, h / 2f); lineTo(w - triW * 0.95f, h); close()
            }, color)
        }
    }
}

@Composable
private fun NotificationAccessGate(onGrant: () -> Unit, onRecheck: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Media access needed", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                "To show what your phone is playing and offer play/pause, this app needs " +
                    "Notification Access. That's the only way Android exposes the connected " +
                    "phone's media session to a third-party app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Open Notification Access settings", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onRecheck,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("I've enabled it — check again", fontSize = 15.sp) }
        }
    }
}
