package io.github.ealang.operator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ealang.operator.telegram.TelegramEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Everything the screen can do to the world. The screen itself never touches
 * the service, config, or engine directly — the activity supplies these, and
 * signals changed facts by bumping [MainScreen]'s `tick`.
 */
class ScreenActions(
    val toggleServer: (wantOn: Boolean) -> Unit,
    val savePort: (text: String) -> Unit,
    val regenerateToken: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val submitAuthInput: (String) -> Unit,
    val logOut: () -> Unit,
    val restartLogin: () -> Unit,
    val searchChats: (query: String, onResult: (Result<List<TelegramEngine.ChatHit>>) -> Unit) -> Unit,
    val pickChat: (TelegramEngine.ChatHit) -> Unit,
    val saveSignature: (String) -> Unit,
    val retryAllFailed: () -> Unit,
    val discardAllFailed: () -> Unit,
    val resetSentTotal: () -> Unit,
)

/**
 * Facts the screen renders, re-read by the activity on every bump. Kept as a
 * plain snapshot so the screen has no reason to reach into live objects.
 */
class ScreenState(
    val serverWanted: Boolean,
    val serverRunning: Boolean,
    val port: Int,
    val token: String,
    val lastStartFailure: String?,
    val bindFailed: Boolean,
    val batteryExempt: Boolean,
    val sentTotal: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val logTail: List<String>,
    val telegramStage: TelegramEngine.Stage,
    val telegramStatus: String,
    val telegramHealth: TelegramEngine.Health,
    val telegramAccount: String?,
    val telegramConfigured: Boolean,
    val telegramChatId: Long?,
    val telegramChatTitle: String,
    val telegramSignature: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: ScreenState, actions: ScreenActions, onPoll: () -> Unit) {
    // A low-cost heartbeat while the screen is visible: counters and the log
    // move without user action, so re-snapshot every couple of seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            onPoll()
        }
    }

    // Tab layout: one concern per tab — Telegram (account + destination),
    // Index webhook (the server the Pebble index talks to). Telegram first
    // because it is the one everybody sets up; the Status tab that used to
    // open the app is gone: counters and the log are
    // debugging aids, and greeting a fresh install with them, plus setup
    // "issues" in red, was clutter before anything had gone wrong. They
    // live behind the overflow menu as the debug log now. Survives
    // rotation via rememberSaveable, same shell pattern throughout.
    var tab by rememberSaveable { mutableIntStateOf(TAB_TELEGRAM) }
    var showDebugLog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        // The app draws edge to edge, so the keyboard is an inset like any
        // other: without this it covers the lower fields instead of
        // shortening the page they scroll in.
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Pebble Operator") },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Debug log") },
                            onClick = {
                                menuOpen = false
                                showDebugLog = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_TELEGRAM,
                    onClick = { tab = TAB_TELEGRAM },
                    icon = { TabIcon(TelegramIcon, flagged = false) },
                    label = { Text("Telegram") },
                )
                // The only tab dot left: the server failing to start is the
                // one state that is broken rather than merely not set up yet.
                NavigationBarItem(
                    selected = tab == TAB_INDEX,
                    onClick = { tab = TAB_INDEX },
                    icon = { TabIcon(IndexIcon, serverFailing(state)) },
                    label = { Text("Index Webhook") },
                )
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Scaffold measures the status and navigation bars and the
                // top bar's height into this; skipping it is what puts the
                // first card under the clock and the last under the gesture
                // bar.
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                TAB_TELEGRAM -> {
                    TelegramAuthCard(state, actions)
                    DestinationChatCard(state, actions)
                }
                TAB_INDEX -> IndexWebhookTab(state, actions)
            }
        }
    }

    if (showDebugLog) {
        DebugLogScreen(state, actions) { showDebugLog = false }
    }
}

private const val TAB_TELEGRAM = 0
private const val TAB_INDEX = 1

/**
 * A tab icon that gains a red dot to its left when the tab has a setup
 * issue. The dot, not the warning triangle the cards use: on a tab the
 * triangle crowds the glyph, and the dot is the established tab idiom —
 * but drawn here rather than by [BadgedBox], whose dot is smaller and
 * fixed to the glyph's top-right, where it sat on the paper plane's nose
 * and read as part of the icon.
 */
@Composable
private fun TabIcon(image: ImageVector, flagged: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flagged) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
        Icon(image, contentDescription = null)
    }
}

// ------------------------------- server -------------------------------

/**
 * Whether the listener is on but not actually listening for a reason the
 * user has to act on. One test, so the tab dot, the header line and the
 * settings card's warning icon all agree.
 */
private fun serverFailing(state: ScreenState): Boolean =
    state.serverWanted && !state.serverRunning &&
        (state.bindFailed || state.lastStartFailure != null)

/**
 * The page is about one thing, the listener, so its switch sits at the
 * very top rather than inside a card. Off is the
 * default and, when off, the whole state: nothing below the header is
 * shown, because every card on this page describes a server that is not
 * there. Turning it on reveals the rest.
 */
@Composable
private fun IndexWebhookTab(state: ScreenState, actions: ScreenActions) {
    val failing = serverFailing(state)
    ServerHeader(state, failing, actions)
    if (!state.serverWanted) return
    CopySettingsCard(state)
    if (!state.batteryExempt) BatteryCard(actions)
    ServerSettingsCard(state, failing, actions)
}

/**
 * Shown only while the app is subject to battery optimization — granted,
 * the card is gone. The dialog it opens is the one the switch already asks
 * for; this is the way back to it after a "Deny".
 */
@Composable
private fun BatteryCard(actions: ScreenActions) {
    SectionCard("Battery optimization") {
        Text(
            "Android may stop this app to save battery. Exempting the app " +
                "from battery optimization keeps it running.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = actions.requestBatteryExemption) {
            Text("Allow running in the background")
        }
    }
}

/**
 * Icon as a state light, title, one status line, the switch. Problems are
 * said here, in the warning styling, and the fix is pointed at — the
 * settings card below carries the matching icon on its header.
 */
@Composable
private fun ServerHeader(state: ScreenState, failing: Boolean, actions: ScreenActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            IndexIcon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = when {
                !state.serverWanted -> MaterialTheme.colorScheme.outline
                state.serverRunning -> MaterialTheme.colorScheme.primary
                failing -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.weight(1f)) {
            Text("Webhook server", style = MaterialTheme.typography.titleLarge)
            when {
                !state.serverWanted -> StatusLine("Off — the webhook server is not needed for the Pebble app.")
                state.serverRunning ->
                    StatusLine("Listening on 127.0.0.1:${state.port} (POST /index).")
                // Bind failures keep the service alive and retrying, so they
                // are a live flag on the service rather than a persisted
                // last-failure; checked first because it is the fresher fact.
                state.bindFailed -> WarnText(
                    "Can't use port ${state.port} — another app may already " +
                        "be using it. Change the port under Server settings.",
                    MaterialTheme.typography.bodySmall,
                )
                state.lastStartFailure != null -> WarnText(
                    "Last start failed: ${state.lastStartFailure}",
                    MaterialTheme.typography.bodySmall,
                )
                else -> StatusLine("Starting…")
            }
        }
        Switch(
            checked = state.serverWanted,
            onCheckedChange = { actions.toggleServer(it) },
        )
    }
}

/** The header's quiet status prose, for states that need no action. */
@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Set-once settings, folded away. Opens itself when something in it needs
 * fixing, with the warning icon on its title saying why.
 */
@Composable
private fun ServerSettingsCard(state: ScreenState, failing: Boolean, actions: ScreenActions) {
    // Editable copy, re-seeded only when the *saved* value changes — never
    // on the periodic refresh, which would wipe in-progress typing.
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }

    CollapsibleCard("Server settings", initiallyExpanded = failing, warn = failing) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { actions.savePort(portText) },
                enabled = portText != state.port.toString(),
            ) { Text("Save") }
        }

        // The token itself lives in the Copy webhook settings card above —
        // showing it here too was the same value twice. Only the action
        // that changes it stays.
        TextButton(onClick = actions.regenerateToken) { Text("Regenerate bearer token") }
    }
}

/**
 * The values the Pebble app's webhook page asks for, laid out to mirror
 * that page field for field (see its "Webhook URL" and "Headers" sections)
 * so filling it in is pure transcription: tap a box here, paste into the
 * matching box there. Its "What to send" choice sits where that page puts
 * it, greyed out: nothing to copy, just the setting to pick there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopySettingsCard(state: ScreenState) {
    SectionCard("Copy webhook settings") {
        Text(
            "Mirrors the Pebble app's webhook page. Tap a value to copy it, " +
                "then paste it into the matching field there.",
            style = MaterialTheme.typography.bodySmall,
        )

        FieldLabel("Webhook URL")
        CopyBox("http://127.0.0.1:${state.port}/index", "webhook URL")

        // The Pebble app's segmented row, same labels and order. It defaults
        // to Recording, which the server rejects; Transcription is the one
        // to pick. Disabled greys the text, but Material also fades the
        // outline to near nothing; the border is kept at full strength so
        // the boxes read like the ones above.
        FieldLabel("What to send")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf("Recording", "Transcription", "Both")
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == "Transcription",
                    onClick = {},
                    enabled = false,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    colors = SegmentedButtonDefaults.colors(
                        disabledActiveBorderColor = MaterialTheme.colorScheme.outline,
                        disabledInactiveBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                ) { Text(mode) }
            }
        }

        FieldLabel("Headers")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CopyBox("Authorization", "header name", Modifier.weight(1f))
            CopyBox("Bearer ${state.token}", "header value", Modifier.weight(1f))
        }
    }
}

/** The muted little section label the Pebble webhook page uses. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * An outlined, read-only value that copies itself when tapped. Not a text
 * field: there is nothing to edit, and a disabled field greys the text as
 * if it were unavailable. Single line, clipped — the clipboard gets the
 * full string regardless, and the bearer value is secret-ish anyway.
 */
@Composable
private fun CopyBox(value: String, what: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Text(
        value,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        // The same theme slot button text draws from — the app-wide "this
        // is tappable" color, not a hardcoded value.
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboard.setText(AnnotatedString(value))
                // Android 13+ shows its own "copied" overlay.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        "Copied $what.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

// ------------------------------ debug log ------------------------------

/**
 * The old Status tab, whole, behind the overflow menu:
 * counters, failure handling and the log, which records everything the
 * app does — both front doors, the queue, Telegram delivery, the listener.
 * A debugging aid, not a page most people open, so it is a full-screen
 * dialog over the main screen rather than a tab: back or the X closes it.
 * Full-screen because a log wants the height; the alert-sized dialog the
 * log used to open in showed six lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogScreen(state: ScreenState, actions: ScreenActions, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Debug log") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        if (state.logTail.isNotEmpty()) {
                            TextButton(onClick = {
                                // Copied as shown: newest first, one line per event.
                                clipboard.setText(AnnotatedString(state.logTail.joinToString("\n")))
                            }) { Text("Copy") }
                        }
                    },
                )
            },
        ) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Long-press the counters to reset Sent. Pending and Failed
                // are live counts of real queued items, not counters;
                // clearing those means discarding messages, which the
                // buttons below do as a batch.
                SectionCard("Messages", onLongClick = actions.resetSentTotal) {
                    KeyValue("Sent", state.sentTotal.toString())
                    KeyValue("Pending", state.pendingCount.toString())
                    KeyValue("Failed", state.failedCount.toString())
                    // Failures are handled as a batch; the log has the reasons.
                    if (state.failedCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = actions.retryAllFailed) { Text("Retry all") }
                            TextButton(onClick = actions.discardAllFailed) { Text("Discard all") }
                        }
                    }
                }

                if (state.logTail.isEmpty()) {
                    Text("No activity recorded yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.logTail) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------ telegram ------------------------------

@Composable
private fun TelegramAuthCard(state: ScreenState, actions: ScreenActions) {
    SectionCard("Telegram authorization") {
        // One line saying what state we are in and what to do about it —
        // never which step of a login is pending, which is the dialog's
        // job. Offline claims nothing else: an unreachable server cannot
        // confirm the session is still good, so "connected as …" alongside
        // "offline" would be a contradiction, not extra detail. Plain text
        // in every state: not logged in yet is where a
        // fresh install starts, not a fault, and red at first open taught
        // nothing. The warning styling is kept for things that break.
        val line = when {
            // Only in a build made without credentials — nothing the user
            // can fix on the phone, so it names the build.
            !state.telegramConfigured -> "This build has no Telegram credentials."
            state.telegramHealth == TelegramEngine.Health.NOT_AUTHENTICATED ->
                "Not logged in — log in below."
            state.telegramHealth == TelegramEngine.Health.OFFLINE ->
                "Offline — messages queue until the network is back."
            state.telegramChatId == null -> "Logged in, but no destination chat is set below."
            else -> "Logged in as: ${state.telegramAccount ?: "unknown account"}"
        }
        Text(line, style = MaterialTheme.typography.bodySmall)

        // Login lives in a dialog, not an always-there field: Log in
        // whenever anything short of a session exists — a stale login
        // included — and Log out when connected. Closing the dialog
        // abandons the attempt; reopening starts a new one.
        var loginDialog by remember { mutableStateOf(false) }
        if (state.telegramConfigured) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.telegramHealth == TelegramEngine.Health.NOT_AUTHENTICATED) {
                    TextButton(onClick = {
                        // Always from the phone number. Resuming mid-flight
                        // sounds considerate and is a trap: a login code
                        // that never arrives leaves the dialog stuck at
                        // "enter the code" forever.
                        if (state.telegramStage != TelegramEngine.Stage.PHONE) {
                            actions.restartLogin()
                        }
                        loginDialog = true
                    }) { Text("Log in") }
                }
                if (state.telegramStage == TelegramEngine.Stage.READY) {
                    TextButton(onClick = actions.logOut) { Text("Log out") }
                }
            }
        }
        if (loginDialog) {
            LoginDialog(
                stage = state.telegramStage,
                status = state.telegramStatus,
                submit = actions.submitAuthInput,
                onDismiss = { loginDialog = false },
            )
        }

        // No API ID/hash fields: the pair is baked in at build time. It
        // identifies the app rather than the account, so every install
        // shares one pair and there is nothing for the user to decide.
    }
}

@Composable
private fun DestinationChatCard(state: ScreenState, actions: ScreenActions) {
    SectionCard("Destination chat") {
        // The destination, named rather than numbered. The id field and
        // its Test button are gone: an id that came from a picker needs
        // no testing, and one nobody can mistype needs no validating. The
        // picker is only offered when connected — it is a live server
        // search.
        Text(
            state.telegramChatId?.let { id ->
                "Sending to: " + state.telegramChatTitle.ifBlank { "chat $id" }
            } ?: "No destination chat chosen.",
            style = MaterialTheme.typography.bodySmall,
        )
        var chatPicker by remember { mutableStateOf(false) }
        TextButton(
            enabled = state.telegramStage == TelegramEngine.Stage.READY,
            onClick = { chatPicker = true },
        ) { Text(if (state.telegramChatId == null) "Choose chat" else "Change chat") }
        if (chatPicker) {
            ChatPickerDialog(
                search = actions.searchChats,
                onPick = { hit ->
                    actions.pickChat(hit)
                    chatPicker = false
                },
                onDismiss = { chatPicker = false },
            )
        }

        SignatureRow(state, actions)
    }
}

@Composable
private fun SignatureRow(state: ScreenState, actions: ScreenActions) {
    var signatureText by remember(state.telegramSignature) {
        mutableStateOf(state.telegramSignature)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = signatureText,
            onValueChange = { signatureText = it },
            label = { Text("Signature (appended line, blank for none)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { actions.saveSignature(signatureText) },
            enabled = signatureText.trim() != state.telegramSignature,
        ) { Text("Save") }
    }
}

/**
 * The whole login in one dialog: a single field whose meaning follows the
 * stage TDLib is waiting in (phone → code → password when two-step is on),
 * and a status line carrying TDLib's own error when a step is rejected.
 * Closes itself once the session is up. Deliberately owns no wizard state —
 * the stage lives in TDLib, and the Log in button resets it before opening
 * this, so there is never a half-finished attempt to resume into and no
 * Back button to build. Close is therefore a real cancel, not a pause.
 */
@Composable
private fun LoginDialog(
    stage: TelegramEngine.Stage,
    status: String,
    submit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }

    LaunchedEffect(stage) {
        if (stage == TelegramEngine.Stage.READY) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Only Close (or Back) ends this, never a stray tap outside the
        // dialog. Cancelling is destructive here — restartLogin() wipes
        // the attempt, so reopening means retyping the number and waiting
        // for a second code — and the moment you are most likely to
        // fumble a tap is while reaching for the code.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Log in to Telegram") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = {
                        Text(
                            when (stage) {
                                TelegramEngine.Stage.PHONE -> "Phone number (+1…)"
                                TelegramEngine.Stage.CODE -> "Login code"
                                TelegramEngine.Stage.PASSWORD -> "Two-step password"
                                else -> "(no input needed right now)"
                            },
                        )
                    },
                    visualTransformation = if (stage == TelegramEngine.Stage.PASSWORD) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = when (stage) {
                        TelegramEngine.Stage.PHONE ->
                            KeyboardOptions(keyboardType = KeyboardType.Phone)
                        TelegramEngine.Stage.CODE ->
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        else -> KeyboardOptions.Default
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                submit(input)
                input = ""
            }) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Pick the destination chat by name. Opens blank on purpose: there is no
 * recents list to show, because nothing in this app keeps a chat list —
 * the results are whatever the server just said, merged with nothing.
 *
 * Search-as-you-type over a network call needs two guards, both supplied
 * by [LaunchedEffect] keyed on the query rather than by hand. Typing a
 * letter cancels the previous effect, so the debounce below only elapses
 * once typing pauses; and a search already in flight has its continuation
 * abandoned, so a slow early response cannot overwrite a fast later one.
 * Getting that wrong shows the results for "mu" above the results for
 * "mum", which is the classic form of this bug.
 */
@Composable
private fun ChatPickerDialog(
    search: (String, (Result<List<TelegramEngine.ChatHit>>) -> Unit) -> Unit,
    onPick: (TelegramEngine.ChatHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TelegramEngine.ChatHit>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            hits = emptyList()
            note = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        note = "Searching…"
        val found = suspendCancellableCoroutine { cont ->
            search(trimmed) { cont.resume(it) }
        }
        hits = found.getOrDefault(emptyList())
        note = found.fold(
            onSuccess = { if (it.isEmpty()) "No chat matches that name." else null },
            onFailure = { "Search failed: ${it.message}" },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose destination chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Say what is being searched: the bare field reads like it
                // might search the whole of Telegram, and picking an
                // unknown stranger as the destination is exactly the
                // mistake worth making impossible to worry about.
                Text(
                    "Searches your existing Telegram chats by name.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                ) {
                    hits.forEach { hit ->
                        TextButton(
                            onClick = { onPick(hit) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                hit.title,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Long enough to swallow a burst of typing, short enough to feel live. */
private const val SEARCH_DEBOUNCE_MS = 300L
