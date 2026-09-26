# 校园教学楼导航（FindClass）

面向学生、新生、访客的**教学楼室内导航** Android App。

解决一件事：**知道自己在哪栋楼、哪层，然后用纯中文文字指令走到目标教室。**

- 用 GPS 判断在**哪栋楼**（点在楼栋多边形内即锁定）
- 用气压计判断在**哪一层**（相对高度匹配楼层表）
- 默认选中当前楼层中**离用户最近的元素**作为当前位置，不弹窗、不打断
- 用户可手动修改**楼栋 / 楼层 / 当前位置**三级，手动优先
- 搜索中文名（"物理实验室"）或点快捷目标
- 生成**同层 / 跨层 / 跨楼**三种文字导航

**不用米数、不用教室编号，只列中文名。**

需求来源：`docs/产品设计文档.txt`、`docs/技术方案.txt`、`docs/Demo.html`。

---

## 一、当前状态

| 项目 | 状态 |
| --- | --- |
| Debug APK 构建 | ✅ 成功（`app/build/outputs/apk/debug/`，按 ABI 拆分 + universal） |
| `:core` 单元测试 | ✅ **80 个用例全绿**（7 个测试类；1 个 skip 是平台相关的有序性检查） |
| `:app` 单元测试 | ✅ **99 个用例全绿**（4 个测试类：编辑器状态机 / 多配置存储 / Key / 定位兜底） |
| 地图编辑器（绘制 + 编辑形状 + 多配置） | ✅ 已实现，⚠️ **未在真机验证地图渲染**（需要你自己的高德 Key） |
| 真机验证（GPS / 气压计 / 权限） | ❌ 未做（本机无设备，见 7.2） |

> ⚠️ 请先看**第七节「已知问题」**，那里写清了未验证的部分，以及为什么 `No target device found`。

## 一·一、导航结构

底部是胶囊悬浮导航，三个页签：

| 页签 | 内容 |
| --- | --- |
| **首页** | 当前位置卡片（三级可改）+ 搜索/快捷目标 + 文字导航 |
| **地图** | 地图编辑器：**卫星底图全屏**，右上角下拉切换绘制模式（教学楼 / 教室 / 办公室 / 圆形区域 / 楼梯口 / 卫生间），顶部搜索地点，右上角一排工具按钮，画完保存并自动导入 |
| **我的** | 设置入口（填高德 Key + **切换 / 新建 / 复制 / 改名 / 删除配置文件**）+ 定位测试（实时经纬度、精度、来源、高度）+ 关于 |

**首次使用地图编辑器前必须先填高德 Key**：进入「我的 → 设置」粘贴 Key。
没填时地图页不会白屏，而是显示引导和「去设置里填写 Key」按钮。

### 地图编辑器怎么用

1. 打开地图页，会自动定位到你所在位置（第一次取到定位时把地图移过去）
2. 如果在家里、看不到学校：点顶部**搜索框** —— 它会就地展开成输入框（把右侧模式下拉
   **吞并**掉），**边输边出结果**，结果面板从搜索框下面拉出来，点结果跳到那个地点
3. 右上角下拉选模式，**三种画法**：

   | 模式 | 画法 |
   | --- | --- |
   | **教学楼**（外轮廓） | **按住拖动框出范围**，松手即成型，再填名称 + 楼层数 |
   | **教室 / 办公室** | 先在「清单」里点一栋楼作为宿主、选好楼层，再按住拖动框出范围 |
   | **圆形区域** | 按下的点是**圆心**，拖出去的距离是**半径**（存成 32 边多边形，导航算法不用为「圆」单独开分支） |
   | **楼梯口 / 卫生间** | **点一下就是它** —— 不用拖、不用画边界，地图上画成实心圆点，一定看得见 |

   楼梯额外要选**起始层与结束层**（楼梯穿过楼板，会在覆盖的每一层都生成一份同名楼梯）。
4. 右侧一排工具按钮（从上到下）：

   | 按钮 | 作用 |
   | --- | --- |
   | 定位 | 把地图移回我的位置 |
   | **撤销 / 重做** | 整份数据的快照栈，**连已经写进配置文件的楼栋也能撤回**；一次拖拽只算一步 |
   | **编辑形状** | 打开后可以点选图形、拖顶点、拖整体平移 |
   | **旋转** | 选中图形每次转 15° |
   | **加点 / 删点** | 在「最近点击处」的边上插点、或删掉最近的顶点 |
   | **删除图形** | 删掉当前选中的轮廓或元素 |
   | 放弃草稿 | 丢掉正在画的那一笔 |

5. 底部「保存导入」写入配置文件，**下次启动 App 时自动生效**

**为什么是拖拽而不是逐点点选**：点三个点又慢又难对齐。矩形是最常见的房间形状，
拖一下就有四条边；而楼梯口、卫生间本来就没有明确边界，画矩形纯属白费功夫 ——
导航只需要知道它「在哪」，所以它们就是一个点。

**为什么要「编辑形状」而不是重画**：轮廓画歪一点是常事，重画等于把那一层的
元素全丢掉。选中之后拖顶点 / 加点 / 删点 / 旋转，改的是同一份数据，楼层元素不受影响。

地图上的元素按类型着色：楼梯口橙色（导航里的关键转向点）、卫生间灰色、其余青色；
楼栋外轮廓是绿色。**选中的图形会把每个顶点画成橙色小手柄**，方便看清楚在拖哪个角。

**切页签不会重置地图**：地图页**常驻组合树**，切走时只是把它设为不可见并停掉手势。
（早先试过两个做法都不行：把 ViewModel 提到 Activity 作用域只解决了状态重建，
MapView 仍会随页面销毁；换成 `movableContentOf` 也没用，`AndroidView` 在移动时
View 还是会被重建。）

### 多份配置：这个软件不针对某一个学校

配置是「一个学校一份」，都放在同一个目录里：

```
/data/data/<包名>/files/config/
  ├── editor_buildings.json     <- 默认那一份
  ├── 实验中学.json
  └── ...
/sdcard/Android/data/<包名>/files/config/   <- 同一份内容镜像到这里，方便取走
```

在「我的 → 设置 → 配置（一个学校一份）」里可以：

- **切换**：点整行即可，当前生效的那份用蓝框 + 「使用中」标出来
- **新建 / 复制 / 改名 / 删除**：复制是「照着改」，比从零画快得多；删除有二次确认
- **换学校**：把别人的 `xxx.json` 拷进上面那个外部目录，点「切换」就生效

「当前用哪一份」记在 SharedPreferences 而不是文件内容里 —— 配置本身应该是自包含的
数据，「这台机器正在看哪一份」是本地状态，混进数据里文件就没法互相拷贝了。
**切换配置会清空撤销栈**，避免「在新学校里撤销，结果退回上一所学校」。

### 关于元素类型

`ElementType` 只有四种：**教室 / 办公室 / 楼梯口 / 卫生间**。

**电梯口（elevator）与入口（entrance）已连同数据一起删除**：

- 电梯口在 MVP 里没有数据来源，导航算法也没真正用到它（跨层换乘只用楼梯）；
- 入口既不能作为导航目标、作为中途转向点也没有路径数据支撑，
  跨楼导航只给楼栋级指引（「先出 A 栋，前往 B 栋」）；
- 保留它们只会让编辑器多两个「画了没用」的选项。

旧数据里若还残留这两种类型，`ElementType.fromRaw` 会**降级成教室**而不是崩溃。

---

## 二、快速开始

### 2.1 环境要求

| 依赖 | 版本 | 本机实际情况 |
| --- | --- | --- |
| JDK | 17 | `C:\Program Files\Java\jdk-17` ✅ |
| Android SDK | Platform 36 + Build-Tools 36.1.0 | `D:\xiangmu\Projram\Android\SDK` ✅ |
| Gradle | 8.13 | 已缓存的发行版 ✅ |
| Android Gradle Plugin | 8.13.2 | ✅ |
| Kotlin | 2.1.20 | 首次构建需联网下载 |

`local.properties` 已经写好本机 SDK 路径；换机器时改这一行或设置 `ANDROID_HOME`：

```properties
sdk.dir=D\:\\xiangmu\\Projram\\Android\\SDK
```

### 2.2 构建与测试

用仓库里的包装脚本（它会处理本机沙箱与代理的坑，见脚本内注释）：

```powershell
# 编译 Debug APK
powershell -File tools/gradle.ps1 assembleDebug

# 跑纯算法单元测试（:core，80 个用例，不需要设备）
powershell -File tools/gradle.ps1 :core:test

# 跑编辑器状态机 / 配置存储 / Key / 定位兜底（:app，99 个用例）
powershell -File tools/gradle.ps1 :app:testDebugUnitTest

# 交付前全量：core 单测 + app 单测 + 构建 APK
powershell -File tools/gradle.ps1 :core:test :app:testDebugUnitTest :app:assembleDebug

# 迭代时只跑相关的（快很多）
powershell -File tools/gradle.ps1 :app:testDebugUnitTest --tests "*MapEditorViewModelTest"
```

生成的 APK（按 ABI 拆分，另有一个 universal）：

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk      42.7 MB
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk    36.4 MB
app/build/outputs/apk/debug/app-universal-debug.apk      55.6 MB
```

安装到设备：

```powershell
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

> **注意**：本机沙箱不允许写入 `%USERPROFILE%\.gradle`，所以 `tools/gradle.ps1` 把
> `GRADLE_USER_HOME` 指到了仓库内的 `.gradle-home/`。在正常开发机上直接
> `./gradlew assembleDebug` 即可，不需要这个脚本。

### 2.3 重新生成楼栋数据

室内元素是规则排布的（南北两排 + 走廊），直接手写几百个经纬度点容易出错。
数据由脚本一次性生成，产物是仓库里的静态 JSON，**App 运行时只读不生成**：

```powershell
node tools/gen-buildings-json.mjs
# 已生成 .../app/src/main/assets/buildings.json
# 楼栋 2 栋 / 楼层 6 层 / 元素 50 个
```

### 2.4 高德地图 / 定位（重要更正）

**之前的 README 写「高德 aar 只在自己仓库分发、网络受限拉不到」，这是错的。**

实测结论：

| 仓库 | 结果 |
| --- | --- |
| `maven.amap.com`（高德官方） | ❌ 连接超时 |
| **Maven Central / 阿里云公共仓库** | ✅ **全套 `com.amap.api` 产物都在** |

可用产物：

| artifactId | 可用版本 | 说明 |
| --- | --- | --- |
| `com.amap.api:3dmap` | 5.0.0 → **10.0.600**（54 个版本） | ✅ 地图编辑器用的就是它，现代包名 `com.amap.api.maps` |
| `com.amap.api:map2d` | 只到 6.0.0 | ❌ 老版 2D SDK，包名 `com.amap.api.maps2d`，已停更，不要用 |
| `com.amap.api:location` | 3.x → 11.3.000 | ✅ 可用 |

已验证 `3dmap-10.0.600.jar`：18.9 MB / 1174 个类，**自带 `arm64-v8a` 与
`armeabi-v7a` 的 `libAMapSDK_MAP_*.so`**。因此只需一句依赖，不需要加高德私有仓库：

```kotlin
implementation("com.amap.api:3dmap:10.0.600")
```

**Key 由用户在「我的 → 设置」里填写**，存本机 SharedPreferences：

- 不写进 `BuildConfig`：Key 打进 APK 可以被反编译出来；
- 高德 Key 与「包名 + 签名 SHA1」绑定，换机器 / 换签名就要换 Key，
  让用户现场填比每次改代码重新打包务实；
- debug 与 release 签名不同，两个都要在高德控制台各自添加，否则报 `INVALID_USER_KEY`。

定位通道仍然是「有 Key 就用高德、没 Key 用系统定位」，见 `AppContainer`。

### 2.5 地点搜索：两个 Key，能力差很多

搜索有**两个后端**，配了哪个用哪个：

| 后端 | 能力 | 前置条件 |
| --- | --- | --- |
| **高德 POI 搜索**（`restapi.amap.com/v3/place/text`） | ✅ **模糊匹配**：少写字、同音字、错字都能找到 | 需要**「Web服务」类型的 Key** |
| Android 内置 `Geocoder` | ⚠️ 只做**精确地址解析**，少一个字或一个同音字就搜不到 | 零配置，但能力弱 |

**强烈建议在「我的 → 设置」里把「高德搜索 Key」也填上** —— 不填就只能享受 Geocoder
那种"必须写全名且不能有同音字"的体验。

**两个 Key 不能互换**：高德的 Key 按「服务平台」区分用途，Android 平台的 Key
不能用于 Web 服务，反之亦然。填错会报 `INVALID_USER_KEY` —— 那不是 Key 失效，是类型不对。
建 Key 时：地图选「Android 平台」（要填包名 + SHA1），搜索选「Web服务」（不用填包名）。

没用高德时的兜底（`Geocoder` 路径）也做了尽力而为的降级重试：
精确搜不到就逐步放宽 —— 去掉末尾的字（中文地名限定语通常在后面，「佛山大学」→「佛山」），
最多砍 3 次；再尝试「主体名 + 常见后缀」（大学 / 学院 / 中学 / 医院 / 地铁站…）。
结果里会标出**「近似匹配「X」」**，让你知道这条是放宽后搜到的。
但**同音字它依然搜不到** —— 那需要拼音/模糊匹配能力，`Geocoder` 没有。

搜索框有 **300ms 防抖**：Web API 有配额、Geocoder 有调用频率限制，
每敲一个字就查一次很容易被限流。

**为什么不用高德 SDK 里的搜索**：3dmap 的 jar 里 `com.amap.api.search` 一个类都没有
（已用工具逐个核对）；独立的搜索 SDK 最高只到 **9.7.1（2017 年）**，太老。
所以走 Web API（自己发 HTTP、自己解析 JSON，不依赖 SDK 版本）。

### 2.6 地图外观

- **卫星底图**：写实影像能看到真实楼顶轮廓，比矢量图容易对着描边界
- **关掉全部高德自带控件**（加减号、比例尺、指南针、定位按钮、室内图开关）：
  它们是高德自己的视觉语言，和本应用「胶囊导航 + 白色圆角卡片 + 蓝绿强调色」摆在一起很割裂。
  缩放用双指，只保留法律要求的 Logo。
- 同时关掉**旋转与倾斜手势** —— 画轮廓时误触旋转会让人非常难受。

---

## 三、工程结构

```
FindClass/
├── core/                        纯 Kotlin（无 Android 依赖，可完整单测）
│   └── src/main/kotlin/com/school/nav/core/
│       ├── model/
│       │   ├── Models.kt        Building / Floor / Element / Position / Target
│       │   └── Geo.kt           射线法多边形判定、米制换算、质心、Haversine
│       ├── floor/
│       │   └── FloorMatcher.kt  气压 -> 相对高度 -> 楼层（滑动平均 + 校准 + 滞后）
│       ├── navigation/
│       │   ├── FloorSorter.kt   PCA 求走廊主轴、排序、左右方位、楼梯口选择
│       │   ├── RouteText.kt     中文文案模板（算法与文案分离）
│       │   └── NavigationEngine.kt  同层 / 跨层 / 跨楼 三种路线组装
│       └── data/
│           └── CampusRepository.kt  解析 JSON、搜索中文名、数据自检
│
├── app/                         Android 层（Compose + 传感器 + DataStore）
│   └── src/main/
│       ├── assets/buildings.json    静态楼栋数据（运行时直接读取）
│       ├── java/com/school/nav/
│       │   ├── MainActivity.kt      单 Activity，请求权限 + 挂载 Compose
│       │   ├── NavApplication.kt    创建依赖容器 + 数据自检
│       │   ├── AppContainer.kt      手写依赖容器（MVP 规模下比 Hilt 轻）
│       │   ├── data/
│       │   │   ├── CampusAssets.kt  读 assets + 当前生效的配置装配仓库
│       │   │   ├── ConfigStore.kt   多份配置文件：列出 / 切换 / 新建 / 复制 / 改名 / 删除
│       │   │   ├── ApiKeyStore.kt   高德 Key（Android 平台 + Web服务）
│       │   │   ├── PoiSearcher.kt   搜索统一入口（高德 Web API 优先，退回 Geocoder）
│       │   │   └── AmapPoiSearcher.kt  高德 Web 服务 POI 搜索
│       │   ├── location/
│       │   │   ├── SystemLocationSource.kt  LocationManager 实现
│       │   │   ├── AmapLocationSource.kt    高德 SDK（反射接入，可选）
│       │   │   ├── BarometricAltimeter.kt   TYPE_PRESSURE -> Flow<Float>
│       │   │   └── FloorEstimator.kt        楼层估算 + 楼栋漂移锁定
│       │   ├── state/
│       │   │   ├── NavViewModel.kt          状态机：手动优先、导航派生重算
│       │   │   ├── MapEditorViewModel.kt    编辑器状态机：绘制 / 编辑形状 / 撤销 / 多配置
│       │   │   ├── NavUiState.kt            UI 状态模型
│       │   │   └── UserPreferences.kt       DataStore 持久化手动修正
│       │   └── ui/
│       │       ├── theme/Theme.kt          与 Demo 一致的配色
│       │       ├── home/AppShell.kt        底部导航 + 三页签 + 设置子页
│       │       ├── home/HomeScreen.kt      首页
│       │       ├── editor/MapEditorScreen.kt  编辑器控件层（浮在地图上的卡片与按钮）
│       │       ├── editor/AmapEditorView.kt   高德地图渲染 + 触摸转发
│       │       ├── settings/SettingsScreen.kt Key 与配置管理
│       │       └── components/             位置卡片、搜索卡片、导航卡片、修改弹层
│       └── res/                         图标（矢量 + 各密度 PNG）、主题、文案
│
├── docs/                        产品文档、技术方案、HTML Demo（原始需求）
└── tools/
    ├── gradle.ps1               构建入口（处理沙箱 / 代理）
    ├── gen-buildings-json.mjs   生成 buildings.json
    └── gen-icons.ps1            生成启动图标
```

依赖方向单一：`app → core`，`core` 不反向依赖 Android。
导航算法因此可以在 JVM 上直接单测，也方便以后抽成 KMP 模块或搬到后端。

---

## 四、关键设计决策

这部分说明技术方案里没写死、但实现时必须定的地方，以及为什么这样做。

### 4.1 「相对高度」的语义与气压计校准

技术方案给了公式 `h = 44330 * (1 - (P / P0) ^ (1 / 5.255))`，但没说 `P0` 从哪来。

**决定：`relative_height_m` 以楼栋地面层为 0**（1 楼 = 0，2 楼 ≈ 4，3 楼 ≈ 8），
并且 `P0` **不硬编码** —— 气压绝对值受天气、空调、密闭程度影响，硬编码必然不准。

实现方式（`FloorMatcher`）：

1. 用户在「修改位置」里选定楼层后，把当时的平滑气压记为该楼层的基准；
2. 之后所有高度都用 `relativeHeightMeters(基准气压, 当前气压, 基准楼层高度)` 推算；
3. 与各楼层 `relative_height_m` 比最近的一层；
4. **加滞后**：切换楼层需要比当前楼层「近」超过 1.5 米，避免两层之间来回跳；
5. 离所有楼层都超过 6 米时**返回 null** —— 电梯井、楼梯间、楼外不硬套一个楼层，
   交给用户手动确认。

这样手机气压计的绝对偏差被消掉，只依赖它的相对分辨率（一层约 0.48 hPa），
比直接用海平面气压可靠得多。

### 4.2 走廊主轴：PCA 求主轴，然后**吸附到主方向**

产品文档要求「计算同层元素的质心，沿走廊主轴方向投影排序」，但没说主轴怎么求。

**决定**：用 PCA（2×2 协方差矩阵主特征向量）自动求主轴，不需要额外录入走廊走向。
**然后必须把主轴吸附到正东西或正南北。**

吸附这一步是实测出来的，不是想当然（`docs/技术方案落地说明.md` 有完整排查记录）：

- 真实数据里「东楼梯口」与北侧教室列在 x 上错开约 4 米，PCA 主轴被带偏约 **6°**；
- 用偏轴算「左手边」，会让**同一侧**的两间相邻教室产生约 1 米的假横向差，
  于是「目标数学教研室在左手边」被误判成「就在正前方」；
- 吸附成 `±(1,0)` / `±(0,1)` 后，`leftVector` 变成纯净的北向或东向，判定恢复正常。

排序键还需要**确定性次级键**（投影坐标 → 北侧优先 → 自西向东 → id）：
走廊同一经度上完全可能有多个元素（示例数据里「卫生间」就与南侧教室同经度），
没有次级键时相等键的相对顺序不可复现，导航文案就会不稳定。
投影坐标还要先量化到 1e-6 米 —— 实测浮点噪声在 1.45e-9 米量级，
不量化的话它会一直把次级键盖掉。

### 4.3 左右方位：看「目标在走廊哪一侧」

**决定**：不用「目标相对当前位置的横向分量」，而是看**目标自身落在走廊的哪一侧**。

理由：同一间教室，从东边走过去还是从西边走过去，它都在走道的**同一侧**。
沿走廊前进时，北侧始终是左手边、南侧始终是右手边 —— 这与人的直觉一致，
也不会因为当前位置选在哪而给出互相矛盾的指引。

两侧不同（一个在北一个在南）时，按目标的实际侧给左右；两侧相同时同样取目标那一侧。

### 4.4 导航方向：用走廊主轴，而不是两点连线

同层文案里的「向东走 / 向西走」取**走廊主轴方向**（排序正序为 `direction`，倒序取反向），
不是当前元素到目标元素的精确方位角。

原因：两间教室一南一北错开几米时，两点连线的方位角会算出「向东北走」这种不自然的说法。
走廊是一条线，用户沿它走，「向东」永远是向东。

### 4.5 楼栋漂移：保持最后锁定

室内 GPS 必然漂移。`BuildingLocator` 的规则：

- 点落在某栋楼多边形内 → 锁定该栋；
- 已锁定时，位移小于 12 米的抖动**保持原楼栋**，不跳；
- 已锁定但明显走远且落在楼外时，只有**另一栋楼更近**才换；
- 尚未锁定且点在楼外时，允许 60 米内**就近吸附**，避免「第一次定位差一点点就没结果」。

### 4.6 楼层判断的取舍

`FloorEstimator` 区分四种结果，让 UI 能说清「为什么」：

| 结果 | 含义 | UI 表现 |
| --- | --- | --- |
| `Known` | 判断出楼层 | 正常显示 `A栋 · 3楼 · 语文教研室` |
| `Stabilizing` | 样本不够，正在稳定 | 「正在读取气压计判断楼层…」 |
| `SensorMissing` | 设备没有气压计 | 「本机没有气压计，请在修改位置里选择楼层」 |
| `Unreliable` | 气压不可信 | 「气压数据不太准，建议手动确认楼层」 |

**楼层未知时 `floor = null`，不猜。** 楼层未知时导航不可用，提示用户手动选择——
这比给一个错误楼层更有价值。

### 4.7 搜索策略

`CampusRepository.search` 的分级匹配（从强到弱）：

1. 完全相等（`物理实验室`）
2. 以输入开头 / 结尾（`物理`）
3. 包含输入（`实验室`）
4. 输入包含目标名（`物理实验室在哪`）

同分时优先「离用户更近」：同楼栋 +4、同楼层 +3、按距离最多 +3。
**入口（`entrance`）不作为导航目标**，避免出现「导航到南门」这种没意义的指令。

---

## 五、数据结构

`app/src/main/assets/buildings.json`（坐标为 **GCJ-02**，全工程统一）：

```json
{
  "buildings": [
    {
      "id": "A",
      "name": "A栋",
      "polygon": [ { "lng": 113.1230874, "lat": 23.1232915 }, ... ],
      "floors": [
        {
          "id": "A_1F",
          "level": 1,
          "relative_height_m": 0,
          "elements": [
            {
              "id": "A_1F_e000",
              "type": "room",
              "name": "语文教研室",
              "points": [ { "lng": ..., "lat": ... }, ... ]
            }
          ]
        }
      ]
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `type` | `room` 教室 / `office` 办公室 / `stair` 楼梯口 / `toilet` 卫生间（**只有这四种**） |
| `relative_height_m` | **相对地面层高度**，首层必须为 0，逐层递增 |
| `points` | 统一用点串表示。楼栋是多边形；教室 / 办公室是矩形或圆（圆存成 32 边多边形）；楼梯口 / 卫生间是**单个点** |
| `to_level` | 仅跨层元素（楼梯口）有：结束楼层。合并进导航数据时会在覆盖的每一层都生成一份同名楼梯 |
| `id` | 仅内部关联，**任何导航文案都不出现 id** |
| `name` | 展示与导航文案唯一使用的字段 |

内置数据：**A栋**（1F 教研室层 / 2F 实验室层 / 3F 高一年级层）、**B栋**（1F 报告厅层 / 2F 图书馆层 / 3F 机房层），共 6 层 50 个元素。

`CampusRepository.validate()` 会检查：多边形点数、楼层高度递增、首层为 0、
id 唯一、元素有中文名与几何点。App 启动时自检并把问题打到 logcat（不崩溃，
「能用手动模式走通」比「启动即报错」更有用）。

---

## 六、页面与交互

对应 `docs/Demo.html` 的布局：

```
        校园教学楼导航
  GPS 判楼栋 · 气压计判楼层 · 文字导航

┌────────────────────────────────┐
│ ● 当前位置                      │
│ A栋 · 3楼 · 语文教研室           │
│ [修改位置]      [重新定位]       │
└────────────────────────────────┘

┌────────────────────────────────┐
│ ● 要去哪里                      │
│ [输入教室名，如 物理实验室] [导航] │
│ 快捷目标                        │
│ (物理实验室) (教务处) (图书馆) …  │
└────────────────────────────────┘

┌────────────────────────────────┐
│ ● 导航指令                      │
│ 先沿走廊向东走，经过计算机房，    │
│ 到东楼梯口，上到 3 楼。          │
│ 出楼梯后，沿走廊向东走，经过…    │
│ 目标教务处就在正前方。           │
└────────────────────────────────┘
```

- **修改位置**：底部弹层，楼栋 / 楼层 / 当前位置三级联动下拉，确定后立即重算导航
- **重新定位**：清空手动修正与气压基准，重新采用 GPS 与气压计结果（唯一会覆盖手动修改的动作）
- **toast**：位置已更新 / 已重新定位 / 没找到这个位置
- **导航结果是由 `(当前位置, 目标)` 纯函数派生的状态**，所以任何一方变化都会**自动重算**——
  这就是验收标准「用户改完位置后，导航立即重算」的实现方式，不需要手工触发刷新

---

## 七、已知问题

### 7.1 曾经踩过、已经修好并复跑通过的坑

这些都是**已经修掉并且在完整构建里跑绿**的，写在这里是为了以后别再踩一遍：

| 症状 | 根因 | 修复 |
| --- | --- | --- |
| 走廊同经度上多个元素顺序不可复现 | PCA 主轴被真实数据带偏约 6°，加上浮点噪声（1.45e-9 m）盖住了次级键 | `FloorSorter` 先把主轴**吸附到正东西 / 正南北**，再把投影坐标量化到 1e-6 m，最后用显式 `Comparator`：投影 → 北侧优先 → 自西向东 → id |
| 方位说反（左右颠倒） | `atan2` 两个参数写反，方位角整体差 90° | 改成 `atan2(uy, ux)` |
| 方向是反的（往西走却说往东） | 纬度比较写反（`b.lat.compareTo(a.lat)`） | 改成北侧优先的比较顺序 |
| 撤不掉「已经写进配置」的楼栋 | 旧实现只记「本次新画的点」 | 改成整份 `List<EditorBuilding>` 快照栈，不区分来源；一次拖拽用 `ShapeDrag` 合并成一步 |
| 楼梯口、厕所添加后看不见 | 点元素只画在 Marker 上，透明度和层级都不受控 | 点元素改用 `CircleOptions` 画实心圆（半径 6 m），必可见 |
| 切页签地图还是刷新 | ViewModel 作用域、`movableContentOf` 都救不了 MapView | 地图**常驻组合树**，`active=false` 时 `MapView` 自己设 `INVISIBLE` 并解除手势 |
| 搜索栏被状态栏盖住 / 搜索不到东西 | 没做 `windowInsetsPadding`；Geocoder 只做精确匹配 | 顶部避让状态栏；搜索改走高德 Web 服务 POI（可模糊），Geocoder 只作兜底 |
| 定位中途被关掉时永远停在「定位中…」 | `locationUpdates()` 返回空流，`isLocating` 无人置回 | 流类型改为 `Flow<LocationUpdate>`，显式承载 `Unavailable(reason)` |
| 设置页里 `list()` 直接把 App 打崩（栈溢出） | `list()` 和 `activeFileName()` 互相调用 | `list()` 只读 SharedPreferences 里记的原始名字，不再走带回退的 `activeFileName()` |
| 把配置改名成同一个名字，结果多出一份 `xxx-2.json` | 先查重再判断同名，顺序反了 | `rename` 先比显示名，相同就直接返回；`sanitize` 顺手吃掉 `.json` 后缀 |

### 7.2 `No target device found`：没有可用设备

在 Android Studio 点运行报 `No target device found`，**不是代码或构建问题** ——
APK 已经构建成功，只是没有设备可以安装。本机实测：

| 检查项 | 结果 |
| --- | --- |
| `adb devices` | 空 —— 没有连接真机 |
| AVD 列表 | 空 —— 没有任何模拟器 |
| `system-images` | 未安装（模拟器镜像缺失） |
| `cmdline-tools` / `sdkmanager` | 未安装 |
| SDK 目录可写性 | **只读**，装镜像需要提权 |

两条出路：

**A. 用真机（推荐，且是唯一能验证定位与楼层的办法）**

1. 手机：设置 → 关于手机 → 连点「版本号」7 次 → 开发者选项 → 打开「USB 调试」；
   小米/OPPO/vivo 还需打开「USB 安装」
2. USB 连接后，手机弹「允许 USB 调试」→ 允许
3. 确认已识别（状态必须是 `device`，不能是 `unauthorized`）：

   ```powershell
   D:\xiangmu\Projram\Android\SDK\platform-tools\adb.exe devices -l
   ```

4. 安装并启动：

   ```powershell
   D:\xiangmu\Projram\Android\SDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
   ```

**B. 装模拟器（约 1.5 GB 下载，只能验证界面与算法）**

需要先装 `cmdline-tools` 拿到 `sdkmanager`，再装 system-image 并创建 AVD；
因为 SDK 目录在工作区之外且只读，这一步需要放宽文件沙箱权限。

**模拟器的固有限制**：模拟器**没有气压计** → 楼层判断只能走 `SensorMissing`
手动兜底分支；GPS 要在模拟器里手动 set location 才能测楼栋判定。
所以模拟器验证不了「高度判楼层」这条核心能力。

### 7.3 未做真机验证

以下只能上真机确认，本机无设备：

- GPS 取点与楼栋多边形命中（尤其 Android 12+ 只给「大致位置」时）
- 气压计楼层判断的实测精度与滞后参数（`FloorMatcherConfig` 的窗口 8、阈值 1.5 米、
  最大偏差 6 米都是估值，需要真机调）
- 各厂商 ROM 的后台限制与权限弹窗差异
- Compose UI 测试（`app/src/androidTest/HomeScreenTest.kt`）需要在模拟器/真机上运行
- **地图本身的渲染**：高德 `MapView` 是否正常出图、卫星底图能不能加载、POI 搜索
  Key 是否配对了「Web服务」平台 —— 这些都需要有效 Key + 真机
- **编辑手感**：拖拽框选的容差（`SELECT_TOLERANCE_M` 8 m / `VERTEX_HIT_M` 12 m）、
  旋转 15° 是否够用、点元素 6 m 的圆点在大屏手机上是不是太小
- **切页签后相机是否真的没变**、顶部控件有没有被状态栏或挖孔挡住

### 7.4 有意偏离技术方案的地方

| 技术方案 / 产品文档原文 | 实际实现 | 原因 |
| --- | --- | --- |
| 高德定位 SDK | 默认系统定位，高德走**反射可选接入** | 高德 aar 只在自己仓库分发，网络受限环境拉不到就把整个工程卡死。抽成 `LocationSource` 接口后换实现不影响任何业务代码 |
| 高德隐私合规弹窗 | 未做首启同意弹窗 | 上架前必须补（见下） |
| `kotlinx.serialization` 或 Moshi | 用 `kotlinx.serialization` | 与技术方案首选项一致，`relative_height_m` 用 `@SerialName` 映射 |
| 多模块 `core-model` / `core-navigation` / `core-location` | 单 `core` 模块 + 分层包 | 技术方案说「MVP 可先单模块」，代码量还没到需要拆的程度 |
| 产品文档把**地图绘制编辑器**列为「不做」 | **做了**，并且页签常驻入口 | 用户明确要求：没有编辑器就得手写 JSON 录数据，实际不可用 |
| 产品文档把**平面图 / 自动生成数据**列为「不做」 | 做的是「在高德卫星底图上画轮廓 + 自动导入」，不是自绘平面图 | 用真实底图比自绘平面图省一个数据源，也更符合「教学楼本来就真实存在」 |
| 编辑器只有「矩形 / 点」两类图形 | 增加了**圆形区域**与**编辑形状**（拖顶点 / 插点 / 删点 / 旋转） | 用户反馈：画歪了只能重画、楼梯口厕所添加后看不见、没有 PS 那种改图形的能力 |

### 7.5 上架前必须补

- 首次启动的**隐私政策同意**（高德 SDK 合规要求，也是国内商店硬性要求）
- 隐私政策、权限说明、SDK 清单
- 正式签名（`assembleRelease` / `bundleRelease`）

---

## 八、验收对照

`docs/产品设计文档.txt` 第十一节 + `docs/技术方案.txt` 第十五节：

| 验收标准 | 实现 | 自动化验证 |
| --- | --- | --- |
| 进入 A 栋能自动识别楼栋 | `Geo.containsPoint` 射线法 + `BuildingLocator` | `GeoTest`、`CampusRepositoryTest` |
| 上到 3 楼能估算楼层 | `FloorMatcher` 气压 → 相对高度 → 最近楼层 | `FloorMatcherTest`（13 例） |
| 默认当前位置基本合理，错了能手动改 | `nearestStandableElement` + 三级修改弹层 | `CampusRepositoryTest`、`HomeScreenTest` |
| 搜「物理实验室」能匹配到目标 | `CampusRepository.search` 分级匹配 | `CampusRepositoryTest`、`BuildingsDataTest` |
| 同层导航能列出中间经过的中文名 | `RouteText.sameFloor` | `NavigationEngineTest` |
| 跨层导航能提示先上/下到几楼 | `RouteText.verticalTransition` | `NavigationEngineTest` |
| 跨楼导航能提示先出楼、前往目标楼栋 | `RouteText.crossBuilding` | `NavigationEngineTest` |
| 全程不用米数、不用教室编号 | 文案模板 + 正则断言 | `NavigationEngineTest`（含 `\d+\s*(m\|M\|米)` 反向断言） |
| 用户改完位置后导航立即重算 | 导航是 `(位置, 目标)` 的派生状态 | 结构保证 |
| 无气压计或定位失败时仍可手动完成导航 | `floor = null` 分支 + 手动弹层 | `FloorMatcherTest`、`HomeScreenTest` |
| （自加）不针对单一学校，能换配置 | `ConfigStore` 多文件 + 设置页配置管理 | `ConfigStoreTest`、`MapEditorViewModelTest` |
| （自加）撤销能覆盖已写进配置的数据 | 整份数据快照栈 | `MapEditorViewModelTest` |
| （自加）点元素一定可见、图形可改而不是只能重画 | `CircleOptions` 点标记 + 编辑形状模式 | `MapEditorViewModelTest` |

---

## 九、后续扩展

技术方案第十四节提到的方向，以及本工程为它们留的位置：

| 扩展 | 已留的接口 |
| --- | --- |
| 可视化编辑器 / 平面图 | 数据模型已用「经纬度点串」统一表示所有图形，编辑器只需要生成同样的 JSON |
| 教室编码（a 教室 / b 办公室 / k 楼梯口） | `elementType` 已经是结构化的枚举，加一层编码映射即可；文案层不需要改 |
| 众包纠错 | `CampusRepository.validate()` 可复用作数据校验入口 |
| 蓝牙 / WiFi 辅助定位 | 换 `LocationSource` 实现即可 |
| 课表导入、下一节课一键导航 | `NavigationEngine.route(Position, Target)` 已经是纯函数，传入目标即可 |
| 后端 + PostGIS | `CampusRepository` 只依赖一段 JSON 字符串，把数据源换成网络请求不影响上层 |

---

## 十、文档索引

| 文件 | 内容 |
| --- | --- |
| `docs/产品设计文档.txt` | 原始产品需求（MVP 范围、验收标准） |
| `docs/技术方案.txt` | 原始技术方案 |
| `docs/Demo.html` | 原始交互 Demo |
| `docs/技术方案落地说明.md` | **本工程**：技术方案 → 代码的映射、算法细节、排查记录 |
| `README.md` | 本文件 |
