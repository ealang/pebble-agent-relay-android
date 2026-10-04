package io.github.ealang.pebbletelegramagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 with the stock palette, light or dark per the system setting.
 *
 * Deliberately plain: no brand colours and no wallpaper-derived scheme. A
 * settings page for a background service has nothing to express through
 * colour, and a fixed palette is one whose contrast can be reasoned about
 * and whose screenshots look the same on every device.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
