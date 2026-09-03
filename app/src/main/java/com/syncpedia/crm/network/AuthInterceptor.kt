package com.syncpedia.crm.network

import com.syncpedia.crm.utils.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches Bearer token except on public auth endpoints (login/signup/forgot).
 * Login now sends `action` in the JSON body (not query); treat unauthenticated
 * POSTs to auth.php as public as well.
 */
class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        val query = original.url.query.orEmpty()
        val publicActions = listOf(
            "action=login",
            "action=signup",
            "action=forgot_password",
            "action=google_login",
            "action=verify_reset_otp",
            "action=reset_password"
        )
        val isPublicAuth = path.contains("auth.php") && (
            publicActions.any { query.contains(it) } ||
                (original.method.equals("POST", ignoreCase = true) &&
                    session.getToken().isNullOrBlank())
            )

        val token = session.getToken()
        val request = if (!isPublicAuth && !token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
