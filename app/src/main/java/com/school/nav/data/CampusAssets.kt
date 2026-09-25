package com.school.nav.data

import android.content.Context
import com.school.nav.core.data.CampusRepository
import java.io.BufferedReader

/**
 * 从 assets 读取打包好的楼栋数据。
 *
 * MVP 零后端：楼栋、楼层、元素全部来自 `assets/buildings.json`，
 * 运行时只读一次，不生成、不修改。
 */
object CampusAssets {

    const val DATA_FILE_NAME = "buildings.json"

    fun loadJson(context: Context, fileName: String = DATA_FILE_NAME): String =
        context.assets.open(fileName).bufferedReader().use(BufferedReader::readText)

    fun loadRepository(context: Context, fileName: String = DATA_FILE_NAME): CampusRepository =
        CampusRepository(loadJson(context, fileName))
}
