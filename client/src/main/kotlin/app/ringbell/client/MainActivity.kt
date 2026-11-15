package app.ringbell.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: RingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only a genuinely new launch rings; rotation / process-restore re-creation does not.
        if (savedInstanceState == null) vm.onFreshLaunch()

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val name by vm.name.collectAsStateWithLifecycle()
                    val status by vm.status.collectAsStateWithLifecycle()
                    var editing by rememberSaveable { mutableStateOf(false) }

                    Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                        val n = name
                        if (n == null || editing) {
                            NameScreen(
                                initial = n.orEmpty(),
                                isEdit = n != null,
                                onSave = { vm.saveName(it); editing = false },
                                onBack = { editing = false },
                            )
                        } else {
                            RingScreen(
                                name = n,
                                status = status,
                                onCancel = vm::cancel,
                                onRing = vm::ring,
                                onEditName = { editing = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameScreen(initial: String, isEdit: Boolean, onSave: (String) -> Unit, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (isEdit) "Change your name" else "What's your name?",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The host will see this name when you ring. From the next time you open the app, it rings automatically.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSave(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (isEdit) TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun RingScreen(
    name: String,
    status: Status,
    onCancel: () -> Unit,
    onRing: () -> Unit,
    onEditName: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (headline, detail) = when (status) {
                Status.Idle -> "Ready" to "Signed in as $name"
                Status.Sending -> "Ringing…" to "Sending to the host as $name"
                Status.Ringing -> "Ringing" to "The host is being rung as $name until they dismiss it."
                Status.Dismissed -> "Dismissed" to "The host dismissed your ring."
                Status.Cancelled -> "Cancelled" to "You cancelled the ring."
                is Status.Failed ->
                    (if (status.whileCancelling) "Couldn't cancel" else "Couldn't ring") to status.message
            }
            Text(headline, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))

            when {
                status is Status.Sending || status is Status.Ringing ->
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel ring") }

                status is Status.Failed && status.whileCancelling ->
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Try cancelling again") }

                else ->
                    OutlinedButton(onClick = onRing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (status is Status.Failed) "Try again" else "Ring again")
                    }
            }
        }
        TextButton(onClick = onEditName) { Text("Change name ($name)") }
    }
}
