package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val apps = MutableLiveData<List<ApplicationInfo>>()
    val isScanning = MutableLiveData(false)
    val query = MutableLiveData("")
    val displayApps = MutableLiveData<List<ApplicationInfo>>()

    private val cacheFile = File(getApplication<HailApp>().filesDir, "v1/apps_cache.json")
    private var scanJob: Job? = null
    private var searchJob: Job? = null

    fun ensureLoaded() {
        if (apps.value.isNullOrEmpty()) {
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) { loadDiskCache() }
                if (!cached.isNullOrEmpty()) {
                    apps.value = cached
                    updateDisplayAppList()
                }
                updateAppList()
            }
        }
    }

    private fun loadDiskCache(): List<ApplicationInfo>? = runCatching {
        if (!cacheFile.exists()) return null
        val content = HFiles.read(cacheFile.absolutePath)
        if (content.isNullOrBlank()) return null
        val jsonArray = JSONArray(content)
        val list = ArrayList<ApplicationInfo>(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val pkg = obj.getString("pkg")
            val uid = obj.optInt("uid", 0)
            val flags = obj.optInt("flags", 0)
            val enabled = obj.optBoolean("enabled", true)
            val sourceDir = obj.optString("sourceDir", "").takeIf { it.isNotEmpty() }
            val label = obj.optString("label", "")
            val icon = obj.optInt("icon", 0)
            val info = ApplicationInfo().apply {
                this.packageName = pkg
                this.uid = uid
                this.flags = flags
                this.enabled = enabled
                this.sourceDir = sourceDir
                this.publicSourceDir = sourceDir
                this.icon = icon
                if (label.isNotEmpty()) {
                    this.nonLocalizedLabel = label
                    AppLabelCache.put(pkg, uid / 100000, label)
                }
            }
            list.add(info)
        }
        list
    }.getOrNull()

    private fun saveDiskCache(list: List<ApplicationInfo>) {
        runCatching {
            val pm = getApplication<HailApp>().packageManager
            val jsonArray = JSONArray()
            for (info in list) {
                val label = AppLabelCache.getOrLoad(pm, info)
                val obj = JSONObject().apply {
                    put("pkg", info.packageName)
                    put("uid", info.uid)
                    put("flags", info.flags)
                    put("enabled", info.enabled)
                    info.sourceDir?.let { put("sourceDir", it) }
                    put("icon", info.icon)
                    put("label", label)
                }
                jsonArray.put(obj)
            }
            HFiles.write(cacheFile.absolutePath, jsonArray.toString())
        }
    }

    fun postQuery(text: String, delayTime: Long = 300L) {
        searchJob?.cancel()
        if (delayTime == 0L) {
            query.postValue(text)
        } else {
            searchJob = viewModelScope.launch {
                delay(delayTime)
                query.postValue(text)
            }
        }
    }

    /**
     * Refreshes all installed applications across users and saves to disk cache.
     */
    fun updateAppList() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            isScanning.postValue(true)
            try {
                val installed = withContext(Dispatchers.IO) {
                    HPackages.getApplicationsAcrossUsers()
                }
                apps.postValue(installed)
                withContext(Dispatchers.IO) {
                    saveDiskCache(installed)
                }
            } finally {
                isScanning.postValue(false)
            }
        }
    }

    fun updateDisplayAppList() {
        apps.value?.let {
            viewModelScope.launch {
                displayApps.postValue(filterList(it, query.value))
            }
        }
    }

    private val ApplicationInfo.isSystemApp: Boolean
        get() = flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM
    private val ApplicationInfo.isAppFrozen get() = AppManager.isAppFrozen(packageName, HPackages.userIdOf(this))

    private suspend fun filterList(
        appList: List<ApplicationInfo>,
        query: String?
    ): List<ApplicationInfo> {
        val pm = getApplication<HailApp>().packageManager
        val trimmedQuery = query?.trim()
        val hasQuery = !trimmedQuery.isNullOrEmpty()

        val checkUserApps = HailData.filterUserApps
        val checkSystemApps = HailData.filterSystemApps
        val bypassTypeFilter = checkUserApps && checkSystemApps

        val checkFrozen = HailData.filterFrozenApps
        val checkUnfrozen = HailData.filterUnfrozenApps
        val bypassFrozenFilter = checkFrozen && checkUnfrozen

        return withContext(Dispatchers.Default) {
            return@withContext appList.filter {
                (bypassTypeFilter || (checkUserApps && !it.isSystemApp) || (checkSystemApps && it.isSystemApp))
                        && (bypassFrozenFilter || (checkFrozen && it.isAppFrozen) || (checkUnfrozen && !it.isAppFrozen))
                        && (!hasQuery || searchApp(it, trimmedQuery, pm))
            }.run {
                when (HailData.sortBy) {
                    HailData.SORT_INSTALL -> sortedBy {
                        HPackages.getUnhiddenPackageInfoOrNull(it.packageName, userId = HPackages.userIdOf(it))
                            ?.firstInstallTime ?: 0
                    }

                    HailData.SORT_UPDATE -> sortedByDescending {
                        HPackages.getUnhiddenPackageInfoOrNull(it.packageName, userId = HPackages.userIdOf(it))?.lastUpdateTime ?: 0
                    }

                    else -> sortedWith(NameComparator)
                }
            }
        }
    }

    private fun searchApp(info: ApplicationInfo, query: String, pm: android.content.pm.PackageManager): Boolean {
        val label = AppLabelCache.getOrLoad(pm, info)
        val pkg = info.packageName
        return (HailData.nineKeySearch && NineKeySearch.search(query, pkg, label))
                || FuzzySearch.search(pkg, query)
                || FuzzySearch.search(label, query)
                || PinyinSearch.searchPinyinAll(label, query)
    }
}
