package com.ykb.review.config

import java.net.URI

class ConfigurationError(message: String) : Exception(message)

object ConfigValidation {

    /** HTTP(S), kimlik bilgisi / query / fragment içermeyen URL doğrular; sondaki `/` kaldırılmış hali döner. */
    fun httpUrl(value: String, label: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) throw ConfigurationError("Eksik ayar: $label")
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw ConfigurationError("Geçersiz URL ayarı: $label")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw ConfigurationError("$label HTTP veya HTTPS kullanmalıdır.")
        if (uri.host.isNullOrBlank()) throw ConfigurationError("Geçersiz URL ayarı: $label")
        if (!uri.rawUserInfo.isNullOrEmpty()) throw ConfigurationError("$label kimlik bilgisi içermemelidir.")
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
            throw ConfigurationError("$label query veya fragment içermemelidir.")
        }
        return raw.trimEnd('/')
    }

    fun bitbucketBaseUrl(value: String): String {
        val url = httpUrl(value, "Bitbucket Base URL")
        val host = URI(url).host.lowercase()
        if (host == "bitbucket.org" || host == "api.bitbucket.org") {
            throw ConfigurationError("Yalnızca Bitbucket Server/Data Center Base URL desteklenir.")
        }
        return url
    }

    fun modelBaseUrl(value: String): String = httpUrl(value, "AI API Base URL")

    fun modelName(value: String, label: String): String =
        value.trim().ifEmpty { throw ConfigurationError("$label ayarlanmamış.") }

    fun interval(value: Int): Int {
        if (value !in 1..1440) throw ConfigurationError("Otomatik review aralığı 1–1440 dakika arasında olmalıdır.")
        return value
    }
}
