package com.ykb.review.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Gizli bilgileri IDE'nin güvenli deposunda (PasswordSafe: Windows Credential Manager / KeePass / macOS Keychain)
 * saklar. VS Code eklentisindeki SecretStorage'ın karşılığıdır.
 */
object SecretStore {

    enum class Key(val id: String) {
        BITBUCKET_TOKEN("bitbucketToken"),
        MODEL_API_KEY("modelApiKey"),
    }

    private fun attributes(key: Key) = CredentialAttributes(generateServiceName("YKB AI Review", key.id))

    /** Değeri okur. Eski sürümde XML'de düz metin saklanan değer varsa PasswordSafe'e taşır ve XML'den siler. */
    fun get(key: Key): String? {
        val stored = PasswordSafe.instance.getPassword(attributes(key))
        if (!stored.isNullOrBlank()) return stored

        val settings = ReviewSettingsState.instance
        val legacy = when (key) {
            Key.BITBUCKET_TOKEN -> settings.bitbucketToken
            Key.MODEL_API_KEY -> settings.aiApiKey
        }.trim()
        if (legacy.isEmpty()) return null
        set(key, legacy)
        when (key) {
            Key.BITBUCKET_TOKEN -> settings.bitbucketToken = ""
            Key.MODEL_API_KEY -> settings.aiApiKey = ""
        }
        return legacy
    }

    fun set(key: Key, value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        PasswordSafe.instance.setPassword(attributes(key), normalized)
        val settings = ReviewSettingsState.instance
        when (key) {
            Key.BITBUCKET_TOKEN -> settings.bitbucketTokenStored = normalized != null
            Key.MODEL_API_KEY -> settings.modelApiKeyStored = normalized != null
        }
    }

    fun clearAll() {
        Key.values().forEach { set(it, null) }
        val settings = ReviewSettingsState.instance
        settings.bitbucketToken = ""
        settings.aiApiKey = ""
    }
}
