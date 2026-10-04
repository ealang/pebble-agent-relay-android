package io.github.ealang.pebbletelegramwebhook.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.ealang.pebbletelegramwebhook.pipeline.MessageStore
import io.github.ealang.pebbletelegramwebhook.telegram.TelegramEngine
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
    val copyToken: () -> Unit,
    val regenerateToken: () -> Unit,
    val submitAuthInput: (String) -> Unit,
    val logOut: () -> Unit,
    val restartLogin: () -> Unit,
    val searchChats: (query: String, onResult: (Result<List<TelegramEngine.ChatHit>>) -> Unit) -> Unit,
    val pickChat: (TelegramEngine.ChatHit) -> Unit,
    val saveSignature: (String) -> Unit,
    val retryFailed: (name: String) -> Unit,
    val discardFailed: (name: String) -> Unit,
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
    val sentSinceUp: Int,
    val pendingCount: Int,
    val failedItems: List<MessageStore.Item>,
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

    Scaffold(
        // The app draws edge to edge, so the keyboard is an inset like any
        // other: without this it covers the lower fields instead of
        // shortening the page they scroll in.
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = { TopAppBar(title = { Text("Pebble Telegram Webhook") }) },
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
            ServerCard(state, actions)
            StatusCard(state, actions)
            TelegramCard(state, actions)
        }
    }
}

// ------------------------------- server -------------------------------

@Composable
private fun ServerCard(state: ScreenState, actions: ScreenActions) {
    // Editable copy, re-seeded only when the *saved* value changes — never
    // on the periodic refresh, which would wipe in-progress typing.
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    var showToken by remember { mutableStateOf(false) }

    SectionCard("Server") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.serverRunning) "Listening on 127.0.0.1:${state.port}"
                    else if (state.serverWanted) "Starting…"
                    else "Off",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "POST /webhook, loopback only",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = state.serverWanted,
                onCheckedChange = { actions.toggleServer(it) },
            )
        }

        state.lastStartFailure?.let {
            Text(
                "Last start failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

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

        Column {
            Text("Bearer token", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (showToken) state.token else "•".repeat(24),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide" else "Show")
                }
                TextButton(onClick = actions.copyToken) { Text("Copy") }
                TextButton(onClick = actions.regenerateToken) { Text("Regenerate") }
            }
        }
    }
}

// ------------------------------- status -------------------------------

@Composable
private fun StatusCard(state: ScreenState, actions: ScreenActions) {
    var showLog by remember { mutableStateOf(false) }

    SectionCard("Status") {
        KeyValue("Sent since server up", state.sentSinceUp.toString())
        KeyValue("Pending", state.pendingCount.toString())
        KeyValue("Failed", state.failedItems.size.toString())

        state.failedItems.forEach { item ->
            FailedRow(item, actions)
        }

        TextButton(onClick = { showLog = true }) { Text("Recent activity") }
    }

    if (showLog) {
        LogDialog("Recent activity", state.logTail) { showLog = false }
    }
}

@Composable
private fun FailedRow(item: MessageStore.Item, actions: ScreenActions) {
    var expanded by remember(item.name) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }) {
            Text(item.name, style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            val reason = runCatching { item.failureReason() }.getOrNull()
            val text = runCatching { item.meta.text }.getOrNull()
            Text(
                "Reason: ${reason ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            text?.let {
                Text(
                    it.take(200),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.retryFailed(item.name) }) { Text("Retry") }
                TextButton(onClick = { actions.discardFailed(item.name) }) { Text("Discard") }
            }
        }
    }
}

// ------------------------------ telegram ------------------------------

@Composable
private fun TelegramCard(state: ScreenState, actions: ScreenActions) {
    SectionCard("Telegram") {
        // One line saying what state we are in and what to do about it —
        // never which step of a login is pending, which is the dialog's
        // job. Offline claims nothing else: an unreachable server cannot
        // confirm the session is still good, so "connected as …" alongside
        // "offline" would be a contradiction, not extra detail.
        val (line, fine) = when {
            // Only in a build made without credentials — nothing the user
            // can fix on the phone, so it names the build.
            !state.telegramConfigured ->
                "This build has no Telegram credentials." to false
            state.telegramHealth == TelegramEngine.Health.NOT_AUTHENTICATED ->
                "Not authenticated — log in below." to false
            state.telegramHealth == TelegramEngine.Health.OFFLINE ->
                "Offline — messages queue until the network is back." to false
            state.telegramChatId == null ->
                "Connected, but no destination chat is set below." to false
            else -> {
                val who = state.telegramAccount?.let { " as $it" } ?: ""
                "Healthy — connected$who." to true
            }
        }
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (fine) okGreen else MaterialTheme.colorScheme.error,
        )

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

        HorizontalDivider()

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
