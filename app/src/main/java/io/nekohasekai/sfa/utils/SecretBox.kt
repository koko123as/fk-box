package io.nekohasekai.sfa.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (account passwords kept in SharedPreferences) with an
 * AES-GCM key held in the Android Keystore, so the stored value is useless
 * without this app's key on this device. Values written before this existed
 * are plain text; [isEncrypted] tells them apart for a one-time migration.
 */
internal object SecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "fkbox_secret_box"
    private const val PREFIX = "enc1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteArray(1 + iv.size + sealed.size)
        payload[0] = iv.size.toByte()
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(sealed, 0, payload, 1 + iv.size, sealed.size)
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        require(isEncrypted(value)) { "Not an encrypted value" }
        val payload = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
        val ivSize = payload[0].toInt()
        require(ivSize in 12..16 && payload.size > 1 + ivSize) { "Damaged encrypted value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 1, ivSize))
        return String(cipher.doFinal(payload, 1 + ivSize, payload.size - 1 - ivSize), Charsets.UTF_8)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
