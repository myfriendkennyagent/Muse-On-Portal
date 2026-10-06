package com.myfriendkennyagent.museportal

import com.myfriendkennyagent.museportal.voice.EnergyVad
import com.myfriendkennyagent.museportal.voice.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {
  @Test
  fun `markdown and links read naturally`() {
    assertEquals(
      "Here are your options: Pasta. Salad. Details at a link",
      Speaker.forSpeech("**Here** are your options:\n- Pasta\n- [Salad](https://x.example)\n\nDetails at https://y.example/abc"),
    )
    assertEquals("Run it (code on screen) now", Speaker.forSpeech("Run it ```ls -la``` now"))
    assertEquals("Title. Body", Speaker.forSpeech("# Title\nBody"))
    assertEquals("First. Second", Speaker.forSpeech("1. First\n2) Second"))
  }

  @Test
  fun `long text splits at sentence ends`() {
    val text = "One two three. ".repeat(400).trim()
    val parts = Speaker.split(text, 1000)
    assertTrue(parts.all { it.length <= 1000 })
    assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
  }

  @Test
  fun `vad ends after speech then a pause`() {
    val vad = EnergyVad()
    repeat(25) { vad.feed(100.0) } // room noise
    assertFalse(vad.heardSpeech)
    repeat(30) { vad.feed(3000.0) } // talking
    assertTrue(vad.heardSpeech)
    assertFalse(vad.endOfSpeech)
    repeat(59) { vad.feed(120.0) }
    assertFalse(vad.endOfSpeech)
    vad.feed(120.0) // 1.2 s of quiet
    assertTrue(vad.endOfSpeech)
  }

  @Test
  fun `a short click is not speech`() {
    val vad = EnergyVad()
    repeat(25) { vad.feed(100.0) }
    repeat(3) { vad.feed(5000.0) }
    repeat(100) { vad.feed(100.0) }
    assertFalse(vad.heardSpeech)
    assertFalse(vad.endOfSpeech)
  }
}
