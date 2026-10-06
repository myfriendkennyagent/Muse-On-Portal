package com.myfriendkennyagent.museportal.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (device tokens, the SDK token) with an AES-256-GCM
 * key that never leaves the Android Keystore, so the files are useless if
 * copied off the device (for example with `run-as` on a debug build).
 *
 * If the Keystore misbehaves on this firmware, falls back to storing text as
 * is: it still sits in app-private storage, as the Linux SDK keeps it in a
 * root-only directory.
 */
object SecretBox {
  private const val TAG = "MuseSecretBox"
  private const val ALIAS = "museportal-store"
  private const val PREFIX = "enc1:"

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return gen.generateKey()
  }

  fun seal(plain: String): String =
    try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
      val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
      Log.w(TAG, "Keystore unavailable, storing in private files unencrypted: $e")
      plain
    }

  /** Opens text from [seal]; text without the prefix (imports, fallbacks) passes through. */
  fun open(stored: String): String? {
    if (!stored.startsWith(PREFIX)) return stored
    return try {
      val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
      val cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
          init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        }
      String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
      Log.w(TAG, "couldn't decrypt a stored secret: $e")
      null
    }
  }
}
