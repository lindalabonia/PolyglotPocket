package com.example.polyglotpocket.ui.login

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel della schermata di login.
 *
 * Qui vive la concurrency (REQ. 7): il login sara' una chiamata di rete al
 * backend Flask, quindi una `suspend fun` lanciata in `viewModelScope`. Per ora
 * l'autenticazione e' SIMULATA con un `delay` per mostrare lo spinner senza
 * bloccare la UI. Verra' sostituita dalla vera chiamata Retrofit (REQ. 2/9).
 */
class LoginViewModel : ViewModel() {

    /** Stati possibili della schermata di login. */
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Success(val username: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = State.Error("empty")
            return
        }
        _state.value = State.Loading
        viewModelScope.launch {
            // TODO: sostituire con la chiamata reale al backend (POST /login).
            delay(1000)
            _state.value = State.Success(username.trim())
        }
    }

    /** Riporta lo stato a Idle dopo aver mostrato un errore/successo. */
    fun consumeState() {
        _state.value = State.Idle
    }
}
