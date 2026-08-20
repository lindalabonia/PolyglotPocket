package com.example.polyglotpocket.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.data.TokenStore
import kotlinx.coroutines.launch

/**
 * Login/registration against the backend. The network calls run in
 * viewModelScope (coroutines, REQ. 7); on success the JWT is stored locally.
 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Success(val username: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    fun login(username: String, password: String) = run(username, password, register = false)
    fun register(username: String, password: String) = run(username, password, register = true)

    fun loginWithGoogle(idToken: String, rawNonce: String) {
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = try {
                val result = BackendApi.googleSignIn(idToken, rawNonce)
                TokenStore.save(getApplication(), result.token)
                State.Success(result.username)
            } catch (e: Exception) {
                State.Error(e.message ?: "Network error")
            }
        }
    }

    private fun run(username: String, password: String, register: Boolean) {
        val u = username.trim()
        if (u.isBlank() || password.isBlank()) {
            _state.value = State.Error("Enter username and password")
            return
        }
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = try {
                val result = if (register) BackendApi.register(u, password) else BackendApi.login(u, password)
                TokenStore.save(getApplication(), result.token)
                State.Success(result.username)
            } catch (e: Exception) {
                State.Error(e.message ?: "Network error")
            }
        }
    }

    fun consumeState() {
        _state.value = State.Idle
    }
}
