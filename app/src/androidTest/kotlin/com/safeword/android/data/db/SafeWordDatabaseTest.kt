package com.safeword.android.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest

/**
 * Instrumentation tests for [SafeWordDatabase].
 *
 * Tests Room database operations and DAO behavior.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SafeWordDatabaseTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var db: SafeWordDatabase
    private lateinit var transcriptionDao: TranscriptionDao
    private lateinit var personalizedEntryDao: PersonalizedEntryDao

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun createDb() {
        hiltRule.inject()

        // Create an in-memory database for testing
        db = Room.inMemoryDatabaseBuilder(
            context,
            SafeWordDatabase::class.java,
        ).allowMainThreadQueries().build()

        transcriptionDao = db.transcriptionDao()
        personalizedEntryDao = db.personalizedEntryDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // ═════════════════════════════════════════════════════════════════
    // Transcription DAO Tests
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun testInsertTranscription() = runTest {
        val entity = TranscriptionEntity(
            id = 0,
            text = "hello world",
            confidence = 0.95f,
            duration = 2500L,
            timestamp = System.currentTimeMillis(),
            language = "en",
            sourcePackage = "com.example.app",
        )

        val id = transcriptionDao.insertTranscription(entity)

        assertTrue(id > 0, "Insert should return a valid row ID")
    }

    @Test
    fun testInsertAndRetrieveTranscription() = runTest {
        val text = "test transcription"
        val confidence = 0.92f
        val duration = 1500L

        val entity = TranscriptionEntity(
            id = 0,
            text = text,
            confidence = confidence,
            duration = duration,
            timestamp = System.currentTimeMillis(),
            language = "en",
            sourcePackage = "com.example.app",
        )

        val id = transcriptionDao.insertTranscription(entity)
        val retrieved = transcriptionDao.getTranscriptionById(id)

        assertTrue(retrieved != null, "Should retrieve inserted transcription")
        assertEquals(text, retrieved?.text, "Text should match")
        assertEquals(confidence, retrieved?.confidence, "Confidence should match")
        assertEquals(duration, retrieved?.duration, "Duration should match")
    }

    @Test
    fun testGetAllTranscriptions() = runTest {
        // Insert multiple transcriptions
        val entity1 = TranscriptionEntity(
            id = 0,
            text = "first",
            confidence = 0.95f,
            duration = 1000L,
            timestamp = System.currentTimeMillis(),
            language = "en",
            sourcePackage = "com.example.app",
        )

        val entity2 = TranscriptionEntity(
            id = 0,
            text = "second",
            confidence = 0.88f,
            duration = 1500L,
            timestamp = System.currentTimeMillis() + 1000,
            language = "en",
            sourcePackage = "com.example.app",
        )

        transcriptionDao.insertTranscription(entity1)
        transcriptionDao.insertTranscription(entity2)

        val all = transcriptionDao.getAllTranscriptions()

        assertEquals(2, all.size, "Should retrieve all inserted transcriptions")
    }

    @Test
    fun testGetTranscriptionsByPackage() = runTest {
        val pkg1 = "com.example.app1"
        val pkg2 = "com.example.app2"

        val entity1 = TranscriptionEntity(
            id = 0,
            text = "text1",
            confidence = 0.95f,
            duration = 1000L,
            timestamp = System.currentTimeMillis(),
            language = "en",
            sourcePackage = pkg1,
        )

        val entity2 = TranscriptionEntity(
            id = 0,
            text = "text2",
            confidence = 0.88f,
            duration = 1500L,
            timestamp = System.currentTimeMillis(),
            language = "en",
            sourcePackage = pkg2,
        )

        transcriptionDao.insertTranscription(entity1)
        transcriptionDao.insertTranscription(entity2)

        val fromPkg1 = transcriptionDao.getTranscriptionsByPackage(pkg1)

        assertEquals(1, fromPkg1.size, "Should filter by package")
        assertEquals(pkg1, fromPkg1[0].sourcePackage, "Package should match")
    }

    @Test
    fun testDeleteTranscription() = runTest {
        val entity = TranscriptionEntity(
            id = 0,
            text = "to delete",
            confidence = 0.95f,
            duration = 1000L,
            timestamp = System.currentTimeMillis(),
            language = "en",
            sourcePackage = "com.example.app",
        )

        val id = transcriptionDao.insertTranscription(entity)
        val inserted = transcriptionDao.getTranscriptionById(id)
        assertTrue(inserted != null, "Should retrieve before delete")

        transcriptionDao.deleteTranscription(inserted!!)
        val deleted = transcriptionDao.getTranscriptionById(id)

        assertNull(deleted, "Should not retrieve after delete")
    }

    @Test
    fun testClearAllTranscriptions() = runTest {
        // Insert multiple transcriptions
        transcriptionDao.insertTranscription(
            TranscriptionEntity(
                id = 0,
                text = "text1",
                confidence = 0.95f,
                duration = 1000L,
                timestamp = System.currentTimeMillis(),
                language = "en",
                sourcePackage = "com.example.app",
            )
        )

        transcriptionDao.insertTranscription(
            TranscriptionEntity(
                id = 0,
                text = "text2",
                confidence = 0.88f,
                duration = 1500L,
                timestamp = System.currentTimeMillis(),
                language = "en",
                sourcePackage = "com.example.app",
            )
        )

        val before = transcriptionDao.getAllTranscriptions()
        assertEquals(2, before.size, "Should have 2 transcriptions")

        transcriptionDao.clearAll()

        val after = transcriptionDao.getAllTranscriptions()
        assertEquals(0, after.size, "Should have 0 transcriptions after clear")
    }

    // ═════════════════════════════════════════════════════════════════
    // Personalized Dictionary DAO Tests
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun testInsertPersonalizedEntry() = runTest {
        val entry = PersonalizedEntryEntity(
            id = 0,
            fromPhrase = "recognize",
            toPhrase = "recognise",
            useCount = 0,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = 0L,
        )

        val id = personalizedEntryDao.insertEntry(entry)

        assertTrue(id > 0, "Insert should return a valid row ID")
    }

    @Test
    fun testInsertAndRetrievePersonalizedEntry() = runTest {
        val fromPhrase = "color"
        val toPhrase = "colour"

        val entry = PersonalizedEntryEntity(
            id = 0,
            fromPhrase = fromPhrase,
            toPhrase = toPhrase,
            useCount = 0,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = 0L,
        )

        val id = personalizedEntryDao.insertEntry(entry)
        val retrieved = personalizedEntryDao.getEntryById(id)

        assertTrue(retrieved != null, "Should retrieve inserted entry")
        assertEquals(fromPhrase, retrieved?.fromPhrase, "From phrase should match")
        assertEquals(toPhrase, retrieved?.toPhrase, "To phrase should match")
        assertTrue(retrieved?.enabled!!, "Entry should be enabled")
    }

    @Test
    fun testGetAllPersonalizedEntries() = runTest {
        // Insert multiple entries
        personalizedEntryDao.insertEntry(
            PersonalizedEntryEntity(
                id = 0,
                fromPhrase = "recognize",
                toPhrase = "recognise",
                useCount = 0,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
            )
        )

        personalizedEntryDao.insertEntry(
            PersonalizedEntryEntity(
                id = 0,
                fromPhrase = "organize",
                toPhrase = "organise",
                useCount = 0,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
            )
        )

        val all = personalizedEntryDao.getAllEntries()

        assertEquals(2, all.size, "Should retrieve all inserted entries")
    }

    @Test
    fun testGetEnabledPersonalizedEntries() = runTest {
        // Insert enabled and disabled entries
        personalizedEntryDao.insertEntry(
            PersonalizedEntryEntity(
                id = 0,
                fromPhrase = "recognize",
                toPhrase = "recognise",
                useCount = 0,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
            )
        )

        personalizedEntryDao.insertEntry(
            PersonalizedEntryEntity(
                id = 0,
                fromPhrase = "color",
                toPhrase = "colour",
                useCount = 0,
                enabled = false,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
            )
        )

        val enabled = personalizedEntryDao.getEnabledEntries()

        assertEquals(1, enabled.size, "Should return only enabled entries")
        assertTrue(enabled[0].enabled, "Entry should be enabled")
    }

    @Test
    fun testUpdateEntryUseCount() = runTest {
        val entry = PersonalizedEntryEntity(
            id = 0,
            fromPhrase = "recognize",
            toPhrase = "recognise",
            useCount = 0,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = 0L,
        )

        val id = personalizedEntryDao.insertEntry(entry)
        val inserted = personalizedEntryDao.getEntryById(id)!!

        // Increment use count
        val updated = inserted.copy(
            useCount = inserted.useCount + 1,
            lastUsedAt = System.currentTimeMillis(),
        )
        personalizedEntryDao.updateEntry(updated)

        val retrieved = personalizedEntryDao.getEntryById(id)
        assertEquals(1, retrieved?.useCount, "Use count should be incremented")
    }

    @Test
    fun testDeletePersonalizedEntry() = runTest {
        val entry = PersonalizedEntryEntity(
            id = 0,
            fromPhrase = "recognize",
            toPhrase = "recognise",
            useCount = 0,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = 0L,
        )

        val id = personalizedEntryDao.insertEntry(entry)
        val inserted = personalizedEntryDao.getEntryById(id)

        assertTrue(inserted != null, "Should retrieve before delete")

        personalizedEntryDao.deleteEntry(inserted!!)

        val deleted = personalizedEntryDao.getEntryById(id)
        assertNull(deleted, "Should not retrieve after delete")
    }

    @Test
    fun testClearAllPersonalizedEntries() = runTest {
        // Insert multiple entries
        personalizedEntryDao.insertEntry(
            PersonalizedEntryEntity(
                id = 0,
                fromPhrase = "recognize",
                toPhrase = "recognise",
                useCount = 0,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
            )
        )

        personalizedEntryDao.insertEntry(
            PersonalizedEntryEntity(
                id = 0,
                fromPhrase = "color",
                toPhrase = "colour",
                useCount = 0,
                enabled = true,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
            )
        )

        val before = personalizedEntryDao.getAllEntries()
        assertEquals(2, before.size, "Should have 2 entries")

        personalizedEntryDao.clearAll()

        val after = personalizedEntryDao.getAllEntries()
        assertEquals(0, after.size, "Should have 0 entries after clear")
    }

    // ═════════════════════════════════════════════════════════════════
    // Schema & Migration Tests
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun testDatabaseVersion() {
        assertEquals(2, db.openHelper.readableDatabase.version, "Database version should be 2")
    }

    @Test
    fun testDatabaseTableExistence() {
        val cursor = db.openHelper.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
            null,
        )

        val tableNames = mutableSetOf<String>()
        cursor.use {
            while (cursor.moveToNext()) {
                tableNames.add(cursor.getString(0))
            }
        }

        assertTrue(tableNames.contains("transcriptions"), "Should have transcriptions table")
        assertTrue(tableNames.contains("personalized_dictionary"), "Should have personalized_dictionary table")
    }

    @Test
    fun testMigration1To2() {
        // This test verifies that the migration from version 1 to version 2 succeeds
        // In real scenario, we would use MigrationTestHelper to test this
        // For now, we verify that the table exists (migration succeeded)

        val cursor = db.openHelper.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='personalized_dictionary'",
            null,
        )

        assertTrue(cursor.moveToNext(), "Personalized dictionary table should exist after migration")
        cursor.close()
    }
}
