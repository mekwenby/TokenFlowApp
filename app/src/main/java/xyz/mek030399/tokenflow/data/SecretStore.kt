package xyz.mek030399.tokenflow.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStoreSnapshot internal constructor(internal val values: Map<String, String?>)

class SecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun read(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            require(raw.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE)))
            cipher.doFinal(raw.copyOfRange(IV_SIZE, raw.size)).decodeToString()
        }.getOrElse {
            preferences.edit().remove(name).apply()
            null
        }
    }

    @Synchronized
    fun write(name: String, value: String) {
        writeAll(mapOf(name to value))
    }

    @Synchronized
    fun writeAll(values: Map<String, String?>) {
        require(values.keys.none(String::isBlank))
        val encoded = values.mapValues { (_, value) -> value?.let(::encrypt) }
        val editor = preferences.edit()
        encoded.forEach { (name, value) ->
            if (value == null) editor.remove(name) else editor.putString(name, value)
        }
        check(editor.commit()) { "Unable to persist encrypted secrets" }
    }

    @Synchronized
    fun replaceWithSnapshot(
        clearNames: Set<String> = emptySet(),
        clearPrefixes: Set<String> = emptySet(),
        updates: Map<String, String?> = emptyMap(),
    ): SecretStoreSnapshot {
        require(clearNames.none(String::isBlank) && clearPrefixes.none(String::isBlank))
        val matchingNames = preferences.all.keys.filterTo(mutableSetOf()) { name ->
            clearPrefixes.any(name::startsWith)
        }
        val affectedNames = matchingNames + clearNames + updates.keys
        val previous = affectedNames.associateWith(::read)
        writeAll(affectedNames.associateWith { null as String? } + updates)
        return SecretStoreSnapshot(previous)
    }

    @Synchronized
    fun restore(snapshot: SecretStoreSnapshot) {
        writeAll(snapshot.values)
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    @Synchronized
    fun remove(name: String) {
        preferences.edit().remove(name).apply()
    }

    fun providerKeyName(providerId: String) = "provider:$providerId"
    fun cloudPrivateKeyName(serverId: String) = "cloud-private-key:$serverId"
    fun cloudPrivateKeyPassphraseName(serverId: String) = "cloud-private-key-passphrase:$serverId"
    fun cloudMcpEnvironmentPrefix(mcpServerId: String) = "cloud-mcp-env:$mcpServerId:"
    fun cloudMcpHeaderPrefix(mcpServerId: String) = "cloud-mcp-header:$mcpServerId:"
    fun cloudMcpEnvironmentName(mcpServerId: String, name: String) = "cloud-mcp-env:$mcpServerId:$name"
    fun cloudMcpHeaderName(mcpServerId: String, name: String) = "cloud-mcp-header:$mcpServerId:$name"

    fun clearLegacyMobileToken() {
        appContext.getSharedPreferences("tokenflow_auth", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(LEGACY_KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val EXA_KEY = "exa"
        const val INFOFLOW_KEY = "infoflow"
        const val MIMO_TTS_KEY = "mimo_tts"
        private const val PREFERENCES = "tokenflow_secrets_v2"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tokenflow_local_secrets_v2"
        private const val LEGACY_KEY_ALIAS = "tokenflow_mobile_session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
