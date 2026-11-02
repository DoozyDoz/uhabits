/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.sync

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PCloudDatabaseMergerTest {
    @Test
    fun testMergeInsertsUpdatesAndMapsRepetitionsByUuid() = runBlocking {
        val local = TestDatabaseHelper.createEmptyDatabase()
        val remoteFile = createRemoteDatabase()
        try {
            insertHabit(local, id = 1, uuid = "local-only", name = "Local only", position = 0)
            insertHabit(local, id = 2, uuid = "shared", name = "Shared old", position = 1)
            insertRepetition(local, habitId = 2, timestamp = 100, value = 1, notes = "local")

            val localSharedId = habitId(local, "shared")
            assertEquals(2L, localSharedId)

            PCloudDatabaseMerger.merge(local, remoteFile.absolutePath)

            assertEquals(3L, local.queryLong("SELECT COUNT(*) FROM Habits"))
            assertEquals(1L, local.queryLong("SELECT COUNT(*) FROM Habits WHERE uuid = 'local-only'"))
            assertEquals(
                "Shared new",
                local.querySingle("SELECT name FROM Habits WHERE uuid = 'shared'") {
                    it.getText(0)
                }
            )

            val remoteOnlyLocalId = habitId(local, "remote-only")
            assertNotEquals(1L, remoteOnlyLocalId)
            assertEquals(
                "Remote only",
                local.querySingle("SELECT name FROM Habits WHERE uuid = 'remote-only'") {
                    it.getText(0)
                }
            )

            assertEquals(
                2,
                local.querySingle(
                    "SELECT value FROM Repetitions WHERE habit = ? AND timestamp = ?",
                    localSharedId.toString(),
                    "100"
                ) { it.getInt(0) }
            )
            assertEquals(
                3,
                local.querySingle(
                    "SELECT value FROM Repetitions WHERE habit = ? AND timestamp = ?",
                    localSharedId.toString(),
                    "200"
                ) { it.getInt(0) }
            )
            assertEquals(
                4,
                local.querySingle(
                    "SELECT value FROM Repetitions WHERE habit = ? AND timestamp = ?",
                    remoteOnlyLocalId.toString(),
                    "300"
                ) { it.getInt(0) }
            )
            assertEquals(
                0L,
                local.queryLong("SELECT COUNT(*) FROM Repetitions WHERE habit = 1")
            )
        } finally {
            local.close()
            remoteFile.delete()
        }
    }

    @Test
    fun testVerifyIntegrity() = runBlocking {
        val remoteFile = createRemoteDatabase()
        try {
            assertTrue(
                PCloudDatabaseMerger.verifyIntegrity(
                    JavaDatabaseOpener(),
                    remoteFile.absolutePath
                )
            )
        } finally {
            remoteFile.delete()
        }
    }

    private suspend fun createRemoteDatabase(): File {
        val file = Files.createTempFile("pcloud-remote", ".db").toFile()
        val db = JavaDatabaseOpener().open(file.absolutePath)
        try {
            db.setVersion(8)
            db.migrateTo(DATABASE_VERSION) { version ->
                TestDatabaseHelper.loadMigrationSQL(version)
            }
            insertHabit(db, id = 1, uuid = "remote-only", name = "Remote only", position = 0)
            insertHabit(db, id = 2, uuid = "shared", name = "Shared new", position = 1)
            insertRepetition(db, habitId = 1, timestamp = 300, value = 4, notes = "remote a")
            insertRepetition(db, habitId = 2, timestamp = 100, value = 2, notes = "remote b")
            insertRepetition(db, habitId = 2, timestamp = 200, value = 3, notes = "remote c")
        } finally {
            db.close()
        }
        return file
    }

    private fun insertHabit(
        db: Database,
        id: Long,
        uuid: String,
        name: String,
        position: Int
    ) {
        db.run(
            """
            INSERT INTO Habits (
                id, name, description, question, freq_num, freq_den, color,
                position, reminder_days, highlight, archived, type, target_value,
                target_type, unit, uuid, category
            ) VALUES (
                $id, '$name', '', '', 1, 1, 0, $position, 127, 0, 0, 0, 0, 0, '',
                '$uuid', ''
            )
            """.trimIndent()
        )
    }

    private fun insertRepetition(
        db: Database,
        habitId: Long,
        timestamp: Long,
        value: Int,
        notes: String
    ) {
        db.run(
            """
            INSERT INTO Repetitions (habit, timestamp, value, notes)
            VALUES ($habitId, $timestamp, $value, '$notes')
            """.trimIndent()
        )
    }

    private fun habitId(db: Database, uuid: String): Long {
        return db.querySingle("SELECT id FROM Habits WHERE uuid = ?", uuid) {
            it.getLong(0)
        } ?: error("Habit not found: $uuid")
    }
}
