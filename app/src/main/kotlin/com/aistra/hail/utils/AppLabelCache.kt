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

    fun clear() {
        cache.clear()
    }
}
