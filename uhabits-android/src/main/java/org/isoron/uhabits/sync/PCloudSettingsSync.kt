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

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the user-facing app preferences that are safe to share between
 * devices. Device-specific values such as the backup folder, ringtone URI,
 * widget configuration, and pCloud credentials are deliberately excluded.
 */
object PCloudSettingsSync {
    const val FILENAME = "settings.json"

    private val syncedKeys = setOf(
        "pref_short_toggle",
        "pref_day_start_hour",
        "pref_skip_enabled",
        "pref_unknown_enabled",
        "pref_checkmark_reverse_order",
        "pref_pure_black",
        "pref_disable_animation",
        "pref_widget_opacity",
        "pref_first_weekday",
        "pref_sticky_notifications",
        "pref_developer",
        "pref_theme",
        "pref_default_habit_palette_color",
        "pref_default_order",
        "pref_default_secondary_order",
        "pref_score_view_interval",
        "pref_bar_card_bool_spinner",
        "pref_bar_card_numerical_spinner"
    )

    data class Snapshot(
        val updatedAt: Long,
        val values: JSONObject
    )

    fun isSyncedKey(key: String): Boolean = key in syncedKeys

    fun export(preferences: SharedPreferences, updatedAt: Long): String {
        val values = JSONObject()
        for (key in syncedKeys) {
            val value = preferences.all[key] ?: continue
            encodeValue(value)?.let { values.put(key, it) }
        }
        return JSONObject()
            .put("version", 1)
            .put("updated_at", updatedAt)
            .put("values", values)
            .toString()
    }

    fun parse(json: String): Snapshot? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("version", 0) != 1) return null
            val values = root.optJSONObject("values") ?: return null
            Snapshot(root.optLong("updated_at", 0L), values)
        } catch (e: Exception) {
            null
        }
    }

    fun apply(snapshot: Snapshot, preferences: SharedPreferences) {
        val editor = preferences.edit()
        val keys = snapshot.values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!isSyncedKey(key)) continue
            val encoded = snapshot.values.optJSONObject(key) ?: continue
            runCatching { applyValue(editor, key, encoded) }
        }
        editor.commit()
    }

    private fun encodeValue(value: Any): JSONObject? {
        return when (value) {
            is Boolean -> JSONObject().put("type", "boolean").put("value", value)
            is Int -> JSONObject().put("type", "int").put("value", value)
            is Long -> JSONObject().put("type", "long").put("value", value)
            is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
            is String -> JSONObject().put("type", "string").put("value", value)
            is Set<*> -> {
                val array = JSONArray()
                value.filterIsInstance<String>().forEach(array::put)
                JSONObject().put("type", "string_set").put("value", array)
            }
            else -> null
        }
    }

    private fun applyValue(
        editor: SharedPreferences.Editor,
        key: String,
        encoded: JSONObject
    ) {
        when (encoded.getString("type")) {
            "boolean" -> editor.putBoolean(key, encoded.getBoolean("value"))
            "int" -> editor.putInt(key, encoded.getInt("value"))
            "long" -> editor.putLong(key, encoded.getLong("value"))
            "float" -> editor.putFloat(key, encoded.getDouble("value").toFloat())
            "string" -> editor.putString(key, encoded.getString("value"))
            "string_set" -> {
                val array = encoded.getJSONArray("value")
                val values = mutableSetOf<String>()
                for (i in 0 until array.length()) values.add(array.getString(i))
                editor.putStringSet(key, values)
            }
        }
    }
}
