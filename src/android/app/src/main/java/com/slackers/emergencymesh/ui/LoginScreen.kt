package com.slackers.emergencymesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.slackers.emergencymesh.security.AppRole

@Composable
internal fun LoginScreen(
    checking: Boolean,
    error: String?,
    onContinueAsUser: () -> Unit,
    onLogin: (AppRole, String) -> Unit,
) {
    // Intentionally not rememberSaveable: a secret is never written to saved UI state.
    var code by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("EmergencyMesh", style = MaterialTheme.typography.headlineMedium)
            Text("Choose how you're using this phone. Logins work with no internet.")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("User", style = MaterialTheme.typography.titleMedium)
                    Text("Send messages and SOS alerts, and relay for others. No code needed.")
                    Button(onClick = onContinueAsUser, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue as User")
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Responder or Admin", style = MaterialTheme.typography.titleMedium)
                    Text("Responders warn everyone in an area. Admins also see where people need help. The code is checked on this phone and never sent.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 64) code = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !checking,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Role code") },
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { onLogin(AppRole.RESPONDER, code) },
                        enabled = !checking && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log in as Responder") }
                    OutlinedButton(
                        onClick = { onLogin(AppRole.ADMIN, code) },
                        enabled = !checking && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log in as Admin") }
                }
            }
        }
    }
}
