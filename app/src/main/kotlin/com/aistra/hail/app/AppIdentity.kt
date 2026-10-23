package com.aistra.hail.app

/** Android installs the same package independently for each user/profile. */
data class AppIdentity(val packageName: String, val userId: Int) {
    init {
        require(packageName.isNotBlank())
        require(userId >= 0) { "An explicit, nonnegative Android user ID is required" }
    }

    val key: String get() = "$userId:$packageName"

    companion object {
        fun fromStored(packageName: String, storedUserId: Int?, currentUserId: Int): AppIdentity? {
            val userId = storedUserId ?: currentUserId
            return if (userId < 0 || packageName.isBlank()) null else AppIdentity(packageName, userId)
        }
    }
}
