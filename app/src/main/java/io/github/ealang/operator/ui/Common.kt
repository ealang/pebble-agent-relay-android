package io.github.ealang.operator.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** The one-pager is a stack of these. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionCard(
    title: String,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // The whole card is the long-press target: a single text line is too
    // small a thumb target for a hidden gesture.
    val modifier = if (onLongClick == null) Modifier.fillMaxWidth()
    else Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongClick)
    Card(modifier = modifier) {
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

/** A small "label: value" line. */
@Composable
fun KeyValue(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}
