package com.looplingo.horizon.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

object SecurePrefs {

    private const val PREFS_FILE = "looplingo_secure_prefs"
    private const val LEGACY_PREFS_FILE = "looplingo_prefs"

    fun get(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val securePrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            migrateLegacyKey(context, securePrefs)

            securePrefs
        } catch (e: Exception) {
            Timber.w(e, "EncryptedSharedPreferences unavailable, falling back to plain prefs")
            context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun migrateLegacyKey(context: Context, securePrefs: SharedPreferences) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        val legacyKey = legacyPrefs.getString("groq_api_key", null)
        if (!legacyKey.isNullOrBlank() && securePrefs.getString("groq_api_key", null).isNullOrBlank()) {
            securePrefs.edit().putString("groq_api_key", legacyKey).apply()
            legacyPrefs.edit().remove("groq_api_key").apply()
            Timber.i("Migrated Groq API key from plain to encrypted prefs")
        }
    }
}
