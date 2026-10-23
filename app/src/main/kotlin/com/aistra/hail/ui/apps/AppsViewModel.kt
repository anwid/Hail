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

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val apps = MutableLiveData<List<ApplicationInfo>>()
    val isRefreshing = MutableLiveData(false)
    val query = MutableLiveData("")
    val displayApps = MutableLiveData<List<ApplicationInfo>>()

    init {
        updateAppList()
    }

    private var refreshJob: Job? = null
    private var refreshStateJob: Job? = null

    /**
     * Delaying changes to the refreshing state prevents the progress bar from flickering.
     * */
    private fun postRefreshState(state: Boolean, delayTime: Long = 200L) {
        if (!state) {
            refreshStateJob?.cancel()
            isRefreshing.postValue(false)
        } else if (refreshStateJob == null || refreshStateJob!!.isCompleted) {
            refreshStateJob = viewModelScope.launch {
                delay(delayTime)
                isRefreshing.postValue(true)
            }
        }
    }

    fun postQuery(text: String, delayTime: Long = 300L) {
        refreshJob?.cancel()
        if (delayTime == 0L)
            query.postValue(text)
        else {
            refreshJob = viewModelScope.launch {
                delay(delayTime)
                query.postValue(text)
            }
        }
    }

    /**
     * This method is only used to refresh all the applications that the user has installed
     * and has no filtering or sorting effect.
     * */
    fun updateAppList() {
        viewModelScope.launch {
            postRefreshState(true)
            AppLabelCache.clear()
            val installed = withContext(Dispatchers.IO) { HPackages.getApplicationsAcrossUsers() }
            apps.postValue(installed)
        }
    }

    /**
     * The list that the user actually sees.
     *
     * This method is different from `updateAppList()` in that it filters and rearranges the data
     * from `apps` and places it in `displayApps`.
     * */
    fun updateDisplayAppList() {
        apps.value?.let {
            viewModelScope.launch {
                postRefreshState(true)
                displayApps.postValue(filterList(it, query.value))
                postRefreshState(false)
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
