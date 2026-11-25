package com.syncpedia.crm.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.syncpedia.crm.databinding.ActivityLoginBinding
import com.syncpedia.crm.models.GoogleLoginRequest
import com.syncpedia.crm.models.LoginRequest
import com.syncpedia.crm.models.LoginResponse
import com.syncpedia.crm.models.OrganizationInfo
import com.syncpedia.crm.models.UserInfo
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private val gson = Gson()
    private var googleClientId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager.getInstance(this)
        if (session.isLoggedIn()) {
            goMain()
            return
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnGoogle.setOnClickListener { startGoogleSignIn() }
        loadGoogleSignIn()
    }

    private fun loadGoogleSignIn() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.googleConfig()
                val id = resp.body()?.clientId?.trim().orEmpty()
                if (resp.isSuccessful && id.isNotEmpty()) {
                    googleClientId = id
                    binding.googleSection.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.w("LoginActivity", "google config unavailable", e)
            }
        }
    }

    private fun startGoogleSignIn() {
        val clientId = googleClientId
        if (clientId.isBlank()) {
            showLoginError("Google sign-in is not configured")
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val result = CredentialManager.create(this@LoginActivity)
                    .getCredential(this@LoginActivity, request)
                val cred = result.credential
                if (cred !is CustomCredential ||
                    cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    showLoginError("Google did not return a sign-in credential")
                    return@launch
                }
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                if (idToken.isBlank()) {
                    showLoginError("Google did not return a sign-in token")
                    return@launch
                }
                completeGoogleLogin(idToken)
            } catch (_: GetCredentialCancellationException) {
                // Account picker dismissed.
            } catch (_: NoCredentialException) {
                showLoginError("No Google account is available on this phone. Add one in Settings, or sign in with email.")
            } catch (e: GetCredentialException) {
                Log.e("LoginActivity", "google credential failed", e)
                showLoginError(googleSignInError(e))
            } catch (e: Exception) {
                Log.e("LoginActivity", "google sign-in failed", e)
                showLoginError(e.message ?: "Google sign-in failed")
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun completeGoogleLogin(idToken: String) {
        val resp = RetrofitClient.api.googleLogin(GoogleLoginRequest(idToken))
        val body = resp.body()
        if (resp.isSuccessful && body != null && !body.token.isNullOrBlank() && body.user != null) {
            saveSessionAndEnter(body)
            return
        }
        val errText = resp.errorBody()?.string().orEmpty()
        val parsedErr = try {
            gson.fromJson(errText.ifBlank { "{}" }, LoginResponse::class.java)
        } catch (_: Exception) {
            null
        }
        val serverMsg = parsedErr?.error
            ?: parsedErr?.message
            ?: body?.error
            ?: body?.message
            ?: sanitizeError(errText).takeIf { it.isNotBlank() }
            ?: "Google sign-in failed (HTTP ${resp.code()})"
        Log.e("LoginActivity", "google login failed code=${resp.code()}")
        showLoginError(serverMsg)
    }

    private fun googleSignInError(e: GetCredentialException): String {
        val raw = (e.message ?: "").lowercase()
        if (raw.contains("28444") || raw.contains("developer console") || raw.contains(": 10") || raw.contains("10:")) {
            return "Google sign-in is not set up for this app build yet. In Google Cloud, add an Android OAuth client for com.syncpedia.crm using this app’s SHA-1."
        }
        return sanitizeError(e.message ?: "Google sign-in failed").ifBlank { "Google sign-in failed" }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            return
        }
        binding.tilEmail.error = null

        if (password.isEmpty()) {
            binding.tilPassword.error = "Enter password"
            return
        }
        binding.tilPassword.error = null

        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.login(
                    body = LoginRequest(email, password)
                )
                val body = resp.body()
                val errText = resp.errorBody()?.string().orEmpty()

                if (resp.isSuccessful && body != null && !body.token.isNullOrBlank() && body.user != null) {
                    saveSessionAndEnter(body)
                    return@launch
                }

                if (resp.isSuccessful && body?.token.isNullOrBlank()) {
                    val raw = withContext(Dispatchers.IO) { rawLogin(email, password) }
                    Log.e("LoginActivity", "retrofit body empty; http ok but parse failed")
                    val parsed = parseLoginJson(raw)
                    if (parsed != null && !parsed.token.isNullOrBlank() && parsed.user != null) {
                        saveSessionAndEnter(parsed)
                        return@launch
                    }
                    showLoginError("Login succeeded but response could not be read")
                    return@launch
                }

                val parsedErr = try {
                    gson.fromJson(errText.ifBlank { "{}" }, LoginResponse::class.java)
                } catch (_: Exception) {
                    null
                }
                val serverMsg = parsedErr?.error
                    ?: parsedErr?.message
                    ?: body?.error
                    ?: body?.message
                    ?: sanitizeError(errText).takeIf { it.isNotBlank() }
                    ?: "Login failed (HTTP ${resp.code()})"

                Log.e("LoginActivity", "login failed code=${resp.code()}")
                showLoginError(serverMsg)
            } catch (e: Exception) {
                Log.e("LoginActivity", "login exception", e)
                try {
                    val raw = withContext(Dispatchers.IO) { rawLogin(email, password) }
                    val parsed = parseLoginJson(raw)
                    if (parsed != null && !parsed.token.isNullOrBlank() && parsed.user != null) {
                        saveSessionAndEnter(parsed)
                        return@launch
                    }
                    showLoginError(sanitizeError(raw).ifBlank { e.message ?: "Network error" })
                } catch (e2: Exception) {
                    showLoginError(e2.message ?: e.message ?: "Network error — check internet")
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private fun saveSessionAndEnter(body: LoginResponse) {
        val user = body.user!!
        val token = body.token!!
        session.saveToken(token)
        session.saveUser(user)
        val orgFromJwt = SessionManager.extractClaim(token, "org_id")
        val orgId = body.organization?.id?.takeIf { it.isNotBlank() }
            ?: user.orgId?.takeIf { it.isNotBlank() }
            ?: orgFromJwt
        val orgName = body.organization?.name
        session.saveOrganization(orgId, orgName)
        goMain()
    }

    private fun parseLoginJson(raw: String): LoginResponse? {
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.has("error")) return null
            val token = root.get("token")?.asString ?: return null
            val userObj = root.getAsJsonObject("user") ?: return null
            val user = UserInfo(
                id = userObj.get("id")?.asString.orEmpty(),
                email = userObj.get("email")?.asString.orEmpty(),
                role = userObj.get("role")?.asString.orEmpty(),
                fullName = userObj.get("full_name")?.asString,
                name = userObj.get("name")?.asString,
                orgId = userObj.get("org_id")?.asString
                    ?: SessionManager.extractClaim(token, "org_id")
            )
            val org = root.getAsJsonObject("organization")?.let {
                OrganizationInfo(
                    id = it.get("id")?.asString,
                    name = it.get("name")?.asString,
                    slug = it.get("slug")?.asString
                )
            }
            LoginResponse(token = token, user = user, organization = org)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fallback for when Retrofit's Gson conversion throws on an otherwise-OK response.
     * MUST reuse RetrofitClient's pinned OkHttpClient — building a fresh client here would
     * send the password over a connection with no certificate pinning.
     */
    private fun rawLogin(email: String, password: String): String {
        val client = RetrofitClient.okHttpClient
        val json = gson.toJson(LoginRequest(email, password))
        val req = Request.Builder()
            .url("https://crm.syncpedia.in/api/auth.php")
            .header("Accept", "application/json")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun sanitizeError(message: String): String {
        return message
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private fun showLoginError(message: String) {
        val short = sanitizeError(message).ifBlank { "Login failed" }
        AlertDialog.Builder(this)
            .setTitle("Login failed")
            .setMessage(short)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnGoogle.isEnabled = !loading
    }

    private fun goMain() {
        com.syncpedia.crm.workers.NotificationWorker.schedule(this)
        val next = if (com.syncpedia.crm.utils.ConsentPrefs.hasAccepted(this)) {
            MainActivity::class.java
        } else {
            CallConsentActivity::class.java
        }
        startActivity(
            Intent(this, next).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }
}
