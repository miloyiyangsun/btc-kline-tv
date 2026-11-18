package com.milolab.btctv.data.overview

import android.content.Context
import java.io.File

/**
 * Android 侧唯一依赖 Context 的入口：缓存落在 filesDir/overview_cache.json。
 * 该文件在 preview 的 copySources 中被排除，其余 overview 代码保持纯 Kotlin 可测。
 */
fun createOverviewRepository(context: Context): OverviewRepository =
    OverviewRepository.withDefaults(File(context.filesDir, "overview_cache.json"))
