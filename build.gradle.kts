// 顶层构建脚本：只声明插件版本，不在此处 apply。
// 各模块通过 plugins { id(...) } 复用这里的版本。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
