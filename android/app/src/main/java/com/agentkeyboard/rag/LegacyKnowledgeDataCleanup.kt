package com.agentkeyboard.rag

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException

private val Context.retiredEmbeddingSettings by preferencesDataStore(name = "embedding_settings")
private val Context.legacyKnowledgeCleanupState by preferencesDataStore(name = "legacy_knowledge_cleanup")

internal fun enableLegacyKnowledgeSecureDelete(db: SupportSQLiteDatabase) {
    db.query("PRAGMA secure_delete = ON").use { }
}

/** One-time removal of data owned by the retired device-local knowledge implementation. */
internal object LegacyKnowledgeDataCleanup {
    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cleanedKey = booleanPreferencesKey("complete")
        if (app.legacyKnowledgeCleanupState.data.first()[cleanedKey] == true) return@withContext

        val database = com.agentkeyboard.data.db.AgentDatabase.get(app)
        val db = database.openHelper.writableDatabase
        enableLegacyKnowledgeSecureDelete(db)
        val checkpointBusy = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }
        if (checkpointBusy) throw IOException("旧知识库数据库仍被占用，无法完成清理")
        db.execSQL("VACUUM")
        app.retiredEmbeddingSettings.edit { it.clear() }
        deleteDirectory(File(app.filesDir, "models/bge-small-zh-v1.5"))
        VecExtensionInstaller.removeLegacyCopy(app)
        app.legacyKnowledgeCleanupState.edit { it[cleanedKey] = true }
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("无法删除旧知识库模型文件")
        }
    }
}
