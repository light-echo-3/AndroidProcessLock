package syncbox.micosocket.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Process

object ProcessUtils {
    private var processName: String? = null

    fun getCurrentProcessName(context: Context): String? {
        processName ?: let {
            processName = getCurrentProcessNameInternal(context)
        }
        return processName
    }

    private fun getCurrentProcessNameInternal(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return null
        val pid = Process.myPid()
        for (processInfo in processes) {
            if (processInfo.pid == pid) {
                return processInfo.processName
            }
        }
        return null
    }

    fun isMainProcess(context: Context): Boolean {
        return getCurrentProcessName(context)?.contains(":") == false
    }

}