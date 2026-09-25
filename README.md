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
| Debug APK 构建 | ✅ 成功（`app/build/outputs/apk/debug/app-debug.apk`，约 20.9 MB） |
| `:app` 编译（Compose + Kotlin） | ✅ 通过 |
| `:core` 单元测试 | ⚠️ **63 个用例，3 个断言待复跑验证**（详见第七节） |
| 定位不可用兜底修复 | ⚠️ 已实现 + 已补测试，**未编译验证**（详见 7.2） |
| 真机验证（GPS / 气压计 / 权限） | ❌ 未做（本机无设备，见 7.3） |

> ⚠️ 请先看**第七节「已知问题」**，那里写清了剩下的 3 个断言、未编译验证的改动，以及为什么 `No target device found`。

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

# 跑纯算法单元测试
powershell -File tools/gradle.ps1 :core:test

# 校验 assets/buildings.json 的数据自检与验收场景
powershell -File tools/gradle.ps1 :app:testDebugUnitTest

# 全部一起
powershell -File tools/gradle.ps1 :core:test :app:assembleDebug
```

生成的 APK：

```
app/build/outputs/apk/debug/app-debug.apk
```

安装到设备：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
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
# 楼栋 2 栋 / 楼层 6 层 / 元素 58 个
```

### 2.4 接入高德定位（可选）

MVP 默认走 **Android 系统定位**（`LocationManager`），不引第三方 SDK 也能完整跑通导航。
要换成技术方案指定的高德定位：

1. 在 `local.properties` 填入 Key：

   ```properties
   amap.key=你的高德Key
   ```

2. 在 `app/build.gradle.kts` 里加高德仓库与依赖（**只需加依赖，业务代码不用改**）：

   ```kotlin
   repositories {
       maven { url = uri("https://maven.aliyun.com/repository/public") }
   }
   dependencies {
       implementation("com.amap.api:location:6.4.5")
   }
   ```

`AmapLocationSource` 是用**反射**写的，检测到 SDK 存在且 Key 非空就自动启用
（`BuildConfig.USE_AMAP`），否则自动落回系统定位。高德 SDK 的隐私合规声明
（`updatePrivacyShow` / `updatePrivacyAgree`）已在反射初始化里调用。

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
│       │   ├── data/CampusAssets.kt 读 assets
│       │   ├── location/
│       │   │   ├── SystemLocationSource.kt  LocationManager 实现
│       │   │   ├── AmapLocationSource.kt    高德 SDK（反射接入，可选）
│       │   │   ├── BarometricAltimeter.kt   TYPE_PRESSURE -> Flow<Float>
│       │   │   └── FloorEstimator.kt        楼层估算 + 楼栋漂移锁定
│       │   ├── state/
│       │   │   ├── NavViewModel.kt  状态机：手动优先、导航派生重算
│       │   │   ├── NavUiState.kt    UI 状态模型
│       │   │   └── UserPreferences.kt  DataStore 持久化手动修正
│       │   └── ui/
│       │       ├── theme/Theme.kt        与 Demo 一致的配色
│       │       ├── home/HomeScreen.kt    首页
│       │       └── components/           位置卡片、搜索卡片、导航卡片、修改弹层
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
走廊同一经度上可能同时有教室和电梯口，没有次级键时相等键的相对顺序不可复现，
导航文案就会不稳定。

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
| `type` | `room` 教室 / `office` 办公室 / `stair` 楼梯口 / `elevator` 电梯口 / `entrance` 入口 / `toilet` 卫生间 |
| `relative_height_m` | **相对地面层高度**，首层必须为 0，逐层递增 |
| `points` | 统一用点串表示。楼栋是多边形，教室/楼梯/电梯也是点串（MVP 不做平面图渲染） |
| `id` | 仅内部关联，**任何导航文案都不出现 id** |
| `name` | 展示与导航文案唯一使用的字段 |

内置数据：**A栋**（1F 教研室层 / 2F 实验室层 / 3F 高一年级层）、**B栋**（1F 报告厅层 / 2F 图书馆层 / 3F 机房层），共 6 层 58 个元素。

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

### 7.1 剩余 3 个单元测试断言（已修，待复跑）

上一轮完整构建的结果是：**`:app:assembleDebug` 成功产出 APK**，
`:core:test` 共 63 个用例，**3 个断言未过**。三者都已定位并修好，但**尚未重新编译验证**：

| 用例 | 原因 | 修复 |
| --- | --- | --- |
| `按走廊主轴自西向东排序` | 走廊同一经度上同时有「电梯口」和「历史教研室」，缺少确定性次级键，顺序不可复现 | `FloorSorter.sort` 排序键补上「投影坐标 → 北侧优先 → 自西向东 → id」 |
| `南北走向的走廊给出确定顺序` | 与上同源：退化分支同样需要次级键兜底 | 同上（该用例期望 `[南一, 北一, 电梯]`） |
| `向西走时仍按目标所在的走廊侧给方位` | 测试期望字符串里少了一个逗号（`多媒体教室` 后缺 `，`） | 修正断言字符串 |

复跑命令：

```powershell
powershell -File tools/gradle.ps1 :core:test
```

如果还有失败，`core/build/reports/tests/test/index.html` 有完整报告；
断言都用 `assertEquals` 并带上实际文案，失败信息里能直接看到差异。

### 7.2 定位不可用兜底缺陷：已修，未编译验证

**症状**：如果用户是在 App 运行中途去系统设置关掉定位服务，界面会**永远停在「定位中…」**，
既不说明原因，也看不到「请手动选择位置」的提示。

**原因**：`observeLocation()` 早期只在订阅**前**检查一次 `locationSource.isAvailable()`。
初始有权限时检查通过，之后服务被关闭，`locationUpdates()` 返回空流、collect 立刻结束，
而 `isLocating` 再没有任何地方把它置回 `false`。

**修复**（3 处）：

1. `LocationSource.locationUpdates()` 的返回类型从 `Flow<RawLocationFix>` 改为
   `Flow<LocationUpdate>`，流里显式承载 `Fix` 或 `Unavailable(reason)` ——
   空流无法表达「为什么取不到点」；
2. 新增 `LocationAvailability` 枚举区分 `PermissionDenied` / `ServiceDisabled` /
   `NoProvider`，UI 能给出不同提示；
3. `NavViewModel` 收到 `Unavailable` 或流结束时**必定**把 `isLocating` 置回 `false`，
   并通过新增的 `NavUiState.locationUnavailable` 把原因送到位置卡片；
   `relocate()` 在「之前判定过不可用」时会重新订阅（否则用户去系统里打开定位后点
   「重新定位」永远恢复不了）。

**顺带做的可测试性改造**：抽出 `PressureSource` 与 `ManualPositionStore` 两个接口，
让 `NavViewModel` 的单测不必拉起 `SensorManager` 和 DataStore。

**新增测试**：`app/src/test/java/com/school/nav/state/NavViewModelLocationFallbackTest.kt`
（6 个用例），覆盖「不卡 loading」「原因到达界面文案」「定位不可用时仍能手动完成导航」
「定位恢复后重新锁定楼栋」。

**⚠️ 这些改动尚未编译验证。** 复跑命令：

```powershell
powershell -File tools/gradle.ps1 :app:testDebugUnitTest
```

### 7.3 `No target device found`：没有可用设备

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

### 7.4 未做真机验证

以下只能上真机确认，本机无设备：

- GPS 取点与楼栋多边形命中（尤其 Android 12+ 只给「大致位置」时）
- 气压计楼层判断的实测精度与滞后参数（`FloorMatcherConfig` 的窗口 8、阈值 1.5 米、
  最大偏差 6 米都是估值，需要真机调）
- 各厂商 ROM 的后台限制与权限弹窗差异
- Compose UI 测试（`app/src/androidTest/HomeScreenTest.kt`）需要在模拟器/真机上运行

### 7.5 有意偏离技术方案的地方

| 技术方案原文 | 实际实现 | 原因 |
| --- | --- | --- |
| 高德定位 SDK | 默认系统定位，高德走**反射可选接入** | 高德 aar 只在自己仓库分发，网络受限环境拉不到就把整个工程卡死。抽成 `LocationSource` 接口后换实现不影响任何业务代码 |
| 高德隐私合规弹窗 | 未做首启同意弹窗 | 上架前必须补（见下） |
| `kotlinx.serialization` 或 Moshi | 用 `kotlinx.serialization` | 与技术方案首选项一致，`relative_height_m` 用 `@SerialName` 映射 |
| 多模块 `core-model` / `core-navigation` / `core-location` | 单 `core` 模块 + 分层包 | 技术方案说「MVP 可先单模块」，代码量还没到需要拆的程度 |

### 7.6 上架前必须补

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
