package com.pricefighter.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.pricefighter.data.db.HistoryEntity

/** Sentinel for "the user collapsed the open card, so nothing is expanded". */
private const val COLLAPSED = Long.MIN_VALUE

@Composable
fun HistoryTab(
    history: List<HistoryEntity>,
    onDelete: (Long) -> Unit,
    agentState: AgentUiState,
    onSubmitQuery: (String) -> Unit,
    onDismissAgentState: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        AgentPromptBar(
            agentState = agentState,
            onSubmit = onSubmitQuery,
            onDismiss = onDismissAgentState,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (history.isEmpty()) {
            EmptyHistory(Modifier.weight(1f))
        } else {
            HistoryList(
                history = history,
                onDelete = onDelete,
                bottomPadding = contentPadding.calculateBottomPadding(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Voice/text entry point for the on-device price-check agent. An empty field shows a mic (speak a
 * request); typing swaps it for a send button. Below it, a live status line reflects the agent's
 * progress, result, or error.
 */
@Composable
private fun AgentPromptBar(
    agentState: AgentUiState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    val working = agentState is AgentUiState.Working
    val voiceAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onSubmit)
        }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to price-check")
        }
        runCatching { voiceLauncher.launch(intent) }
    }

    fun submit() {
        val query = text.trim()
        if (query.isNotEmpty()) {
            onSubmit(query)
            text = ""
            focusManager.clearFocus()
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !working,
            singleLine = true,
            placeholder = { Text("Price check anything…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = {
                if (text.isBlank()) {
                    if (voiceAvailable) {
                        IconButton(onClick = ::startVoice, enabled = !working) {
                            Icon(Icons.Filled.Mic, contentDescription = "Price check by voice")
                        }
                    }
                } else {
                    IconButton(onClick = ::submit, enabled = !working) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Price check")
                    }
                }
            },
        )

        AgentStatus(
            agentState = agentState,
            onDismiss = onDismiss,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AgentStatus(
    agentState: AgentUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (agentState) {
        is AgentUiState.Idle -> Unit

        is AgentUiState.Working -> Row(
            modifier = modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(agentState.step, style = MaterialTheme.typography.bodyMedium)
        }

        is AgentUiState.Done -> StatusBanner(
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            message = buildString {
                append("Priced “${agentState.query}”")
                append(if (agentState.judgedByNano) " — matches judged by Gemini Nano" else " — matched by keywords")
            },
            onDismiss = onDismiss,
            modifier = modifier,
        )

        is AgentUiState.Error -> StatusBanner(
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            icon = null,
            message = agentState.message,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
}

@Composable
private fun StatusBanner(
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: (@Composable () -> Unit)?,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(10.dp))
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun HistoryList(
    history: List<HistoryEntity>,
    onDelete: (Long) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // null = default (most recent open); a specific id = that one open; COLLAPSED = none open.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openId = when (expandedId) {
        COLLAPSED -> null
        null -> history.first().id
        else -> expandedId
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(history, key = { it.id }) { entry ->
            HistoryRow(
                entry = entry,
                expanded = entry.id == openId,
                onToggle = { expandedId = if (openId == entry.id) COLLAPSED else entry.id },
                onDelete = { onDelete(entry.id) },
            )
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val currency = entry.currency
    val range = "${Format.money(entry.minPrice, currency)} – ${Format.money(entry.maxPrice, currency)}"

    Card(modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
        Column(Modifier.padding(16.dp)) {
            // Header — always visible: title plus a summary (price range) when collapsed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.searchTerm,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (expanded) Format.timestamp(entry.createdAtEpochMs) else range,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            // Full details — only for the open item.
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        Format.money(entry.averagePrice, currency),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("average sold price", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    StatRow("Median", Format.money(entry.medianPrice, currency))
                    StatRow("Range", range)
                    StatRow("Sold (sample)", entry.soldCount.toString())
                    StatRow("Velocity (30d)", "${entry.velocityLast30Days} sold")
                    StatRow("Active listings", entry.activeListings.takeIf { it >= 0 }?.toString() ?: "—")
                    StatRow("Lowest active", Format.money(entry.lowestActivePrice, currency))

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, entry.soldDeeplink.toUri()))
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("View sold listings on eBay")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No price checks yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap the mic and say “price check a MacBook Pro M1”, type it above, " +
                    "or snap a photo in the Camera tab. Results show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
