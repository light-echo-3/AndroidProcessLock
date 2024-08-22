package com.wuzhu.libprocesslock

import com.tencent.mmkv.MMKV

/**
 * 支持多进程的mmkv
 */
val multiProcessMMKV = MMKV.mmkvWithID("MultiProcessMMKV", MMKV.MULTI_PROCESS_MODE)
