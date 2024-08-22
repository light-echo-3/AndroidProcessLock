package syncbox.micosocket.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import com.wuzhu.libprocesslock.multiProcessMMKV
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors


/***
 * 前后台切换监控器，支持跨进程
 */
object AppForegroundBackgroundSwitchMonitor {

    private const val BROADCAST_ACTION = "app.foreground.background.switch.broadcast"
    private const val BROADCAST_EXTRA_EVENT = "foregroundBackgroundEvent"
    private const val BROADCAST_EXTRA_ACTIVITY_NAME = "broadcast_extra_activity_name"

    // APP 前后台切换事件监听
    private const val EVENT_APP_BACKGROUND = "event_app_background"
    private const val EVENT_APP_FOREGROUND = "event_app_foreground"

    private const val TAG = "AppFBSwitchMonitor"

    private const val APP_LIFE_PAGE_IS_PAUSED = "app_life_page_is_paused"
    private const val APP_LIFE_PAGE_IS_FOREGROUND = "app_life_page_is_foreground"

    @SuppressLint("StaticFieldLeak")
    private lateinit var processLock: ProcessLock

    private var processName: String? = null

    private var appForegroundBackgroundSwitchListeners =
        CopyOnWriteArrayList<AppForegroundBackgroundSwitchListener>()

    //用于判断是否程序在前台
    private var isForeground = false  //用于判断是否程序在前台
        get() = multiProcessMMKV.getBoolean(APP_LIFE_PAGE_IS_FOREGROUND, false)
        set(value) {
            field = value
            multiProcessMMKV.putBoolean(APP_LIFE_PAGE_IS_FOREGROUND, value)
        }

    private var isPaused = true
        get() = multiProcessMMKV.getBoolean(APP_LIFE_PAGE_IS_PAUSED, true)
        set(value) {
            field = value
            multiProcessMMKV.putBoolean(APP_LIFE_PAGE_IS_PAUSED, value)
        }
    private var lastResumeActivityHC = 0

    private val singleExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor()
    }

    /**
     * 注册监听
     * @param app Application
     */
    fun init(app: Application) {
        processName = ProcessUtils.getCurrentProcessName(app)
        processLock = ProcessLock(app)
        Log.w(TAG, "init: processName = $processName")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {


            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                Log.d(TAG, "onActivityCreated:processName=$processName, activity=$activity")
            }


            override fun onActivityStarted(activity: Activity) {
                Log.d(TAG, "onActivityStarted:processName=$processName, activity=$activity")
            }


            override fun onActivityResumed(activity: Activity) {
                Log.d(TAG, "onActivityResumed:processName=$processName, activity=$activity")
                onActivityResumedAsync(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                Log.d(TAG, "onActivityPaused:processName=$processName, activity=$activity")
                onActivityPausedAsync()
            }

            override fun onActivityStopped(activity: Activity) {
                Log.d(TAG, "onActivityStopped:processName=$processName, activity=$activity")
                checkBackground(activity)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
                Log.d(TAG, "onActivityDestroyed:processName=$processName, activity=$activity")
            }
        })
        app.registerReceiver(InnerBroadcastReceiver(), broadcastFilter())
        initAsync(app)
    }

    private fun initAsync(app: Application) {
        if (ProcessUtils.isMainProcess(app)) {
            singleExecutor.execute {
                try {
                    processLock.lock()
                    isForeground = false
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    processLock.unlock()
                }
            }
        }
    }


    private fun onActivityPausedAsync() {
        singleExecutor.execute {
            try {
                processLock.lock()
                isPaused = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                processLock.unlock()
            }
        }
    }


    private fun onActivityResumedAsync(activity: Activity) {
        singleExecutor.execute {
            try {
                processLock.lock()
                isPaused = false
                lastResumeActivityHC = activity.hashCode()
                if (!isForeground) {
                    isForeground = true
                    sendBroadcast(activity, EVENT_APP_FOREGROUND)
                    Log.e(TAG, "应用进入前台 processName = $processName")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                processLock.unlock()
            }
        }
    }

    /***
     * 同一个Activity 的 onPause & onStop 连续调用（中间没有onResume,说明程序切换到了后台）
     * lastResumeActivity 一定等于 lastPauseActivity
     */
    private fun checkBackground(activity: Activity) {
        singleExecutor.execute {
            try {
                processLock.lock()
                if (activity.hashCode() == lastResumeActivityHC && isPaused && isForeground) {
                    isForeground = false
                    sendBroadcast(activity, EVENT_APP_BACKGROUND)
                    Log.e(TAG, "应用进入后台 processName = $processName")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                processLock.unlock()
            }
        }
    }

    /**
     * 主进程注册监听
     */
    @JvmStatic
    fun registerListenerInMainProcess(
        context: Context,
        appForegroundBackgroundSwitchListener: AppForegroundBackgroundSwitchListener
    ) {
        if (ProcessUtils.isMainProcess(context)) {
            if (!appForegroundBackgroundSwitchListeners.contains(
                    appForegroundBackgroundSwitchListener
                )
            ) {
                appForegroundBackgroundSwitchListeners.add(appForegroundBackgroundSwitchListener)
            }
        }
    }

    /**
     * 所有进程注册监听
     */
    @Suppress("unused")
    @JvmStatic
    fun registerListener(appForegroundBackgroundSwitchListener: AppForegroundBackgroundSwitchListener) {
        if (!appForegroundBackgroundSwitchListeners.contains(appForegroundBackgroundSwitchListener)) {
            appForegroundBackgroundSwitchListeners.add(appForegroundBackgroundSwitchListener)
        }
    }

    @JvmStatic
    fun unregisterListener(appForegroundBackgroundSwitchListener: AppForegroundBackgroundSwitchListener) {
        this.appForegroundBackgroundSwitchListeners.remove(appForegroundBackgroundSwitchListener)
    }

    private fun sendBroadcast(activity: Activity, foregroundBackgroundEvent: String) {
        val intent = Intent()
        //下面action需要与服务端APP清单文件里面的广播配置的action字段保持一致
        intent.action = BROADCAST_ACTION
        intent.putExtra(BROADCAST_EXTRA_EVENT, foregroundBackgroundEvent)
        intent.putExtra(BROADCAST_EXTRA_ACTIVITY_NAME, activity::class.java.canonicalName)
        activity.sendBroadcast(intent)
    }

    private fun broadcastFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(BROADCAST_ACTION)
        }
    }

    /**
     * 广播接收跨进程数据
     */
    private class InnerBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent?) {
            if (BROADCAST_ACTION == intent?.action) {
                val event = intent.getStringExtra(BROADCAST_EXTRA_EVENT)
                val activityName = intent.getStringExtra(BROADCAST_EXTRA_ACTIVITY_NAME) ?: ""
                Log.w(
                    TAG,
                    "InnerBroadcastReceiver-onReceive: event=$event,processName=$processName"
                )
                when (event) {
                    EVENT_APP_FOREGROUND -> {
                        appForegroundBackgroundSwitchListeners.forEach {
                            it.onAppToForeground(
                                activityName, processName ?: "", ProcessUtils.isMainProcess(context)
                            )
                        }

                    }

                    EVENT_APP_BACKGROUND -> {
                        appForegroundBackgroundSwitchListeners.forEach {
                            it.onAppToBackground(
                                activityName, processName ?: "", ProcessUtils.isMainProcess(context)
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
