package com.aistra.hail.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.os.UserManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData
import java.util.concurrent.ConcurrentHashMap

/** Shell fallback for root, where the app process cannot call cross-user package APIs. */
object HUserApps {
    private data class CachedApp(val info: ApplicationInfo, val loadedAt: Long)
    private val cache = ConcurrentHashMap<String, CachedApp>()
    private const val CACHE_TIME_MS = 5_000L

    internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal fun execute(command: String): Pair<Int, String?> = when {
        HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
        HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
        else -> 1 to null
    }

    fun getUserIds(): List<Int> {
        val frameworkProfiles = runCatching {
            (app.getSystemService(Context.USER_SERVICE) as? UserManager)?.userProfiles?.map { it.hashCode() }
        }.getOrNull().orEmpty()
        val result = execute("pm list users")
        val pmUsers = if (result.first == 0) {
            Regex("UserInfo\\{(\\d+):").findAll(result.second.orEmpty()).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        } else emptyList()
        return (listOf(HPackages.myUserId) + frameworkProfiles + pmUsers).filter { it >= 0 }.distinct()
    }

    internal fun invalidate(packageName: String, userId: Int) {
        cache.remove("$userId:$packageName")
    }

    private data class UserState(
        val installed: Boolean,
        val hidden: Boolean,
        val stopped: Boolean,
        val suspended: Boolean,
        val enabled: Int
    )

    private fun parseStates(dump: String): Map<String, UserState> {
        val states = mutableMapOf<String, UserState>()
        var packageName: String? = null
        val packageLine = Regex("^\\s*Package \\[([^]]+)]")
        val userLine = Regex("^\\s*User (\\d+): (.*)")
        for (line in dump.lineSequence()) {
            packageLine.find(line)?.let { packageName = it.groupValues[1] }
            val match = userLine.find(line) ?: continue
            val pkg = packageName ?: continue
            val fields = match.groupValues[2].split(' ').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            // A missing/unknown state must never be interpreted as the owner's state.
            val installed = fields["installed"]?.toBooleanStrictOrNull() ?: continue
            states["${match.groupValues[1]}:$pkg"] = UserState(
                installed, fields["hidden"] == "true", fields["stopped"] == "true",
                fields["suspended"] == "true", fields["enabled"]?.toIntOrNull() ?: 0
            )
        }
        return states
    }

    private fun makeInfo(packageName: String, userId: Int, path: String?, uid: Int?, state: UserState): ApplicationInfo? {
        if (!state.installed) return null
        val local = HPackages.getApplicationInfoOrNull(packageName)
        val archive = if (local == null && path != null) runCatching {
            app.packageManager.getPackageArchiveInfo(path, 0)?.applicationInfo
        }.getOrNull() else null
        val info = (local ?: archive)?.let(::ApplicationInfo) ?: ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = packageName
        }
        if (path != null) {
            info.sourceDir = path
            info.publicSourceDir = path
        }
        info.uid = uid ?: userId * 100000 + ((local?.uid ?: 0) % 100000)
        // Never use a UID that belongs to a different user, even on unusual OEM output.
        if (HPackages.userIdOf(info) != userId) return null
        info.flags = info.flags or ApplicationInfo.FLAG_INSTALLED
        info.flags = if (state.stopped) info.flags or ApplicationInfo.FLAG_STOPPED
        else info.flags and ApplicationInfo.FLAG_STOPPED.inv()
        if (HTarget.N) info.flags = if (state.suspended) info.flags or ApplicationInfo.FLAG_SUSPENDED
        else info.flags and ApplicationInfo.FLAG_SUSPENDED.inv()
        info.enabled = when (state.enabled) {
            1 -> true
            2, 3, 4 -> false
            else -> if (local?.enabled != false) true else runCatching {
                app.packageManager.getPackageArchiveInfo(info.sourceDir, 0)?.applicationInfo?.enabled ?: true
            }.getOrDefault(true)
        }
        runCatching {
            val field = ApplicationInfo::class.java.getField("privateFlags")
            val flags = field.getInt(info)
            field.setInt(info, if (state.hidden) flags or 1 else flags and 1.inv())
        }
        cache["$userId:$packageName"] = CachedApp(info, SystemClock.elapsedRealtime())
        return info
    }

    fun getApplications(userIds: List<Int>): List<ApplicationInfo> {
        cache.clear()
        val dump = execute("dumpsys package")
        if (dump.first != 0) return emptyList()
        val states = parseStates(dump.second.orEmpty())
        val result = mutableListOf<ApplicationInfo>()
        for (userId in userIds.filter { it >= 0 && it != HPackages.myUserId }) {
            var listing = execute("pm list packages -f -U -u --user $userId")
            if (listing.first != 0) listing = execute("pm list packages -f -u --user $userId")
            if (listing.first != 0) continue
            for (line in listing.second.orEmpty().lineSequence()) {
                if (!line.startsWith("package:")) continue
                val entry = line.removePrefix("package:").substringBefore(" uid:").trim()
                val packageName = entry.substringAfterLast('=')
                val state = states["$userId:$packageName"] ?: continue
                val path = entry.substringBeforeLast('=', "").takeIf { it.isNotBlank() }
                val uid = line.substringAfter(" uid:", "").trim().toIntOrNull()
                makeInfo(packageName, userId, path, uid, state)?.let(result::add)
            }
        }
        return result
    }

    fun getApplicationInfo(packageName: String, userId: Int): ApplicationInfo? {
        if (userId < 0) return null
        cache["$userId:$packageName"]?.let {
            if (SystemClock.elapsedRealtime() - it.loadedAt < CACHE_TIME_MS) return it.info
        }
        val result = execute("dumpsys package ${quote(packageName)}")
        if (result.first != 0) return null
        val state = parseStates(result.second.orEmpty())["$userId:$packageName"] ?: return null
        val local = HPackages.getApplicationInfoOrNull(packageName)
        val path = local?.sourceDir ?: execute("pm path --user $userId ${quote(packageName)}").second
            ?.lineSequence()?.firstOrNull { it.startsWith("package:") }?.removePrefix("package:")
        return makeInfo(packageName, userId, path, null, state)
    }
}
