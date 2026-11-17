package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LegacyCompanionMigrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TokenFlowDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tenToTwelveAddsChatKindAndCompanionStorageWithoutChangingMessages() {
        val name = "migration-ten-twelve-${System.nanoTime()}.db"
        try {
            migrations.createDatabase(name, 10).use { db -> seedConversationAndMessage(db) }

            migrations.runMigrationsAndValidate(name, 12, true, TokenFlowDatabase.MIGRATION_10_12).use { db ->
                assertEquals(12, db.version)
                db.query("SELECT kind,title FROM conversations WHERE id='retained-chat'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("CHAT", cursor.getString(0))
                    assertEquals("Original conversation", cursor.getString(1))
                }
                assertMessageRetained(db)
                db.query("SELECT COUNT(*) FROM companion_profiles").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun observedElevenUpgradesWithKindAndCompanionRowsIntactAcrossConversationSaves() = runBlocking {
        val name = "migration-observed-eleven-${System.nanoTime()}.db"
        try {
            createObservedEleven(name)
            migrations.runMigrationsAndValidate(name, 12, true, TokenFlowDatabase.MIGRATION_11_12).use { db ->
                assertEquals(12, db.version)
                assertCompanionRetained(db)
                assertMessageRetained(db)
                db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    // Room hashes the structure rather than the version. The generated
                    // v12 schema exactly matches the observed v11 structure.
                    assertEquals(OBSERVED_V11_IDENTITY_HASH, cursor.getString(0))
                }
            }

            val database = Room.databaseBuilder(context, TokenFlowDatabase::class.java, name).build()
            try {
                val dao = database.localDao()
                val original = requireNotNull(dao.conversation("retained-chat")).toDomain()
                assertEquals("COMPANION", original.kind)
                dao.putConversation(original.copy(title = "Renamed after upgrade").toEntity())
                dao.updateConversation(original.id) { current ->
                    current.toDomain().copy(systemPrompt = "Updated settings").toEntity()
                }
                assertEquals("COMPANION", dao.conversation(original.id)?.toDomain()?.kind)
                assertEquals("Renamed after upgrade", dao.conversation(original.id)?.title)
                assertCompanionRetained(database.openHelper.readableDatabase)
                assertMessageRetained(database.openHelper.readableDatabase)

                dao.deleteConversation(original.id)
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM companion_profiles").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun incompatibleElevenFailsValidationAndKeepsItsVersionAndRows() {
        val name = "migration-incompatible-eleven-${System.nanoTime()}.db"
        try {
            createObservedEleven(name, compatibleKind = false)

            val failure = runCatching {
                migrations.runMigrationsAndValidate(name, 12, true, TokenFlowDatabase.MIGRATION_11_12).close()
            }.exceptionOrNull()

            assertTrue("Expected schema validation failure, got $failure", failure is IllegalStateException)
            context.openOrCreateDatabase(name, 0, null).use { db ->
                assertEquals(11, db.version)
                db.rawQuery("SELECT content FROM messages WHERE id='retained-message'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Original question", cursor.getString(0))
                }
                db.rawQuery("SELECT modelPath FROM companion_profiles WHERE id=9876543210", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("/private/models/retained.gguf", cursor.getString(0))
                }
                db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(OBSERVED_V11_IDENTITY_HASH, cursor.getString(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sevenAndPreReleaseEightRetainDataThroughTheFullAdditiveChainToTwelve() {
        for (version in listOf(7, 8)) {
            val name = "migration-legacy-$version-twelve-${System.nanoTime()}.db"
            try {
                migrations.createDatabase(name, 7).use { db ->
                    seedConversationAndMessage(db)
                    if (version == 8) db.execSQL("PRAGMA user_version = 8")
                }
                migrations.runMigrationsAndValidate(
                    name, 12, true,
                    TokenFlowDatabase.MIGRATION_7_9,
                    TokenFlowDatabase.MIGRATION_8_9,
                    TokenFlowDatabase.MIGRATION_9_10,
                    TokenFlowDatabase.MIGRATION_10_12,
                ).use { db ->
                    assertEquals(12, db.version)
                    assertMessageRetained(db)
                    db.query("SELECT kind,knowledgeScopeJson,contextPolicyJson FROM conversations WHERE id='retained-chat'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("CHAT", cursor.getString(0))
                        assertEquals("{}", cursor.getString(1))
                        assertEquals("{}", cursor.getString(2))
                    }
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    private fun createObservedEleven(name: String, compatibleKind: Boolean = true) {
        migrations.createDatabase(name, 10).use { db ->
            seedConversationAndMessage(db)
            db.execSQL(if (compatibleKind) {
                "ALTER TABLE conversations ADD COLUMN kind TEXT NOT NULL DEFAULT 'CHAT'"
            } else {
                "ALTER TABLE conversations ADD COLUMN kind INTEGER NOT NULL DEFAULT 0"
            })
            db.execSQL("UPDATE conversations SET kind='COMPANION' WHERE id='retained-chat'")
            db.execSQL("CREATE TABLE companion_profiles (id INTEGER NOT NULL, conversationId TEXT NOT NULL, name TEXT NOT NULL, voice TEXT NOT NULL, modelPath TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX index_companion_profiles_conversationId ON companion_profiles(conversationId)")
            db.execSQL("INSERT INTO companion_profiles(id,conversationId,name,voice,modelPath) VALUES (9876543210,'retained-chat','Retained companion','retained-voice','/private/models/retained.gguf')")
            db.execSQL("UPDATE room_master_table SET identity_hash='$OBSERVED_V11_IDENTITY_HASH' WHERE id=42")
            db.execSQL("PRAGMA user_version = 11")
        }
    }

    private fun seedConversationAndMessage(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO conversations (id,title,titleAutoGenerated,thinkingEffort,systemPrompt,nickname,userAvatar,assistantAvatar,maxToolCalls,status,statusMessage,createdAt,updatedAt) VALUES ('retained-chat','Original conversation',0,'medium','Original prompt','','U','AI',7,'idle','',1,1)")
        db.execSQL("INSERT INTO messages (id,conversationId,parentMessageId,requestId,role,content,metadata,status,createdAt) VALUES ('retained-message','retained-chat',NULL,'request','user','Original question','','completed',1)")
    }

    private fun assertCompanionRetained(db: SupportSQLiteDatabase) {
        db.query("SELECT id,conversationId,name,voice,modelPath FROM companion_profiles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(9876543210L, cursor.getLong(0))
            assertEquals("retained-chat", cursor.getString(1))
            assertEquals("Retained companion", cursor.getString(2))
            assertEquals("retained-voice", cursor.getString(3))
            assertEquals("/private/models/retained.gguf", cursor.getString(4))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertMessageRetained(db: SupportSQLiteDatabase) {
        db.query("SELECT content FROM messages WHERE id='retained-message'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Original question", cursor.getString(0))
        }
    }

    private companion object {
        const val OBSERVED_V11_IDENTITY_HASH = "237600ab1389ed8bde14f18364d2b8db"
    }
}
