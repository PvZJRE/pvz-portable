# PvZ-Portable (Java)

[PvZ-Portable](https://github.com/wszqkzqk/PvZ-Portable)——社区以 C++20 / SDL2 / OpenGL ES 2.0 重写的《植物大战僵尸》年度版——的 **Java 桌面移植版**，运行在**真正的 [Fabric Loader](https://github.com/FabricMC/fabric-loader)**（含 Sponge Mixin）上：`fabric.mod.json`、`ModInitializer` 入口点、`mods/` 目录、Mixin，以及在游戏挂钩点触发的事件总线。

当前版本 **1.2.5**，Maven 坐标 `io.github.pvzjre:pvz-portable:1.2.5`。它**不**跟随上游 C++ 项目的发布标签。移植分三层，与上游对应：引擎（`sexy/`，相当于 SexyAppFramework）、游戏（`lawn/`，相当于 `src/Lawn/`）、可移植运行时（`pvzp/`，相当于 `PvzpLib`）。窗口/渲染/输入用 **LWJGL 3.4.3 的 `lwjgl-sdl`（SDL3）**，音频用 OpenAL + stb_vorbis（MO3 经 libopenmpt）。

游戏代码本身**不依赖 Fabric**：Fabric Loader 只是启动器，在 loader 眼中本游戏是一个 mod id 为 `pvz-portable` 的内置模组。

## 特性

- **完整移植**：上游全部可运行的模式、界面与子系统（见[移植状态](#移植状态)），含禅境花园、Zombatar、全部挑战模式、Crazy Dave 商店过场。
- **Fabric 模组生态**：游戏自身无注册表、无模组概念——内容（自定义植物/僵尸）、事件、资源覆盖全部由 `fabric-api`（五个功能模块）以 Mixin 和事件总线提供；配套 ModMenu、示例模组与修改器模组，均为独立仓库（见[相关项目](#相关项目)）。
- **修改器模组（Trainer Mod）**：游戏内全功能修改器（阳光/免种植/关卡控制/僵尸与植物操作/隐藏内容开关等），带 ModMenu 集成面板与配置入口。
- **单文件 exe 启动器**：`PlantsVsZombies.exe` 内嵌游戏 jar 与版本信息/图标，自动定位本机 Java 25（缺失时提示并跳转下载），双击即玩。
- **全局自定义光标**：游戏目录放置 `cursor.cur` 即替换全部游戏光标。
- **隐藏功能启动参数**：`-debug` / `-trial` / `-demo` / `-cheat`（见[启动参数](#启动参数)）。
- **隐码**：`future`、`mustache`、`trickedout`、`pinata`、`dance`、`daisies`、`sukhbir` 之外另有 16 个新增隐码（无敌/秒杀/高速/解锁全部等，`-debug` 下生效）。

## 游戏资源

游戏需要零售版资源（`main.pak` + `properties/` 目录），按以下顺序查找，命中即止：

1. `-resdir <path>` 游戏参数
2. `-Dpvz.resDir=<path>` JVM 参数
3. `PVZ_RES_DIR` 环境变量
4. 工作目录
5. 运行中的 jar/可执行文件所在目录
6. 本机 **Steam** 安装（App ID 3590，遍历每个库目录，含 macOS 的 app bundle 布局）

**本仓库不附带任何游戏资源**——《植物大战僵尸》版权归 PopCap/EA 所有。

存档写入 `<资源目录>/saves`（任何启动方式共用；旧版目录 `savedata` 在更新后的首次启动时自动移入 `saves`）；`-savedir <path>` 可覆盖。设置注册表 `registry.regemu` 在 `<资源目录>/config/`。运行日志在游戏目录 `logs/latest.log`；未捕获异常产出 `crash-reports/` 下的 MC 形状崩溃报告。

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **25** | 工具链锁定，`--release 25` 编译 |
| Gradle | **9.8.1** | wrapper 锁定 |
| 平台绑定 | **LWJGL 3.4.3**（`lwjgl-sdl` 等） | 只有 3.4.x 提供 SDL3 绑定 |
| 音频（可选） | `libopenmpt` | 仅 MO3 音乐需要；发行包内置 Windows 版，其余平台用系统安装 |
| Kotlin | 2.4.21 | **仅模组需要**（运行时需 `fabric-language-kotlin`） |

`gradle.properties` 设置 `org.gradle.java.installations.auto-download=false`，JDK 25 需已安装。

## 构建与运行

```bash
./gradlew build                # 编译 + 全量测试套件
./gradlew run                  # 在 Fabric Loader 上启动游戏（游戏目录 run/）
./gradlew test --tests 'io.github.pvzjre.portable.lawn.BoardTest'   # 单个测试类
./gradlew publishToMavenLocal  # 发布 io.github.pvzjre:pvz-portable
```

首次构建需要姊妹构件就位（全部以 mavenLocal 坐标互相消费，按依赖顺序发布）：

```bash
./gradlew publishToMavenLocal -x test      # 本项目（API 要针对它编译）
cd ../fabric-api && ./gradlew publishToMavenLocal
cd ../pvz-example-mod && ./gradlew publishToMavenLocal
cd ../PvZ-Portable && ./gradlew build      # 现在可以完整构建和测试了
```

`./gradlew run` 的工作目录是 `run/`：`run/mods/` 是 loader 读模组的位置（与正式安装一致），`run/logs/` 是日志。开发运行带 `-Dfabric.development=true`，类路径上的模组也会被发现。

## 发行与部署

| 任务 | 产物 |
|---|---|
| `./gradlew distZip` | `pvz-portable-1.2.5-offline.zip`——一份通吃所有平台的应用式发行包（`bin/` 启动脚本 + `lib/` 全部依赖与 36 个 natives jar） |
| `./gradlew mcVersionZip` | `pvz-portable-1.2.5-online.zip`——Minecraft 启动器风格版本包（jar + 版本 json + natives） |
| `./gradlew installMcVersion` | 把版本包装进 `.minecraft/versions/PvZ-Portable-1.2.5/`（PCL/HMCL 可直接启动；`mods/`、存档、config 在重装间保留） |
| `./gradlew installExeDist` | 构建单文件启动器并部署到项目 `dist/`（见下） |
| `./gradlew deployAll` | 以上全部：MC 版本文件夹 + dist exe 一次到位 |

**`PlantsVsZombies.exe`**（`installExeDist`）：单文件启动器，内嵌游戏 jar 与版本信息/图标，不需要捆绑运行时——启动时自动定位本机 Java 25（JAVA_HOME、注册表、常见安装目录，逐个校验版本），缺失或低于 25 时弹提示并跳转 Adoptium 下载页。游戏 jar 首次启动解压到 `%LOCALAPPDATA%\PvZ-Portable\`。部署不含 `main.pak`：把它放进 exe 旁边即可游玩。参数透传（`PlantsVsZombies.exe -debug`）。

**模组安装**：发行包与版本目录的 `mods/` 与启动脚本/exe 并排，放进去的 jar 就是 loader 加载的模组。

## 启动参数

| 参数 | 效果 |
|---|---|
| `-debug` | F1 关卡跳转、全部隐码、修改器常显；再次 `-debug` 启动会结束正在运行的实例并接管（旧实例走正常退出流程保存存档） |
| `-trial` | 试用分支：存档隔离到 `saves_trial`，试用锁生效，四处"解锁完整版"入口真正解锁 |
| `-demo` | `-debug` 全部功能 + `-cheat` 调试键 + 预置存档；存档隔离到 `saves_demo` |
| `-cheat` | 调试键 |
| `--graphicsBackend <名称>` | 指定渲染管线：`direct3d11`（默认）/ `direct3d12` / `opengl` / `opengles2` / `vulkan` / `gpu`（SDL_GPU）/ `metal` / `software`；MC 启动器可在游戏参数里追加，未知值记日志忽略（静默回落软件渲染还不如明确拒绝）。`-Dpvz.renderer=<名称>` JVM 参数等效且优先 |
| `--enable3Dacceleration` / `--disable3Dacceleration` | 无视自动检测强行开/关 3D 加速（自动语义下默认按设备能力判定）；强行开启但设备不支持时降级软件渲染（日志记录，不崩），并在进入游戏菜单后弹一次原版"显示卡不支持"提示 |

资源目录：`-resdir <path>` 游戏参数 / `-Dpvz.resDir=<path>` JVM 参数 / `PVZ_RES_DIR` 环境变量。

## 模组开发

游戏的模组就是一个普通 Fabric 模组：带 `fabric.mod.json` 和（可选）Mixin 配置的 jar，放进游戏目录的 `mods/`。

```json
{
  "schemaVersion": 1,
  "id": "my-mod",
  "version": "1.1.0",
  "environment": "*",
  "entrypoints": { "main": ["com.example.MyMod"] },
  "mixins": ["mymod.mixins.json"],
  "depends": { "fabricloader": ">=0.19.0", "pvz-portable": "*", "fabric-api": ">=1.2.2" }
}
```

- **入口点**是 Fabric 自己的接口（`ModInitializer`、`ClientModInitializer`、`PreLaunchEntrypoint`），在游戏主类之前调用；模组间无顺序保证。
- **Mixin** 是 loader 自带的 Sponge Mixin，按原样引用、无需 refmap。
- **内容**（自定义植物/僵尸）来自 `api-plant`/`api-zombie`：注册写入容量固定的定义表，API 的 Mixin 把行为分派进游戏方法；种子选择器与图鉴在原版网格之后的页面展示注册内容。
- **事件**来自 API：`io.github.pvzjre.api.lawn.LawnEvents`（关卡/波次/生成/死亡/受伤/放置/收集/tick/绘制）+ Fabric 风格事件总线（`io.github.pvzjre.api.event`）。
- **模组资源**（自己的种子包图标、重着色等）来自 `api-resource`。

构建模组只需一行插件 `io.github.pvzjre.loom`（[loom 仓库](https://github.com/PvZJRE/loom)）：一行插件带来 loader、Mixin、ASM、API、本游戏（发行包形式）和一个 `runClient` 任务。Java 与 Kotlin 皆可（Kotlin 经 `fabric-language-kotlin` 提供运行时，入口点用带无参构造器的 `class`）。

参考实现：[pvz-example-mod](https://github.com/PvZJRE/pvz-example-mod)（API 事件 + 模组钩子 + Mixin）、[pvz-trainer-mod](https://github.com/PvZJRE/pvz-trainer-mod)（完整修改器）、[pvz-modmenu](https://github.com/PvZJRE/pvz-modmenu)（模组列表界面）。

## 移植状态

移植已完成：上游每一个可运行的模式、界面和子系统都已实现。逐模块的测试证据：

| 模块 | 状态 | 验证 |
|---|---|---|
| 引擎：SDL3 平台层、渲染后端、Image/Graphics/字体、资源/PAK/资源发现、音频（OGG/MO3）、Widget/Dialog/主循环 | Done | 像素级渲染测试（软件 + 真实 GPU）、`ResourceManagerRealAssetsTest`、62 个音频测试、`SdlRendererTest` 等 |
| 游戏：Board/波次/植物/僵尸/弹射物/硬币、LawnApp/界面/过场、存档、禅境花园、Zombatar、全部挑战模式、商店过场 | Done | `BoardTest`（逐条对照上游）、`ZenGardenTest`（34 个）、`ZombatarTest`、`ChallengeModeCoverageTest`（**73 个模式**逐一启动并断言各自结构）、`SaveGameTest`（字节级黄金样本） |
| Fabric Loader 集成 | Done | `FabricLoaderEndToEndTest`：第二 JVM 启动真实 loader，API 与模组作为 `mods/` 里仅有的 jar，验证模组列表、Mixin 应用、钩子链与游戏启动到 `SCENE_PLAYING` |
| 旧版 `.dat` 关卡存档 | N/A | 上游明言它是原始内存转储、不可跨平台；移植版识别并提示用上游版本迁移为 `.v4` |

## 与上游的差异

移植忠实于上游，偏离处标注 `PORT-NOTE` 并保持 `Ported from src/...:<行号>` 溯源。用户可见的差异：

- **渲染**：SDL2 + SDL_GL（GLES2）→ **SDL3 的 `SDL_Renderer`**（LWJGL 只提供 SDL3 绑定）；
- **音频**：SDL-Mixer-X fork → **OpenAL + stb_vorbis**，MO3 经 libopenmpt（JDK FFM）；
- **图像解码**：libpng/libjpeg → **Java ImageIO** + 自实现 TGA；
- **旧版 `.dat` 存档**：识别并提示迁移，不伪造解码器。

新增内容（上游没有的）：[特性](#特性)一节列出的全部条目——它们是加法，不改变上游行为的语义。

## 开发任务

真实窗口/游玩驱动，手工运行，开关以 `-P…` 传入：

| 任务 | 用途 |
|---|---|
| `./gradlew dumpWindow` | 真实窗口逐帧转储（画布 + 窗口内容），`-Ppvz.renderer` 选择后端 |
| `./gradlew sceneDump` | 渲染场景逐帧查看（graveRise/fume/packet/zomboni） |
| `./gradlew zoomDump` | 关卡奖励拾取前后帧 + 缩放绘制探针 |
| `./gradlew playthrough` | 合成 SDL 事件完整游玩冒险模式 |
| `./gradlew bench` | 真实循环的 fps/绘制耗时/GC |

驱动程序的三条硬规则：`SDL_RenderPresent` 之前回读、在帧循环线程上回读、"作弊"放在驱动自己的钩子监听器里，绝不动游戏代码。

测试夹具：`test/HeadlessTestApp.java`（桩资源，`new LawnApp()` 即可测）、`lawn/LawnEntityTestSupport.java`、`pvzp/reanim/ReanimTestSupport.java`、`pvzp/audio/TestAudio.java`、`contentmodfixture/`（loader 端到端）。

## 许可证

- 移植版 **LGPL-3.0-or-later**，跟随上游项目，文件头保留 `Ported from …` 溯源。`LICENSE`、`COPYING`、`NOTICE`、`SexyAppFramework-LICENSE.txt` 随仓库分发。
- **不附带任何游戏资源**：版权归 PopCap/EA 所有。
- 第三方：LWJGL（BSD-3）、Fabric Loader（Apache-2.0）、Sponge Mixin（MIT）、ASM（BSD-3）、Gson（Apache-2.0）、Kotlin（Apache-2.0）、JUnit（EPL-2.0）、libopenmpt 0.8.9（BSD-3-Clause，发行包 `lib/native/` 附其许可证与逐文件溯源 `libopenmpt-natives.txt`）。

## 相关项目

| 仓库 | 内容 |
|---|---|
| [fabric-api](https://github.com/PvZJRE/fabric-api) | 模组 API：`api-base`（事件总线 + 入口点）、`api-events`（lawn 与生命周期事件）、`api-plant`/`api-zombie`（内容注册 + 分派 Mixin）、`api-resource`（模组资源）。以聚合 jar `fabric-api` 安装进 `mods/` |
| [loom](https://github.com/PvZJRE/loom) | 模组 Gradle 插件 `io.github.pvzjre.loom`：一行插件带来 loader、Mixin、ASM、API、本游戏与 `runClient` |
| [pvz-example-mod](https://github.com/PvZJRE/pvz-example-mod) | 参考模组：API 事件、模组钩子、注入游戏的 Mixin |
| [pvz-trainer-mod](https://github.com/PvZJRE/pvz-trainer-mod) | 游戏内修改器模组 |
| [pvz-modmenu](https://github.com/PvZJRE/pvz-modmenu) | 模组列表界面 |

模组在自己的 `fabric.mod.json` 中声明 `"pvz-portable": "*"`，游戏出新版本不需要模组改动。
