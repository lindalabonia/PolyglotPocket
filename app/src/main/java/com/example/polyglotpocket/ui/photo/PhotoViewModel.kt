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

// Acts as the central brain and memory for the entire photo flow. It holds the picture the user took
// so both the Capture screen and the Result screen can share it, and talks to the server to run the AI.
class PhotoViewModel(app: Application) : AndroidViewModel(app) {

    // To save data and upload speed, we shrink the user's huge 48MP photos so their longest side is 1024 pixels.
    private val maxImageSide = 1024

    // The language the user wants to learn (e.g., "es" for Spanish) passed from the Home screen.
    var targetLang: String = ""
    
    // Keeps the shrunken photo in memory so the Result screen can draw it later without reloading files.
    var capturedBitmap: Bitmap? = null
        private set

    // The possible UI states during the first phase: uploading the photo and waiting for the AI to find objects.
    sealed interface DetectState {
        data object Idle : DetectState
        data object Loading : DetectState // Spinner is active while uploading.
        data class Ready(val objects: List<DetectedObject>) : DetectState // AI found boxes! Time to switch screens.
        data object Empty : DetectState // AI couldn't recognize anything in the photo.
        data class Error(val message: String) : DetectState // Network crash.
    }

    // at creation set the state to Idle
    private val _detect = MutableLiveData<DetectState>(DetectState.Idle)
    // this variable is used by the ViewModel to change the state
    // and by the fragment to read the state (through viewModel.detect.observe(viewLifeCycleOwner)
    val detect: LiveData<DetectState> = _detect

    // The possible UI states during the second phase: translating the English words the user tapped on.
    sealed interface TranslateState {
        data object Idle : TranslateState
        data object Loading : TranslateState // Spinner is active while translating.
        data class Ready(val words: List<TranslatedWord>) : TranslateState // Translations arrived.
        data class Error(val message: String) : TranslateState
    }

    private val _translate = MutableLiveData<TranslateState>(TranslateState.Idle)
    val translate: LiveData<TranslateState> = _translate

    // Resets the state machines when the user opens the camera fresh. 
    // This stops the app from accidentally skipping the camera if an old "Ready" state was still lingering.
    fun resetForNewCapture() {
        _detect.value = DetectState.Idle
        _translate.value = TranslateState.Idle
    }

    // Called by the CaptureFragment the moment the shutter is pressed (or gallery picture is picked).
    fun onImageCaptured(bitmap: Bitmap) {
        // Shrink the photo and save it in memory.
        capturedBitmap = scaleDown(bitmap)
        _translate.value = TranslateState.Idle
        
        // Immediately start uploading it to the AI server.
        detectObjects()
    }

    // Uploads the shrunken photo to the backend's AI.
    private fun detectObjects() {
        val bitmap = capturedBitmap ?: return
        _detect.value = DetectState.Loading // change the state to Loading while the image is sent to the server
        
        viewModelScope.launch {
            try {
                // Ensure the user is still logged in securely.
                val token = TokenStore.get(getApplication())
                if (token == null) {
                    _detect.value = DetectState.Error("Session expired, please log in again.")
                    return@launch
                }
                
                // Converting an image to text (Base64) is heavy math, so we push it to the background IO thread.
                val base64 = withContext(Dispatchers.IO) { toBase64(bitmap) }
                
                // Send the text-image to the server and wait for the list of bounding boxes.
                val objects = BackendApi.detectObjects(token, base64)
                
                // If the AI found nothing, tell the UI it's empty. Otherwise, change the state.
                // the PhotoCaptureFragment will notice the state change and trigger the jump to the PhotoResultFragment
                _detect.value = if (objects.isEmpty()) DetectState.Empty else DetectState.Ready(objects)
            } catch (e: Exception) {
                _detect.value = DetectState.Error(e.localizedMessage ?: "Detection failed")
            }
        }
    }

    // Asks the server to translate the English words of the objects the user tapped on (e.g., ["dog", "tree"]).
    fun translateWords(words: List<String>) {
        _translate.value = TranslateState.Loading
        viewModelScope.launch {
            try {
                val token = TokenStore.get(getApplication())
                if (token == null) {
                    _translate.value = TranslateState.Error("Session expired, please log in again.")
                    return@launch
                }
                
                // Fetch the translations from the server. For each the server also tells if it is already in the cards
                // (fragment behavior changes if it is a new or not card)
                val results = BackendApi.translateWords(token, words, targetLang)
                _translate.value = TranslateState.Ready(results)
            } catch (e: Exception) {
                _translate.value = TranslateState.Error(e.localizedMessage ?: "Translation failed")
            }
        }
    }

    // Tells the server to permanently save this newly translated word into the user's personal flashcard deck
    // if the user decides to do so by clicking on the 'add card' button.
    // Returns true if created successfully, or false if the user already had this card.
    suspend fun addCard(word: TranslatedWord, theme: String): Boolean {
        val token = TokenStore.get(getApplication()) ?: error("Session expired")
        return BackendApi.createCard(token, word.wordSource, word.wordTarget, targetLang, theme)
    }

    // Proportionally shrinks the photo down so its longest side is exactly "maxImageSide" (1024px).
    // This saves cellular data and makes the AI response much faster.
    private fun scaleDown(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxImageSide) return src
        
        val ratio = maxImageSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true
        )
    }

    // Compresses the raw image into a JPEG format (at 85% quality) and encodes it into a long Base64 text string 
    // so it can travel safely inside a JSON package.
    private fun toBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
