package com.fytbt.ui

import android.bluetooth.BluetoothAdapter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fytbt.ui.theme.ThemeMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fytbt.bt.BtDevice
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun BluetoothScreen(
    adapterEnabled: Boolean,
    scanMode: Int,
    discoverableUntil: Long?,
    paired: List<BtDevice>,
    a2dpSinkConnected: Set<String>,
    adapterName: String?,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onStopDiscoverable: () -> Unit,
    onSetAdapterName: (String) -> Unit,
    onRefreshPaired: () -> Unit,
    onUnpair: (BtDevice) -> Unit,
    onConnect: (BtDevice) -> Unit,
    fallbackAccent: Int,
    onPickAccent: (Int) -> Unit,
    takeOverOnOpen: Boolean,
    onToggleTakeOverOnOpen: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    // The whole page scrolls as one — the paired list renders all devices inline (rather than
    // being squeezed into a fixed weighted box), so it extends as far as it needs and the settings
    // below it are reached by scrolling instead of cramming everything onto one fixed screen.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        HeaderCard(
            adapterEnabled = adapterEnabled,
            scanMode = scanMode,
            discoverableUntil = discoverableUntil,
            adapterName = adapterName,
            permissionsGranted = permissionsGranted,
            onRequestPermissions = onRequestPermissions,
            onRequestEnableBluetooth = onRequestEnableBluetooth,
            onMakeDiscoverable = onMakeDiscoverable,
            onStopDiscoverable = onStopDiscoverable,
            onSetAdapterName = onSetAdapterName,
        )
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Paired devices")
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onRefreshPaired,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text("Refresh", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(8.dp))
        PairedList(
            devices = paired,
            a2dpSinkConnected = a2dpSinkConnected,
            onUnpair = onUnpair,
            onConnect = onConnect,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(26.dp))
        TakeOverToggle(enabled = takeOverOnOpen, onToggle = onToggleTakeOverOnOpen)
        Spacer(Modifier.height(22.dp))
        ThemePicker(selected = themeMode, onSelect = onSetThemeMode)
        Spacer(Modifier.height(22.dp))
        AccentPicker(selected = fallbackAccent, onPick = onPickAccent)
        Spacer(Modifier.height(16.dp))
    }
}

/** Light / dark / follow-system segmented control. The selected segment uses the accent. */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionLabel("Theme")
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.SYSTEM to "System")
            .forEach { (mode, label) ->
                val sel = mode == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

/**
 * Setting: when ON (default), opening the app claims Bluetooth as the active source — it stops the
 * radio and pauses on-unit players (like the stock BT app). It does not start playback; you press
 * play yourself. When OFF, opening the app leaves whatever's playing alone.
 */
@Composable
private fun TakeOverToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Take over audio when opened",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Stops the radio / other apps when you open this app. Doesn't auto-play.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Lets the user choose the player accent used when there's no album art to pull colors from. */
@Composable
private fun AccentPicker(selected: Int, onPick: (Int) -> Unit) {
    SectionLabel("Player accent (no artwork)")
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ACCENT_PRESETS.forEach { argb ->
            val color = Color(argb)
            val isSelected = argb == selected
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

private val ACCENT_PRESETS = listOf(
    0xFF7AB7FF.toInt(), // blue (default)
    0xFF4DD0E1.toInt(), // cyan
    0xFF66BB6A.toInt(), // green
    0xFFB388FF.toInt(), // purple
    0xFFF06292.toInt(), // pink
    0xFFFF8A65.toInt(), // coral
    0xFFFFB74D.toInt(), // orange
    0xFFFFD54F.toInt(), // amber
)

@Composable
private fun HeaderCard(
    adapterEnabled: Boolean,
    scanMode: Int,
    discoverableUntil: Long?,
    adapterName: String?,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onStopDiscoverable: () -> Unit,
    onSetAdapterName: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Bluetooth", style = MaterialTheme.typography.headlineMedium)
            when {
                !permissionsGranted -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Permissions needed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                    BigButton(label = "Grant Bluetooth permissions", onClick = onRequestPermissions)
                }
                !adapterEnabled -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bluetooth off",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                    BigButton(label = "Turn Bluetooth on", onClick = onRequestEnableBluetooth)
                }
                else -> {
                    Spacer(Modifier.height(16.dp))
                    DeviceNameEditor(adapterName = adapterName, onSave = onSetAdapterName)
                    Spacer(Modifier.height(16.dp))
                    DiscoverableButton(
                        scanMode = scanMode,
                        discoverableUntil = discoverableUntil,
                        onMakeDiscoverable = onMakeDiscoverable,
                        onStopDiscoverable = onStopDiscoverable,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Tap, then choose “" +
                            (adapterName?.takeIf { it.isNotBlank() } ?: "this stereo") +
                            "” in your phone's Bluetooth settings to pair a new device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceNameEditor(adapterName: String?, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(adapterName, editing) {
        mutableStateOf(adapterName.orEmpty())
    }
    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Discoverable as",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    adapterName?.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = { editing = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            ) { Text("Rename", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    } else {
        Column {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(31) },
                singleLine = true,
                label = { Text("Broadcast name") },
                supportingText = { Text("Up to 31 characters. Others see this when scanning.") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        draft = adapterName.orEmpty()
                        editing = false
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Cancel") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        val trimmed = draft.trim()
                        if (trimmed.isNotBlank()) {
                            onSave(trimmed)
                            editing = false
                        }
                    },
                    enabled = draft.trim().isNotBlank() && draft.trim() != adapterName,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/**
 * "Make discoverable" as a button that becomes a draining progress bar while the discoverable
 * window is open. The filled portion represents time remaining (full → empty over 2 min); tapping
 * re-triggers/extends. A Stop control appears while active.
 */
@Composable
private fun DiscoverableButton(
    scanMode: Int,
    discoverableUntil: Long?,
    onMakeDiscoverable: () -> Unit,
    onStopDiscoverable: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(discoverableUntil, scanMode) {
        while (discoverableUntil != null && discoverableUntil > nowMs) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
        nowMs = System.currentTimeMillis()
    }
    // Drive the UI from OUR tracked window, not the OS scanMode: stopDiscoverable() clears the
    // window immediately, so the button reverts to "Make discoverable" even if the reflective
    // OS-level cancel is blocked (the OS window then just times out on its own).
    val remainingMs = if (discoverableUntil != null) max(0L, discoverableUntil - nowMs) else 0L
    val discoverable = remainingMs > 0
    val totalMs = DISCOVERABLE_TOTAL_SECONDS * 1000f
    val fraction = (remainingMs / totalMs).coerceIn(0f, 1f)
    val secs = (remainingMs / 1000).toInt()

    Column {
        // FIXED height — a child with fillMaxHeight inside a min-height-only Box expands to fill
        // the whole column (that was the "huge bar" bug). Pin it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (discoverable) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
                .clickable { onMakeDiscoverable() },
            contentAlignment = Alignment.Center,
        ) {
            if (discoverable) {
                // Draining fill = time remaining.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .align(Alignment.CenterStart)
                        .background(MaterialTheme.colorScheme.secondary)
                )
            }
            Text(
                text = if (discoverable) "Discoverable · ${secs / 60}:${"%02d".format(secs % 60)} left — tap to extend"
                else "Make discoverable",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (discoverable) MaterialTheme.colorScheme.onSecondary
                else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (discoverable) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onStopDiscoverable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Stop being discoverable", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private const val DISCOVERABLE_TOTAL_SECONDS = 120

@Composable
private fun BigButton(label: String, onClick: () -> Unit, prominent: Boolean = true) {
    val colors = if (prominent) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        )
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairedList(
    devices: List<BtDevice>,
    a2dpSinkConnected: Set<String>,
    onUnpair: (BtDevice) -> Unit,
    onConnect: (BtDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) {
        EmptyState(text = "No paired devices yet.", modifier = modifier)
        return
    }
    val sorted = devices.sortedWith(
        compareByDescending<BtDevice> { a2dpSinkConnected.contains(it.address) }
            .thenBy { it.displayName.lowercase() }
    )
    // Plain Column (not LazyColumn): this lives inside the page's verticalScroll, and the paired
    // set is tiny, so rendering all rows is correct and lets the whole page scroll as one.
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            sorted.forEachIndexed { i, d ->
                val connected = a2dpSinkConnected.contains(d.address)
                PairedDeviceRow(
                    device = d,
                    connected = connected,
                    onConnect = { onConnect(d) },
                    onUnpair = { onUnpair(d) },
                )
                if (i < sorted.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun PairedDeviceRow(
    device: BtDevice,
    connected: Boolean,
    onConnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 92.dp)
        .background(
            if (connected) accent.copy(alpha = 0.10f) else Color.Transparent
        )
    Row(
        modifier = rowModifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left accent bar — only visible when connected.
        Box(
            modifier = Modifier
                .width(4.dp)
                .heightIn(min = 56.dp)
                .background(
                    if (connected) accent else Color.Transparent,
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (connected) {
                    Spacer(Modifier.width(10.dp))
                    ConnectedBadge()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(BtDevice.bondStateLabel(device.bondState))
                    append("  ·  ").append(device.address)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!connected) {
                Button(
                    onClick = onConnect,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("Connect", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(
                onClick = onUnpair,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Forget", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun ConnectedBadge() {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            "CONNECTED",
            color = MaterialTheme.colorScheme.onSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
