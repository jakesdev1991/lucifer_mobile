package com.lucifer.mobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small app-private secret store backed by an AES-256 key held by Android Keystore.
 * Files contain only a version marker, a random GCM IV, and authenticated ciphertext.
 */
object SecretStore {
    private const val KEY_ALIAS = "lucifer.mobile.secret-store.v1"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    @Synchronized
    fun read(context: Context, relativePath: String): String? {
        val file = resolve(context, relativePath) ?: return null
        if (!file.isFile) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            if (!content.startsWith(PREFIX)) {
                // One-time migration for older app versions that wrote plaintext secrets.
                // Fail closed if migration cannot encrypt the value; do not hand out a plaintext fallback.
                if (!write(context, relativePath, content)) return null
                return content
            }
            val bytes = Base64.decode(content.removePrefix(PREFIX), Base64.NO_WRAP)
            if (bytes.size <= IV_SIZE_BYTES) return null
            val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun write(context: Context, relativePath: String, value: String): Boolean {
        val file = resolve(context, relativePath) ?: return false
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(payload, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                temp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun delete(context: Context, relativePath: String) {
        resolve(context, relativePath)?.delete()
    }

    private fun resolve(context: Context, relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.startsWith('/') ||
            relativePath.split('/').any { it == ".." || it.isBlank() }) return null
        val root = context.filesDir.canonicalFile
        val file = File(root, relativePath).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
