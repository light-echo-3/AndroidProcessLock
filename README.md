# AndroidProcessLock
安卓进程锁，解决多进程前后台切换检测异常问题。

## AppForegroundBackgroundSwitchMonitor
```kotlin

/***
 * 前后台切换监控器，支持跨进程
 */
object AppForegroundBackgroundSwitchMonitor {

    ...

    private lateinit var processLock: ProcessLock

    private var processName: String? = null

    private var appForegroundBackgroundSwitchListeners =
        CopyOnWriteArrayList<AppForegroundBackgroundSwitchListener>()

    //用于判断是否程序在前台
    private var isForeground = false  //用于判断是否程序在前台
        get() = MultiProcessMMKV.mmkv.getBoolean(APP_LIFE_PAGE_IS_FOREGROUND, false)
        set(value) {
            field = value
            MultiProcessMMKV.mmkv.putBoolean(APP_LIFE_PAGE_IS_FOREGROUND, value)
        }

    private var isPaused = true
        get() = MultiProcessMMKV.mmkv.getBoolean(APP_LIFE_PAGE_IS_PAUSED, true)
        set(value) {
            field = value
            MultiProcessMMKV.mmkv.putBoolean(APP_LIFE_PAGE_IS_PAUSED, value)
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
            ...
            
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

            ...
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

```

## ProcessLock
```kotlin
class ProcessLock(private val context: Context) {

    private var fos: FileOutputStream? = null
    private var lockFile: File = File(context.getDir("lock", Context.MODE_PRIVATE), "process.lock")
        get() {
            checkLockFile(field)
            return field
        }

    private var fileLock: FileLock? = null
    private val threadLockCountMap = ConcurrentHashMap<Long, Int>()

    /**
     * 超时时间，millisecond
     */
    var timeout = 1000 * 10
    private var lockTime = 0L

    companion object {
        private const val TIME_INTERVAL = 10L
    }

    @Throws(LockException::class)
    fun lock() {
        val currentThreadId = Thread.currentThread().id

        // 如果当前线程已经持有锁，增加计数器并返回
        if (threadLockCountMap.containsKey(currentThreadId)) {
            threadLockCountMap[currentThreadId] = threadLockCountMap[currentThreadId]!! + 1
            return
        }

        try {
            fos = FileOutputStream(lockFile)
            lockTime = SystemClock.uptimeMillis()
            while (!tryLock(fos)) {
                if (SystemClock.uptimeMillis() - lockTime > timeout) {
                    throw LockException("timeout:timeout=$timeout")
                }
                Log.e(
                    "AppFBSwitchMonitor",
                    "ProcessLock: 获取锁失败，$TIME_INTERVAL millisecond 后再次尝试,processName=${ProcessUtils.getCurrentProcessName(context)}"
                )
                try {
                    Thread.sleep(TIME_INTERVAL)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // 记录当前线程持有锁的计数
            threadLockCountMap[currentThreadId] = 1
        } catch (e: Exception) {
            throw LockException(cause = e)
        }
    }

    /***
     * @return true 文件锁成功获取,else 文件锁获取失败，表示锁已被其他进程占用
     */
    private fun tryLock(fos: FileOutputStream?): Boolean {
        return try {
            lockFile
            fileLock = fos?.channel?.tryLock()
            fileLock != null
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun unlock() {
        val currentThreadId = Thread.currentThread().id

        // 如果当前线程持有锁的计数大于1，减少计数并返回
        if (threadLockCountMap.containsKey(currentThreadId)) {
            val count = threadLockCountMap[currentThreadId]!!
            if (count > 1) {
                threadLockCountMap[currentThreadId] = count - 1
                return
            }
        }

        // 当前线程不再持有锁，释放锁资源
        try {
            fileLock?.release()
            fos?.close()
            // 清除当前线程的锁计数
            threadLockCountMap.remove(currentThreadId)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun checkLockFile(lockFile: File) {
        if (!lockFile.exists()) {
            lockFile.parentFile?.mkdirs()
            lockFile.createNewFile()
        }
    }

    class LockException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
}
```
