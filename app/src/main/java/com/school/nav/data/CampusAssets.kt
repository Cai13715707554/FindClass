package com.school.nav.data

import android.content.Context
import com.school.nav.core.data.CampusRepository
import java.io.BufferedReader

/**
 * 楼栋数据的装配。
 *
 * 数据有两个来源，装配顺序固定：
 *  1. `assets/buildings.json` —— 编译期打包的完整数据（楼栋 + 楼层 + 元素）；
 *  2. 地图编辑器导出的配置 —— 运行期写在应用私有目录，目前只有楼栋外轮廓。
 *
 * 合并规则在 [CampusRepository.create] 里，核心是**配置覆盖几何、assets 保留楼层**，
 * 所以编辑器的改动不需要重新打包就能生效，而内置示例数据也不会被写坏。
 */
object CampusAssets {

    const val DATA_FILE_NAME = "buildings.json"

    fun loadJson(context: Context, fileName: String = DATA_FILE_NAME): String =
        context.assets.open(fileName).bufferedReader().use(BufferedReader::readText)

    /**
     * 装配仓库：assets + **当前生效的那份**编辑器配置。
     *
     * 注意「当前生效」是 [ConfigStore] 的职责：用户可以有多份配置（一个学校一份），
     * 这里只读它指定的那一份。
     *
     * assets 读失败（数据文件损坏）时不抛异常，降级为空数据集 ——
     * App 仍然能启动并让用户手动/用编辑器修数据，比直接崩掉有用。
     */
    fun loadRepository(context: Context, store: ConfigStore): CampusRepository {
        val assetJson = runCatching { loadJson(context) }.getOrElse { """{"buildings":[]}""" }
        return CampusRepository.create(
            assetJson = assetJson,
            editorConfigJson = store.loadActiveJson(),
        ).repository
    }
}
