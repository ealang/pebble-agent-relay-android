package io.github.ealang.pebbletelegramagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * The "this is fine" green for status text, beside the theme's error red.
 * Deliberately not a theme slot: with Material You dynamic color every
 * palette position is derived from the user's wallpaper, so a position
 * carries no meaning — only a literal color does. Two shades so it stays
 * legible on either background.
 */
val okGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7BC67E) else Color(0xFF2E7D32)

/** Scrollable monospace log lines in a dialog, newest first. */
@Composable
fun LogDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
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
    )
}
