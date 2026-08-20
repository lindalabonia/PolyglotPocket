package com.example.polyglotpocket.ui.apitest

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.polyglotpocket.data.BackendApi
import kotlinx.coroutines.launch

/**
 * ViewModel for the backend test screen. Runs the network calls in
 * viewModelScope (coroutines) and exposes a simple text state to display.
 */
class ApiTestViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val output: String = "",
    )

    private val _state = MutableLiveData(UiState())
    val state: LiveData<UiState> = _state

    fun loadLanguages() {
        _state.value = UiState(loading = true, output = "Loading /languages ...")
        viewModelScope.launch {
            _state.value = try {
                val langs = BackendApi.getLanguages()
                UiState(output = "Available languages:\n" + langs.joinToString(", "))
            } catch (e: Exception) {
                UiState(output = "ERROR: ${e.message}")
            }
        }
    }

    fun loadCard(targetLang: String) {
        val lang = targetLang.trim()
        if (lang.isEmpty()) {
            _state.value = UiState(output = "Enter a language code (e.g. spa)")
            return
        }
        _state.value = UiState(loading = true, output = "Loading a card ($lang) ...")
        viewModelScope.launch {
            _state.value = try {
                val cards = BackendApi.getCards(lang, 1)
                if (cards.isEmpty()) {
                    UiState(output = "No card for '$lang'")
                } else {
                    val c = cards.first()
                    UiState(output = "Card:\n${c.wordSource}  ->  ${c.wordTarget}\ntheme: ${c.theme}")
                }
            } catch (e: Exception) {
                UiState(output = "ERROR: ${e.message}")
            }
        }
    }
}
