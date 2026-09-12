package com.example.polyglotpocket.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


// It uses AES/GCM encryption with 256-bit key kept in the Android Keystore to encrypt the JWT before it goes into the DataStore,
// ensuring no one can read it from the phone's storage.
object Encryptor {
    
    // The unique name given to our secret key inside the Android Keystore.
    private const val KEY_ALIAS = "PolyglotTokenKey"
    
    // We use AES encryption with Galois/Counter Mode (GCM). GCM provides both secrecy and tampering detection.
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    
    // The Initialization Vector (IV) size for GCM must be exactly 12 bytes.
    private const val IV_SIZE = 12
    
    // The authentication tag size (128 bits) ensures the data hasn't been modified.
    private const val TAG_SIZE = 128

    // Grabs our secret key from the hardware vault. If it doesn't exist yet, it creates a fresh one.
    private fun secretKey(): SecretKey {
        // Open the hardware-backed Android Keystore.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        
        // If we already created the key in the past, return it.
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        // Otherwise, ask the hardware to generate a brand new 256-bit AES key.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                // Tell the hardware this key is strictly for encrypting and decrypting.
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM) // Required mode for our AES/GCM setup.
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256) // Military-grade key length.
                .build()
        )
        return generator.generateKey()
    }

    // Encrypts the plain text token. It generates a random IV every single time so identical tokens look completely different.
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        
        // Initialize the cipher. The system automatically creates a fresh random IV.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        
        // Actually encrypt the text.
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        // Combine the random IV and the encrypted text, then convert it to a printable Base64 string.
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    // Decrypts the stored Base64 string back into the original plain text token.
    fun decrypt(encryptedData: String): String {
        // Convert the printable Base64 string back into raw bytes.
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        
        // Split the combined bytes: the first 12 bytes are the IV, the rest is the encrypted token.
        val iv = combined.copyOfRange(0, IV_SIZE)
        val cipherText = combined.copyOfRange(IV_SIZE, combined.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        
        // Initialize the cipher for decryption, handing it the exact IV that was used to encrypt this specific data.
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_SIZE, iv))
        
        // Decrypt the text and convert it back to a readable string.
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
