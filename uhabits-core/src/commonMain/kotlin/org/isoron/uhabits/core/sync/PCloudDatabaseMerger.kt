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

import org.isoron.platform.io.Database
import org.isoron.platform.io.DatabaseOpener
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run

/**
 * Verifies and merges a downloaded Loop Habit Tracker database into the local
 * database. Habits are matched by UUID rather than by their local SQLite id,
 * so that auto-increment ids from two devices never collide.
 */
object PCloudDatabaseMerger {
    private val habitColumns = listOf(
        "name",
        "description",
        "question",
        "freq_num",
        "freq_den",
        "color",
        "position",
        "reminder_hour",
        "reminder_min",
        "reminder_days",
        "highlight",
        "archived",
        "type",
        "target_value",
        "target_type",
        "unit",
        "uuid",
        "category"
    )

    private val updatableHabitColumns = habitColumns.filter { it != "uuid" }

    fun verifyIntegrity(databaseOpener: DatabaseOpener, path: String): Boolean {
        val db = databaseOpener.open(path)
        return try {
            db.querySingle("PRAGMA integrity_check") { stmt ->
                stmt.getText(0)
            }.equals("ok", ignoreCase = true)
        } finally {
            db.close()
        }
    }

    fun merge(localDb: Database, remotePath: String) {
        val escapedPath = remotePath.replace("'", "''")
        var attached = false
        try {
            localDb.run("ATTACH DATABASE '$escapedPath' AS remote_db")
            attached = true
            localDb.run("BEGIN")
            try {
                insertMissingHabits(localDb)
                updateExistingHabits(localDb)
                mergeRepetitions(localDb)
                localDb.run("COMMIT")
            } catch (e: Exception) {
                runCatching { localDb.run("ROLLBACK") }
                throw e
            }
        } finally {
            if (attached) localDb.run("DETACH DATABASE remote_db")
        }
    }

    private fun insertMissingHabits(localDb: Database) {
        val columns = habitColumns.joinToString(", ")
        val remoteColumns = habitColumns.joinToString(", ") { "rh.$it" }
        localDb.run(
            """
            INSERT INTO main.Habits ($columns)
            SELECT $remoteColumns
            FROM remote_db.Habits rh
            WHERE rh.uuid IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM main.Habits m WHERE m.uuid = rh.uuid
              )
            """.trimIndent()
        )
    }

    private fun updateExistingHabits(localDb: Database) {
        val assignments = updatableHabitColumns.joinToString(",\n") { column ->
            "$column = (SELECT rh.$column FROM remote_db.Habits rh " +
                "WHERE rh.uuid = main.Habits.uuid)"
        }
        localDb.run(
            """
            UPDATE main.Habits
            SET $assignments
            WHERE uuid IN (
                SELECT uuid FROM remote_db.Habits WHERE uuid IS NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun mergeRepetitions(localDb: Database) {
        localDb.run(
            """
            INSERT OR REPLACE INTO main.Repetitions (habit, timestamp, value, notes)
            SELECT m.id, r.timestamp, r.value, r.notes
            FROM remote_db.Repetitions r
            JOIN remote_db.Habits rh ON r.habit = rh.id
            JOIN main.Habits m ON rh.uuid = m.uuid
            """.trimIndent()
        )
    }
}
