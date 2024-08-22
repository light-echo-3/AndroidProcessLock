package syncbox.micosocket.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap

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
