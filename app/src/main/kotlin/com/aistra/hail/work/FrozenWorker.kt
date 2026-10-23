package com.aistra.hail.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HPackages

class FrozenWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        inputData.getString(HailData.KEY_PACKAGE)?.let {
            val success = AppManager.setAppFrozen(it, inputData.getBoolean(HailData.KEY_FROZEN, true),
                inputData.getInt(HailData.KEY_USER_ID, HPackages.myUserId))
            return if (success) Result.success() else Result.failure()
        }
        return Result.failure()
    }
}
