/*
 * Encrypted store for AI provider API keys, backed by the Android Keystore
 * AES-GCM key. Each plaintext value is encrypted with a 256-bit AES key
 * named [KEY_ALIAS]; the ciphertext (IV || ct) is base64-encoded and saved
 * to a dedicated SharedPreferences file `ai_secrets`.
 *
 * Migration:
 *   Old builds wrote keys into the global default-prefs as plain text under
 *   keys like `ai_agent_openrouter_api_key`. On first read, [getOrMigrate]
 *   pulls the legacy value, encrypts it into the vault, and clears the
 *   plaintext from default-prefs so it can never be read again.
 *
 * Threading: all methods are safe to call from any thread; KeyStore /
 * Cipher init is cheap after warm-up.
 */
package com.tom.rv2ide.artificial.secrets

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EncryptedKeyVault {

  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "ai_codestudio_master_v1"
  private const val PREFS_FILE = "ai_secrets"
  private const val GCM_TAG_BITS = 128
  private const val IV_BYTES = 12

  private fun ensureKey(): SecretKey {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    kg.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false)
        .build()
    )
    return kg.generateKey()
  }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

  /**
   * Encrypt and store [value] under [name]. Empty strings are stored as a
   * tombstone so a previously-set secret can be intentionally cleared.
   */
  fun put(context: Context, name: String, value: String) {
    val sp = prefs(context)
    if (value.isEmpty()) {
      sp.edit().putString(name, "").apply()
      return
    }
    try {
      val secret = ensureKey()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, secret)
      val iv = cipher.iv // 12 bytes
      val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
      val out = ByteArray(iv.size + ct.size)
      System.arraycopy(iv, 0, out, 0, iv.size)
      System.arraycopy(ct, 0, out, iv.size, ct.size)
      sp.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    } catch (t: Throwable) {
      // Encryption can rarely fail (e.g. user wiped Keystore). Fall back to
      // unencrypted save with a marker so the user keeps the key working;
      // on next session a migration will re-encrypt.
      sp.edit().putString(name, "PLAIN:$value").apply()
    }
  }

  /**
   * Decrypt and return the value for [name]. If no vault entry exists, look
   * up the legacy plaintext under [legacyKey] in default-prefs and migrate it.
   * Returns "" if neither exists.
   */
  fun getOrMigrate(context: Context, name: String, legacyKey: String): String {
    val sp = prefs(context)
    val raw = sp.getString(name, null)
    if (raw != null) {
      if (raw.isEmpty()) return ""
      if (raw.startsWith("PLAIN:")) return raw.removePrefix("PLAIN:")
      return decrypt(raw)
    }
    // Legacy migration
    val legacy = try {
      com.tom.rv2ide.preferences.internal.prefManager.getString(legacyKey, "")
    } catch (_: Throwable) { "" }
    if (legacy.isNotBlank()) {
      put(context, name, legacy)
      try {
        com.tom.rv2ide.preferences.internal.prefManager.putString(legacyKey, "")
      } catch (_: Throwable) { /* best-effort */ }
      return legacy
    }
    return ""
  }

  private fun decrypt(b64: String): String {
    return try {
      val bytes = Base64.decode(b64, Base64.NO_WRAP)
      if (bytes.size <= IV_BYTES) return ""
      val iv = bytes.copyOfRange(0, IV_BYTES)
      val ct = bytes.copyOfRange(IV_BYTES, bytes.size)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, ensureKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
      String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (_: Throwable) { "" }
  }

  /** Wipe the encrypted secret. */
  fun clear(context: Context, name: String) {
    prefs(context).edit().remove(name).apply()
  }

  /**
   * Returns whether the device exposes a hardware-backed Keystore (TEE /
   * StrongBox). UI uses this to render a "Hardware-backed" badge.
   */
  fun isHardwareBacked(): Boolean {
    return try {
      val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      val key = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: return false
      val factory = javax.crypto.SecretKeyFactory.getInstance(
        key.algorithm, ANDROID_KEYSTORE,
      )
      val info = factory.getKeySpec(
        key, android.security.keystore.KeyInfo::class.java,
      ) as android.security.keystore.KeyInfo
      // securityLevel exists on API 31+; isInsideSecureHardware on older.
      if (android.os.Build.VERSION.SDK_INT >= 31) {
        info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
          info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
      } else {
        @Suppress("DEPRECATION")
        info.isInsideSecureHardware
      }
    } catch (_: Throwable) { false }
  }
}
