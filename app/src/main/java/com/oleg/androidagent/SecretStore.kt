package com.oleg.androidagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StateStore {
    fun get(key: String): String
    fun put(key: String, value: String)
}

/** Every value, including pending meals and Telegram offsets, is encrypted at rest. */
class SecretStore(context: Context) : StateStore {
    private val prefs = context.getSharedPreferences("agent_private", Context.MODE_PRIVATE)
    companion object { private val lock = Any(); private const val ALIAS = "android-agent-v1" }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun get(key: String): String = synchronized(lock) {
        val saved = prefs.getString(key, null) ?: return@synchronized ""
        val parts = saved.split(":")
        require(parts.size == 2) { "Encrypted storage invalid; clear app data and configure again" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    override fun put(key: String, value: String) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val saved = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(key, saved).commit()) { "Unable to persist state; action stopped" }
    }
    fun config(): JSONObject = get("config").let { if (it.isBlank()) JSONObject() else JSONObject(it) }
}
