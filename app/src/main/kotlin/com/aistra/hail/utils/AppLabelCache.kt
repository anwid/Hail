package com.aistra.hail.utils

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

object AppLabelCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun getOrLoad(pm: PackageManager, info: ApplicationInfo): String {
        val key = info.packageName + "_" + (info.uid / 100000)
        return cache.getOrPut(key) {
            info.loadLabel(pm).toString()
        }
    }

    fun get(info: ApplicationInfo): String? {
        val key = info.packageName + "_" + (info.uid / 100000)
        return cache[key]
    }

    fun put(packageName: String, userId: Int, label: String) {
        cache[packageName + "_" + userId] = label
    }

    fun get(packageName: String, userId: Int): String? {
        return cache[packageName + "_" + userId]
    }

    fun clear() {
        cache.clear()
    }
}
