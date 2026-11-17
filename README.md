# 🐾星棱狐🐾 — 安卓 Root 管理器（二次开发版）

包名：`com.iki.xlh`
展示名：`🐾星棱狐🐾`

---

## 1. 这份东西是怎么来的

| 项目 | 说明 |
| --- | --- |
| 底座源码 | [`SukiSU-Ultra/SukiSU-Ultra`](https://github.com/SukiSU-Ultra/SukiSU-Ultra)（KernelSU 分支，完整内核 + 用户态 + 管理器） |
| 形态参考 | MapleSU 的双底层 / 无痕 / TEE / 一键迁移「一体化管理器」思路 |
| 本仓库 | 重新命名的 `StarFoxManager`，包名 `com.iki.xlh`，展示名 `🐾星棱狐🐾` |

> 说明：MapleSU 的管理器源码目前只在 Telegram 内测渠道发 APK，GitHub 上公开的是它的模块（`TEESimulator-MapleSU`）和模块市场。所以这里用**能力等价**的开源底座 SukiSU 来做，把它当成 MapleSU 那一套的完全开源替代：内核层 SU、模块生态、SUSFS 隐藏、KPM、Zygisk、App Profile 全都在。

---

## 2. 改了什么

### 2.1 品牌层
- 包名 `com.sukisu.nova` → `com.iki.xlh`（548 个文件全量替换，含 `aidl`、`build.gradle.kts`、`proguard`、`namespace`）
- `applicationId` / `namespace`：`com.iki.xlh`
- 展示名：`🐾星棱狐🐾`（`resValue("string","app_name", …)`）
- AI 子模块包：`com.sukisu.ultra.ai.api` → `com.iki.xlh.ai.api`
- AIDL 包：`com.sukisu.zako` → `com.iki.xlh.zako`
- 用户可见文案 `SukiSU` → `星棱狐`

### 2.2 新增「星棱狐增强中心」
新包 `com.iki.xlh.starfox` + 界面 `ui/screen/starfox/StarFoxScreen.kt`，从 **设置 → 工具** 进入：

| 功能 | 落地方式 |
| --- | --- |
| **双底层模式** KSU / Ultra | 写 `/data/adb/xlh/mode`，内核补丁启动时读取；Ultra 默认带开无痕 |
| **无痕模式** | `/data/adb/xlh/stealth`，过滤挂载痕迹 |
| **TEE 模拟兼容层** | 开关同时启用/禁用 `/data/adb/modules/TEESimulator` |
| **模块生态兼容 Zygisk** | `/data/adb/xlh/zygisk` |
| **内核 Panic 加固** | `/data/adb/xlh/panic` |
| **一键迁移** | 扫 `/data/adb/{ksu,magisk,ap,skroot}`，备份授权数据到 `/data/adb/xlh/migrate-*` |
| **寄生 / WebUI 管理器** | `/data/adb/xlh/webui` + `webui.port` |

配置全部收在 `/data/adb/xlh`，**不动** `/data/adb/ksu`，所以老模块、老授权、老内核补丁全都照常能跑。

### 2.3 底层
- 内核、`ksud`、`libksu.so`、`/data/adb/ksu` **保持原样**——这些是二进制接口，改名会直接把已刷好的内核打废。
- 「改底层」体现在**双轨身份**：老路径继续兼容，新路径 `/data/adb/xlh` 归星棱狐所有，以后要换真内核也不用再动管理器。

---

## 3. 目录

```
星棱狐/
├── manager/                         ← 管理器 APK 工程（主要改这里）
│   ├── app/src/main/java/com/iki/xlh/
│   │   ├── starfox/StarFoxCore.kt          ★ 增强中心核心（存储 + shell）
│   │   ├── ui/screen/starfox/StarFoxScreen.kt ★ 增强中心界面
│   │   ├── ui/screen/{home,module,superuser,settings,…}  原有页面
│   │   └── ui/navigation3/Routes.kt        ★ 新增 Route.StarFox
│   ├── app/src/main/res/values/strings.xml ★ 新增 starfox_* 文案
│   └── app/build.gradle.kts         ★ 包名 / 展示名
├── kernel/                          ← 内核补丁（未改名，保证兼容）
├── userspace/ksud, ksuinit          ← 用户态守护进程
├── uapi/                            ← 内核与管理器共用的头文件
└── README.md
```

---

## 4. 编译

### 4.1 环境
| 项 | 版本 |
| --- | --- |
| JDK | 21（`sourceCompatibility = VERSION_21`） |
| Android SDK | compileSdk **37** / buildTools **37.0.0** |
| NDK | 见 `manager/gradle/libs.versions.toml` 的 `ndk` |
| Gradle | 用仓库自带 `gradlew` |

### 4.2 最快：双击一键编译

根目录 `build-apk.bat`（双击）或 PowerShell 里 `.\build-apk.ps1`。

它自动搞定全套，全部塞在 `.toolchain/` 里，不动系统环境：

```
JDK 21 → Android commandline-tools → SDK 平台 → build-tools → NDK
→ local.properties → 自动生成签名密钥 → gradlew assembleRelease
```

| 参数 | 作用 |
| --- | --- |
| 无 | 出签名好的 release APK |
| `-Debug` | 出 debug 包，自签，装得快 |
| `-Clean` | 清掉 `.toolchain` 重来 |

产物：`manager/app/build/outputs/apk/release/*.apk`，**已自动签名，直接 `adb install` 能装**。

> 签名密钥自动生成在 `.toolchain/starfox.jks`，口令 `starfox123`，alias `starfox`。要换自己的就改 `manager/sign.properties`。
>
> 脚本会把 `libs.versions.toml` 的 `ndk` 和根 `build.gradle.kts` 的 compileSdk 对齐到本机实际装上的版本（37 装不上就落 36），这几行会真实改动项目文件。

### 4.3 手动命令

```powershell
cd 星棱狐\manager

@"
sdk.dir=C\:\\Android\\Sdk
"@ | Out-File -Encoding ascii local.properties

.\gradlew.bat :app:assembleRelease -PKSU_PACKAGE_NAME=com.iki.xlh
```

### 4.4 用自己的签名
`app/build.gradle.kts` 里的 `apksign` 插件读这几个属性，放 `manager/sign.properties`：

```properties
KEYSTORE_FILE=你的.jks
KEYSTORE_PASSWORD=xxxx
KEY_ALIAS=xxxx
KEY_PASSWORD=xxxx
```

不配也能出 `app-release-unsigned.apk`，自己用 `apksigner` 签。

### 4.5 离线提示
- `downloadKpmTools` 会去 GitHub 拉 KPM 工具，**拉不到只警告不报错**（已改）。
- 根 `build.gradle.kts` 的版本号以前依赖 `git rev-list`，现在没有 git 仓库也能编（已改，回退到 `1.0.0-xinglengfu` / `4001`）。

---

## 5. 装到手机上

管理器只是个壳，**必须先有内核层**：

1. 用 `ksud` 或管理器里的「安装」页，给 `boot.img` / `init_boot.img` 打补丁
2. `fastboot flash boot <patched.img>`，重启
3. 装上 `🐾星棱狐🐾` 的 APK
4. 打开 → 超级用户页能看到 App 列表 = 通了
5. 设置 → 工具 → **🐾星棱狐🐾 增强中心** 开双模式 / 无痕 / TEE

> 管理器认的是内核里的 `ksu` 接口，不是 APK 名字，所以换名换包名不影响已刷好的内核。
>
> 换了包名之后，第一次打开可能会提示「不是当前管理器」。进 **设置 → 扩展 → 动态管理器（Dynamic managers）** 把 `com.iki.xlh` 授权成管理器就行——内核侧有 `dynamic_manager` 机制，按包名 + 签名哈希登记，**不用重刷内核**。

---

## 6. 完全自研的 root 识别（不走上游那套）

入口：`manager/app/src/main/java/com/iki/xlh/starfox/StarFoxDetector.kt`
接管点：`ui/util/KsuCli.kt` 的 `rootAvailable()` → 先问 StarFoxDetector，再兜底 libsu。

五路独立证据，任一路过就算有 root：

| # | 证据 | 判定方式 |
| --- | --- | --- |
| ① | 模块台账 | `cat /proc/modules`，看有没有 ksu 系模块名 |
| ② | 模块目录 | `/sys/module/<name>` 存不存在（LKM 已 insmod 的硬证据） |
| ③ | 守护进程 | `/data/adb/ksu/bin/ksud --version` |
| ④ | 星棱狐标记 | `/data/adb/xlh/root.marker`（刷入时 ramdisk 钩子写） |
| ⑤ | 实测 | `id -u` 真的出 0 |

结果带 30 秒缓存，界面里能看到每一路的原始轨迹。

**认哪些模块名**（`StarFoxDetector.KNOWN_MODULE_NAMES`，直接加就行）：

```kotlin
val KNOWN_MODULE_NAMES = listOf(
    "xlh", "starfox", "kernelsu", "kernelsu_next", "sukisu", "ksu",
)
```

---

## 7. 换掉内核模块名（.ko 全走你自己的）

```powershell
# 先看会改哪些点，不动文件
.\scripts\ko-pipeline.ps1 -Task Rename

# 确认后真改：kernelsu_init/exit/cache -> xlh_init/exit/cache
.\scripts\ko-pipeline.ps1 -Task Rename -Apply
```

改完要同步三处：
1. `StarFoxDetector.KNOWN_MODULE_NAMES` 里加 `xlh`
2. `userspace/ksud` 里 modprobe 的模块名
3. 重新编内核，`.ko` 传到自己的仓库

把编好的 `.ko` 拿回来：

```powershell
# 从你自己的仓库 Release 拉 .ko，落到 manager/app/src/main/assets/lkm/
.\scripts\ko-pipeline.ps1 -Task Fetch -Repo 你的用户名/你的内核仓库 -Tag lkm-latest

# 看现在仓库里认哪些模块名、.ko 有没有就位
.\scripts\ko-pipeline.ps1 -Task Status
```

---

## 8. 上传到 GitHub 自动编译

已经放好流水线：`.github/workflows/build-release.yml`

### 8.1 免 git：走 API 直接推（推荐）

本机没装 git 也能推。双击 `push-to-github.bat`，按提示填两样东西：

```
Repo  (owner/name) : 你的用户名/你的仓库名
Token              : ghp_xxxxxxxx
```

Token 去 <https://github.com/settings/tokens> 生成，勾 **`repo`** + **`workflow`** 两个权限就够。

或者直接命令行：

```powershell
.\push-to-github.ps1 -Repo 你的用户名/starfox -Token ghp_xxx -Build
```

脚本做的事：

| 步骤 | 动作 |
| --- | --- |
| 0 | `GET /user` 校验 token |
| 1 | 仓库不存在就 `POST /user/repos` 建一个 |
| 2 | 扫目录，自动排除 `.git` / `build` / `.gradle` / `.toolchain` / `node_modules` / `*.apk` |
| 3 | 逐个 `POST /git/blobs` 上传文件内容（被限流自动等 60 秒重试） |
| 4 | `POST /git/trees` 建目录树 |
| 5 | `POST /git/commits` 建提交 |
| 6 | `POST/PATCH /git/refs` 推到 `main`，顺带设为默认分支 |
| 7 | `POST /actions/workflows/build-release.yml/dispatches` 触发编译 → 轮询状态 → 自动下载 artifact 解出 APK 到 `_download/` |

参数：

| 参数 | 作用 |
| --- | --- |
| `-Private` | 建成私有仓库 |
| `-Build` | 推完立刻触发编译并等结果（不加就只推） |
| `-SkipUpload` | 跳过上传，只触发编译 |
| `-Branch` | 默认 `main` |

### 8.2 有 git 的话

```bash
git init
git add .
git commit -m "🐾星棱狐🐾"
git branch -M main
git remote add origin https://github.com/你的用户名/你的仓库.git
git push -u origin main
```

然后去仓库 **Actions → Build 🐾星棱狐🐾 → Run workflow**。

工作流会做：
- JDK 21 + Android SDK 37 + NDK 29
- `./gradlew :app:assembleRelease -PKSU_PACKAGE_NAME=com.iki.xlh`
- 收集 APK → 上传 artifact
- 手动触发，不会和上游 `release.yml` 的 `v*` tag 打架

> 仓库里本来就带了上游的全套流水线：`build-manager.yml`（管理器）、`build-lkm.yml` + `ddk-lkm.yml`（**自己编 .ko**）、`ksud.yml`（用户态）、`release.yml`（tag 发版）。想让 action 顺便把你的内核 `.ko` 也编出来，直接用 `build-lkm.yml`，把产物传到自己的 Release，再让 `ko-pipeline.ps1 -Task Fetch` 拉回来。

**想让它顺带拉你自己的 `.ko`**：改 workflow 顶部两个值，或者手动触发时填：

```yaml
env:
  KO_REPO: 你的用户名/你的内核仓库   # owner/repo
  KO_TAG: lkm-latest
```

它先走 `gh release download`，Release 里没有就退一步 clone 仓库找 `*.ko`。

---

## 9. 已知边界

- `/data/adb/ksu` 这套老路径保留 → 和原版 SukiSU 内核 100% 互通，可以互相刷。
- 增强中心的开关写的是**约定文件**，要真生效需要配套内核补丁读这些 flag（`/data/adb/xlh/*`）。没打补丁时开关只是记录状态，不会破坏任何东西。
- 内核模块改名后，旧的已刷内核就认不到了，必须把新 `.ko` 一起刷进去。
- `compileSdk 37` 属于新 SDK，本地编要装对应 preview 平台；用 GitHub Actions 就省了这步。

