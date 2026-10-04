package io.github.ealang.pebbletelegramagent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Tab icons the bundled material-icons-core set does not carry, inlined as
 * path data rather than pulled in via material-icons-extended — the
 * extended artifact is thousands of icons for the sake of two.
 */
private fun icon(name: String, pathData: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
}.build()

/**
 * The Telegram paper plane, from the Material Design Icons community set
 * (Apache-2.0). Closer to the actual Telegram logo than core's Send icon.
 */
val TelegramIcon = icon(
    "Telegram",
    "M9.78,18.65 L10.06,14.42 L17.74,7.5 C18.08,7.19 17.67,7.04 17.22,7.31 " +
        "L7.74,13.3 L3.64,12 C2.76,11.75 2.75,11.14 3.84,10.7 L19.81,4.54 " +
        "C20.54,4.21 21.24,4.72 20.96,5.84 L18.24,18.65 C18.05,19.56 17.5," +
        "19.78 16.74,19.36 L12.6,16.3 L10.61,18.23 C10.38,18.46 10.19,18.65 " +
        "9.78,18.65 Z",
)

/**
 * Material's Notes glyph — the same icon CoreApp uses for its own Index
 * tab (Icons.AutoMirrored.Outlined.Notes), so the two apps' Index tabs
 * match.
 */
val IndexIcon = icon(
    "Index",
    "M3,18 h12 v-2 H3 v2 Z M3,6 v2 h18 V6 H3 Z M3,13 h18 v-2 H3 v2 Z",
)
