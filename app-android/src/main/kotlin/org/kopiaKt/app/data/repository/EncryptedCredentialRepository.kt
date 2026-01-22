package org.kopiaKt.app.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.kopiaKt.app.domain.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedCredentialRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialRepository {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "kopia_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun storePassword(connectionId: String, password: String) {
        sharedPreferences.edit()
            .putString(keyForConnection(connectionId), password)
            .apply()
    }

    override suspend fun getPassword(connectionId: String): String? {
        return sharedPreferences.getString(keyForConnection(connectionId), null)
    }

    override suspend fun deletePassword(connectionId: String) {
        sharedPreferences.edit()
            .remove(keyForConnection(connectionId))
            .apply()
    }

    override suspend fun hasPassword(connectionId: String): Boolean {
        return sharedPreferences.contains(keyForConnection(connectionId))
    }

    private fun keyForConnection(connectionId: String): String = "password_$connectionId"
}
