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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fytbt.media.ArtColors

enum class Pane { NowPlaying, Phone, Contacts, Bluetooth }

@Composable
fun RootScreen(
    artColors: ArtColors?,
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
            BottomTabs(selected = pane, artColors = artColors, onSelect = { pane = it })
        }
    }
}

@Composable
private fun BottomTabs(selected: Pane, artColors: ArtColors?, onSelect: (Pane) -> Unit) {
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
            TabButton(
                label = "Now Playing",
                icon = Icons.Filled.Bluetooth,
                selected = selected == Pane.NowPlaying,
                artColors = artColors,
                onClick = { onSelect(Pane.NowPlaying) },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                label = "Dialer",
                icon = Icons.Filled.Dialpad,
                selected = selected == Pane.Phone,
                artColors = artColors,
                onClick = { onSelect(Pane.Phone) },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                label = "Contacts",
                icon = Icons.Filled.Contacts,
                selected = selected == Pane.Contacts,
                artColors = artColors,
                onClick = { onSelect(Pane.Contacts) },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                label = "Bluetooth settings",
                icon = Icons.Filled.Settings,
                selected = selected == Pane.Bluetooth,
                artColors = artColors,
                onClick = { onSelect(Pane.Bluetooth) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    artColors: ArtColors?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selected tab picks up the album-art accent (with a readable on-color); falls back to theme.
    val accent = artColors?.let { Color(it.accent) }
    val onAccent = artColors?.let { Color(it.onAccent) }
    val container = when {
        !selected -> MaterialTheme.colorScheme.surface
        accent != null -> accent
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        !selected -> MaterialTheme.colorScheme.onSurfaceVariant
        onAccent != null -> onAccent
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
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
