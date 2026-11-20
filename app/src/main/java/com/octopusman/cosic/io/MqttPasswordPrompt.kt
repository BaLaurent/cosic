package com.octopusman.cosic.io

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.octopusman.cosic.R
import cosic.io.mqtt.PasswordPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One question the engine asked: the password of [user] at [host]. [answer] must be called once, with null for "none". */
class PasswordRequest internal constructor(val host: String, val user: String, private val reply: (String?) -> Unit) {
    private var answered = false

    @Synchronized
    fun answer(password: String?) {
        if (answered) return
        answered = true
        reply(password)
    }
}

/**
 * The passwords MQTT connections ask for (`.mqtt('user', undefined, ...)`): requests queue up here, [pending] is the one to show and
 * [MqttPasswordDialog] shows it. A request that nobody answers is answered with null by [cancelAll] (when the engine is stopped).
 */
class MqttPasswordPrompt : PasswordPrompt {
    private val queue = ArrayDeque<PasswordRequest>()
    private val state = MutableStateFlow<PasswordRequest?>(null)

    /** The question to show now, or null. */
    val pending: StateFlow<PasswordRequest?> get() = state

    override fun request(host: String, user: String, onResult: (String?) -> Unit) {
        val request = PasswordRequest(host, user) { password ->
            advance()
            onResult(password)
        }
        synchronized(queue) {
            queue.addLast(request)
            if (state.value == null) state.value = queue.first()
        }
    }

    private fun advance() {
        synchronized(queue) {
            queue.removeFirstOrNull()
            state.value = queue.firstOrNull()
        }
    }

    /** Answers everything that waits with "no password". */
    fun cancelAll() {
        val all = synchronized(queue) { queue.toList() }
        all.forEach { it.answer(null) }
    }
}

/** Asks for the password of the request [prompt] shows, if any. Put it once in the content of the editor. */
@Composable
fun MqttPasswordDialog(prompt: MqttPasswordPrompt) {
    val request by prompt.pending.collectAsState()
    val current = request ?: return
    var field by remember(current) { mutableStateOf("") }
    val focus = remember(current) { FocusRequester() }
    val confirm = { current.answer(field) }

    AlertDialog(
        onDismissRequest = { current.answer(null) },
        title = { Text(stringResource(R.string.mqtt_password_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.mqtt_password_message, current.user, current.host))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(stringResource(R.string.mqtt_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.focusRequester(focus),
                )
            }
            LaunchedEffect(current) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.mqtt_password_connect)) } },
        dismissButton = { TextButton(onClick = { current.answer(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
