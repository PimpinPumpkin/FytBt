package com.fytbt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.CallLog
import com.fytbt.phone.CallLogEntry
import com.fytbt.phone.PhoneData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vivid green for the call button (the theme's muddy secondary read as dull). */
private val CallGreen = Color(0xFF30D158)

@Composable
fun PhoneScreen(
    callLogGranted: Boolean,
    contactsGranted: Boolean,
    onRequestCallLog: () -> Unit,
    onDial: (String) -> Unit,
) {
    val ctx = LocalContext.current
    // Keyed on contactsGranted too, so recents re-resolve names once contacts access is allowed.
    val recents by produceState(initialValue = emptyList<CallLogEntry>(), callLogGranted, contactsGranted) {
        value = if (callLogGranted) withContext(Dispatchers.IO) { PhoneData.recents(ctx) } else emptyList()
    }
    var entered by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Recents (top) ---
        Text(
            "RECENTS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Recents only claim the big top area when there's actually a list. When empty or
        // permission-gated, show a slim notice and let the keypad rise into the freed space.
        val showList = callLogGranted && recents.isNotEmpty()
        if (showList) {
            Box(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
                RecentsList(recents = recents, onDial = onDial)
            }
            Spacer(Modifier.height(12.dp))
            Dialpad(
                entered = entered,
                onDigit = { entered += it },
                onBackspace = { entered = entered.dropLast(1) },
                onClear = { entered = "" },
                onCall = { if (entered.isNotBlank()) onDial(entered) },
            )
        } else {
            if (!callLogGranted) {
                SlimNotice("Allow call history to see recent calls.", onTap = onRequestCallLog)
            } else {
                SlimNotice(
                    "No recent calls yet. They sync over Bluetooth when “share call history” is on."
                )
            }
            Spacer(Modifier.weight(1f))
            Dialpad(
                entered = entered,
                onDigit = { entered += it },
                onBackspace = { entered = entered.dropLast(1) },
                onClear = { entered = "" },
                onCall = { if (entered.isNotBlank()) onDial(entered) },
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/** A compact one-line notice (optionally tappable) used in place of a big empty card. */
@Composable
private fun SlimNotice(text: String, onTap: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun RecentsList(
    recents: List<CallLogEntry>,
    onDial: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn {
            items(recents) { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .clickable { onDial(e.number) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (e.type) {
                            CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade
                            CallLog.Calls.MISSED_TYPE -> Icons.Filled.CallMissed
                            else -> Icons.Filled.CallReceived
                        },
                        contentDescription = null,
                        tint = if (e.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (e.count > 1) "${e.displayName}  (${e.count})" else e.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (e.name != null) {
                            Text(
                                e.number,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = "Call",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
        }
    }
}

@Composable
private fun Dialpad(
    entered: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Entered number + backspace
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(48.dp))
            Text(
                text = entered.ifEmpty { "Enter a number" },
                style = MaterialTheme.typography.headlineMedium,
                color = if (entered.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            // Always present so it reads as a permanent key; just disabled (dimmed) when empty.
            IconButton(
                onClick = onBackspace,
                enabled = entered.isNotEmpty(),
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Backspace, contentDescription = "Delete") }
        }
        Spacer(Modifier.height(8.dp))
        val rows = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (digit, letters) ->
                    DialKey(digit = digit, letters = letters, onClick = { onDigit(digit) })
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        // Big call button — vivid green so the primary action pops.
        FilledIconButton(
            onClick = onCall,
            enabled = entered.isNotBlank(),
            modifier = Modifier.size(84.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CallGreen,
                contentColor = Color.White,
                disabledContainerColor = CallGreen.copy(alpha = 0.3f),
                disabledContentColor = Color.White.copy(alpha = 0.5f),
            ),
        ) {
            Icon(Icons.Filled.Call, contentDescription = "Call", modifier = Modifier.size(38.dp))
        }
    }
}

@Composable
private fun DialKey(digit: String, letters: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(width = 96.dp, height = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (letters.isNotEmpty()) {
                Text(
                    letters,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PermissionGate(text: String, onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = onGrant,
                modifier = Modifier.heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Grant access", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
internal fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
