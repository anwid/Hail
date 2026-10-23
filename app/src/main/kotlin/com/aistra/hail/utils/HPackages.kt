package com.aistra.hail.utils

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HPackages {
    val myUserId get() = android.os.Process.myUserHandle().hashCode()

    fun userIdOf(info: ApplicationInfo): Int = info.uid / 100000

    /** Call on a worker thread: root discovery uses package-manager shell commands. */
    fun getApplicationsAcrossUsers(): List<ApplicationInfo> {
        val ownApps = getInstalledApplications()
        if (!AppManager.supportsOtherUsers) return ownApps
        val users = HUserApps.getUserIds().filter { it != myUserId }
        if (users.isEmpty()) return ownApps
        val otherApps = if (HailData.workingMode.startsWith(HailData.SHIZUKU)) {
            val shizukuApps = users.flatMap { userId ->
                HShizuku.getInstalledApplications(userId).filter {
                    it.flags and ApplicationInfo.FLAG_INSTALLED != 0
                }
            }
            if (shizukuApps.isNotEmpty()) shizukuApps else HUserApps.getApplications(users)
        } else HUserApps.getApplications(users)
        return (ownApps + otherApps).distinctBy { userIdOf(it) to it.packageName }
    }

    fun packageUri(packageName: String) = "package:$packageName"

    @RequiresApi(Build.VERSION_CODES.N)
    fun packageUid(packageName: String, userId: Int = myUserId): Int {
        if (userId != myUserId) return getApplicationInfoOrNull(packageName, userId = userId)?.uid
            ?: throw PackageManager.NameNotFoundException(packageName)
        return if (HTarget.T) app.packageManager.getPackageUid(
        packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    ) else app.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
    }

    fun getInstalledApplications(flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192): List<ApplicationInfo> =
        if (HTarget.T) app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getInstalledApplications(flags)

    fun getUnhiddenPackageInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192,
        userId: Int = myUserId
    ): PackageInfo? = runCatching {
        if (userId < 0) return@runCatching null
        if (userId != myUserId) {
            if (!AppManager.supportsOtherUsers) return@runCatching null
            if (HailData.workingMode.startsWith(HailData.SHIZUKU))
                return@runCatching HShizuku.getPackageInfo(packageName, flags, userId)
            val info = getApplicationInfoOrNull(packageName, flags, userId) ?: return@runCatching null
            val metadata = getUnhiddenPackageInfoOrNull(packageName, flags)
                ?: info.sourceDir?.let { app.packageManager.getPackageArchiveInfo(it, flags) }
            return@runCatching metadata?.apply { applicationInfo = info }
        }
        if (HTarget.T) app.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    fun getApplicationInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192,
        userId: Int = myUserId
    ): ApplicationInfo? = runCatching {
        if (userId < 0) return@runCatching null
        if (userId != myUserId) return@runCatching when {
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.getApplicationInfo(packageName, flags, userId)
                ?: HUserApps.getApplicationInfo(packageName, userId)
            HailData.workingMode.startsWith(HailData.SU) -> HUserApps.getApplicationInfo(packageName, userId)
            else -> null
        }
        if (HTarget.T) app.packageManager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getApplicationInfo(packageName, flags)
    }.getOrNull()

    fun isAppDisabled(packageName: String, userId: Int = myUserId): Boolean =
        getApplicationInfoOrNull(packageName, userId = userId)?.enabled?.not() ?: false

    fun isAppHidden(packageName: String, userId: Int = myUserId): Boolean = getApplicationInfoOrNull(packageName, userId = userId)?.let {
        runCatching { (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 1 == 1 }.getOrDefault(false)
    } ?: false

    fun isAppStopped(packageName: String, userId: Int = myUserId): Boolean =
        getApplicationInfoOrNull(packageName, userId = userId)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppSuspended(packageName: String, userId: Int = myUserId): Boolean = getApplicationInfoOrNull(packageName, userId = userId)?.let {
        when {
//            This method will cause NameNotFoundException with uninstalled packages
//            HTarget.Q -> app.packageManager.isPackageSuspended(packageName)
            HTarget.N -> it.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
            else -> false
        }
    } ?: false

    fun isAppUninstalled(packageName: String, userId: Int = myUserId): Boolean =
        getApplicationInfoOrNull(packageName, userId = userId)?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 8 == 8
    } ?: false

    fun canUninstallNormally(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.sourceDir?.startsWith("/data") ?: false

    fun launchApp(packageName: String, userId: Int = myUserId): Boolean {
        if (userId < 0) return false
        if (userId == myUserId) return runCatching {
            val intent = app.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!AppManager.supportsOtherUsers) return false
        val result = HUserApps.execute(
            "am start --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${HUserApps.quote(packageName)}"
        )
        return result.first == 0 && !result.second.orEmpty().contains("Error", ignoreCase = true)
    }

    fun showAppDetails(packageName: String, userId: Int = myUserId): Boolean {
        if (userId < 0) return false
        if (userId == myUserId) return HUI.startActivity(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(packageName))
        if (!AppManager.supportsOtherUsers) return false
        val result = HUserApps.execute(
            "am start --user $userId -a android.settings.APPLICATION_DETAILS_SETTINGS -d ${HUserApps.quote(packageUri(packageName))}"
        )
        return result.first == 0 && !result.second.orEmpty().contains("Error", ignoreCase = true)
    }

    fun forceStopApp(packageName: String): Boolean = runCatching {
        app.getSystemService<ActivityManager>()!!.let {
            if (HTarget.P) HiddenApiBypass.invoke(it::class.java, it, "forceStopPackage", packageName)
            else it::class.java.getMethod("forceStopPackage", String::class.java).invoke(it, packageName)
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean {
        getApplicationInfoOrNull(packageName) ?: return false
        if (disabled) forceStopApp(packageName)
        runCatching {
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }.onFailure {
            HLog.e(it)
        }
        return isAppDisabled(packageName) == disabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        app.getSystemService<AppOpsManager>()!!.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }
}
