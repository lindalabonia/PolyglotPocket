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
 * Manages the flashcard training game logic:
 * - Fetches flashcards from backend asynchronously with Coroutines
 * - Maintains state (current card, correct answers, wrong answers)
 * - Verifies typed answers against target translations
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

    // Session tracking fields
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
     * Fetches cards from backend and starts the training session (random, errors, or gps with theme).
     */
    fun startTraining(mode: String = "random", targetLang: String = "spa", numCards: Int = 10, theme: String? = null) {
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
                // If mode is "errors", fetch only past mistakes (requires token)
                cards = when (mode) {
                    "errors" -> {
                        val token = TokenStore.get(getApplication())
                        if (token == null) {
                            _state.value = State.Error("Session expired, please log in again.")
                            return@launch
                        }
                        BackendApi.getErrorCards(token, targetLang, numCards)
                    }
                    "gps" -> {
                        BackendApi.getCards(targetLang, numCards, theme = theme, token)
                    }
                    else -> {
                        BackendApi.getCards(targetLang, numCards, token)
                    }
                }

                if (cards.isEmpty()) {
                    if (mode == "errors") {
                        _state.value = State.Error("No mistakes recorded for this language. Great job!")
                    } else if (mode == "gps" && !theme.isNullOrBlank()) {
                        _state.value = State.Error("No cards found for theme: $theme. Try random training!")
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
     * Compares user input against the expected target word.
     */
    fun checkAnswer(userAnswer: String) {
        val currentCard = cards.getOrNull(currentIndex) ?: return

        // Normalize strings: trim whitespace and lowercase
        val cleanUser = userAnswer.trim().lowercase()
        val cleanTarget = currentCard.wordTarget.trim().lowercase()

        val isCorrect = cleanUser == cleanTarget
        if (isCorrect) {
            correctCount++
        } else {
            wrongCount++
        }

        attempts.add(AttemptRecord(cardId = currentCard.id, answerGiven = cleanUser, isCorrect = isCorrect))
        showCurrentCard(feedback = Feedback(isCorrect = isCorrect, correctAnswer = currentCard.wordTarget))
    }

    /**
     * Advances to next flashcard or completes the session.
     */
    fun nextCard() {
        if (finished) return  // ignore extra calls (button + gyroscope) after the last card
        currentIndex++
        if (currentIndex < cards.size) {
            showCurrentCard(feedback = null)
        } else {
            finished = true
            val durationMs = System.currentTimeMillis() - startTimeMs

            // Send session results in background to server
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
                    // Non-blocking if save fails
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