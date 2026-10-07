package io.github.ealang.pebbletelegramagent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The one-pager is a stack of these. */
@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * A card whose body folds away behind its own title row. Collapsed by
 * default: these hold settings that are set once and then in the way.
 * [warn] puts the warning icon beside the title — the card's way of saying
 * something inside needs the user even while it is folded shut. Same
 * pattern as the other settings cards.
 */
@Composable
fun CollapsibleCard(
    title: String,
    initiallyExpanded: Boolean = false,
    warn: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon before the title: WarnText and the tab dots all lead
                // with their marker, so the card does too.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (warn) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Needs attention",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                // Core icon set only: the chevrons do the expand/collapse job
                // without pulling in material-icons-extended (see AppIcons).
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) content()
        }
    }
}

/**
 * Red status text with the warning icon to its left — the one styling for
 * "needs the user", everywhere it appears.
 */
@Composable
fun WarnText(text: String, style: TextStyle? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = "Needs attention",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text,
            style = style ?: MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** A small "label: value" line; an optional long-press action (e.g. reset). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyValue(label: String, value: String, onLongClick: (() -> Unit)? = null) {
    val modifier = if (onLongClick == null) Modifier
    else Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

/** Scrollable monospace log lines in a dialog, newest first. */
@Composable
fun LogDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (lines.isEmpty()) {
                Text("No activity recorded yet.")
            } else {
                LazyColumn {
                    items(lines) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (lines.isNotEmpty()) {
                TextButton(onClick = {
                    // Copied as shown: newest first, one line per event.
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                }) { Text("Copy") }
            }
        },
    )
}
