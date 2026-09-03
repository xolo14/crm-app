package com.syncpedia.crm.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.syncpedia.crm.databinding.ActivityLoginBinding
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private val gson = Gson()

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

    private fun rawLogin(email: String, password: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val json = gson.toJson(LoginRequest(email, password))
        val req = Request.Builder()
            .url("https://crm.syncpedia.in/api/auth.php")
            .header("Accept", "application/json")
            .header("User-Agent", "SyncpediaCRM-Android/1.0")
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
        Toast.makeText(this, short, Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this)
            .setTitle("Login failed")
            .setMessage(short)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
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
