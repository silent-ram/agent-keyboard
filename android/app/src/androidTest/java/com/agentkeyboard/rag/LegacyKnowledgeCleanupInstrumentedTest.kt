package com.agentkeyboard.rag

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentkeyboard.data.db.AgentDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyKnowledgeCleanupInstrumentedTest {

    @Test
    fun secureDeletePragmaRunsOnBundledSqliteAndIsEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build()

        try {
            val db = database.openHelper.writableDatabase
            enableLegacyKnowledgeSecureDelete(db)
            db.query("PRAGMA secure_delete").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }
}
