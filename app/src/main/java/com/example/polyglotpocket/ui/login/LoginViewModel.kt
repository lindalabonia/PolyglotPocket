package com.example.polyglotpocket.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.polyglotpocket.data.ApiException
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
        data object Offline : State
        data class Success(val username: String) : State
        data class Error(val message: String) : State
    }

    // Start in Loading so the login form stays hidden until the auto-login check resolves.
    private val _state = MutableLiveData<State>(State.Loading)
    val state: LiveData<State> = _state

    fun login(username: String, password: String) = run(username, "", password, register = false)
    fun register(username: String, email: String, password: String) =
        run(username, email, password, register = true)

    /** On startup: validate a stored token and, if good, go straight to Home;
     *  otherwise reveal the login form. */
    fun tryAutoLogin() {
        viewModelScope.launch {
            val token = TokenStore.get(getApplication())
            _state.value = if (token == null) {
                State.Idle
            } else try {
                State.Success(BackendApi.getMe(token))
            } catch (e: ApiException) {
                // Token rejected by the server (invalid/expired): drop it, show login.
                TokenStore.clear(getApplication())
                State.Idle
            } catch (e: Exception) {
                // No connection: keep the token and tell the user to get online.
                State.Offline
            }
        }
    }

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

    private fun run(username: String, email: String, password: String, register: Boolean) {
        val u = username.trim()
        if (u.isBlank() || password.isBlank()) {
            _state.value = State.Error("Enter username and password")
            return
        }
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = try {
                val result = if (register) BackendApi.register(u, email.trim(), password)
                             else BackendApi.login(u, password)
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
