package io.github.ealang.pebbletelegramwebhook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.github.ealang.pebbletelegramwebhook.pipeline.MessageStore
import io.github.ealang.pebbletelegramwebhook.telegram.TelegramEngine
import kotlinx.coroutines.delay

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
    val saveTelegramApp: (apiId: String, apiHash: String) -> Unit,
    val submitAuthInput: (String) -> Unit,
    val saveChatId: (text: String) -> Unit,
    val testChatId: (onResult: (String) -> Unit) -> Unit,
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
    val telegramApiId: Int?,
    val telegramApiHash: String?,
    val telegramChatId: Long?,
    val telegramSignature: String,
)

@Composable
fun MainScreen(tick: Int, state: ScreenState, actions: ScreenActions, onPoll: () -> Unit) {
    // A low-cost heartbeat while the screen is visible: counters and the log
    // move without user action, so re-snapshot every couple of seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            onPoll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pebble Telegram Webhook", style = MaterialTheme.typography.headlineSmall)
        ServerCard(state, actions)
        StatusCard(state, actions)
        TelegramCard(state, actions)
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
    // Each editable copy is keyed on its saved value: it re-seeds when a
    // save lands (including from elsewhere), but survives the periodic
    // refresh untouched while the user is typing.
    var apiIdText by remember(state.telegramApiId) {
        mutableStateOf(state.telegramApiId?.toString() ?: "")
    }
    var apiHashText by remember(state.telegramApiHash) {
        mutableStateOf(state.telegramApiHash ?: "")
    }
    var authInput by remember { mutableStateOf("") }
    var chatIdText by remember(state.telegramChatId) {
        mutableStateOf(state.telegramChatId?.toString() ?: "")
    }
    var chatCheckResult by remember { mutableStateOf<String?>(null) }
    var signatureText by remember(state.telegramSignature) {
        mutableStateOf(state.telegramSignature)
    }

    SectionCard("Telegram") {
        Text(state.telegramStatus, style = MaterialTheme.typography.bodyMedium)

        // One input serves the whole login; its meaning follows TDLib's state.
        val authLabel = when (state.telegramStage) {
            TelegramEngine.Stage.PHONE -> "Phone number (+1…)"
            TelegramEngine.Stage.CODE -> "Login code"
            TelegramEngine.Stage.PASSWORD -> "Two-step password"
            else -> null
        }
        if (authLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = authInput,
                    onValueChange = { authInput = it },
                    label = { Text(authLabel) },
                    singleLine = true,
                    visualTransformation =
                        if (state.telegramStage == TelegramEngine.Stage.PASSWORD) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        actions.submitAuthInput(authInput)
                        authInput = ""
                    },
                    enabled = authInput.isNotBlank(),
                ) { Text("Submit") }
            }
        }

        HorizontalDivider()

        Text("Application (my.telegram.org)", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = apiIdText,
            onValueChange = { apiIdText = it },
            label = { Text("API ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiHashText,
            onValueChange = { apiHashText = it },
            label = { Text("API hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { actions.saveTelegramApp(apiIdText, apiHashText) },
            enabled = apiIdText.isNotBlank() && apiHashText.isNotBlank(),
        ) { Text("Save & connect") }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = chatIdText,
                onValueChange = { chatIdText = it },
                label = { Text("Chat ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { actions.saveChatId(chatIdText) }) { Text("Save") }
            TextButton(
                onClick = {
                    chatCheckResult = "Checking…"
                    actions.testChatId { chatCheckResult = it }
                },
                enabled = state.telegramChatId != null,
            ) { Text("Test") }
        }
        chatCheckResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
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
}
