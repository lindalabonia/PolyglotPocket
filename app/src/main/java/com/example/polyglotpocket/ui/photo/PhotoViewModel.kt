package com.example.polyglotpocket.ui.photo

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.data.DetectedObject
import com.example.polyglotpocket.data.TokenStore
import com.example.polyglotpocket.data.TranslatedWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Shared across the photo flow (capture -> result). Holds the captured image and
 * drives the two backend steps: detect objects, then translate the picked ones.
 * The bitmap kept here is the exact one sent to Vision, so the boxes it returns
 * line up with what the user sees.
 */
class PhotoViewModel(app: Application) : AndroidViewModel(app) {

    // Long side the image is scaled to before upload: enough for Vision, small payload.
    private val maxImageSide = 1024

    var targetLang: String = ""
    var capturedBitmap: Bitmap? = null
        private set

    sealed interface DetectState {
        data object Idle : DetectState
        data object Loading : DetectState
        data class Ready(val objects: List<DetectedObject>) : DetectState
        data object Empty : DetectState
        data class Error(val message: String) : DetectState
    }

    private val _detect = MutableLiveData<DetectState>(DetectState.Idle)
    val detect: LiveData<DetectState> = _detect

    sealed interface TranslateState {
        data object Idle : TranslateState
        data object Loading : TranslateState
        data class Ready(val words: List<TranslatedWord>) : TranslateState
        data class Error(val message: String) : TranslateState
    }

    private val _translate = MutableLiveData<TranslateState>(TranslateState.Idle)
    val translate: LiveData<TranslateState> = _translate

    /** Clear stale state when entering the capture screen, so a previous "Ready"
     *  detection is not redelivered and does not bounce us straight to the result. */
    fun resetForNewCapture() {
        _detect.value = DetectState.Idle
        _translate.value = TranslateState.Idle
    }

    /** Store the scaled bitmap and immediately ask the backend to locate objects. */
    fun onImageCaptured(bitmap: Bitmap) {
        capturedBitmap = scaleDown(bitmap)
        _translate.value = TranslateState.Idle
        detectObjects()
    }

    private fun detectObjects() {
        val bitmap = capturedBitmap ?: return
        _detect.value = DetectState.Loading
        viewModelScope.launch {
            try {
                val token = TokenStore.get(getApplication())
                if (token == null) {
                    _detect.value = DetectState.Error("Session expired, please log in again.")
                    return@launch
                }
                val base64 = withContext(Dispatchers.IO) { toBase64(bitmap) }
                val objects = BackendApi.detectObjects(token, base64)
                _detect.value = if (objects.isEmpty()) DetectState.Empty else DetectState.Ready(objects)
            } catch (e: Exception) {
                _detect.value = DetectState.Error(e.localizedMessage ?: "Detection failed")
            }
        }
    }

    /** Translate the English names the user picked, in one batch call. */
    fun translateWords(words: List<String>) {
        _translate.value = TranslateState.Loading
        viewModelScope.launch {
            try {
                val token = TokenStore.get(getApplication())
                if (token == null) {
                    _translate.value = TranslateState.Error("Session expired, please log in again.")
                    return@launch
                }
                val results = BackendApi.translateWords(token, words, targetLang)
                _translate.value = TranslateState.Ready(results)
            } catch (e: Exception) {
                _translate.value = TranslateState.Error(e.localizedMessage ?: "Translation failed")
            }
        }
    }

    /** Save one card. Returns true if it was actually created (false if already there). */
    suspend fun addCard(word: TranslatedWord, theme: String): Boolean {
        val token = TokenStore.get(getApplication()) ?: error("Session expired")
        return BackendApi.createCard(token, word.wordSource, word.wordTarget, targetLang, theme)
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxImageSide) return src
        val ratio = maxImageSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true
        )
    }

    private fun toBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
