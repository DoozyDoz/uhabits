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

import org.junit.Test
import kotlin.test.assertEquals

class PCloudDigestTest {
    @Test
    fun testSha1HexKnownVectors() {
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            PCloudDigest.sha1Hex("")
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            PCloudDigest.sha1Hex("abc")
        )
    }

    @Test
    fun testEmailHashNormalizesAndLowercasesEmail() {
        assertEquals(
            "63a710569261a24b3766275b7000ce8d7b32e2f7",
            PCloudDigest.emailHash(" User@Example.COM ")
        )
    }

    @Test
    fun testPasswordDigest() {
        assertEquals(
            "0edaf35922a0f48f9a746b7485065678e2f77c13",
            PCloudDigest.passwordDigest(
                password = "p@ssw0rd",
                email = " User@Example.COM ",
                digest = "abcdef0123456789"
            )
        )
    }
}
