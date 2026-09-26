package com.school.nav.data

import android.content.Context
import android.content.SharedPreferences
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

/** 一份配置文件的元信息。 */
data class ConfigEntry(
    /** 文件名（不含目录），同时作为这份配置的唯一标识。 */
    val fileName: String,
    /** 用户起的名字，展示用。 */
    val displayName: String,
    /** 里面有多少栋楼；为 null 表示文件读不出来。 */
    val buildingCount: Int?,
    /** 最后修改时间（毫秒）。 */
    val lastModified: Long,
    /** 是否是当前生效的那一份。 */
    val isActive: Boolean,
) {
    val isBroken: Boolean get() = buildingCount == null
}

/** 激活配置的持久化：只存「当前用哪个文件」，与配置文件本身分开存。 */
interface ActiveConfigStore {
    fun activeFileName(): String?
    fun setActiveFileName(name: String?)
}

/** SharedPreferences 实现。 */
class SharedPrefsActiveConfigStore(context: Context) : ActiveConfigStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun activeFileName(): String? =
        prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    override fun setActiveFileName(name: String?) {
        prefs.edit().apply {
            if (name.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, name)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "editor_configs"
        const val KEY_ACTIVE = "active_config_file"
    }
}

/**
 * 多份编辑器配置的管理。
 *
 * ## 为什么一个文件不够
 *
 * 这个软件不针对单独一个学校 —— 换一个学校就是换一份数据。
 * 所以配置目录里可以放多份 `*.json`，用户能在设置页里切换「当前用哪一份」，
 * 也可以新建 / 删除 / 重命名。
 *
 * ## 目录结构
 *
 * ```
 * /data/data/<包名>/files/config/
 *   ├── buildings.json          <- 某个学校的配置
 *   ├── 实验中学.json
 *   └── ...
 * /sdcard/Android/data/<包名>/files/config/   <- 同步一份，方便取走
 * ```
 *
 * ## 「当前用哪一份」记在哪
 *
 * 记在 SharedPreferences 而不是文件内容里：配置本身应该是自包含的数据，
 * 「谁的机器上正在看哪一份」是设备本地状态，混进数据里会让文件没法互相拷贝。
 *
 * 没设置过、或设置的文件已被删掉时，回退到目录里**按修改时间最新的**一份；
 * 目录为空则用默认文件名兜底。
 */
class ConfigStore(
    private val filesDir: File,
    private val externalFilesDir: File?,
    private val activeConfigStore: ActiveConfigStore,
) {

    /** 配置目录（应用私有）。 */
    val internalDir: File get() = File(filesDir, EditorConfigCodec.DIR_NAME)

    /** 外部目录（给用户取走的副本）。 */
    val externalDir: File? get() = externalFilesDir?.let { File(it, EditorConfigCodec.DIR_NAME) }

    // ------------------------------------------------------------ 列表与激活

    /**
     * 列出所有配置，按最后修改时间从新到旧。
     *
     * 注意这里读的是**设置里记着的那个名字**（[ActiveConfigStore.activeFileName]），
     * 不是 [activeFileName] —— 后者在名字失效时会回退并调用 [list]，
     * 两者互相调用会直接栈溢出。
     */
    fun list(): List<ConfigEntry> {
        val dir = internalDir
        if (!dir.isDirectory) return emptyList()
        val active = activeConfigStore.activeFileName()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) }
            .orEmpty()
            .map { file ->
                val config = readQuietly(file)
                ConfigEntry(
                    fileName = file.name,
                    displayName = displayNameOf(file.name),
                    buildingCount = config?.buildings?.size,
                    lastModified = file.lastModified(),
                    isActive = file.name == active,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    /**
     * 当前生效的配置文件名。
     *
     * 三级兜底：设置里指定的 -> 目录里最新的 -> 默认文件名。
     * 这样即使用户删掉了正在用的那份，也不会突然没有数据可用。
     */
    fun activeFileName(): String {
        val configured = activeConfigStore.activeFileName()
        if (configured != null && File(internalDir, configured).isFile) return configured

        val newest = list().firstOrNull()?.fileName
        if (newest != null) return newest

        return EditorConfigCodec.FILE_NAME
    }

    /** 当前生效的配置。 */
    fun loadActive(): EditorConfig = readQuietly(activeFile()) ?: EditorConfig()

    /** 当前生效的配置文件。 */
    fun activeFile(): File = File(internalDir, activeFileName())

    /** 切换当前使用的配置。 */
    fun setActive(fileName: String) {
        activeConfigStore.setActiveFileName(fileName)
    }

    // ------------------------------------------------------------ 新建 / 删除 / 改名

    /**
     * 新建一份空配置。
     *
     * @return 实际创建的文件名；重名时自动加序号。
     */
    fun create(name: String): String {
        val safe = sanitize(name)
        val fileName = uniqueFileName(safe)
        writeTo(File(internalDir, fileName), EditorConfig())
        setActive(fileName)
        return fileName
    }

    /** 复制一份现有配置（「照着改」比从零画快得多）。 */
    fun duplicate(fileName: String): String? {
        val source = File(internalDir, fileName)
        val config = readQuietly(source) ?: return null
        val base = displayNameOf(fileName)
        val newName = uniqueFileName(sanitize("$base 副本"))
        writeTo(File(internalDir, newName), config)
        return newName
    }

    /**
     * 删除一份配置。
     *
     * 如果删的正好是当前生效的那份，会自动切到剩下最新的一份；
     * 一份都不剩时回到默认文件名（此时仓库会退回只用 assets 内置数据）。
     */
    fun delete(fileName: String): Boolean {
        val removed = File(internalDir, fileName).delete()
        externalDir?.let { File(it, fileName).delete() }
        if (removed && activeConfigStore.activeFileName() == fileName) {
            val next = list().firstOrNull()?.fileName
            activeConfigStore.setActiveFileName(next)
        }
        return removed
    }

    /** 重命名：改的是「显示名」，即文件名本身。 */
    fun rename(fileName: String, newDisplayName: String): String? {
        val source = File(internalDir, fileName)
        if (!source.isFile) return null
        // 目标显示名和现在一样就直接返回 —— 否则 uniqueFileName 会以为撞名，
        // 把「填了同样的名字」变成「多出一份 xxx-2.json」
        if (sanitize(newDisplayName) == displayNameOf(fileName)) return fileName

        val targetName = uniqueFileName(sanitize(newDisplayName))
        val target = File(internalDir, targetName)
        if (!source.renameTo(target)) return null
        externalDir?.let { dir ->
            runCatching { File(dir, fileName).renameTo(File(dir, targetName)) }
        }
        if (activeConfigStore.activeFileName() == fileName) setActive(targetName)
        return targetName
    }

    // ------------------------------------------------------------ 读写

    /**
     * 保存到当前生效的配置（内部 + 外部各一份）。
     *
     * 外部那份是给用户取走的副本；写失败只降级为 warning，内部那份才是权威。
     */
    fun saveActive(
        outlines: List<com.school.nav.core.data.EditorBuilding>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SaveResult {
        val config = EditorConfig(
            schemaVersion = EditorConfig.CURRENT_SCHEMA_VERSION,
            exportedAtMillis = nowMillis,
            // 丢掉什么都没画的草稿：既没有合法轮廓、也没有任何元素的，存进去只是噪声
            buildings = outlines.filter { it.hasValidPolygon || it.elementCount > 0 },
        )
        val warnings = mutableListOf<String>()

        val internalPath = try {
            writeTo(activeFile(), config)
            activeFile().absolutePath
        } catch (e: IOException) {
            warnings += "写入应用私有目录失败：${e.message}"
            null
        }

        val externalPath = try {
            val dir = externalDir
            if (dir == null) {
                warnings += "外部目录不可用（存储未挂载），只保存到了应用私有目录。"
                null
            } else {
                val file = File(dir, activeFileName())
                writeTo(file, config)
                file.absolutePath
            }
        } catch (e: IOException) {
            warnings += "写入外部目录失败：${e.message}"
            null
        }

        return SaveResult(internalPath, externalPath, warnings)
    }

    /** 读出当前生效配置的 JSON 文本，供仓库装配。 */
    fun loadActiveJson(): String? {
        val file = activeFile()
        if (!file.isFile) {
            // 内部没有时退回外部 —— 用户把文件拷进外部目录就能直接生效
            val external = externalDir?.let { File(it, activeFileName()) } ?: return null
            return runCatching { external.takeIf { it.isFile }?.readText() }.getOrNull()
        }
        return runCatching { file.readText() }.getOrNull()
    }

    /** 删除当前配置里的全部内容（保留文件本身）。 */
    fun clearActive() {
        writeTo(activeFile(), EditorConfig())
    }

    // ------------------------------------------------------------ 内部

    private fun readQuietly(file: File): EditorConfig? {
        if (!file.isFile) return null
        return runCatching { EditorConfigCodec.decode(file.readText()) }.getOrNull()
    }

    private fun writeTo(file: File, config: EditorConfig) {
        file.parentFile?.mkdirs()
        val text = EditorConfigCodec.encode(config)
        // 先写临时文件再改名：写到一半被中断也不会留下半截 JSON 把原数据弄坏
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    /** 显示名就是去掉 .json 后缀的文件名。 */
    private fun displayNameOf(fileName: String): String = fileName.removeSuffix(JSON_SUFFIX)

    /**
     * 文件名净化。
     *
     * 去掉路径分隔符等非法字符，保证是安全的单层文件名；顺手吃掉用户
     * 可能带上的 `.json` 后缀（「实验中学.json」和「实验中学」应当等价），
     * 否则会出现 `实验中学.json.json` 这种双后缀。
     */
    private fun sanitize(name: String): String {
        val cleaned = name.trim()
            .removeSuffix(JSON_SUFFIX)
            .trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { DEFAULT_CONFIG_NAME }
        return cleaned.take(MAX_NAME_LENGTH)
    }

    /** 目录里已有同名文件时加序号，避免覆盖别人的配置。 */
    private fun uniqueFileName(base: String): String {
        val withSuffix = if (base.endsWith(JSON_SUFFIX)) base else base + JSON_SUFFIX
        if (!File(internalDir, withSuffix).exists()) return withSuffix
        var n = 2
        while (File(internalDir, base + "-$n$JSON_SUFFIX").exists()) n++
        return "$base-$n$JSON_SUFFIX"
    }

    companion object {
        const val JSON_SUFFIX = ".json"
        const val DEFAULT_CONFIG_NAME = "默认配置"
        const val MAX_NAME_LENGTH = 40

        /** Android 侧工厂。 */
        fun fromDirectories(
            filesDir: File,
            externalFilesDir: File?,
            activeConfigStore: ActiveConfigStore,
        ): ConfigStore = ConfigStore(filesDir, externalFilesDir, activeConfigStore)
    }
}
