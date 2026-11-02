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
package org.isoron.uhabits.activities.settings

import android.app.backup.BackupManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.RESULT_BUG_REPORT
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_CSV
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_DB
import org.isoron.uhabits.activities.habits.list.RESULT_IMPORT_DATA
import org.isoron.uhabits.activities.habits.list.RESULT_REPAIR_DB
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.notifications.AndroidNotificationTray.Companion.createAndroidNotificationChannel
import org.isoron.uhabits.notifications.RingtoneManager
import org.isoron.uhabits.sync.PCloudHosts
import org.isoron.uhabits.sync.PCloudSyncManager
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.startActivitySafely
import org.isoron.uhabits.widgets.WidgetUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    private var sharedPrefs: SharedPreferences? = null
    private var ringtoneManager: RingtoneManager? = null
    private lateinit var prefs: Preferences
    private var widgetUpdater: WidgetUpdater? = null
    private lateinit var pCloudSyncManager: PCloudSyncManager

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RINGTONE_REQUEST_CODE -> {
                ringtoneManager!!.update(data)
                updateRingtoneDescription()
                return
            }
            PUBLIC_BACKUP_REQUEST_CODE -> {
                val uri = data?.data ?: return
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                sharedPrefs?.edit()?.putString("publicBackupFolder", uri.toString())?.apply()
                updatePublicBackupFolderSummary()
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        val appContext = requireContext().applicationContext
        if (appContext is HabitsApplication) {
            prefs = appContext.component.preferences
            widgetUpdater = appContext.component.widgetUpdater
            pCloudSyncManager = appContext.component.pCloudSyncManager
        }
        setResultOnPreferenceClick("importData", RESULT_IMPORT_DATA)
        setResultOnPreferenceClick("exportCSV", RESULT_EXPORT_CSV)
        setResultOnPreferenceClick("exportDB", RESULT_EXPORT_DB)
        setResultOnPreferenceClick("repairDB", RESULT_REPAIR_DB)
        setResultOnPreferenceClick("bugReport", RESULT_BUG_REPORT)
    }

    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        // NOP
    }

    override fun onPause() {
        sharedPrefs!!.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sr = StyledResources(context!!)
        view.setBackgroundColor(sr.getColor(R.attr.contrast0))
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater?,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): RecyclerView? {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState)
            .also { it.applyBottomInset() }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val key = preference.key ?: return false
        when (key) {
            "reminderSound" -> {
                showRingtonePicker()
                return true
            }
            "reminderCustomize" -> {
                createAndroidNotificationChannel(requireContext())
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationTray.REMINDERS_CHANNEL_ID)
                startActivity(intent)
                return true
            }
            "rateApp" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.playStoreURL)))
                activity?.startActivitySafely(intent)
                return true
            }
            "publicBackupFolder" -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                startActivityForResult(intent, PUBLIC_BACKUP_REQUEST_CODE)
                return true
            }
            "pcloudAccount" -> {
                if (pCloudSyncManager.isConnected) {
                    showPCloudDisconnectDialog()
                } else {
                    showPCloudConnectDialog()
                }
                return true
            }
            "pcloudSyncNow" -> {
                if (!pCloudSyncManager.isConnected) {
                    Toast.makeText(
                        requireContext(),
                        R.string.pcloud_not_connected,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pCloudSyncManager.syncInBackground()
                    Toast.makeText(
                        requireContext(),
                        R.string.pcloud_sync_started,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updatePCloudSummaries()
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onResume() {
        super.onResume()
        ringtoneManager = RingtoneManager(requireActivity())
        sharedPrefs = preferenceManager.sharedPreferences
        sharedPrefs!!.registerOnSharedPreferenceChangeListener(this)
        if (!prefs.isDeveloper) {
            val devCategory = findPreference("devCategory") as PreferenceCategory
            devCategory.isVisible = false
        }
        updateWeekdayPreference()
        updateDayStartHourPreference()
        updatePublicBackupFolderSummary()
        updatePCloudSummaries()

        findPreference("reminderSound").isVisible = false
    }

    private fun updateWeekdayPreference() {
        val weekdayPref = findPreference("pref_first_weekday") as ListPreference
        val currentFirstWeekday = prefs.firstWeekday.daysSinceSunday + 1
        val dayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)
        val dayValues = arrayOf("7", "1", "2", "3", "4", "5", "6")
        weekdayPref.entries = dayNames
        weekdayPref.entryValues = dayValues
        weekdayPref.setDefaultValue(currentFirstWeekday.toString())
        weekdayPref.summary = dayNames[currentFirstWeekday % 7]
    }

    private fun updateDayStartHourPreference() {
        val dayStartPref = findPreference("pref_day_start_hour") as ListPreference
        val value = prefs.dayStartHour.toString()
        dayStartPref.value = value
        val index = dayStartPref.findIndexOfValue(value).coerceAtLeast(0)
        val entry = dayStartPref.entries.getOrElse(index) { value }
        dayStartPref.summary = getString(R.string.pref_day_start_hour_summary, entry)
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        if (key == "pref_widget_opacity" && widgetUpdater != null) {
            Log.d("SettingsFragment", "updating widgets")
            widgetUpdater!!.updateWidgets()
        }
        BackupManager.dataChanged("org.isoron.uhabits")
        updateWeekdayPreference()
        updateDayStartHourPreference()
        updatePCloudSummaries()
    }

    private fun updatePCloudSummaries() {
        if (!::pCloudSyncManager.isInitialized) return
        val accountPref = findPreference("pcloudAccount") ?: return
        val syncPref = findPreference("pcloudSyncNow") ?: return
        val connected = pCloudSyncManager.isConnected
        accountPref.summary = if (connected) {
            getString(
                R.string.pcloud_account_connected,
                pCloudSyncManager.connectedEmail.orEmpty()
            )
        } else {
            getString(R.string.pcloud_account_disconnected)
        }
        syncPref.isEnabled = connected
        syncPref.summary = when {
            !connected -> getString(R.string.pcloud_sync_not_connected)
            pCloudSyncManager.isSyncing -> getString(R.string.pcloud_syncing)
            pCloudSyncManager.lastSyncTime <= 0L -> getString(R.string.pcloud_sync_never)
            else -> {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(pCloudSyncManager.lastSyncTime))
                val status = pCloudSyncManager.lastSyncStatus?.let {
                    getString(R.string.pcloud_sync_failed, it)
                } ?: getString(R.string.pcloud_sync_success)
                getString(R.string.pcloud_sync_last, time, status)
            }
        }
    }

    private fun showPCloudConnectDialog() {
        val ctx = requireContext()
        val padding = dp(24)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
        }
        val regionSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.pcloud_region_us),
                    getString(R.string.pcloud_region_eu)
                )
            )
        }
        val emailInput = EditText(ctx).apply {
            hint = getString(R.string.pcloud_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passwordInput = EditText(ctx).apply {
            hint = getString(R.string.pcloud_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val note = TextView(ctx).apply {
            text = getString(R.string.pcloud_webdav_note)
            setPadding(0, dp(8), 0, 0)
        }
        listOf(regionSpinner, emailInput, passwordInput, note)
            .forEach { layout.addView(it) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.pcloud_login_title)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pcloud_connect, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (email.isEmpty() || password.isEmpty()) {
                    Toast.makeText(
                        ctx,
                        R.string.pcloud_email_password_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val hostname = if (regionSpinner.selectedItemPosition == 0) {
                    PCloudHosts.US
                } else {
                    PCloudHosts.EU
                }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val error = pCloudSyncManager.connect(hostname, email, password)
                    if (!isAdded) return@launch
                    if (error == null) {
                        dialog.dismiss()
                        updatePCloudSummaries()
                        Toast.makeText(
                            requireContext(),
                            R.string.pcloud_connected,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        button.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.pcloud_auth_failed, error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showPCloudDisconnectDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pcloud_disconnect_title)
            .setMessage(R.string.pcloud_disconnect_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pcloud_disconnect) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    pCloudSyncManager.disconnect()
                    if (!isAdded) return@launch
                    updatePCloudSummaries()
                    Toast.makeText(
                        requireContext(),
                        R.string.pcloud_disconnected,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setResultOnPreferenceClick(key: String, result: Int) {
        val pref = findPreference(key)
        pref.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                requireActivity().setResult(result)
                requireActivity().finish()
                true
            }
    }

    private fun showRingtonePicker() {
        val existingRingtoneUri = ringtoneManager!!.getURI()
        val defaultRingtoneUri = Settings.System.DEFAULT_NOTIFICATION_URI
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
            android.media.RingtoneManager.TYPE_NOTIFICATION
        )
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            defaultRingtoneUri
        )
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            existingRingtoneUri
        )
        startActivityForResult(intent, RINGTONE_REQUEST_CODE)
    }

    private fun updateRingtoneDescription() {
        val ringtoneName = ringtoneManager!!.getName() ?: return
        val ringtonePreference = findPreference("reminderSound")
        ringtonePreference.summary = ringtoneName
    }

    private fun updatePublicBackupFolderSummary() {
        val pref = findPreference("publicBackupFolder")
        val uriString = sharedPrefs?.getString("publicBackupFolder", null)
        if (uriString == null) {
            pref.summary = getString(R.string.no_public_backup_folder_selected)
            return
        }
        val uri = Uri.parse(uriString)
        val path = fullPathFor(uri)
        pref.summary = path ?: uriString
    }

    private fun fullPathFor(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val (type, rel) = docId.split(":", limit = 2).let {
                    it[0] to it.getOrElse(1) { "" }
                }
                val base = if (type.equals("primary", true)) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/$type"
                }
                if (rel.isEmpty()) base else "$base/$rel"
            }
            "file" -> java.io.File(uri.path!!).absolutePath
            else -> null
        }
    }

    companion object {
        private const val RINGTONE_REQUEST_CODE = 1
        private const val PUBLIC_BACKUP_REQUEST_CODE = 2
    }
}
