package com.example.polyglotpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
/** Una carta come arriva dal backend. */
data class Card(
    val id: Int,
    val wordSource: String,
    val wordTarget: String,
    val theme: String,
)

/** Rappresenta un singolo tentativo su una carta */
data class AttemptRecord(
    val cardId: Int,
    val answerGiven: String,
    val isCorrect: Boolean,
)
/** Il resoconto completo della sessione da inviare al server */
data class SessionRecord(
    val userId: Int = 1,
    val mode: String,
    val targetLang: String,
    val numCards: Int,
    val numCorrect: Int,
    val numWrong: Int,
    val durationMs: Long,
    val attempts: List<AttemptRecord>,
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

    /** Scarica le carte su cui l'utente ha sbagliato in passato */
    suspend fun getErrorCards(userId: Int = 1, targetLang: String, n: Int = 10): List<Card> = withContext(Dispatchers.IO) {
        val arr = JSONArray(httpGet("/cards/errors?user_id=$userId&target_lang=$targetLang&n=$n"))
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

    /** Invia al backend la sessione completata e i singoli tentativi */
    suspend fun saveSession(session: SessionRecord): Boolean = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("user_id", session.userId)
            put("mode", session.mode)
            put("target_lang", session.targetLang)
            put("num_cards", session.numCards)
            put("num_correct", session.numCorrect)
            put("num_wrong", session.numWrong)
            put("duration_ms", session.durationMs)

            val attemptsArray = JSONArray()
            session.attempts.forEach { att ->
                val attObj = JSONObject().apply {
                    put("card_id", att.cardId)
                    put("answer_given", att.answerGiven)
                    put("is_correct", att.isCorrect)
                }
                attemptsArray.put(attObj)
            }
            put("attempts", attemptsArray)
        }

        httpPost("/sessions", json.toString())
        true
    }

    private fun httpGet(path: String): String {
        val conn = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in (200..299)) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in (200..299)) throw RuntimeException("HTTP $code: $text")
            text
        } finally {
            conn.disconnect()
        }
    }
    private fun httpPost(path: String, jsonBody: String): String {
        val conn = (URL(ApiConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
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
