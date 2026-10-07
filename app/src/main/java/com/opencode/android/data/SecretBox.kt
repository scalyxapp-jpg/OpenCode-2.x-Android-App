package com.opencode.android.data
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.opencode.android.R
import com.opencode.android.util.APP_LOG_TAG
import com.opencode.android.util.AppLog
import com.opencode.android.util.UserMessages
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the backend's HTTP Basic password) with an AES key
 * that lives in the Android Keystore and never leaves it.
 *
 * The password used to be stored as plaintext in SharedPreferences. It is
 * already excluded from cloud backup, but on a rooted device — or in a
 * debug/backup extraction — it was readable. Keystore-backed AES/GCM removes
 * that exposure without pulling in a dependency (Jetpack Security is
 * deprecated, and a platform-API implementation is ~50 lines).
 *
 * Stored form: `"v1:" + base64(iv || ciphertext)`. Any value without the prefix
 * is treated as a legacy plaintext secret, so existing installs keep working
 * and are re-encrypted on the next save.
 */
object SecretBox {
    private const val PREFIX = "v1:"
    private const val KEY_ALIAS = "opencode.backend.secret"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private fun key(): SecretKey? =
        synchronized(this) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                existing ?: KeyGenerator
                    .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    .apply {
                        init(
                            KeyGenParameterSpec
                                .Builder(
                                    KEY_ALIAS,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build(),
                        )
                    }.generateKey()
            } catch (e: Exception) {
                AppLog.e(APP_LOG_TAG, "SecretBox: keystore unavailable: ${e.message}")
                UserMessages.post(R.string.secure_storage_unavailable, e.message ?: "")
                null
            }
        }

    /**
     * Returns the encrypted, prefixed form, or null when the Keystore is
     * unavailable.
     *
     * FAIL CLOSED: it must never return the plaintext. The old fallback wrote
     * the raw password into SharedPreferences — exactly the exposure this class
     * exists to remove — silently and permanently. Callers now drop the
     * credential and surface the failure, so the user re-enters it rather than
     * having it stored unprotected.
     */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return plain
        val secretKey = key() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "SecretBox: encrypt failed: ${e.message}")
            UserMessages.post(R.string.secure_storage_unavailable, e.message ?: "")
            null
        }
    }

    /**
     * Decrypts a prefixed value; anything else is returned unchanged (legacy
     * plaintext). A value that cannot be decrypted yields "" so the user is
     * asked for the password again instead of sending a broken credential.
     */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val secretKey = key() ?: return ""
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= GCM_IV_BYTES) return ""
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val body = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.e(APP_LOG_TAG, "SecretBox: decrypt failed: ${e.message}")
            UserMessages.post(R.string.secure_storage_unavailable, e.message ?: "")
            ""
        }
    }
}
