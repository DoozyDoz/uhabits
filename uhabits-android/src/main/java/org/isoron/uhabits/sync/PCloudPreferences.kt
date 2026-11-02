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
package org.isoron.uhabits.sync

import android.content.Context
import androidx.preference.PreferenceManager
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.inject.AppContext

/**
 * Stores the pCloud WebDAV connection. The account password is encrypted with
 * a non-exportable Android Keystore key; only ciphertext is persisted.
 */
@AppScope
@Inject
class PCloudPreferences(@AppContext context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var hostname: String
        get() = prefs.getString(KEY_HOSTNAME, PCloudHosts.US) ?: PCloudHosts.US
        set(value) {
            prefs.edit().putString(KEY_HOSTNAME, value).apply()
        }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_EMAIL) else putString(KEY_EMAIL, value)
            }.apply()
        }

    var lastSyncedHash: String?
        get() = prefs.getString(KEY_LAST_SYNCED_HASH, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_LAST_SYNCED_HASH)
                } else {
                    putString(KEY_LAST_SYNCED_HASH, value)
                }
            }.apply()
        }

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()
        }

    var lastSyncStatus: String?
        get() = prefs.getString(KEY_LAST_SYNC_STATUS, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_LAST_SYNC_STATUS)
                } else {
                    putString(KEY_LAST_SYNC_STATUS, value)
                }
            }.apply()
        }

    var settingsUpdatedAt: Long
        get() = prefs.getLong(KEY_SETTINGS_UPDATED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SETTINGS_UPDATED_AT, value).apply()
        }

    var settingsDirty: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_DIRTY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SETTINGS_DIRTY, value).apply()
        }

    var lastSyncedSettingsHash: String?
        get() = prefs.getString(KEY_LAST_SYNCED_SETTINGS_HASH, null)
            ?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_LAST_SYNCED_SETTINGS_HASH)
                } else {
                    putString(KEY_LAST_SYNCED_SETTINGS_HASH, value)
                }
            }.apply()
        }

    val isConnected: Boolean
        get() = email != null && encryptedPassword != null

    fun setPassword(password: String) {
        prefs.edit()
            .putString(KEY_ENCRYPTED_PASSWORD, PCloudCredentialCipher.encrypt(password))
            .apply()
    }

    fun getPassword(): String? {
        val encrypted = encryptedPassword ?: return null
        return PCloudCredentialCipher.decrypt(encrypted)
    }

    fun clearConnection() {
        prefs.edit()
            .remove(KEY_HOSTNAME)
            .remove(KEY_EMAIL)
            .remove(KEY_ENCRYPTED_PASSWORD)
            .remove(KEY_LAST_SYNCED_HASH)
            .remove(KEY_LAST_SYNC_TIME)
            .remove(KEY_LAST_SYNC_STATUS)
            .remove(KEY_LAST_SYNCED_SETTINGS_HASH)
            .apply()
    }

    private val encryptedPassword: String?
        get() = prefs.getString(KEY_ENCRYPTED_PASSWORD, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val KEY_HOSTNAME = "pcloud_webdav_hostname"
        private const val KEY_EMAIL = "pcloud_webdav_email"
        private const val KEY_ENCRYPTED_PASSWORD = "pcloud_webdav_password"
        private const val KEY_LAST_SYNCED_HASH = "pcloud_last_synced_hash"
        private const val KEY_LAST_SYNC_TIME = "pcloud_last_sync_time"
        private const val KEY_LAST_SYNC_STATUS = "pcloud_last_sync_status"
        private const val KEY_SETTINGS_UPDATED_AT = "pcloud_settings_updated_at"
        private const val KEY_SETTINGS_DIRTY = "pcloud_settings_dirty"
        private const val KEY_LAST_SYNCED_SETTINGS_HASH = "pcloud_last_synced_settings_hash"
    }
}
