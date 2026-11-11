package com.syncpedia.crm.network

import com.syncpedia.crm.utils.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Clears the local session only when the server clearly rejects the JWT.
 * Does not clear for org-scope / permission style 401 bodies (empty org, etc.).
 */
class SessionExpiryInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val path = request.url.encodedPath
        val isAuthEndpoint = path.contains("auth.php")
        val sentBearer = !request.header("Authorization").isNullOrBlank()

        if (response.code == 401 && !isAuthEndpoint && sentBearer && session.isLoggedIn()) {
            val body = try {
                response.peekBody(4096).string().lowercase()
            } catch (_: Exception) {
                ""
            }
            val orgScoped = body.contains("organization") ||
                body.contains("org_id") ||
                body.contains("no org") ||
                body.contains("switch org")
            val tokenRejected = body.contains("token") ||
                body.contains("expired") ||
                body.contains("unauthorized") ||
                body.contains("invalid") ||
                body.contains("jwt") ||
                body.isBlank()

            // Any non-org 401 means the token is no longer accepted.
            if (!orgScoped && (tokenRejected || response.code == 401)) {
                session.clearSession()
                SessionManager.sessionExpired.postValue(System.currentTimeMillis())
            }
        }
        return response
    }
}
