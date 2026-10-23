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
        get() = HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(HailData.SHIZUKU)

    fun isAppFrozen(packageName: String, userId: Int = HPackages.myUserId): Boolean = with(HailData.workingMode) {
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

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? {
        val excludeMe = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
        var i = 0
        var denied = false
        var name = String()
        when (HailData.workingMode) {
            // call setListFrozen for some batch-style working mode here
            // fallback to setAppFrozen otherwise
            else -> {
                excludeMe.forEach {
                    when {
                        setAppFrozen(it.packageName, frozen, it.userId) -> {
                            i++
                            name = it.name.toString()
                        }

                        it.applicationInfo != null || it.userId != HPackages.myUserId -> denied = true
                    }
                }
            }
        }
        return if (denied && i == 0) null else if (i == 1) name else i.toString()
    }

    fun setAppFrozen(packageName: String, frozen: Boolean, userId: Int = HPackages.myUserId): Boolean {
        if (userId < 0 || packageName == BuildConfig.APPLICATION_ID) return false
        if (userId != HPackages.myUserId && !supportsOtherUsers) return false
        return when (HailData.workingMode) {
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

    fun uninstallApp(packageName: String, userId: Int = HPackages.myUserId): Boolean = with(HailData.workingMode) {
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

    fun reinstallApp(packageName: String, userId: Int = HPackages.myUserId): Boolean = with(HailData.workingMode) {
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
