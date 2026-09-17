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

// Manages network calls and UI state for the Login screen
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    // Represents all possible UI states for the login screen
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data object Offline : State
        data class Success(val username: String) : State
        data class Error(val message: String) : State
    }

    // Start in Loading state to hide the form during the auto-login check
    private val _state = MutableLiveData<State>(State.Loading)
    val state: LiveData<State> = _state

    // see run() function below
    fun login(username: String, password: String) = run(username, "", password, register = false)
    fun register(username: String, email: String, password: String) =
        run(username, email, password, register = true)

    // on initial startup loginFragment calls tryAutoLogin
    // Checks for a saved token on startup and attempts to skip the login screen
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

    // Sends the Google credential and nonce to the backend for verification
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

    // Shared logic for both manual login and registration
    private fun run(username: String, email: String, password: String, register: Boolean) {
        val u = username.trim()
        if (u.isBlank() || password.isBlank()) {
            _state.value = State.Error("Enter username and password")
            return
        }
        
        _state.value = State.Loading

        // new thread used not to block main thread with long request to server
        viewModelScope.launch {
            _state.value = try {
                val result = if (register) BackendApi.register(u, email.trim(), password)
                             else BackendApi.login(u, password)
                // server returns JWT token if password is correct
                // it is saved by tokenstore, then state changes into 'success'
                TokenStore.save(getApplication(), result.token)
                State.Success(result.username)
            } catch (e: Exception) {
                State.Error(e.message ?: "Network error")
            }
        }
    }

    // Resets the state to Idle after showing an error message
    fun consumeState() {
        _state.value = State.Idle
    }
}
