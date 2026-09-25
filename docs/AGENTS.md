# AGENTS.md · 协作注意事项

本文件是给参与本仓库的开发者 / AI 代理的硬性约束，优先级高于个人习惯。

> 注意事项
> 每次改动完成后，都必须创建一个对应的 Git commit，以便后续追踪和回滚。每次改动后，都必须编写或更新相关测试，并在交付给用户前，确保所有测试和验证全部通过。

---

## 一、每次改动都必须提交 commit

**规则：改完就提交，不要攒。**

- 一个 commit 只做一件事（一个功能 / 一个修复 / 一次重构）。不要在一个 commit 里
  混入无关的格式化或顺手改动。
- commit message 用中文写清楚**做了什么**和**为什么**，格式：

  ```
  <类型>: <一句话说明>

  <可选：为什么这么改、有什么取舍>
  ```

  类型用：`feat` / `fix` / `refactor` / `test` / `docs` / `build` / `chore`。

  示例：

  ```
  fix: 定位不可用时不再卡在「定位中…」

  observeLocation 只在订阅前检查一次 isAvailable，用户中途关掉定位服务后
  流会结束而 isLocating 永远为 true。改为让流承载 Unavailable 事件，
  并在流结束时兜底复查可用性。
  ```

- **不要提交**：`build/`、`.gradle-home/`、`local.properties`（含本机 SDK 路径与高德 Key）、
  `.tmp/`、`*.apk`。这些已在 `.gitignore` 里，提交前用 `git status` 确认一遍。

## 二、每次改动都必须有测试

**规则：没有测试的改动不算完成。**

- 新增或修改算法、状态机、文案生成逻辑 → 必须补 `:core:test` 或 `:app:testDebugUnitTest` 用例。
- 修复 bug → **必须补一个能复现该 bug 的用例**。先让它失败，再修代码让它通过。
  这类用例要写清"回归的是什么问题"，例如 `NavViewModelLocationFallbackTest` 的类注释。
- 改动纯 UI 布局 → 至少更新 `app/src/androidTest/` 下的 Compose 测试标签与断言。
- 测试要断言**行为**而不是实现细节。比如导航文案测试直接断言完整中文句子，
  这样文案写错会立刻被发现。

## 三、验证策略：迭代跑子集，交付跑全量

**规则：不要每次改动都跑完整验证（太慢）。**

### 迭代过程中：只跑相关子集

改哪儿跑哪儿，用 `--tests` 只跑相关用例：

```powershell
# 只跑单个测试类（最快，改算法时用这个）
powershell -File tools/gradle.ps1 :core:test --tests "com.school.nav.core.FloorSorterTest"

# 只跑一个模块
powershell -File tools/gradle.ps1 :core:test

# 只编译不跑测试（改 UI 时够用，比跑测试快很多）
powershell -File tools/gradle.ps1 :app:compileDebugKotlin
```

对照表：

| 改动位置 | 迭代时跑什么 |
| --- | --- |
| `core/.../navigation/` | `:core:test --tests "*NavigationEngineTest" --tests "*FloorSorterTest"` |
| `core/.../floor/` | `:core:test --tests "*FloorMatcherTest"` |
| `core/.../model/Geo.kt` | `:core:test --tests "*GeoTest"` |
| `app/.../state/` | `:app:testDebugUnitTest` |
| `app/.../ui/` | `:app:compileDebugKotlin`（Compose 交互改动再补 androidTest） |
| `app/src/main/assets/` | `:app:testDebugUnitTest --tests "*BuildingsDataTest"` |

### 交付前：必须跑全量且全绿

```powershell
# 一次跑完：core 单测 + app 单测 + 构建 APK
powershell -File tools/gradle.ps1 :core:test :app:testDebugUnitTest :app:assembleDebug
```

- **交付标准是全绿**：任何一条失败都不许交付，也不许"先提交、回头再修"。
- 迭代中间态允许有失败用例（正在修的 bug），但**不能把失败状态当作交付**。
- 测试报告：`core/build/reports/tests/test/index.html`、
  `app/build/reports/tests/testDebugUnitTest/index.html`。
- 跑全量很慢（约 3~5 分钟），且本机 Gradle 守护进程偶发崩溃，
  所以只在里程碑和交付时跑；平时用上面的子集命令。
- 没法在当前环境验证的部分（真机 GPS、气压计、厂商 ROM 差异）要在交付说明里
  **明确写出来**，不要含糊带过。

## 四、其他约定

- 依赖方向：`app → core`。`core` 是纯 Kotlin，**不许**引入任何 `android.*` 依赖，
  否则 63 个 JVM 单测就跑不了了。
- 文案硬约束（来自产品文档，不许破坏）：导航文案**不出现米数、不出现教室编号**，
  只用中文名与"左手边/右手边/正前方/隔壁"这类相对描述。
  改动文案后跑 `NavigationEngineTest`，里面有对应断言。
- 数据文件 `app/src/main/assets/buildings.json` 由 `tools/gen-buildings-json.mjs` 生成，
  手改这个 JSON 会被下次生成覆盖；要改楼栋布局请改脚本再重新生成。
- 编程/文档注释用中文，与现有代码保持一致。
