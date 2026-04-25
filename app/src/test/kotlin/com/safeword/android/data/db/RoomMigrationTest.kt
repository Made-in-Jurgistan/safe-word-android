package com.safeword.android.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// TODO: Move to androidTest — MigrationTestHelper needs schema JSON files via Android assets,
//  which are not accessible from local unit tests under Robolectric.
@Ignore("Requires schema assets; move to androidTest source set")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomMigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SafeWordDatabase::class.java,
    )

    @Test
    fun testMigration3to4() {
        // Create version 3 database (oldest available schema)
        helper.createDatabase(TEST_DB, 3).apply {
            close()
        }

        // Test migration 3 -> 4
        helper.runMigrationsAndValidate(
            TEST_DB, 4, true, SafeWordDatabase.MIGRATION_3_4
        ).apply {
            close()
        }
    }
}
