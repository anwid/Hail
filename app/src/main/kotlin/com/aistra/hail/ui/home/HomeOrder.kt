package com.aistra.hail.ui.home

import android.content.Context
import androidx.core.content.edit
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppInfo
import com.aistra.hail.utils.NameComparator
import org.json.JSONArray

/** Home positions belong to a tag and to a particular Android user, not just a package. */
internal object HomeOrder {
    private val preferences get() = app.getSharedPreferences("home_order", Context.MODE_PRIVATE)

    fun sort(tagId: Int, apps: List<AppInfo>): List<AppInfo> {
        val saved = runCatching {
            val json = JSONArray(preferences.getString(tagId.toString(), "[]"))
            (0 until json.length()).map { json.getString(it) }
        }.getOrDefault(emptyList())
        return orderHomeItems(apps, saved, AppInfo::key, NameComparator)
    }

    fun save(tagId: Int, apps: List<AppInfo>) {
        preferences.edit { putString(tagId.toString(), JSONArray(apps.map { it.key }).toString()) }
    }

    fun remove(tagId: Int) {
        preferences.edit { remove(tagId.toString()) }
    }
}
