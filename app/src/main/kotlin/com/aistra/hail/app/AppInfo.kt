package com.aistra.hail.app

import android.content.pm.ApplicationInfo
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HPackages

class AppInfo(
    val packageName: String,
    var pinned: Boolean = false,
    var whitelisted: Boolean = false,
    val tagIdList: MutableList<Int> = mutableListOf(0),
    val userId: Int = HPackages.myUserId
) {
    enum class State { NOT_FOUND, UNFROZEN, FROZEN }

    val identity = AppIdentity(packageName, userId)
    val key: String get() = identity.key
    val applicationInfo: ApplicationInfo? get() = HPackages.getApplicationInfoOrNull(packageName, userId = userId)
    val name get() = applicationInfo?.loadLabel(app.packageManager) ?: packageName
    val state
        get() = when {
            applicationInfo == null -> State.NOT_FOUND
            AppManager.isAppFrozen(packageName, userId) -> State.FROZEN
            else -> State.UNFROZEN
        }

    override fun equals(other: Any?): Boolean =
        other is AppInfo && other.identity == identity
    override fun hashCode(): Int = identity.hashCode()
}
