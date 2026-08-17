package com.example.polyglotpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Una carta come arriva dal backend. */
data class Card(
    val id: Int,
    val wordSource: String,
    val wordTarget: String,
    val theme: String,
)

/**
 * Client REST minimale per il primo test, con la sola libreria standard
 * (HttpURLConnection + org.json): nessuna dipendenza Gradle aggiuntiva.
 * Ogni chiamata di rete gira su Dispatchers.IO (REQ. 7, concurrency).
 * In seguito lo sostituiremo con Retrofit.
 */
object BackendApi {

    suspend fun getLanguages(): List<String> = withContext(Dispatchers.IO) {
        val arr = JSONArray(httpGet("/languages"))
        (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun getCards(targetLang: String, n: Int): List<Card> = withContext(Dispatchers.IO) {
        val arr = JSONArray(httpGet("/cards?target_lang=$targetLang&n=$n"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Card(
                id = o.getInt("id"),
                wordSource = o.getString("word_source"),
                wordTarget = o.getString("word_target"),
                theme = o.getString("theme"),
            )
        }
    }

    private fun httpGet(path: String): String {
        val conn = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
