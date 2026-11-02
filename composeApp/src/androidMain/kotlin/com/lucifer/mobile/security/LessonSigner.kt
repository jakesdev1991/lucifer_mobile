package com.lucifer.mobile.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lucifer.mobile.resilience.LessonExchange
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Wires the lesson-export signer to a non-exportable HMAC key held in the Android Keystore.
 *
 * The key is generated with PURPOSE_SIGN|VERIFY only (no encrypt/decrypt), so it cannot be used
 * to read anything, and it never leaves the Keystore: [LessonExchange.signer] is handed a closure
 * that asks the Keystore to compute the MAC. Install once from [LuciferApplication]; the closure
 * is process-wide and idempotent.
 */
object LessonSigner {

    private const val KEY_ALIAS = "lucifer.mobile.lesson-export-hmac.v1"

    @Volatile private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        LessonExchange.signer = { payload -> signWithDeviceKey(payload) }
        installed = true
    }

    private fun deviceKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setKeySize(256).build()
        )
        return generator.generateKey()
    }

    private fun signWithDeviceKey(payload: String): String? = try {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(deviceKey())
        mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    } catch (error: Exception) {
        android.util.Log.w("LessonSigner", "sign failed: ${error.javaClass.simpleName}")
        null
    }
}
