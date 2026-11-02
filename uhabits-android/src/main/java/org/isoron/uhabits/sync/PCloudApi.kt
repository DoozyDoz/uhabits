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

import android.util.Log
import me.tatarka.inject.annotations.Inject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object PCloudHosts {
    const val US = "webdav.pcloud.com"
    const val EU = "ewebdav.pcloud.com"
}

class PCloudException(
    val resultCode: Int,
    message: String
) : IOException(message)

data class PCloudStat(val hash: String?)

/**
 * Minimal pCloud WebDAV client used by [PCloudSyncManager].
 *
 * pCloud retired password/digest login for its JSON API in 2026, but WebDAV
 * still authenticates with the account email and password over HTTPS. The
 * password is kept encrypted with the Android Keystore by [PCloudPreferences].
 */
@Inject
class PCloudApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val emptyBody = ByteArray(0).toRequestBody(null)
    private val fileMediaType = "application/octet-stream".toMediaType()

    fun authenticate(hostname: String, email: String, password: String) {
        val request = requestBuilder(hostname, "/", email, password).head().build()
        client.newCall(request).execute().use { response ->
            checkResult(response.code, "pCloud WebDAV login")
        }
    }

    fun createFolderIfNotExists(hostname: String, email: String, password: String) {
        val request = requestBuilder(hostname, "$SYNC_FOLDER/", email, password)
            .method("MKCOL", emptyBody)
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code in 200..299 -> Unit
                response.code == HTTP_METHOD_NOT_ALLOWED -> Unit
                response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN ->
                    throw authenticationError()
                else -> throw IOException(
                    "Could not create pCloud folder (HTTP ${response.code})"
                )
            }
        }
    }

    fun stat(
        hostname: String,
        email: String,
        password: String,
        filename: String = SYNC_FILENAME
    ): PCloudStat? {
        val request = requestBuilder(
            hostname,
            "$SYNC_FOLDER/$filename",
            email,
            password
        ).head().build()
        client.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return null
            checkResult(response.code, "pCloud stat")
            val hash = parseEtag(response.header("ETag"))
                ?: response.header("Last-Modified")
            return PCloudStat(hash)
        }
    }

    fun downloadFile(
        hostname: String,
        email: String,
        password: String,
        destination: File,
        filename: String = SYNC_FILENAME
    ) {
        val request = requestBuilder(
            hostname,
            "$SYNC_FOLDER/$filename",
            email,
            password
        ).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) {
                throw IOException("pCloud backup does not exist")
            }
            checkResult(response.code, "pCloud download")
            val body = response.body ?: throw IOException("pCloud returned an empty download")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun uploadFile(
        hostname: String,
        email: String,
        password: String,
        file: File,
        filename: String = SYNC_FILENAME
    ): String? {
        val request = requestBuilder(
            hostname,
            "$SYNC_FOLDER/$filename",
            email,
            password
        ).put(file.asRequestBody(fileMediaType)).build()
        var etag: String? = null
        client.newCall(request).execute().use { response ->
            checkResult(response.code, "pCloud upload")
            etag = parseEtag(response.header("ETag"))
        }
        return etag ?: stat(hostname, email, password, filename)?.hash
    }

    private fun requestBuilder(
        hostname: String,
        path: String,
        email: String,
        password: String
    ): Request.Builder {
        return Request.Builder()
            .url("https://$hostname$path")
            .header("Authorization", Credentials.basic(email.trim(), password, Charsets.UTF_8))
            .header("User-Agent", "LoopHabitTracker")
    }

    private fun parseEtag(value: String?): String? {
        return value
            ?.removePrefix("W/")
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }

    private fun checkResult(code: Int, operation: String) {
        if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
            throw authenticationError()
        }
        if (code !in 200..299) {
            Log.w(TAG, "$operation failed: HTTP $code")
            throw IOException("$operation failed (HTTP $code)")
        }
    }

    private fun authenticationError(): PCloudException {
        Log.w(TAG, "pCloud WebDAV authentication failed")
        return PCloudException(HTTP_UNAUTHORIZED, "Invalid pCloud email or password")
    }

    companion object {
        const val SYNC_FOLDER = "/LoopHabits"
        const val SYNC_FILENAME = "uhabits.db"
        private const val TAG = "PCloudApi"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
    }
}
