package com.example.polyglotpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Card(
    val id: Int,
    val wordSource: String,
    val wordTarget: String,
    val theme: String,
)

data class AuthResult(val token: String, val username: String)

/** Error carrying the backend's message (the "error" field), so the UI can show it. */
class ApiException(message: String) : Exception(message)

/**
 * Minimal REST client using only the standard library (HttpURLConnection +
 * org.json), so no extra Gradle dependency. Every call runs on Dispatchers.IO.
 */
object BackendApi {

    suspend fun register(username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) { parseAuth(post("/auth/register", authBody(username, password))) }

    suspend fun login(username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) { parseAuth(post("/auth/login", authBody(username, password))) }

    suspend fun googleSignIn(idToken: String, nonce: String): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("id_token", idToken).put("nonce", nonce).toString()
        parseAuth(post("/auth/google", body))
    }

    suspend fun getLanguages(): List<String> = withContext(Dispatchers.IO) {
        val arr = JSONArray(request("GET", "/languages", null, null))
        (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun setTargetLang(token: String, targetLang: String) {
        withContext(Dispatchers.IO) {
            request("PUT", "/me/target-lang", JSONObject().put("target_lang", targetLang).toString(), token)
        }
    }

    suspend fun getCards(targetLang: String, n: Int): List<Card> = withContext(Dispatchers.IO) {
        val arr = JSONArray(request("GET", "/cards?target_lang=$targetLang&n=$n", null, null))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Card(o.getInt("id"), o.getString("word_source"), o.getString("word_target"), o.getString("theme"))
        }
    }

    private fun authBody(username: String, password: String): String =
        JSONObject().put("username", username).put("password", password).toString()

    private fun parseAuth(body: String): AuthResult {
        val o = JSONObject(body)
        return AuthResult(o.getString("token"), o.getJSONObject("user").getString("username"))
    }

    private fun post(path: String, jsonBody: String) = request("POST", path, jsonBody, null)

    private fun request(method: String, path: String, jsonBody: String?, token: String?): String {
        val conn = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (jsonBody != null) conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(parseError(text) ?: "HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(body: String): String? = try {
        JSONObject(body).optString("error").ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
