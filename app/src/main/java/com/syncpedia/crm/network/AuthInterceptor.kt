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
        val sentBearer = !isPublicAuth && !token.isNullOrBlank()
        val request = if (sentBearer) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        val response = chain.proceed(request)
        // Keep the session alive: save the server's renewed token, but only if this request used
        // the token still stored — never resurrect a session that was logged out mid-request.
        val refreshed = response.header("X-Refreshed-Token")?.trim()
        if (sentBearer && response.isSuccessful && !refreshed.isNullOrBlank() &&
            refreshed.count { it == '.' } == 2 && token == session.getToken()
        ) {
            session.renewToken(refreshed)
            if (com.syncpedia.crm.BuildConfig.DEBUG) {
                android.util.Log.d("AuthInterceptor", "session renewed via $path")
            }
        }
        return response
    }
}
