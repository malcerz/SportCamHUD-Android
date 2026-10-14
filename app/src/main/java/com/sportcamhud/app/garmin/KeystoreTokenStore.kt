package com.sportcamhud.app.garmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sportcamhud.core.garmin.GarminTokenStore
import com.sportcamhud.core.garmin.GarminTokens
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Garmin DI tokens encrypted with an AES-GCM key that never leaves the Android Keystore
 * (the Android counterpart of Windows Credential Manager). No password is ever stored.
 */
class KeystoreTokenStore(context: Context) : GarminTokenStore {
    private val prefs = context.getSharedPreferences("garmin_session", Context.MODE_PRIVATE)

    private companion object {
        const val ALIAS = "sportcamhud_garmin_tokens"
        const val PREF_KEY = "tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    override fun load(): GarminTokens? {
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            GarminTokens.fromJson(String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun save(tokens: GarminTokens): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val enc = cipher.doFinal(tokens.toJson().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(iv + enc, Base64.NO_WRAP)).commit()
    } catch (_: Exception) {
        false
    }

    @Synchronized
    override fun delete(): Boolean = prefs.edit().remove(PREF_KEY).commit()
}
