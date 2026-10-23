package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.aistra.hail.BuildConfig
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object HShizuku {
    val isRoot get() = Shizuku.getUid() == 0
    private val callerPackage get() = if (isRoot) BuildConfig.APPLICATION_ID else "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any = Class.forName("$className\$Stub").run {
        if (HTarget.P) HiddenApiBypass.invoke(this, null, "asInterface", ShizukuBinderWrapper(original))
        else getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(original))
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    private fun packageQuery(method: String, flags: Int, userId: Int, packageName: String? = null): Any? {
        require(userId >= 0)
        val pm = asInterface("android.content.pm.IPackageManager", "package")
        val flagsType = if (HTarget.T) Long::class.javaPrimitiveType!! else Int::class.javaPrimitiveType!!
        val flagValue: Any = if (HTarget.T) flags.toLong() else flags
        if (HTarget.P) {
            val arguments = if (packageName == null) arrayOf(flagValue, userId)
            else arrayOf(packageName, flagValue, userId)
            return HiddenApiBypass.invoke(pm::class.java, pm, method, *arguments)
        }
        return if (packageName == null) pm::class.java.getMethod(
            method, flagsType, Int::class.javaPrimitiveType!!
        ).invoke(pm, flagValue, userId)
        else pm::class.java.getMethod(
            method, String::class.java, flagsType, Int::class.javaPrimitiveType!!
        ).invoke(pm, packageName, flagValue, userId)
    }

    fun getApplicationInfo(packageName: String, flags: Int, userId: Int): ApplicationInfo? = runCatching {
        (packageQuery("getApplicationInfo", flags, userId, packageName) as? ApplicationInfo)
            ?.takeIf { HPackages.userIdOf(it) == userId }
    }.getOrElse { HLog.e(it); null }

    fun getPackageInfo(packageName: String, flags: Int, userId: Int): PackageInfo? = runCatching {
        (packageQuery("getPackageInfo", flags, userId, packageName) as? PackageInfo)
            ?.takeIf { it.applicationInfo?.let(HPackages::userIdOf) == userId }
    }.getOrElse { HLog.e(it); null }

    fun getInstalledApplications(userId: Int): List<ApplicationInfo> = runCatching {
        val slice = packageQuery("getInstalledApplications", 8192 /* MATCH_UNINSTALLED_PACKAGES */, userId)
            ?: return emptyList()
        val list = if (HTarget.P) HiddenApiBypass.invoke(slice::class.java, slice, "getList")
        else slice::class.java.getMethod("getList").invoke(slice)
        (list as? List<*>)?.filterIsInstance<ApplicationInfo>()
            ?.filter { HPackages.userIdOf(it) == userId } ?: emptyList()
    }.getOrElse { HLog.e(it); emptyList() }

    val lockScreen
        get() = runCatching {
            val input = asInterface("android.hardware.input.IInputManager", Context.INPUT_SERVICE)
            val inject = input::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.java
            )
            val now = SystemClock.uptimeMillis()
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), 0
            )
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), 0
            )
            true
        }.getOrElse {
            HLog.e(it)
            false
        }

    fun forceStopApp(packageName: String, userId: Int = HPackages.myUserId): Boolean = runCatching {
        require(userId >= 0)
        asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE).let {
            if (HTarget.P) HiddenApiBypass.invoke(
                it::class.java, it, "forceStopPackage", packageName, userId
            ) else it::class.java.getMethod(
                "forceStopPackage", String::class.java, Int::class.java
            ).invoke(
                it, packageName, userId
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean, userId: Int = HPackages.myUserId): Boolean {
        if (userId < 0) return false
        HPackages.getApplicationInfoOrNull(packageName, userId = userId) ?: return false
        if (disabled) forceStopApp(packageName, userId)
        val changed = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            if (HTarget.P) {
                HiddenApiBypass.invoke(
                    pm::class.java,
                    pm,
                    "setApplicationEnabledSetting",
                    packageName,
                    newState,
                    0,
                    userId,
                    callerPackage
                )
            } else {
                pm::class.java.getMethod(
                    "setApplicationEnabledSetting",
                    String::class.java,
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                    String::class.java
                ).invoke(pm, packageName, newState, 0, userId, callerPackage)
            }
            true
        }.onFailure {
            HLog.e(it)
        }.getOrDefault(false)
        return changed && HPackages.getApplicationInfoOrNull(packageName, userId = userId)?.enabled == !disabled
    }

    fun setAppHidden(packageName: String, hidden: Boolean, userId: Int = HPackages.myUserId): Boolean {
        if (userId < 0) return false
        HPackages.getApplicationInfoOrNull(packageName, userId = userId) ?: return false
        if (hidden) forceStopApp(packageName, userId)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            if (HTarget.P) {
                HiddenApiBypass.invoke(
                    pm::class.java, pm, "setApplicationHiddenSettingAsUser", packageName, hidden, userId
                ) as Boolean
            } else {
                pm::class.java.getMethod(
                    "setApplicationHiddenSettingAsUser", String::class.java, Boolean::class.java, Int::class.java
                ).invoke(pm, packageName, hidden, userId) as Boolean
            }
        }.getOrElse {
            HLog.e(it)
            false
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean, userId: Int = HPackages.myUserId): Boolean {
        if (userId < 0) return false
        HPackages.getApplicationInfoOrNull(packageName, userId = userId) ?: return false
        if (HTarget.P) setAppRestricted(packageName, suspended, userId)
        if (suspended) forceStopApp(packageName, userId)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            (when {
                HTarget.U -> runCatching {
                    HiddenApiBypass.invoke(
                        pm::class.java,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(packageName),
                        suspended,
                        null,
                        null,
                        if (suspended) suspendDialogInfo else null,
                        0,
                        callerPackage,
                        HPackages.myUserId /*suspendingUserId*/,
                        userId /*targetUserId*/
                    )
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended, userId)
                    else throw it
                }

                HTarget.Q -> runCatching {
                    setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended, userId)
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceP(pm, packageName, suspended, userId)
                    else throw it
                }

                HTarget.P -> setPackagesSuspendedAsUserSinceP(pm, packageName, suspended, userId)

                HTarget.N -> pm::class.java.getMethod(
                    "setPackagesSuspendedAsUser", Array<String>::class.java, Boolean::class.java, Int::class.java
                ).invoke(pm, arrayOf(packageName), suspended, userId)

                else -> return false
            } as Array<*>).isEmpty()
        }.getOrElse {
            HLog.e(it)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPackagesSuspendedAsUserSinceQ(pm: Any, packageName: String, suspended: Boolean, userId: Int): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            if (suspended) suspendDialogInfo else null,
            callerPackage,
            userId
        )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setPackagesSuspendedAsUserSinceP(pm: Any, packageName: String, suspended: Boolean, userId: Int): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            null /*dialogMessage*/,
            callerPackage,
            userId
        )

    private val suspendDialogInfo: Any
        @RequiresApi(Build.VERSION_CODES.Q) @SuppressLint("PrivateApi") get() = HiddenApiBypass.newInstance(
            Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        ).let {
            HiddenApiBypass.invoke(it::class.java, it, "setNeutralButtonAction", 1 /*BUTTON_ACTION_UNSUSPEND*/)
            HiddenApiBypass.invoke(it::class.java, it, "build")
        }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean, userId: Int = HPackages.myUserId): Boolean = runCatching {
        require(userId >= 0)
        val appops = asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
        HiddenApiBypass.invoke(
            appops::class.java,
            appops,
            "setMode",
            HiddenApiBypass.invoke(AppOpsManager::class.java, null, "strOpToOp", "android:run_any_in_background"),
            HPackages.packageUid(packageName, userId),
            packageName,
            if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
        )
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun uninstallApp(packageName: String, userId: Int = HPackages.myUserId): Boolean =
        userId >= 0 && execute("pm uninstall --user $userId ${HUserApps.quote(packageName)}").first == 0

    fun reinstallApp(packageName: String, userId: Int = HPackages.myUserId): Boolean =
        userId >= 0 && execute("pm install-existing --user $userId ${HUserApps.quote(packageName)}").first == 0

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                    it.write(("exec 2>&1\n" + command).toByteArray())
                }
                val output = inputStream.text
                waitFor() to output.also { destroy() }
            }
    }.getOrElse { 1 to it.stackTraceToString() }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
}
