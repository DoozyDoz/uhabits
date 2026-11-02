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

import java.security.MessageDigest

/**
 * Implements the digest used by pCloud's HTTP JSON Digest Authentication.
 *
 * The password itself is never sent to pCloud, and the application never stores
 * it. Only the digest derived from the password and the server challenge is
 * sent over the wire.
 */
object PCloudDigest {
    fun sha1Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun emailHash(email: String): String = sha1Hex(email.trim().lowercase())

    fun passwordDigest(password: String, email: String, digest: String): String =
        sha1Hex(password + emailHash(email) + digest)
}
