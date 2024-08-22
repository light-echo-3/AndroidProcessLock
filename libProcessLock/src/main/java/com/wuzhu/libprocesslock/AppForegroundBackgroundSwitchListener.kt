package syncbox.micosocket.utils


interface AppForegroundBackgroundSwitchListener {

    fun onAppToBackground(activityName: String, processName: String, isMainProcess: Boolean)
    fun onAppToForeground(activityName: String, processName: String, isMainProcess: Boolean)

}