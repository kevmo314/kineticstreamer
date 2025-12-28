package com.kevmo314.kineticstreamer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Singleton provider for the app's DataStore to ensure only one instance exists
 */
object DataStoreProvider {
    private val Context.kineticDataStore: DataStore<Preferences> by preferencesDataStore(name = "kineticstreamer")

    fun getDataStore(context: Context): DataStore<Preferences> {
        return context.applicationContext.kineticDataStore
    }
}
