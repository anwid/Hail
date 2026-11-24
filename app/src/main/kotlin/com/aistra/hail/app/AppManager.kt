package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManager {
    val lockScreen: Boolean
        get() = with(HailData.workingMode) {
            when {
                startsWith(HailData.OWNER) -> HPolicy.lockScreen
                startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.lockScreen
                startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
                else -> false
            }
        }

    val supportsOtherUsers: Boolean
        get() = HailData.workingMode.startsWith(HailData.SU)
            || HailData.workingMode.startsWith(HailData.SHIZUKU)
            || HailData.tags.any {
                val mode = HailData.getWorkingModeForTag(it.id)
                mode.startsWith(HailData.SU) || mode.startsWith(HailData.SHIZUKU)
            }

    fun isAppFrozen(
        packageName: String,
        userId: Int = HPackages.myUserId,
        mode: String = HailData.getWorkingModeForPackage(packageName, userId)
    ): Boolean = with(mode) {
        when {
            endsWith(HailData.STOP) -> HPackages.isAppStopped(packageName, userId)
            endsWith(HailData.DISABLE) -> HPackages.isAppDisabled(packageName, userId)
            endsWith(HailData.HIDE) -> HPackages.isAppHidden(packageName, userId)
            endsWith(HailData.SUSPEND) -> HPackages.isAppSuspended(packageName, userId)
            else -> HPackages.isAppDisabled(packageName, userId)
                    || HPackages.isAppHidden(packageName, userId)
                    || HPackages.isAppSuspended(packageName, userId)
        }
    }

    fun isAppFrozen(appInfo: AppInfo): Boolean {
        val mode = appInfo.frozenMode ?: HailData.getWorkingModeForApp(appInfo)
        val frozen = isAppFrozen(appInfo.packageName, appInfo.userId, mode)
        if (!frozen && appInfo.frozenMode != null) {
            appInfo.frozenMode = null
            HailData.saveApps()
        }
        return frozen
    }

    fun executeSetAppFrozen(
        packageName: String,
        frozen: Boolean,
        userId: Int = HPackages.myUserId,
        mode: String = HailData.getWorkingModeForPackage(packageName, userId)
    ): Boolean {
        if (userId < 0 || packageName == BuildConfig.APPLICATION_ID) return false
        val supportsOtherUsers = mode.startsWith(HailData.SU) || mode.startsWith(HailData.SHIZUKU)
        if (userId != HPackages.myUserId && !supportsOtherUsers) return false

        if (mode.startsWith(HailData.DHIZUKU)) {
            runCatching { HDhizuku.init() }
        }

        return when (mode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_SU_STOP -> !frozen || HShell.forceStopApp(packageName, userId)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen, userId)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen, userId)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen, userId)
            HailData.MODE_SHIZUKU_STOP -> !frozen || HShizuku.forceStopApp(packageName, userId)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen, userId)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen, userId)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen, userId)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_PRIVAPP_STOP -> !frozen || HPackages.forceStopApp(packageName)
            HailData.MODE_JAILBREAK_DISABLE, HailData.MODE_PRIVAPP_DISABLE ->
                HPackages.setAppDisabled(packageName, frozen)

            else -> false
        }
    }

    fun setAppFrozen(appInfo: AppInfo, frozen: Boolean): Boolean {
        val targetMode = HailData.getWorkingModeForApp(appInfo)
        if (frozen) {
            val oldMode = appInfo.frozenMode
            if (oldMode != null && oldMode != targetMode) {
                // Migrate: unfreeze old mode first
                executeSetAppFrozen(appInfo.packageName, false, appInfo.userId, oldMode)
            }
            val ok = executeSetAppFrozen(appInfo.packageName, true, appInfo.userId, targetMode)
            if (ok) {
                appInfo.frozenMode = targetMode
            } else if (oldMode != null && oldMode != targetMode) {
                appInfo.frozenMode = null
            }
            return ok
        } else {
            val unfreezeMode = appInfo.frozenMode ?: targetMode
            val ok = executeSetAppFrozen(appInfo.packageName, false, appInfo.userId, unfreezeMode)
            if (ok) {
                appInfo.frozenMode = null
            }
            return ok
        }
    }

    fun setAppFrozen(
        packageName: String,
        frozen: Boolean,
        userId: Int = HPackages.myUserId
    ): Boolean {
        val appInfo = HailData.checkedList.firstOrNull { it.packageName == packageName && it.userId == userId }
        return if (appInfo != null) {
            val res = setAppFrozen(appInfo, frozen)
            HailData.saveApps()
            res
        } else {
            val mode = HailData.getWorkingModeForPackage(packageName, userId)
            executeSetAppFrozen(packageName, frozen, userId, mode)
        }
    }

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? {
        val excludeMe = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
        var i = 0
        var denied = false
        var name = String()
        var hasChanges = false

        excludeMe.forEach { info ->
            val targetMode = HailData.getWorkingModeForApp(info)
            val isCurrentlyFrozen = isAppFrozen(info)

            if (frozen) {
                // If already frozen and in the target mode, nothing to do
                if (isCurrentlyFrozen && info.frozenMode == targetMode) {
                    return@forEach
                }
                // Unfrozen, or frozen in an old mode that needs migration
                if (setAppFrozen(info, true)) {
                    i++
                    name = info.name.toString()
                    hasChanges = true
                } else {
                    if (info.applicationInfo != null || info.userId != HPackages.myUserId) denied = true
                }
            } else {
                if (!isCurrentlyFrozen) {
                    return@forEach
                }
                if (setAppFrozen(info, false)) {
                    i++
                    name = info.name.toString()
                    hasChanges = true
                } else {
                    if (info.applicationInfo != null || info.userId != HPackages.myUserId) denied = true
                }
            }
        }

        if (hasChanges) {
            HailData.saveApps()
        }
        return if (denied && i == 0) null else if (i == 1) name else i.toString()
    }

    fun uninstallApp(
        packageName: String,
        userId: Int = HPackages.myUserId,
        mode: String = HailData.getWorkingModeForPackage(packageName, userId)
    ): Boolean = with(mode) {
        val supportsOtherUsers = startsWith(HailData.SU) || startsWith(HailData.SHIZUKU)
        if (userId < 0 || (userId != HPackages.myUserId && !supportsOtherUsers)) return false
        if (
            (startsWith(HailData.OWNER) && HPolicy.uninstallApp(packageName))
            || (startsWith(HailData.DHIZUKU) && HDhizuku.uninstallApp(packageName))
            || ((startsWith(HailData.SU) || startsWith(HailData.JAILBREAK)) && HShell.uninstallApp(packageName, userId))
            || (startsWith(HailData.SHIZUKU) && HShizuku.uninstallApp(packageName, userId))
        )
            return true
        if (userId == HPackages.myUserId) HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        false
    }

    fun reinstallApp(
        packageName: String,
        userId: Int = HPackages.myUserId,
        mode: String = HailData.getWorkingModeForPackage(packageName, userId)
    ): Boolean = with(mode) {
        val supportsOtherUsers = startsWith(HailData.SU) || startsWith(HailData.SHIZUKU)
        if (userId < 0 || (userId != HPackages.myUserId && !supportsOtherUsers)) return false
        when {
            startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.reinstallApp(packageName, userId)
            startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName, userId)
            else -> false
        }
    }

    suspend fun execute(command: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        with(HailData.workingMode) {
            when {
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.execute(command, true)
                startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
                else -> 0 to null
            }
        }
    }
}
