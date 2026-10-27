# DSH Mobile

DSH Mobile 是 **DeepSeek Harness（`dsh`）** 的 Android 客户端：用系统 WebView 加载远端 `dsh`
Web GUI，把桌面端的使用体验带到手机上。

它**不打包任何前端静态资源**，自身只负责 WebView 容器、配对与若干原生能力适配。因此界面与
功能始终跟随你连接的 `dsh` 服务端版本，客户端无需随前端迭代频繁重新发版。

当前版本：**v0.4.1**（versionCode 8）。

> v0.4.1 起移除 WebView 右上角的悬浮 ⋮ 按钮（v0.4.0 会遮挡 dsh GUI 右上角
> 「打开右侧边栏」），应用菜单改由**系统返回手势/返回键**在根页面弹出
> （AlertDialog：刷新 / 重新配对 / 登出（网关模式）/ 诊断日志 / 关于 / 退出应用）。

> 本仓库根目录即 Android Gradle 工程根目录：`build.gradle.kts`、`settings.gradle.kts`、
> `gradle.properties`、`gradle/`、`gradlew` 与 `app/` 模块都在根目录，克隆后直接在根目录构建。

## 配套仓库

| 仓库 | 说明 |
| --- | --- |
| [DSH-Mobile](https://github.com/chjy090/DSH-Mobile) | Android 客户端（本仓库） |
| [dsh-auth-gate](https://github.com/chjy090/dsh-auth-gate) | `dsh` 服务端认证网关插件，扫码配对与设备令牌所需 |

v0.4.0 起新增「认证网关模式」：扫码配对、证书 pin、设备令牌续期都依赖部署在 `dsh` 服务端的
[dsh-auth-gate](https://github.com/chjy090/dsh-auth-gate) 插件。未部署插件时仍可使用原有的
「服务器地址 + 访问令牌」直连模式。

## 功能特性

- **远程 WebView**：加载远端 `dsh` Web GUI，不内置前端资源，界面随服务端版本更新。
- **两种配对方式**
  - 直连模式：填写服务器地址与访问令牌，或粘贴带 `?token=` 的完整入口 URL 自动解析。
  - 认证网关模式（v0.4.0）：扫描认证网关生成的配对二维码，自动完成地址、令牌与证书指纹配置。
- **证书 pin**：认证网关模式只信任二维码中携带的证书 SHA-256 指纹，指纹不匹配时阻断连接（fail-closed）；
  网关更换证书后需重新扫码配对。
- **设备令牌**：v0.4.0 支持设备令牌自动续期与登出，无需反复输入服务端凭据。
- **fail-open TLS + 证书警告条**：直连模式下不内置任何证书或指纹，证书不受信任时不阻断连接，
  只在页面顶部显示黄色警告条；点开可查看证书主体、颁发者、有效期、SHA-256 与错误类型并复制详情。
- **HTTPS 安全上下文下的语音输入**：仅在 `https://` 地址下可用（WebView 需要安全上下文才能调用
  麦克风），首次使用会弹出系统麦克风授权。
- **应用内下载器**：页面中的附件/导出文件可交给 App 下载并保存到系统「下载」目录；自签 HTTPS 下同样可用。
- **剪贴板原生桥**：WebView Clipboard API 不可用时回退到原生桥，复制更可靠。
- **诊断日志**：App 内「返回手势 → 应用菜单 → 诊断日志」收集 WebView、JS、下载与 TLS 相关日志，可一键复制；
  写入前会对 `token=` 等敏感查询参数脱敏。
- **细节适配**：沉浸式状态栏/导航栏、软键盘避让、深色模式跟随系统、返回手势打开应用菜单
  （含「退出应用」，菜单项与页面状态按返回状态机优先级处理）、401 鉴权失败
  覆盖层（重试/重新配对）、断网与渲染进程崩溃提示。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android | 8.0（API 26）及以上（targetSdk 35） |
| JDK | 17 或更高 |
| Android SDK | Platform 35（compileSdk 35） |
| Gradle | 使用仓库自带的 Gradle Wrapper（Gradle 8.10.2），无需本机安装 |

## 构建

在**仓库根目录**执行：

```bash
# debug 包
./gradlew assembleDebug

# release 包（需先配置签名，见下节；未配置时仍可构建，只是不签名）
./gradlew assembleRelease
```

Windows 下使用 `gradlew.bat`。首次构建会由 Wrapper 自动下载 Gradle 8.10.2 与依赖，需要网络。

产物路径：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## release 签名配置

签名材料与口令**不入库**。在仓库根目录的 `local.properties`（已被 `.gitignore` 忽略）中配置：

```properties
# Android SDK 路径（Android Studio 通常会自动生成）
sdk.dir=/path/to/android-sdk

# release 签名（可选；不配置则 release 不签名，仍可构建）
dsh.keystore.path=signing/dsh-mobile.jks
dsh.keystore.storePassword=<keystore 口令>
dsh.keystore.keyAlias=<key alias>
dsh.keystore.keyPassword=<key 口令>
```

- `dsh.keystore.path` 可省略，默认 `signing/dsh-mobile.jks`（相对仓库根目录）。
- `dsh.keystore.storePassword` / `dsh.keystore.keyPassword` 也可用环境变量
  `DSH_KEYSTORE_PASSWORD` 兜底（两处口令相同时只需设置该变量）。
- 仅当 keystore 文件存在且路径、口令与 alias 齐全时才会创建 release signingConfig。
- 生成 keystore 的示例：

```bash
keytool -genkeypair -v -keystore signing/dsh-mobile.jks \
  -alias dsh-mobile -keyalg RSA -keysize 2048 -validity 3650
```

> `signing/`、`*.jks`、`*.keystore`、`local.properties` 均已在 `.gitignore` 中，不会被提交。

## 安装与使用

详细的安装与配置步骤见 [docs/installation.md](docs/installation.md)。

1. 将构建出的 APK 安装到 Android 8.0+ 手机或模拟器（可用 `adb install` 或文件管理器）。
2. 确保手机与运行 `dsh` 的机器网络互通。
3. 选择配对方式：
   - **扫码配对（v0.4.0，推荐）**：服务端已部署并启用
     [dsh-auth-gate](https://github.com/chjy090/dsh-auth-gate) 认证网关插件后，在 App 内打开扫码，
     扫描网关生成的配对二维码，即可完成配对并进入网关登录页。
   - **直连配对**：在 `dsh` 启动日志中找到 Web GUI 入口地址（形如
     `https://<host>:<port>/?token=<token>`，具体获取方式以 `dsh` 自身说明为准），在 App 内填写
     服务器地址（只填 `scheme://host:port`，不要带路径和 `?token=`）与访问令牌；也可以直接把完整
     入口 URL 粘贴到地址栏，App 会自动提取令牌。该令牌是 `dsh` 进程级临时凭据，**`dsh` 重启后即失效**。
4. 语音输入需要 HTTPS 安全上下文，请优先用 `https://` 地址配对。

## 安全提示

- **fail-open TLS 存在中间人（MITM）风险**：直连模式下证书校验失败时 App 仍会建立连接并仅给出警告条。
  请只在可信网络中使用；在咖啡厅、酒店等不可信网络中，重要操作请使用受信 CA 签发的证书、改用认证网关
  模式，或先连接 VPN。
- 出现证书警告条时，请点开核对证书指纹与主机名，确认是预期服务器后再继续。
- 认证网关模式采用严格证书 pin：二维码中的证书指纹是唯一信任锚，网关换证书/换地址后必须重新扫码配对。
- 访问令牌与设备令牌等同于登录凭据：请勿外传、勿截图、勿提交到公开渠道。
- App 的配对信息保存在应用私有存储中；共享设备或已 root 的设备请自行评估风险，退出前可通过「重新配对」
  清空，或直接卸载。
- 本项目不包含任何前端资源，页面内容完全由你连接的 `dsh` 服务器提供。

## 目录结构

```text
.
├── README.md
├── LICENSE
├── .gitignore
├── docs/installation.md             安装与配置指南
├── build.gradle.kts                 Gradle 根构建脚本（插件版本声明）
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                  Gradle Wrapper 8.10.2
├── gradlew
├── gradlew.bat
└── app/                             Android 应用模块
    ├── build.gradle.kts             应用模块、版本与签名配置
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/ai/deepseek/dsh/mobile/   Kotlin 源码
        └── res/                     资源（字符串、主题、图标、网络安全配置）
```

## 许可

[MIT](LICENSE) © 2026 chjy090
