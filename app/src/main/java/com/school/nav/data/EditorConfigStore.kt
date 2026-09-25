package com.school.nav.data

import android.util.Log
import com.school.nav.core.data.EditorConfig
import com.school.nav.core.data.EditorConfigCodec
import java.io.File
import java.io.IOException

/** 一次写入的结果，用于给用户反馈「到底存到哪了」。 */
data class SaveResult(
    /** 应用私有目录里的路径（一定会有，除非写入失败）。 */
    val internalPath: String?,
    /** 外部目录里的路径（用户能用文件管理器 / 数据线取走）；不可用时为 null。 */
    val externalPath: String?,
    /** 非致命的失败信息（例如外部目录不可写），供 UI 提示。 */
    val warnings: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = internalPath != null
}

/**
 * 编辑器配置的落盘（纯文件逻辑，不依赖 Android，可直接单测）。
 *
 * ## 为什么写两份
 *
 * 需求是写到 `/data/data/<包名>/config/`。那确实是应用私有目录，
 * 但它在**未 root 的真机上用户拿不到**（文件管理器进不去，只能 adb run-as
 * 或 Android Studio 的 Device Explorer）。
 *
 * 而这份数据的用途是「画完之后导回工程 / 被别人拿走」，必须能取出来，
 * 所以同时再写一份到外部私有目录 `/sdcard/Android/data/<包名>/files/config/`：
 *  - Android 10 及以下：任何文件管理器都能访问；
 *  - Android 11+：文件管理器受限，但用数据线连电脑仍可直接读取，
 *    且写 app-specific 目录**不需要任何存储权限**（不受分区存储限制）。
 *
 * 内部那份是「App 自己立即生效」的权威数据；外部那份是「给人拿走」的副本。
 */
class EditorConfigStore(
    /** 应用私有目录，Android 上是 `context.filesDir`。 */
    private val filesDir: File,
    /** 外部私有目录，Android 上是 `context.getExternalFilesDir(null)`，可能为 null。 */
    private val externalFilesDir: File?,
) {

    /** /data/data/<包名>/files/config/editor_buildings.json */
    val internalFile: File get() = File(configDir(filesDir), EditorConfigCodec.FILE_NAME)

    /** /sdcard/Android/data/<包名>/files/config/editor_buildings.json */
    val externalFile: File?
        get() = externalFilesDir?.let { File(configDir(it), EditorConfigCodec.FILE_NAME) }

    private fun configDir(root: File) = File(root, EditorConfigCodec.DIR_NAME)

    // ------------------------------------------------------------ 读

    /** 读取应用私有目录里的配置；不存在或损坏时返回空配置。 */
    fun load(): EditorConfig = readFrom(internalFile) ?: EditorConfig()

    /**
     * 读取指定文件；不存在或解析失败返回 null。
     *
     * 解析失败**不抛异常**：一个坏掉的配置文件不该让 App 起不来，
     * 记日志并按「没有编辑器数据」处理即可。
     */
    fun readFrom(file: File): EditorConfig? {
        if (!file.isFile) return null
        return try {
            EditorConfigCodec.decode(file.readText()).also {
                if (it == null) logWarn("配置解析失败，已忽略：${file.absolutePath}")
            }
        } catch (e: IOException) {
            logWarn("配置读取失败：${file.absolutePath}", e)
            null
        }
    }

    /**
     * 读出用于装配仓库的配置文本。
     *
     * 优先内部（App 自己写的权威数据），内部没有时退回外部 ——
     * 这样用户把导出的文件拷进外部目录就能直接生效，不必重装 App。
     */
    fun loadConfigJson(): String? =
        readText(internalFile) ?: externalFile?.let { readText(it) }

    private fun readText(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    // ------------------------------------------------------------ 写

    /**
     * 保存配置：先写内部（权威），再尽力写外部（副本）。
     *
     * 外部写入失败**不影响**整体成功 —— 内部那份已经生效，
     * 只是用户暂时拿不到文件，因此返回 warning 让 UI 能如实提示。
     */
    fun save(
        outlines: List<com.school.nav.core.data.EditorBuilding>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SaveResult {
        val config = EditorConfig(
            schemaVersion = EditorConfig.CURRENT_SCHEMA_VERSION,
            exportedAtMillis = nowMillis,
            // 丢掉什么都没画的草稿：既没有合法轮廓、也没有任何元素的，存进去只是噪声
            buildings = outlines.filter { it.hasValidPolygon || it.elementCount > 0 },
        )
        val text = EditorConfigCodec.encode(config)
        val warnings = mutableListOf<String>()

        val internalPath = try {
            writeTo(internalFile, text)
            internalFile.absolutePath
        } catch (e: IOException) {
            logError("写入应用私有目录失败", e)
            warnings += "写入应用私有目录失败：${e.message}"
            null
        }

        val externalPath = try {
            val file = externalFile
            if (file == null) {
                warnings += "外部目录不可用（存储未挂载），只保存到了应用私有目录。"
                null
            } else {
                writeTo(file, text)
                file.absolutePath
            }
        } catch (e: IOException) {
            logWarn("写入外部目录失败", e)
            warnings += "写入外部目录失败：${e.message}"
            null
        }

        return SaveResult(
            internalPath = internalPath,
            externalPath = externalPath,
            warnings = warnings,
        )
    }

    private fun writeTo(file: File, text: String) {
        file.parentFile?.mkdirs()
        // 先写临时文件再改名：写到一半被中断也不会留下半截 JSON 把原数据弄坏
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // renameTo 在某些文件系统上会失败，退化为直接覆盖写
            file.writeText(text)
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ 删

    /** 删掉两份配置（用于「清空已绘制的楼栋」）。 */
    fun clear() {
        runCatching { internalFile.delete() }
        runCatching { externalFile?.delete() }
    }

    companion object {
        private const val TAG = "FindClass.EditorConfig"

        /** Android 侧工厂：把 Context 的两个目录传进来。 */
        fun fromDirectories(filesDir: File, externalFilesDir: File?): EditorConfigStore =
            EditorConfigStore(filesDir = filesDir, externalFilesDir = externalFilesDir)
    }

    /**
     * 安全日志。
     *
     * `android.util.Log` 在纯 JVM 单元测试里是未实现的桩（调用即抛
     * `RuntimeException: Method w in android.util.Log not mocked`），
     * 而本类刻意做成不依赖 Android 以便直接单测，所以日志必须容错。
     */
    private fun logWarn(message: String, e: Throwable? = null) {
        runCatching { if (e == null) Log.w(TAG, message) else Log.w(TAG, message, e) }
    }

    private fun logError(message: String, e: Throwable? = null) {
        runCatching { if (e == null) Log.e(TAG, message) else Log.e(TAG, message, e) }
    }
}
