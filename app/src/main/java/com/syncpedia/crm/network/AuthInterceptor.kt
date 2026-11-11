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
        val action = original.url.queryParameter("action").orEmpty()
        val publicActions = setOf(
            "login",
            "signup",
            "forgot_password",
            "google_login",
            "google_config",
            "verify_reset_otp",
            "reset_password"
        )
        // POST auth.php with no `action` query param is the plain email/password login
        // (LoginActivity.login()), which also has no token yet — still public.
        val isPublicAuth = path.endsWith("auth.php") && (
            action in publicActions ||
                (action.isEmpty() && original.method.equals("POST", ignoreCase = true))
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
