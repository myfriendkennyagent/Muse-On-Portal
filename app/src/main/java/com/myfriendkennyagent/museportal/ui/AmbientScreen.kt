package com.myfriendkennyagent.museportal.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfriendkennyagent.museportal.DisplayContent
import com.myfriendkennyagent.museportal.Face
import com.myfriendkennyagent.museportal.MuseHub
import com.myfriendkennyagent.museportal.protocol.link.ConnectionState

/** Reserve the top strip for Portal's floating back/home/Wi-Fi overlay. */
private val TOP_OVERLAY = 64.dp

@Composable
fun AmbientScreen(onOpenSettings: () -> Unit) {
  val ui by MuseHub.ui.collectAsState()
  val connection by MuseHub.connection.collectAsState()
  val display by MuseHub.display.collectAsState()
  var typing by remember { mutableStateOf(false) }

  Box(Modifier.fillMaxSize().background(PortalColors.Background)) {
    Column(
      Modifier.fillMaxSize().padding(top = TOP_OVERLAY, start = 24.dp, end = 24.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { StatusChip(connection) }
      Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(0.42f), contentAlignment = Alignment.Center) {
          Orb(
            face = ui.face,
            level = ui.level,
            modifier =
              Modifier.size(280.dp).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
              ) {
                MuseHub.actions?.talk()
              },
          )
        }
        Column(Modifier.weight(0.58f).padding(start = 16.dp), verticalArrangement = Arrangement.Center) {
          if (ui.heard.isNotEmpty()) {
            Text(
              "“${ui.heard}”",
              style = MaterialTheme.typography.bodyLarge,
              color = PortalColors.Dim,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
          }
          Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(
              ui.caption.ifEmpty { idleHint(ui.face, connection) },
              style = MaterialTheme.typography.displaySmall,
              color = if (ui.caption.isEmpty()) PortalColors.Dim else PortalColors.Body,
            )
          }
        }
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundButton("⚙", "Settings", onOpenSettings)
        Spacer(Modifier.weight(1f))
        TalkButton(ui.face)
        Spacer(Modifier.weight(1f))
        RoundButton("⌨", "Type a message") { typing = true }
      }
    }
    display?.let { DisplayOverlay(it) }
    if (typing) TypeDialog(onDismiss = { typing = false })
  }
}

private fun idleHint(face: Face, connection: ConnectionState): String =
  when {
    face == Face.LISTENING -> "Listening…"
    face == Face.THINKING -> "…"
    face == Face.WORKING -> "Working on it…"
    connection is ConnectionState.NotPaired -> "Not paired yet. Open settings to connect this Portal to your Muse."
    connection is ConnectionState.Unpaired -> "Muse removed this Portal. Pair it again in settings."
    connection !is ConnectionState.Connected -> "Connecting to Muse…"
    else -> "Tap the orb and ask Muse anything."
  }

@Composable
private fun StatusChip(state: ConnectionState) {
  val (color, label) =
    when (state) {
      ConnectionState.Connected -> PortalColors.Success to "Connected"
      is ConnectionState.Connecting -> PortalColors.Amber to "Connecting"
      is ConnectionState.Waiting -> PortalColors.Amber to "Retrying in ${state.seconds}s"
      ConnectionState.NotPaired -> PortalColors.Dim to "Not paired"
      ConnectionState.Unpaired -> PortalColors.Error to "Unpaired"
    }
  Row(
    Modifier.clip(RoundedCornerShape(20.dp)).background(PortalColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
    Spacer(Modifier.size(8.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = PortalColors.Body)
  }
}

@Composable
private fun TalkButton(face: Face) {
  val (label, color) =
    when (face) {
      Face.LISTENING -> "Done talking" to PortalColors.Success
      Face.SPEAKING,
      Face.THINKING,
      Face.WORKING -> "Stop" to PortalColors.Surface
      else -> "Tap to talk" to PortalColors.Blue
    }
  Button(
    onClick = {
      val actions = MuseHub.actions ?: return@Button
      if (label == "Stop") actions.cancel() else actions.talk()
    },
    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = PortalColors.OnBlue),
    shape = RoundedCornerShape(48.dp),
    modifier = Modifier.heightIn(min = 96.dp).widthIn(min = 280.dp),
  ) {
    Text(label, style = MaterialTheme.typography.headlineSmall)
  }
}

@Composable
private fun RoundButton(symbol: String, description: String, onClick: () -> Unit) {
  Box(
    Modifier.size(72.dp).clip(CircleShape).background(PortalColors.Surface).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(symbol, style = MaterialTheme.typography.headlineMedium, color = PortalColors.Body)
  }
}

/** Muse's face: an orb that breathes when idle, follows your voice, and spins while thinking. */
@Composable
fun Orb(face: Face, level: Float, modifier: Modifier = Modifier) {
  val t = rememberInfiniteTransition(label = "orb")
  val breathe by t.animateFloat(0.94f, 1.0f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breathe")
  val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
  val pulse by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "pulse")
  val voice by animateFloatAsState(level, tween(80), label = "voice")
  val (top, bottom) =
    when (face) {
      Face.ERROR -> PortalColors.Error to Color(0xFF8A1F28)
      Face.WORKING -> PortalColors.Amber to PortalColors.Violet
      Face.LISTENING -> Color(0xFF6CE0FF) to PortalColors.Blue
      else -> PortalColors.Violet to PortalColors.Blue
    }
  Canvas(modifier) {
    val base = size.minDimension / 2 * 0.72f
    val scale =
      when (face) {
        Face.LISTENING -> 0.92f + voice * 0.25f
        Face.SPEAKING -> pulse
        else -> breathe
      }
    val r = base * scale
    drawCircle(
      Brush.radialGradient(listOf(bottom.copy(alpha = 0.35f), Color.Transparent), center, r * 1.6f),
      r * 1.6f,
    )
    drawCircle(Brush.verticalGradient(listOf(top, bottom), center.y - r, center.y + r), r)
    drawCircle(
      Brush.radialGradient(
        listOf(Color(0x99F0F0F0), Color.Transparent),
        Offset(center.x - r * 0.35f, center.y - r * 0.4f),
        r * 0.55f,
      ),
      r * 0.55f,
      Offset(center.x - r * 0.35f, center.y - r * 0.4f),
    )
    if (face == Face.THINKING || face == Face.WORKING) {
      val ring = r * 1.18f
      drawArc(
        color = top,
        startAngle = spin,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(center.x - ring, center.y - ring),
        size = androidx.compose.ui.geometry.Size(ring * 2, ring * 2),
        style = Stroke(width = 10f),
      )
    }
  }
}

/** What Muse put on screen with a display command; a tap clears it. */
@Composable
private fun DisplayOverlay(content: DisplayContent) {
  Box(
    Modifier.fillMaxSize().background(PortalColors.Background).clickable { MuseHub.display.value = null }.padding(top = TOP_OVERLAY),
    contentAlignment = Alignment.Center,
  ) {
    when (content) {
      is DisplayContent.Image ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Image(
            content.bitmap.asImageBitmap(),
            contentDescription = content.caption,
            contentScale = ContentScale.Fit,
            modifier = Modifier.weight(1f, fill = false).padding(16.dp),
          )
          if (content.caption.isNotEmpty()) {
            Text(content.caption, style = MaterialTheme.typography.headlineSmall, color = PortalColors.Body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
          }
        }
      is DisplayContent.Text ->
        Column(
          Modifier.fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(24.dp))
            .background(PortalColors.Surface)
            .padding(32.dp)
            .verticalScroll(rememberScrollState())
        ) {
          if (content.title.isNotEmpty()) {
            Text(content.title, style = MaterialTheme.typography.headlineMedium, color = PortalColors.OnBlue)
            Spacer(Modifier.height(16.dp))
          }
          Text(content.body, style = MaterialTheme.typography.displaySmall, color = PortalColors.Body)
        }
    }
  }
}

@Composable
private fun TypeDialog(onDismiss: () -> Unit) {
  var text by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Message Muse") },
    text = {
      OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge, minLines = 2)
    },
    confirmButton = {
      Button(
        onClick = {
          MuseHub.actions?.sendText(text)
          onDismiss()
        },
        modifier = Modifier.heightIn(min = 64.dp),
      ) {
        Text("Send")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 64.dp)) { Text("Cancel") } },
  )
}
