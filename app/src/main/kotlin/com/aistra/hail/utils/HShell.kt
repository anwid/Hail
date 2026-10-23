package com.aistra.hail.utils

import android.os.Build
import androidx.annotation.RequiresApi

object HShell {
    fun execute(command: String, root: Boolean): Pair<Int, String?> = runCatching {
        ProcessBuilder(if (root) "su" else "sh").redirectErrorStream(true).start().run {
            outputStream.use { it.write(command.toByteArray()) }
            // Drain before waiting: package dumps can exceed the pipe buffer.
            val output = inputStream.bufferedReader().use { it.readText() }
            waitFor() to output.also { destroy() }
        }
    }.getOrElse { 1 to it.stackTraceToString() }

    private fun execSU(command: String) = execute(command, true)

    val checkSU get() = execSU("whoami").first == 0

    val lockScreen get() = execSU("input keyevent KEYCODE_POWER").first == 0

    private fun runForUser(command: String, packageName: String, userId: Int): Boolean {
        if (userId < 0) return false
        val success = execSU("$command --user $userId ${HUserApps.quote(packageName)}").first == 0
        HUserApps.invalidate(packageName, userId)
        return success
    }

    fun forceStopApp(packageName: String, userId: Int = HPackages.myUserId): Boolean =
        runForUser("am force-stop", packageName, userId)

    fun setAppDisabled(packageName: String, disabled: Boolean, userId: Int = HPackages.myUserId): Boolean =
        runForUser("pm ${if (disabled) "disable" else "enable"}", packageName, userId)

    fun setAppHidden(packageName: String, hidden: Boolean, userId: Int = HPackages.myUserId): Boolean =
        runForUser("pm ${if (hidden) "hide" else "unhide"}", packageName, userId)

    fun setAppSuspended(packageName: String, suspended: Boolean, userId: Int = HPackages.myUserId): Boolean =
        runForUser("pm ${if (suspended) "suspend" else "unsuspend"}", packageName, userId)

    fun uninstallApp(packageName: String, userId: Int = HPackages.myUserId): Boolean =
        runForUser("pm uninstall", packageName, userId)

    fun reinstallApp(packageName: String, userId: Int = HPackages.myUserId): Boolean =
        runForUser("pm install-existing", packageName, userId)

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean, userId: Int = HPackages.myUserId) =
        userId >= 0 && execSU(
            "appops set --user $userId ${HUserApps.quote(packageName)} RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}"
        ).first == 0

    fun grantPermission(packageName: String, permission: String) =
        execSU("pm grant --user ${HPackages.myUserId} ${HUserApps.quote(packageName)} ${HUserApps.quote(permission)}").first == 0
}
