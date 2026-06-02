package com.fytbt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fytbt.media.ArtColors

enum class Pane { NowPlaying, Phone, Contacts, Bluetooth }

@Composable
fun RootScreen(
    artColors: ArtColors?,
    fallbackAccent: Int,
    nowPlayingContent: @Composable () -> Unit,
    phoneContent: @Composable () -> Unit,
    contactsContent: @Composable () -> Unit,
    bluetoothContent: @Composable () -> Unit,
) {
    var pane by rememberSaveable { mutableStateOf(Pane.NowPlaying) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                when (pane) {
                    Pane.NowPlaying -> nowPlayingContent()
                    Pane.Phone -> phoneContent()
                    Pane.Contacts -> contactsContent()
                    Pane.Bluetooth -> bluetoothContent()
                }
            }
            BottomTabs(selected = pane, artColors = artColors, fallbackAccent = fallbackAccent, onSelect = { pane = it })
        }
    }
}

@Composable
private fun BottomTabs(selected: Pane, artColors: ArtColors?, fallbackAccent: Int, onSelect: (Pane) -> Unit) {
    // Selected-tab color = album-art accent if present, else the user's chosen fallback accent.
    val accent = artColors?.let { Color(it.accent) } ?: Color(fallbackAccent)
    val onAccent = artColors?.let { Color(it.onAccent) } ?: readableOn(Color(fallbackAccent))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabButton("Now Playing", Icons.Filled.Bluetooth, selected == Pane.NowPlaying, accent, onAccent, { onSelect(Pane.NowPlaying) }, Modifier.weight(1f))
            TabButton("Dialer", Icons.Filled.Dialpad, selected == Pane.Phone, accent, onAccent, { onSelect(Pane.Phone) }, Modifier.weight(1f))
            TabButton("Contacts", Icons.Filled.Contacts, selected == Pane.Contacts, accent, onAccent, { onSelect(Pane.Contacts) }, Modifier.weight(1f))
            TabButton("Bluetooth settings", Icons.Filled.Settings, selected == Pane.Bluetooth, accent, onAccent, { onSelect(Pane.Bluetooth) }, Modifier.weight(1f))
        }
    }
}

/** Black or white, whichever reads on [bg]. */
internal fun readableOn(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

@Composable
private fun TabButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) accent else MaterialTheme.colorScheme.surface
    val content = if (selected) onAccent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        }
    }
}
