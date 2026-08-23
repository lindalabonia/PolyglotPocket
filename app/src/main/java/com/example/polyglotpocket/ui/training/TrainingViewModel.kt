package com.example.polyglotpocket.ui.training

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.data.Card
import com.example.polyglotpocket.data.AttemptRecord
import com.example.polyglotpocket.data.SessionRecord
import com.example.polyglotpocket.data.TokenStore
import kotlinx.coroutines.launch

/**
 * Gestisce la logica di gioco dell'allenamento:
 * - Scarica le flashcard dal backend (concorrenza con Coroutine)
 * - Mantiene lo stato (carta corrente, risposte giuste, errori)
 * - Verifica se la parola digitata dall'utente è corretta
 */
class TrainingViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Loading : State
        data class Question(
            val card: Card,
            val currentIndex: Int,
            val totalCards: Int,
            val feedback: Feedback? = null
        ) : State
        data class Finished(
            val total: Int,
            val correct: Int,
            val wrong: Int
        ) : State
        data class Error(val message: String) : State
    }

    data class Feedback(
        val isCorrect: Boolean,
        val correctAnswer: String
    )

    private val _state = MutableLiveData<State>(State.Loading)
    val state: LiveData<State> = _state

    private var cards: List<Card> = emptyList()
    private var currentIndex = 0
    private var correctCount = 0
    private var wrongCount = 0

    // nuove variabili per tenere traccia della sessione
    private val attempts = mutableListOf<AttemptRecord>()
    private var startTimeMs = 0L
    private var currentMode = "random"
    private var currentLang = "spa"

    // Guards: whether a session was started (re-show the count dialog after a
    // rotation only if not) and whether it already finished (single save).
    var hasStarted = false
        private set
    private var finished = false

    /**
     * Scarica le carte dal backend e avvia la sessione.
     */
    /**
     * Scarica le carte dal backend e avvia la sessione (casuale o errori).
     */
    fun startTraining(mode: String = "random", targetLang: String = "spa", numCards: Int = 10) {
        _state.value = State.Loading
        currentMode = mode
        currentLang = targetLang
        currentIndex = 0
        correctCount = 0
        wrongCount = 0
        attempts.clear()
        hasStarted = true
        finished = false
        startTimeMs = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                val token = TokenStore.get(getApplication())
                if (token == null) {
                    _state.value = State.Error("Session expired, please log in again.")
                    return@launch
                }
                // "errors" = only past mistakes; both modes include the user's own photo cards.
                cards = if (mode == "errors") {
                    BackendApi.getErrorCards(token, targetLang, numCards)
                } else {
                    BackendApi.getCards(targetLang, numCards, token)
                }

                if (cards.isEmpty()) {
                    if (mode == "errors") {
                        _state.value = State.Error("No mistakes recorded for this language. Great job!")
                    } else {
                        _state.value = State.Error("No cards found for the selected language.")
                    }
                } else {
                    showCurrentCard(feedback = null)
                }
            } catch (e: Exception) {
                _state.value = State.Error("Loading error: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Confronta la risposta dell'utente con la traduzione corretta.
     */
    fun checkAnswer(userAnswer: String) {
        val currentCard = cards.getOrNull(currentIndex) ?: return

        // Normalizziamo le stringhe: togliamo spazi extra e rendiamo tutto minuscolo
        val cleanUser = userAnswer.trim().lowercase()
        val cleanTarget = currentCard.wordTarget.trim().lowercase()

        val isCorrect = cleanUser == cleanTarget
        if (isCorrect) {
            correctCount++
        } else {
            wrongCount++
        }

        // <-- AGGIUNGI QUESTA RIGA PER MEMORIZZARE IL TENTATIVO:
        attempts.add(AttemptRecord(cardId = currentCard.id, answerGiven = cleanUser, isCorrect = isCorrect))
        showCurrentCard(feedback = Feedback(isCorrect = isCorrect, correctAnswer = currentCard.wordTarget))
    }

    /**
     * Passa alla carta successiva o conclude l'allenamento.
     */
    fun nextCard() {
        if (finished) return  // ignore extra calls (button + gyroscope) after the last card
        currentIndex++
        if (currentIndex < cards.size) {
            showCurrentCard(feedback = null)
        } else {
            finished = true
            // Calcoliamo la durata della sessione
            val durationMs = System.currentTimeMillis() - startTimeMs

            // Inviamo in background i risultati al server
            viewModelScope.launch {
                try {
                    val token = TokenStore.get(getApplication()) ?: return@launch
                    BackendApi.saveSession(
                        token,
                        SessionRecord(
                            mode = currentMode,
                            targetLang = currentLang,
                            numCards = cards.size,
                            numCorrect = correctCount,
                            numWrong = wrongCount,
                            durationMs = durationMs,
                            attempts = attempts
                        )
                    )
                } catch (_: Exception) {
                    // Se fallisce l'invio non blocchiamo l'utente
                }
            }

            _state.value = State.Finished(
                total = cards.size,
                correct = correctCount,
                wrong = wrongCount
            )
        }
    }

    private fun showCurrentCard(feedback: Feedback?) {
        val card = cards[currentIndex]
        _state.value = State.Question(
            card = card,
            currentIndex = currentIndex,
            totalCards = cards.size,
            feedback = feedback
        )
    }
}