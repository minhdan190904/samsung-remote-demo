package com.example.samsung_remote_test.samsung

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG_REST = "SamsungRest"

class SamsungTvRestClient(
    private val http: OkHttpClient = samsungUnsafeOkHttp()
) {
    fun deviceInfo(host: String, port: Int): String? = request(host, port, "", "GET")

    fun appStatus(host: String, port: Int, appId: String): String? =
        request(host, port, "applications/$appId", "GET")

    fun appRun(host: String, port: Int, appId: String): String? =
        request(host, port, "applications/$appId", "POST")

    fun appClose(host: String, port: Int, appId: String): String? =
        request(host, port, "applications/$appId", "DELETE")

    fun appInstall(host: String, port: Int, appId: String): String? =
        request(host, port, "applications/$appId", "PUT")

    private fun request(host: String, port: Int, route: String, method: String): String? {
        val protocol = if (port == 8002) "https" else "http"
        val url = "$protocol://$host:$port/api/v2/$route"
        val body = "".toRequestBody("text/plain".toMediaType())

        val req = when (method) {
            "POST" -> Request.Builder().url(url).post(body).build()
            "PUT" -> Request.Builder().url(url).put(body).build()
            "DELETE" -> Request.Builder().url(url).delete().build()
            else -> Request.Builder().url(url).get().build()
        }

        Log.d(TAG_REST, "HTTP $method $url")

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val b = resp.body?.string()
                Log.d(TAG_REST, "HTTP ${resp.code} ${resp.message} len=${b?.length ?: 0}")
                if (!resp.isSuccessful) {
                    Log.e(TAG_REST, "HTTP_ERR code=${resp.code} msg=${resp.message} body=${b.orEmpty()}")
                }
                b
            }
        }.onFailure {
            Log.e(TAG_REST, "HTTP_FAIL $method $url msg=${it.message}", it)
        }.getOrNull()
    }
}

fun samsungUnsafeOkHttp(): OkHttpClient {
    val trustAllCerts: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustAllCerts, SecureRandom())
    val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
    val tm = trustAllCerts[0] as X509TrustManager

    return OkHttpClient.Builder()
        .sslSocketFactory(sslSocketFactory, tm)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}