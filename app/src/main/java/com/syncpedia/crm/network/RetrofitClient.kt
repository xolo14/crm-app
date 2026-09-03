package com.syncpedia.crm.network

import com.syncpedia.crm.BuildConfig
import com.syncpedia.crm.utils.SessionManager
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Bearer-token only client — intentionally NO CookieJar / Set-Cookie handling.
 * Base URL: https://crm.syncpedia.in/api/
 */
object RetrofitClient {
    private var _api: ApiService? = null
    private lateinit var session: SessionManager

    /**
     * SPKI pin for crm.syncpedia.in (leaf public key SHA-256).
     * Re-compute after certificate rotation:
     *   openssl s_client -connect crm.syncpedia.in:443 -servername crm.syncpedia.in </dev/null 2>/dev/null \
     *     | openssl x509 -pubkey -noout \
     *     | openssl pkey -pubin -outform der \
     *     | openssl dgst -sha256 -binary \
     *     | openssl enc -base64
     *
     * Placeholder form (DO NOT ship): sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
     */
    private const val CERT_PIN = "sha256/nxjYOvBymwjAHakCts4YC2RBtESbjjQkLOg/KcjYUpQ="

    fun init(session: SessionManager) {
        this.session = session
        _api = null
    }

    val api: ApiService
        get() = _api ?: buildApi().also { _api = it }

    private fun buildApi(): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val userAgent = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "SyncpediaCRM-Android/1.0 (Android; okhttp)")
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        val certificatePinner = CertificatePinner.Builder()
            .add("crm.syncpedia.in", CERT_PIN)
            .build()
        val client = OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(userAgent)
            .addInterceptor(AuthInterceptor(session))
            .addInterceptor(logging)
            .addInterceptor(SessionExpiryInterceptor(session))
            .build()
        return Retrofit.Builder()
            .baseUrl("https://crm.syncpedia.in/api/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
