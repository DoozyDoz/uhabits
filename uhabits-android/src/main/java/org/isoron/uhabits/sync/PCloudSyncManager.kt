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
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.io.DatabaseOpener
import org.isoron.platform.io.run
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.sync.PCloudDatabaseMerger
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.inject.AppContext
import org.isoron.uhabits.widgets.WidgetUpdater
import java.io.File
import java.io.IOException

/**
 * Coordinates pCloud snapshot synchronization. All network and database work
 * happens on [Dispatchers.IO]; UI-visible state is refreshed on the main
 * thread. A [Mutex] guarantees that two syncs never run concurrently.
 */
@AppScope
@Inject
class PCloudSyncManager(
    @AppContext private val context: Context,
    private val api: PCloudApi,
    private val prefs: PCloudPreferences,
    private val database: AndroidDatabase,
    private val dbFile: File,
    private val databaseOpener: DatabaseOpener,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    private val widgetUpdater: WidgetUpdater
) : CommandRunner.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var syncJob: Job? = null
    private var debounceJob: Job? = null
    private var pendingSync = false
    private var listening = false
    private var applyingRemoteSettings = false

    private val settingsListener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences,
            key: String?
        ) {
            if (applyingRemoteSettings) return
            if (key != null && PCloudSettingsSync.isSyncedKey(key)) {
                prefs.settingsUpdatedAt = System.currentTimeMillis()
                prefs.settingsDirty = true
                scheduleDebouncedSync()
            }
        }
    }

    @Volatile
    var isSyncing: Boolean = false
        private set

    val isConnected: Boolean
        get() = prefs.isConnected

    val connectedEmail: String?
        get() = prefs.email

    val lastSyncTime: Long
        get() = prefs.lastSyncTime

    val lastSyncStatus: String?
        get() = prefs.lastSyncStatus

    fun startListening() {
        if (listening) return
        listening = true
        commandRunner.addListener(this)
        sharedPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        commandRunner.removeListener(this)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        debounceJob?.cancel()
    }

    override fun onCommandFinished(command: Command) {
        if (command is PCloudSyncCommand) return
        scheduleDebouncedSync()
    }

    private fun scheduleDebouncedSync() {
        if (!prefs.isConnected) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            syncInBackground()
        }
    }

    fun syncIfStale() {
        if (!prefs.isConnected || isSyncing) return
        val lastSync = prefs.lastSyncTime
        if (lastSync <= 0L || System.currentTimeMillis() - lastSync > STALE_AFTER_MILLIS) {
            syncInBackground()
        }
    }

    fun syncInBackground() {
        if (!prefs.isConnected) return
        if (syncJob?.isActive == true) {
            pendingSync = true
            return
        }
        isSyncing = true
        syncJob = scope.launch {
            try {
                mutex.withLock { performSyncWithStatus() }
            } finally {
                isSyncing = false
                if (prefs.isConnected) {
                    prefs.lastSyncTime = System.currentTimeMillis()
                }
                if (pendingSync) {
                    pendingSync = false
                    syncInBackground()
                }
            }
        }
    }

    suspend fun connect(
        hostname: String,
        email: String,
        password: String
    ): String? {
        return try {
            val resolvedHostname = authenticateWithRegionFallback(
                hostname,
                email,
                password
            )
            syncJob?.cancel()
            pendingSync = false
            prefs.hostname = resolvedHostname
            prefs.email = email.trim()
            prefs.setPassword(password)
            prefs.lastSyncedHash = null
            prefs.lastSyncedSettingsHash = null
            prefs.lastSyncTime = 0L
            prefs.lastSyncStatus = null
            syncInBackground()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage(e)
        }
    }

    private suspend fun authenticateWithRegionFallback(
        preferredHostname: String,
        email: String,
        password: String
    ): String = withContext(Dispatchers.IO) {
        val hosts = if (preferredHostname == PCloudHosts.EU) {
            listOf(PCloudHosts.EU, PCloudHosts.US)
        } else {
            listOf(PCloudHosts.US, PCloudHosts.EU)
        }
        var lastError: Exception? = null
        for (hostname in hosts) {
            try {
                api.authenticate(hostname, email, password)
                if (hostname != preferredHostname) {
                    Log.i(TAG, "Authenticated against alternate pCloud region: $hostname")
                }
                return@withContext hostname
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "pCloud WebDAV authentication failed on $hostname: ${e.message}")
            }
        }
        throw lastError ?: IOException("pCloud authentication failed")
    }

    suspend fun disconnect() {
        syncJob?.cancel()
        debounceJob?.cancel()
        prefs.clearConnection()
    }

    private suspend fun performSyncWithStatus() {
        try {
            performSync()
            prefs.lastSyncStatus = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.lastSyncStatus = errorMessage(e)
        }
    }

    private suspend fun performSync() = withContext(Dispatchers.IO) {
        val email = prefs.email ?: throw IOException("Not connected to pCloud")
        val password = prefs.getPassword() ?: run {
            prefs.clearConnection()
            throw IOException("pCloud password is no longer available; please reconnect")
        }
        val hostname = prefs.hostname

        api.createFolderIfNotExists(hostname, email, password)

        val stat = api.stat(hostname, email, password)
        val remoteHash = stat?.hash
        val shouldMerge = stat != null &&
            (remoteHash == null || remoteHash != prefs.lastSyncedHash)

        if (shouldMerge) {
            val remoteFile = File(context.cacheDir, REMOTE_DB_NAME)
            try {
                api.downloadFile(hostname, email, password, remoteFile)
                if (!PCloudDatabaseMerger.verifyIntegrity(databaseOpener, remoteFile.absolutePath)) {
                    throw IOException("Downloaded pCloud database failed integrity check")
                }
                PCloudDatabaseMerger.merge(database, remoteFile.absolutePath)
                withContext(Dispatchers.Main) {
                    reloadLocalDatabase()
                }
            } finally {
                remoteFile.delete()
            }
        }

        val uploadFile = File(context.cacheDir, UPLOAD_DB_NAME)
        try {
            checkpointAndCreateSnapshot(uploadFile)
            val uploadedHash = api.uploadFile(hostname, email, password, uploadFile)
            prefs.lastSyncedHash = uploadedHash
        } finally {
            uploadFile.delete()
        }

        syncSettings(hostname, email, password)
    }

    private suspend fun syncSettings(hostname: String, email: String, password: String) {
        var localUpdatedAt = prefs.settingsUpdatedAt
        var localDirty = prefs.settingsDirty
        val remoteStat = api.stat(
            hostname,
            email,
            password,
            PCloudSettingsSync.FILENAME
        )
        val remoteHash = remoteStat?.hash
        if (remoteStat != null && remoteHash != prefs.lastSyncedSettingsHash) {
            val remoteFile = File(context.cacheDir, REMOTE_SETTINGS_NAME)
            try {
                api.downloadFile(
                    hostname,
                    email,
                    password,
                    remoteFile,
                    PCloudSettingsSync.FILENAME
                )
                val snapshot = PCloudSettingsSync.parse(
                    remoteFile.readText(Charsets.UTF_8)
                )
                if (snapshot != null &&
                    (!localDirty || snapshot.updatedAt > localUpdatedAt)
                ) {
                    withContext(Dispatchers.Main) {
                        applyRemoteSettings(snapshot)
                    }
                    localUpdatedAt = snapshot.updatedAt
                    localDirty = false
                }
            } finally {
                remoteFile.delete()
            }
        }

        if (localUpdatedAt <= 0L) {
            localUpdatedAt = System.currentTimeMillis()
            prefs.settingsUpdatedAt = localUpdatedAt
        }

        val uploadFile = File(context.cacheDir, UPLOAD_SETTINGS_NAME)
        try {
            uploadFile.writeText(
                PCloudSettingsSync.export(sharedPrefs, localUpdatedAt),
                Charsets.UTF_8
            )
            val uploadedHash = api.uploadFile(
                hostname,
                email,
                password,
                uploadFile,
                PCloudSettingsSync.FILENAME
            )
            prefs.lastSyncedSettingsHash = uploadedHash
            prefs.settingsDirty = false
        } finally {
            uploadFile.delete()
        }
    }

    private fun applyRemoteSettings(snapshot: PCloudSettingsSync.Snapshot) {
        applyingRemoteSettings = true
        try {
            PCloudSettingsSync.apply(snapshot, sharedPrefs)
            prefs.settingsUpdatedAt = snapshot.updatedAt
            prefs.settingsDirty = false
            commandRunner.notifyListeners(PCloudSyncCommand)
            widgetUpdater.updateWidgets()
        } finally {
            applyingRemoteSettings = false
        }
    }

    private fun checkpointAndCreateSnapshot(destination: File) {
        database.run("PRAGMA wal_checkpoint(TRUNCATE)")
        if (destination.exists()) destination.delete()
        dbFile.copyTo(destination, overwrite = true)
    }

    private fun reloadLocalDatabase() {
        (habitList as? SQLiteHabitList)?.reload()
        habitList.size()
        habitList.observable.notifyListeners()
        commandRunner.notifyListeners(PCloudSyncCommand)
        widgetUpdater.updateWidgets()
    }

    private fun errorMessage(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        private const val TAG = "PCloudSyncManager"
        private const val STALE_AFTER_MILLIS = 60_000L
        private const val DEBOUNCE_MILLIS = 10_000L
        private const val REMOTE_DB_NAME = "pcloud_remote.db"
        private const val UPLOAD_DB_NAME = "pcloud_upload.db"
        private const val REMOTE_SETTINGS_NAME = "pcloud_remote_settings.json"
        private const val UPLOAD_SETTINGS_NAME = "pcloud_upload_settings.json"
    }
}

/**
 * Marker command used to notify listeners that a remote merge changed the
 * local database, without starting another debounced sync.
 */
object PCloudSyncCommand : Command {
    override fun run() = Unit
}
