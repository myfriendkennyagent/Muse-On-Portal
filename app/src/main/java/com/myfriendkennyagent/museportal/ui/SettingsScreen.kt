package com.myfriendkennyagent.museportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myfriendkennyagent.museportal.BuildConfig
import com.myfriendkennyagent.museportal.MuseHub
import com.myfriendkennyagent.museportal.PairingStatus
import com.myfriendkennyagent.museportal.protocol.link.ConnectionState
import com.myfriendkennyagent.museportal.store.FileDeviceStore
import com.myfriendkennyagent.museportal.store.Settings
import java.util.UUID

@Composable
fun SettingsScreen(store: FileDeviceStore, settings: Settings, onClose: () -> Unit) {
  val connection by MuseHub.connection.collectAsState()
  val pairing by MuseHub.pairing.collectAsState()
  val identity = remember { store.identity() }
  var hasToken by remember { mutableStateOf(store.sdkToken() != null) }
  var tokenInput by remember { mutableStateOf("") }
  var tokenError by remember { mutableStateOf("") }
  var importResult by remember { mutableStateOf("") }
  var name by remember { mutableStateOf(settings.displayName) }
  var speak by remember { mutableStateOf(settings.speakReplies) }
  var announce by remember { mutableStateOf(settings.announcements) }
  var quietStart by remember { mutableIntStateOf(settings.quietStartHour) }
  var quietEnd by remember { mutableIntStateOf(settings.quietEndHour) }
  var rate by remember { mutableFloatStateOf(settings.speechRate) }
  var sideChat by remember { mutableStateOf(settings.sideChatId != null) }
  val paired = store.loadPairing() != null || connection == ConnectionState.Connected

  Column(
    Modifier.fillMaxSize()
      .background(PortalColors.Background)
      .padding(top = 64.dp, start = 32.dp, end = 32.dp, bottom = 24.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Settings", style = MaterialTheme.typography.headlineMedium, color = PortalColors.OnBlue, modifier = Modifier.weight(1f))
      PrimaryButton("Done", onClose)
    }

    Section("Muse") {
      Line("Status", connectionLabel(connection))
      Line("Device", "${identity.nodeId}  ·  Bluetooth name ${identity.bleName}")
      Line("App version", BuildConfig.VERSION_NAME)
      OutlinedTextField(
        name,
        { name = it },
        label = { Text("Name Muse uses for this Portal") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
      )
      SecondaryButton("Save name (applies on reconnect)") { settings.displayName = name }
    }

    Section("SDK token") {
      if (hasToken) {
        Line("Token", "Saved, encrypted on this Portal")
      } else {
        Text(
          "Get one at gadgets.muse.ai → Account → SDK tokens. Easiest: let tools/deploy.sh push it from your laptop. Or type it here:",
          style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
          tokenInput,
          { tokenInput = it },
          label = { Text("mgst_…") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
        )
        if (tokenError.isNotEmpty()) Text(tokenError, color = PortalColors.Error)
        PrimaryButton("Save token") {
          if (store.saveSdkToken(tokenInput)) {
            hasToken = true
            tokenInput = ""
            tokenError = ""
          } else {
            tokenError = "That doesn't look like an SDK token. Copy it again from gadgets.muse.ai."
          }
        }
      }
    }

    Section("Pairing") {
      Text(pairingLabel(pairing, paired), style = MaterialTheme.typography.bodyLarge)
      Text(
        "Bluetooth: tap below, then in the Muse app turn on Settings → Devices → Developer mode, tap +, and pick " +
          "${identity.bleName}. Pairing only opens from this screen, on this Portal.",
        style = MaterialTheme.typography.bodyMedium,
        color = PortalColors.Dim,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (pairing is PairingStatus.Advertising || pairing is PairingStatus.InProgress) {
          SecondaryButton("Stop pairing") { MuseHub.actions?.stopPairing() }
        } else {
          PrimaryButton("Pair over Bluetooth") { MuseHub.actions?.startPairing() }
        }
        SecondaryButton("Import from laptop") {
          val got = MuseHub.actions?.importFromAdb().orEmpty()
          hasToken = store.sdkToken() != null
          importResult = if (got.isEmpty()) "Nothing to import. See docs/PAIRING.md." else "Imported ${got.joinToString()}."
        }
        if (paired) SecondaryButton("Unpair") { MuseHub.actions?.unpair() }
      }
      if (importResult.isNotEmpty()) Text(importResult, style = MaterialTheme.typography.bodyMedium)
    }

    Section("Voice") {
      Toggle("Speak Muse's replies", speak) {
        speak = it
        settings.speakReplies = it
      }
      Toggle("Speak Muse's own messages (task results, check-ins)", announce) {
        announce = it
        settings.announcements = it
      }
      Text("Speech rate ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyLarge)
      Slider(rate, { rate = it }, valueRange = 0.6f..1.6f, onValueChangeFinished = { settings.speechRate = rate })
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quiet hours", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Stepper("${quietStart}:00", { quietStart = (quietStart + 23) % 24; settings.quietStartHour = quietStart }) {
          quietStart = (quietStart + 1) % 24
          settings.quietStartHour = quietStart
        }
        Text("to", style = MaterialTheme.typography.bodyLarge)
        Stepper("${quietEnd}:00", { quietEnd = (quietEnd + 23) % 24; settings.quietEndHour = quietEnd }) {
          quietEnd = (quietEnd + 1) % 24
          settings.quietEndHour = quietEnd
        }
      }
      var voiceNote by remember { mutableStateOf("") }
      SecondaryButton("Test voice") {
        voiceNote =
          if (MuseHub.actions?.testSpeech() == true) "Playing a sample."
          else "No voice engine yet. Run tools/deploy.sh --tts (see docs/TTS.md)."
      }
      if (voiceNote.isNotEmpty()) Text(voiceNote, style = MaterialTheme.typography.bodyMedium)
      Toggle("Keep Portal conversations in their own chat", sideChat) {
        sideChat = it
        settings.sideChatId = if (it) UUID.randomUUID().toString() else null
      }
    }
  }
}

private fun connectionLabel(state: ConnectionState): String =
  when (state) {
    ConnectionState.Connected -> "Connected"
    is ConnectionState.Connecting -> "Connecting to ${state.vm}"
    is ConnectionState.Waiting -> "${state.reason}; retrying in ${state.seconds}s"
    ConnectionState.NotPaired -> "Not paired"
    ConnectionState.Unpaired -> "Removed by Muse; pair again"
  }

private fun pairingLabel(status: PairingStatus, paired: Boolean): String =
  when (status) {
    is PairingStatus.Advertising -> "Waiting for the Muse app as ${status.bleName} (${status.secondsLeft / 60}:${"%02d".format(status.secondsLeft % 60)} left)"
    is PairingStatus.InProgress -> status.step
    PairingStatus.Paired -> "Paired. Connecting…"
    is PairingStatus.Failed -> status.reason
    PairingStatus.Idle -> if (paired) "This Portal is paired with your Muse." else "Not paired yet."
  }

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PortalColors.Surface).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = PortalColors.OnBlue)
    content()
  }
}

@Composable
private fun Line(label: String, value: String) {
  Row {
    Text(label, style = MaterialTheme.typography.bodyLarge, color = PortalColors.Dim, modifier = Modifier.weight(0.3f))
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.7f))
  }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(checked, onChange)
  }
}

@Composable
private fun Stepper(value: String, onDown: () -> Unit, onUp: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    SecondaryButton("−", onDown)
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 8.dp))
    SecondaryButton("+", onUp)
  }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
  Button(
    onClick,
    colors = ButtonDefaults.buttonColors(containerColor = PortalColors.Blue, contentColor = PortalColors.OnBlue),
    modifier = Modifier.heightIn(min = 64.dp),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
  OutlinedButton(onClick, modifier = Modifier.heightIn(min = 64.dp)) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = PortalColors.Body)
  }
}
