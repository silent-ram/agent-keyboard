package com.agentkeyboard.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

internal val Context.legacyModelSecretsStore by preferencesDataStore(name = "model_secrets")
internal val Context.legacyKnowledgeStore by preferencesDataStore(name = "zhipu_knowledge")
internal val knowledgeConfigKey = stringPreferencesKey("zhipu_config_json")
internal val tavilyCiphertextKey = stringPreferencesKey("tavily_api_key_enc")
private val legacyKnowledgeKey = stringPreferencesKey("config_json")
private val connectionsMigratedKey = booleanPreferencesKey("connection_stores_migrated")

/** Consolidate the existing ciphertext unchanged so an import can publish one snapshot. */
internal fun connectionPreferencesMigration(context: Context): DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[connectionsMigratedKey] != true

        override suspend fun migrate(currentData: Preferences): Preferences {
            val next = currentData.toMutablePreferences()
            context.legacyModelSecretsStore.data.first().asMap().forEach { (key, value) ->
                if (key.name.startsWith("key_") && value is String) {
                    next[stringPreferencesKey(key.name)] = value
                }
            }
            context.legacyKnowledgeStore.data.first()[legacyKnowledgeKey]?.let { next[knowledgeConfigKey] = it }
            context.agentKeyboardDataStore.data.first()[tavilyCiphertextKey]?.let { next[tavilyCiphertextKey] = it }
            next[connectionsMigratedKey] = true
            return next
        }

        override suspend fun cleanUp() {
            context.legacyModelSecretsStore.edit { it.clear() }
            context.legacyKnowledgeStore.edit { it.remove(legacyKnowledgeKey) }
            context.agentKeyboardDataStore.edit { it.remove(tavilyCiphertextKey) }
        }
    }
