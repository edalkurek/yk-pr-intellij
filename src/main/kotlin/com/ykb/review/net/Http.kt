package com.ykb.review.net

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.net.ssl.CertificateManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Ortak HTTP altyapısı.
 * - Proxy: OkHttp varsayılan olarak ProxySelector.getDefault() kullanır; IntelliJ bunu IDE proxy ayarlarına bağlar.
 *   Proxy kimlik doğrulaması için IDE'nin java.net.Authenticator'ı kullanılır.
 * - TLS: Varsayılan olarak IDE sertifika yöneticisi (CertificateManager) kullanılır; böylece IDE'de kabul edilmiş /
 *   işletim sistemi deposundaki kurum sertifikaları geçerli olur. İsteğe bağlı olarak doğrulama kapatılabilir
 *   (VS Code eklentisindeki NODE_TLS_REJECT_UNAUTHORIZED=0 davranışının karşılığı).
 */
object Http {
    private val LOG = Logger.getInstance(Http::class.java)

    val JSON = "application/json; charset=utf-8".toMediaType()

    const val MAX_RETRY_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 1000L
    private const val MAX_DELAY_MS = 30_000L

    fun client(connectSeconds: Long, readSeconds: Long, writeSeconds: Long, disableTlsVerification: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeSeconds, TimeUnit.SECONDS)
            .proxyAuthenticator(okhttp3.Authenticator.JAVA_NET_AUTHENTICATOR)
        try {
            if (disableTlsVerification) {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(trustAll), SecureRandom())
                builder.sslSocketFactory(context.socketFactory, trustAll)
                builder.hostnameVerifier { _, _ -> true }
            } else if (ApplicationManager.getApplication() != null) {
                val manager = CertificateManager.getInstance()
                builder.sslSocketFactory(manager.sslContext.socketFactory, manager.trustManager)
            }
        } catch (e: Exception) {
            LOG.warn("IDE TLS yapılandırması uygulanamadı; JVM varsayılanı kullanılacak.", e)
        }
        return builder.build()
    }

    /**
     * Çağrıyı, mevcut ProgressIndicator iptal edildiğinde HTTP isteğini de iptal edecek şekilde çalıştırır.
     */
    fun execute(client: OkHttpClient, request: Request): Response {
        val call: Call = client.newCall(request)
        val indicator = if (ApplicationManager.getApplication() != null) ProgressManager.getGlobalProgressIndicator() else null
        if (indicator == null) return call.execute()

        val future = CompletableFuture<Response>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!future.complete(response)) response.close()
            }
        })
        while (true) {
            if (indicator.isCanceled) {
                call.cancel()
                future.thenAccept { it.close() }
                throw ProcessCanceledException()
            }
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // iptal kontrolüyle beklemeye devam
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is IOException) cause else IOException(cause?.message ?: "HTTP isteği başarısız.", cause)
            }
        }
    }

    fun isRetryableStatus(status: Int): Boolean = status == 429 || status == 503

    fun parseRetryAfter(header: String?): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toDoubleOrNull()?.let { if (it >= 0) return (it * 1000).toLong() }
        return try {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            maxOf(0L, date.toInstant().toEpochMilli() - System.currentTimeMillis())
        } catch (_: Exception) {
            null
        }
    }

    fun backoffDelay(attempt: Int, retryAfterMs: Long?): Long {
        if (retryAfterMs != null && retryAfterMs >= 0) return min(retryAfterMs, MAX_DELAY_MS)
        val exponential = (BASE_DELAY_MS * 2.0.pow(attempt)).toLong()
        val jitter = Random.nextLong(0, (BASE_DELAY_MS * 0.2).toLong().coerceAtLeast(1))
        return min(exponential + jitter, MAX_DELAY_MS)
    }

    /** Sunucu mesajlarındaki token benzeri uzun dizgeleri maskeler. */
    fun redact(text: String): String = text.replace(Regex("[a-zA-Z0-9_-]{24,}"), "[REDACTED]")

    fun origin(url: String): String {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        return "$scheme://$host:$port"
    }
}
