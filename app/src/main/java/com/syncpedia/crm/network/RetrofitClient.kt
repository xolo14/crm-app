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
    private var _client: OkHttpClient? = null
    private lateinit var session: SessionManager

    /**
     * SPKI pins for crm.syncpedia.in: leaf + its issuing intermediate CA, so a future
     * leaf renewal from the same CA doesn't require an app update. Re-compute after
     * a CA change with:
     *   openssl s_client -connect crm.syncpedia.in:443 -servername crm.syncpedia.in -showcerts </dev/null 2>/dev/null \
     *     | openssl x509 -pubkey -noout \
     *     | openssl pkey -pubin -outform der \
     *     | openssl dgst -sha256 -binary \
     *     | openssl enc -base64
     *
     * Placeholder form (DO NOT ship): sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
     */
    private const val CERT_PIN_LEAF = "sha256/GiEwp7irU6BX3DIMlfJi5jNJfMB64zvl7SlGott33pk="
    private const val CERT_PIN_INTERMEDIATE = "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4="

    fun init(session: SessionManager) {
        this.session = session
        _api = null
        _client = null
    }

    val api: ApiService
        get() = _api ?: buildApi().also { _api = it }

    /** The pinned, authenticated client — reuse this for any hand-rolled request (e.g. LoginActivity.rawLogin). */
    val okHttpClient: OkHttpClient
        get() = _client ?: buildClient().also { _client = it }

    private fun buildClient(): OkHttpClient {
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
            .add("crm.syncpedia.in", CERT_PIN_LEAF)
            .add("crm.syncpedia.in", CERT_PIN_INTERMEDIATE)
            .build()
        return OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(userAgent)
            .addInterceptor(AuthInterceptor(session))
            .addInterceptor(logging)
            .addInterceptor(SessionExpiryInterceptor(session))
            .build()
    }

    private fun buildApi(): ApiService {
        return Retrofit.Builder()
            .baseUrl("https://crm.syncpedia.in/api/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
