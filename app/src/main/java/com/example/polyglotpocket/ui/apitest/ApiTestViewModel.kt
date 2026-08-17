package com.example.polyglotpocket.ui.apitest

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.polyglotpocket.data.BackendApi
import kotlinx.coroutines.launch

/**
 * ViewModel della schermata di test del backend. Lancia le chiamate di rete in
 * viewModelScope (coroutine) e pubblica un semplice stato testuale da mostrare.
 */
class ApiTestViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val output: String = "",
    )

    private val _state = MutableLiveData(UiState())
    val state: LiveData<UiState> = _state

    fun loadLanguages() {
        _state.value = UiState(loading = true, output = "Carico /languages ...")
        viewModelScope.launch {
            _state.value = try {
                val langs = BackendApi.getLanguages()
                UiState(output = "Lingue disponibili:\n" + langs.joinToString(", "))
            } catch (e: Exception) {
                UiState(output = "ERRORE: ${e.message}")
            }
        }
    }

    fun loadCard(targetLang: String) {
        val lang = targetLang.trim()
        if (lang.isEmpty()) {
            _state.value = UiState(output = "Inserisci un codice lingua (es. spa)")
            return
        }
        _state.value = UiState(loading = true, output = "Carico una carta ($lang) ...")
        viewModelScope.launch {
            _state.value = try {
                val cards = BackendApi.getCards(lang, 1)
                if (cards.isEmpty()) {
                    UiState(output = "Nessuna carta per '$lang'")
                } else {
                    val c = cards.first()
                    UiState(output = "Carta:\n${c.wordSource}  ->  ${c.wordTarget}\ntema: ${c.theme}")
                }
            } catch (e: Exception) {
                UiState(output = "ERRORE: ${e.message}")
            }
        }
    }
}
