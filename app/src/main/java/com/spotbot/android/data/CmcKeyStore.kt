package com.spotbot.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** CMC API anahtarının saklandığı yer. Anahtar kaynak koda gömülmez; arayüzde bir daha gösterilmez. */
interface CmcKeyStore {
    val present: StateFlow<Boolean>
    fun get(): String?
    fun set(key: String)
    fun clear()
}

/**
 * Android Keystore'da üretilen (dışarı çıkarılamaz) AES-GCM anahtarıyla şifreleyip uygulamanın özel klasöründe tutar.
 * Uygulama yedeklemeye kapalıdır (allowBackup=false).
 */
class KeystoreCmcKeyStore(dir: File) : CmcKeyStore {
    private val file = File(dir, "cmc-key.bin")
    private val _present = MutableStateFlow(file.exists())
    override val present: StateFlow<Boolean> = _present.asStateFlow()

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    override fun get(): String? = runCatching {
        if (!file.exists()) return null
        val parts = file.readText().split(':')
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    override fun set(key: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val body = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        file.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP))
        _present.value = true
    }

    @Synchronized
    override fun clear() {
        file.delete()
        _present.value = false
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "cmc_api_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** Testler için bellek içi depo. */
class MemoryCmcKeyStore(private var value: String? = null) : CmcKeyStore {
    private val _present = MutableStateFlow(value != null)
    override val present: StateFlow<Boolean> = _present.asStateFlow()
    override fun get() = value
    override fun set(key: String) { value = key; _present.value = true }
    override fun clear() { value = null; _present.value = false }
}
