package com.fytbt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Arbitrary-color picker: a saturation/value panel plus a hue bar, with a live preview and
 * hex readout. Confirms an opaque ARGB int. Self-contained (no external color-picker lib) so
 * it drops cleanly into both FytRadio and FytBt.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial or (0xFF shl 24), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }       // 0..360
    var sat by remember { mutableFloatStateOf(hsv[1]) }       // 0..1
    var value by remember { mutableFloatStateOf(hsv[2]) }     // 0..1

    val color = Color.hsv(hue, sat, value)
    val argb = color.toArgb()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Custom color",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))

                // Saturation (x) / value (y) panel.
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { off ->
                                sat = (off.x / size.width).coerceIn(0f, 1f)
                                value = (1f - off.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f)))
                    )
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val cx = sat * size.width
                    val cy = (1f - value) * size.height
                    drawCircle(Color.White, radius = 12f, center = Offset(cx, cy))
                    drawCircle(Color.Black, radius = 12f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }

                Spacer(Modifier.height(16.dp))

                // Hue bar.
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { off -> hue = (off.x / size.width).coerceIn(0f, 1f) * 360f }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            }
                        },
                ) {
                    drawRect(
                        Brush.horizontalGradient(
                            (0..6).map { Color.hsv(it * 60f, 1f, 1f) }
                        )
                    )
                    val hx = (hue / 360f) * size.width
                    drawCircle(Color.White, radius = size.height / 2f - 3f, center = Offset(hx, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                }

                Spacer(Modifier.height(16.dp))

                // Preview + hex.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "#%06X".format(0xFFFFFF and argb),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(argb) }) { Text("Use color") }
                }
            }
        }
    }
}
