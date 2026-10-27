# DSH Mobile 安装与配置指南

> 适用客户端：**DSH Mobile v0.4.2**（versionCode `9`，applicationId `ai.deepseek.dsh.mobile`）
>
> 配套服务端：`dsh` + [`dsh-auth-gate`](https://github.com/chjy090/dsh-auth-gate) v0.4.0 及以上。
> 其中「认证网关模式」的扫码配对、设备令牌与自动续期需要该插件；`publicFingerprint`
> （反向代理证书指纹）需要 `dsh-auth-gate` v0.6.2 及以上。
>
> 本文以**认证网关模式（推荐）**为主线；旧的「服务器地址 + 访问令牌」直连模式作为兼容方式
> 放在 §4.7。

本文中的地址均为占位符，请替换成你自己的部署：

| 占位符 | 含义 | 示例 |
| --- | --- | --- |
| `<host>` | 运行认证网关的服务器内网地址（IP 或主机名） | `172.16.180.14` |
| `auth.example.com` | 公网访问时使用的域名 | `auth.example.com` |
| `3443` | 认证网关默认 HTTPS 端口 | `3443` |
| `8443` | frp/Caddy 对公网暴露的端口（示例） | `8443` |
| `3080` | `dsh` 自身 Web GUI 端口（**不要对公网暴露**） | `3080` |

---

## 1. 前置要求

### 1.1 手机

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 8.0（API 26）及以上；targetSdk 35 |
| CPU 架构 | 发布 APK 含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 原生库；主流 ARM 手机可直接安装 |
| 系统 WebView | 需要可用的 Android System WebView。App 自身不检查 WebView 版本，但 `dsh` Web GUI 依赖较新的 Chromium；页面异常时先更新 WebView |
| 存储 | APK 约 25.5 MB；建议至少预留 100 MB |
| 相机（可选） | 扫码配对需要；无相机仍可安装，但 v0.4.2 无法完成扫码配对（见 §4.2 说明） |
| 麦克风（可选） | 语音输入需要；还要求页面是 `https://` 安全上下文 |
| Google 服务 | **不需要** Google Play Services；二维码识别使用 bundled ML Kit |

### 1.2 服务端

- 已部署并运行 `dsh`（DeepSeek Harness）。
- 已安装并启用 `dsh-auth-gate` 插件（v0.4.0+）。插件负责：
  - 生成一次性配对二维码（生成时校验 6 位 TOTP）；
  - 提供 `https://<host>:3443` 的认证网关与登录页；
  - 用户名 + 密码登录（**登录时不再输入 TOTP**，设备绑定即第二因素）；
  - 设备令牌续期、设备/会话管理。
- 认证网关已配置 HTTPS（`tlsCert` / `tlsKey`）。二维码只接受 `https://`。
- 已知管理员账号密码，并已在认证器中绑定 TOTP。
- 服务端时间已同步（NTP/chrony）。TOTP 依赖准确时间；时间偏差会导致“动态验证码不正确”。
- 设备数量未达上限（默认最多 8 台，设备有效期默认 90 天）。

> 插件未安装时，App 仍可用旧的直连模式连接普通 `dsh`，但没有扫码配对、证书 pin、
> 设备令牌自动续期和 App 内「退出当前会话 / 忘记此设备」。直连模式详见 §4.7。
>
> v0.4.2 起认证网关模式支持**启动静默登录**（冷启动直接进 GUI，不闪登录页）和
> **前台恢复自动续期**（GUI 已登录且距上次成功续期超过 1 小时，后台换新会话、不刷新页面）；
> 首次扫码配对仍必须完成网关登录（用户名/密码；TOTP 在生成配对二维码时校验）。

### 1.3 网络

先确定手机要访问的地址：

| 场景 | 手机访问的网关地址 | 说明 |
| --- | --- | --- |
| 同一内网/VPN | `https://<host>:3443` | 最简单；自签证书也可，App 用二维码里的指纹 pin |
| 经 frp + Caddy 公网访问 | `https://auth.example.com:8443` | 需在服务端设置 `publicUrl` 与 `publicFingerprint`，见 §6.2 |
| 经 frp TCP 直通 | `https://<公网地址>:<映射端口>` | 网关自己终止 TLS；二维码指纹是网关证书，`publicFingerprint` 留空 |

要求：

- 手机能访问上述网关地址（同一 Wi-Fi、VPN 或公网）。
- 服务器防火墙只放行认证网关端口（默认 `3443`，或 frp 映射端口）。
- **不要**把 `dsh` 的 `3080` 直接暴露到公网；它应只允许认证网关或本机访问。
- 公网使用域名时，手机能正常解析该域名。

### 1.4 App 权限

| 权限 | 类型 | 触发时机 | 用途 |
| --- | --- | --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 安装时自动授予 | 启动/联网 | 访问网关 |
| `CAMERA` | 运行时权限 | 首次点「扫码配对」进入扫码页时 | 扫描配对二维码 |
| `RECORD_AUDIO` | 运行时权限 | 网页首次请求麦克风（如点语音按钮） | 语音输入 |
| `VIBRATE` | 安装时自动授予 | 扫码识别成功 | 震动反馈 |
| `MODIFY_AUDIO_SETTINGS` | 安装时自动授予 | 语音输入 | 让 WebView 正确选择通信音频设备 |
| `WRITE_EXTERNAL_STORAGE` | 运行时权限，仅 Android 9（API 28）及以下 | 首次下载文件 | 写入系统「下载」目录 |

Android 10（API 29）及以上下载走 MediaStore，不需要存储权限。文件选择走系统文件选择器，
不需要额外存储权限。

### 1.5 开始前检查清单

- [ ] 手机为 Android 8.0+，能安装 APK。
- [ ] 已取得 `DSH-Mobile-v0.4.2-release.apk`，SHA-256 与 §2.2 一致。
- [ ] 服务端 `dsh` 与 `dsh-auth-gate` 正常运行。
- [ ] 手机浏览器能打开 `https://<host>:3443/__auth/login`（自签证书会先提示，属正常）。
- [ ] 管理页 `/__admin` 已完成初始化，TOTP 可正常生成 6 位验证码。
- [ ] 服务端时间准确。
- [ ] 已确认最终使用的网关地址（内网或公网），避免配对后频繁更换。

---

## 2. 获取与安装 APK

### 2.1 从 GitHub Releases 下载

发布页：

- 版本页：<https://github.com/chjy090/DSH-Mobile/releases/tag/v0.4.2>（由 Lead 验证后发布）
- APK 直链：
  <https://github.com/chjy090/DSH-Mobile/releases/download/v0.4.2/DSH-Mobile-v0.4.2-release.apk>

v0.4.2 的 Release 资产：

| 文件 | 说明 |
| --- | --- |
| `DSH-Mobile-v0.4.2-release.apk` | 签名后的 release APK |
| `SHA256SUMS-v0.4.2.txt` | APK 的 SHA-256 校验文件 |
| `release-notes-v0.4.2.md` | 发布说明 |

当前构建版本信息（本地 `deliverables/` 与构建产物已核对；GitHub Release 待 Lead 发布）：

| 项目 | 值 |
| --- | --- |
| versionName | `0.4.2` |
| versionCode | `9` |
| 文件大小 | 25,516,087 字节（约 24.33 MiB） |
| SHA-256 | `1ad91b691e183c03039c5f6e88a3639c9474a68a06b6b96f2620d3e9b61b3495` |
| 签名证书 SHA-256 | `45dee26ac9aa785dda281970bc07072665624ef381616d310331f849216f62c8` |

> 只从上述官方 Release 或你信任的内部渠道获取 APK。不要使用来源不明的“破解版”“去广告版”
> 或第三方网盘文件；不同签名的 APK 无法覆盖升级，且可能窃取设备令牌与密码。

### 2.2 校验 APK

#### 2.2.1 SHA-256

Linux/macOS：

```bash
cd ~/Downloads
sha256sum DSH-Mobile-v0.4.2-release.apk
# macOS 也可用：shasum -a 256 DSH-Mobile-v0.4.2-release.apk
```

期望输出：

```text
1ad91b691e183c03039c5f6e88a3639c9474a68a06b6b96f2620d3e9b61b3495  DSH-Mobile-v0.4.2-release.apk
```

如果同时下载了 `SHA256SUMS-v0.4.2.txt`，可在同一目录执行：

```bash
sha256sum -c SHA256SUMS-v0.4.2.txt
# 期望：DSH-Mobile-v0.4.2-release.apk: OK
```

Windows PowerShell：

```powershell
Get-FileHash .\DSH-Mobile-v0.4.2-release.apk -Algorithm SHA256
```

哈希不一致时**不要安装**，重新下载并再次校验。

#### 2.2.2 签名校验（可选，推荐）

需要 Android SDK Build-Tools（35.0.0 或其他兼容版本）：

```bash
export ANDROID_HOME=/path/to/android-sdk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify \
  --verbose --print-certs DSH-Mobile-v0.4.2-release.apk
```

期望结果包含：

```text
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=DSH Mobile, OU=Personal, O=Personal, L=NA, ST=NA, C=CN
Signer #1 certificate SHA-256 digest: 45dee26ac9aa785dda281970bc07072665624ef381616d310331f849216f62c8
```

如果证书 SHA-256 不是上面这个值，说明不是同一发布签名。**不要**拿它覆盖安装现有
DSH Mobile，否则会因签名不一致失败；也不要信任该文件。

#### 2.2.3 版本信息（可选）

```bash
"$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging \
  DSH-Mobile-v0.4.2-release.apk | head -n 8
```

应能看到：

```text
package: name='ai.deepseek.dsh.mobile' versionCode='9' versionName='0.4.2' ...
minSdkVersion:'26'
targetSdkVersion:'35'
```

### 2.3 在手机上安装

#### 方式 A：文件管理器/浏览器点击安装

1. 把 APK 传到手机（USB、局域网、云盘等均可），记住保存位置。
2. 用文件管理器点开 APK。
3. 系统首次会拦截，按提示允许当前应用“安装未知应用”：
   - Android 8/9：设置 → 应用和通知（或“安全”）→ 特殊应用访问 → 安装未知应用 →
     选择你正在使用的文件管理器/浏览器 → 允许。
   - Android 10+：设置 → 应用 → 特殊应用访问 → 安装未知应用 → 选择对应应用 → 允许。
   - 不同厂商（MIUI、ColorOS、OriginOS 等）路径和文案略有差异，按“安装未知应用”搜索即可。
4. 返回安装界面，点「安装」。
5. 如果 Google Play Protect 弹出“未识别的应用/可能有害”，**仅在哈希与签名校验通过时**选择
   「更多详情」→「仍然安装」。
6. 安装完成后点「打开」。首次打开应显示「连接 DSH Host」配对页。

#### 方式 B：ADB 安装

```bash
adb devices                 # 确认设备已授权
adb install DSH-Mobile-v0.4.2-release.apk
```

已安装同签名旧版本时，覆盖安装：

```bash
adb install -r DSH-Mobile-v0.4.2-release.apk
```

安装后确认版本：

```bash
adb shell dumpsys package ai.deepseek.dsh.mobile | grep -E "versionCode|versionName"
```

期望类似：

```text
versionCode=9 minSdk=26 targetSdk=35
versionName=0.4.2
```

> v0.4.2 的 release APK 使用 v2 签名方案；Android 8.0+ 支持。不要用 `adb install` 安装
> `app-debug.apk` 去覆盖 release 包，debug 与 release 签名不同，会报
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

### 2.4 覆盖升级、卸载与数据

- **同签名覆盖升级**：直接安装新 APK（或 `adb install -r`）会保留：
  - 服务器地址、证书 pin、认证网关标记；
  - 经 Android Keystore 加密的设备令牌；
  - WebView 的 Cookie（包括设备绑定 cookie 和登录 session）。
- **卸载**会删除应用私有数据，包括配对信息、设备令牌、WebView Cookie。重新安装后必须重新扫码配对。
- App 的 `android:allowBackup="false"`，系统备份/换机迁移**不保证**恢复配对数据；换手机请重新配对。
- 签名不同（例如从 debug 包换到 release 包，或从第三方修改版换到官方版）不能覆盖：
  先卸载（数据丢失），再安装，重新配对。
- 降级安装见 §10.3。

### 2.5 versionCode 与 versionName

- `versionName` 是给人看的版本号（`0.4.2`），App「关于」对话框显示的是它。
- `versionCode` 是给 Android 比较新旧的整数（`9`）。正常安装要求新包的 versionCode
  不低于已安装版本；降级需要显式允许（`adb install -r -d`）。
- 覆盖升级时 Android 还会校验签名证书一致；versionCode 高但签名不同同样无法安装。

---

## 3. 服务端准备与自检

本节只做 App 配对所需的最小检查；插件的完整安装、初始化、TOTP 绑定与 frp 配置请以
[`dsh-auth-gate` README](https://github.com/chjy090/dsh-auth-gate) 为准。

### 3.1 确认网关在监听

在能访问服务器的电脑上执行：

```bash
# 自签证书需要 -k；返回 200 + {"authenticated":false} 即网关可达
curl -k -sS -D - https://<host>:3443/__auth/whoami
```

期望：

```text
HTTP/1.1 200 OK
...
{"authenticated":false}
```

再确认登录页：

```bash
curl -k -sS -o /dev/null -w '%{http_code}\n' https://<host>:3443/__auth/login
# 期望：200
```

如果连接被拒绝、超时或返回 404：

- 插件未启用或未监听 3443；
- 防火墙未放行 3443；
- `publicUrl`/监听地址配置错误；
- `dsh` 未启动。查看服务端日志：

```bash
journalctl -u dsh -n 200 --no-pager | grep -iE 'auth-gate|listening|error'
```

（服务名按你的 systemd 单元调整。）

### 3.2 打开管理页

任选一种入口：

- 浏览器打开 `https://<host>:3443/__admin`（自签证书先接受警告）；
- 或从**认证网关地址**打开 `dsh` Web GUI，在左侧栏进入「认证管理」。

管理页包含「配对 / 账号 / 设备 / 会话 / 设置 / 审计」等 tab。首次部署如果还没初始化，
需要先按插件 README 的 WebUI 向导创建管理员、绑定 TOTP；本指南假设已完成。

> 从 `dsh` 的 `3080` 端口直连时，侧边栏「认证管理」可能只显示“请从网关端口访问”的说明，
> 不会发起管理 API 请求；这是插件的同源限制，不是 App 问题。

### 3.3 确认对外地址与证书

在管理页「设置」tab 检查：

| 项 | 内网直连 | frp + Caddy 公网 |
| --- | --- | --- |
| 监听地址/端口 | `<host>:3443` | 网关本机 LAN 地址:3443 |
| `publicUrl` | `https://<host>:3443` | `https://auth.example.com:8443` |
| `publicFingerprint` | 留空（使用网关自身证书） | **必须**填 Caddy 对外证书 SHA-256 |
| 二维码当前生效指纹 | 网关证书指纹 | Caddy 证书指纹 |

二维码只使用 `publicUrl`，不使用监听地址。`publicUrl` 必须是纯 origin
（`scheme://host[:port]`），不能带路径、查询、片段或用户名密码；协议必须与 TLS 状态一致
（网关配了 TLS 就必须用 `https://`，未配 TLS 才用 `http://`）。`publicFingerprint` 只能是
空（使用网关自身证书）或 64 位十六进制 SHA-256。

### 3.4 防火墙与暴露面

- 内网：放行手机所在网段访问 `3443/tcp`。
- 公网：只放行 frp/Caddy 的入口端口（如 `8443/tcp`），以及 frps 所需的映射端口；
  **不要**把 `3080` 暴露到公网。
- 认证网关管理页 `/__admin` 也只应通过 HTTPS 访问；不要把管理端口另行暴露到不受信网络。

---

## 4. 首次使用：扫码配对

### 4.1 在服务端生成配对二维码

1. 在电脑浏览器打开管理页：
   - `https://<host>:3443/__admin`，或
   - 从网关访问 `dsh`，侧边栏 →「认证管理」。
2. 切到 **「配对」** tab。
3. 在「6 位动态验证码」输入认证器当前显示的 6 位 TOTP。
   - 这里是**生成配对二维码**时的授权，不是 App 登录时的输入。
   - 连续输错会触发限流（默认 5 次失败锁 5 分钟）；等倒计时结束再试。
4. 点「生成配对二维码」。
5. 页面显示二维码、配对链接和过期时间。默认 **120 秒**内有效，且**一次性**：
   - 票据只在二维码 URL 的 fragment 中，不会发给服务器或写入访问日志；
   - 服务端只保存票据的 SHA-256；
   - 兑换成功即失效；过期需重新生成；
   - 二维码签发默认限 10 次/分钟/IP。

> **不要把二维码截图发到群里或保存到公共相册。** 任何人在 120 秒内拿到这张二维码，
> 都可以抢先把它兑换成自己设备的绑定。过期或怀疑泄露时，直接重新生成。

### 4.2 在 App 中扫码

1. 打开 DSH Mobile。首次启动显示「连接 DSH Host」。
2. 点蓝色主按钮 **「扫码配对」**。
3. 首次使用会弹出系统相机权限请求，选择「允许」。
   - 拒绝后扫码页会提示并退出；需要到系统设置 → 应用 → DSH Mobile → 权限 → 相机中重新允许。
4. 把二维码放入取景框，保持稳定。识别成功后手机会震动一次并自动返回 App。
5. App 弹出「确认配对」对话框，显示：
   - **服务器**：`https://<host>:3443` 或 `https://auth.example.com:8443`；
   - **证书 SHA-256**：64 位十六进制指纹；
   - 一段说明：此设备首次配对会向该服务器注册设备，并保存用于自动续期的设备凭据。
6. **逐字核对服务器地址和证书指纹**。确认二维码来自你的管理页、地址与指纹都正确后，点「确定」。
   - 地址或指纹不对：点「取消」，不要继续；重新生成二维码并检查服务端配置。
7. App 显示不可取消的兑换进度，向 `POST /__auth/pair/redeem` 提交 ticket 和手机型号作为设备名。
   - 成功后 Toast「配对成功，请登录」；
   - 失败会弹出「配对失败」及错误详情（如 `HTTP 401 · expired_ticket`），见 §8。

> **v0.4.2 没有“从相册选二维码”或“粘贴配对链接”的入口。** 不要用系统相机、微信等
> 扫描二维码：通常只会打开浏览器里的配对页，不会把配置写入 App。必须用 App 内的「扫码配对」。
> 相机不可用或无法扫码时，只能改用 §4.7 的旧直连模式（且不适用于认证网关），或换一台
> 能显示二维码的设备。

### 4.3 配对二维码里有什么

二维码内容形如：

```text
https://auth.example.com:8443/__auth/pair#v=1&t=<一次性ticket>&fp=<64位证书SHA-256>&n=<服务器名>
```

| 字段 | 含义 | App 行为 |
| --- | --- | --- |
| `https://host[:port]` | 网关地址，来自服务端 `publicUrl` | 保存为服务器地址；只接受 `https` 和 `/__auth/pair` 路径 |
| `t` | 一次性配对 ticket（fragment 中） | 兑换设备令牌；不写日志、不落盘 |
| `fp` | 网关对外证书的 leaf DER SHA-256（64 位小写 hex，无冒号） | 作为唯一信任锚；之后 WebView 与原生续期都按它 pin |
| `n` | 服务器主机名（可选） | 仅解析，当前版本不用于设备命名/展示 |
| `v=1` | 载荷版本 | 必须为 1，否则拒绝 |

设备名**不在二维码里**：App 在兑换时把本机型号（`Build.MODEL`，空则用“Android 设备”）
作为 `deviceName` 提交，所以管理页「设备」列表看到的是手机型号，而不是二维码里的 `n`。

### 4.4 首次登录

配对成功后 App 会：

1. 保存服务器地址、证书 pin、加密后的 deviceToken；
2. 把配对响应里的 `Set-Cookie`（设备绑定 cookie `dsh_gate_device`）注入 WebView；
3. 用 pin 严格校验预检 `GET /__auth/whoami`，通过后加载网关；
4. 网关把未登录的页面请求重定向到 `/__auth/login`。

登录页只需要：

- **用户名**
- **密码**

**不需要再输入 TOTP。** TOTP 只在管理页生成配对二维码时使用；登录时的第二因素是
“已配对设备”的 `dsh_gate_device` cookie。

登录成功后进入 `dsh` Web GUI。首次进入主界面时会出现一次 Toast，提示“从屏幕边缘向内滑动
（返回手势）可打开应用菜单”；正常情况下每个安装只提示一次。
清除数据/重新配对会重置该提示标记，因此可能再次出现。

常见登录错误：

| 页面提示/错误码 | 含义 | 处理 |
| --- | --- | --- |
| `invalid_credentials` | 用户名或密码错误 | 重新输入；注意大小写；忘记密码走服务端管理页重置 |
| `device_required` | 当前 WebView 没有有效的设备绑定 cookie | 重新扫码配对（见 §8） |
| `origin_changed` | 服务端 `publicUrl` 改了，设备绑定的 origin 不匹配 | 重新扫码配对 |
| `rate_limited` | 失败/请求次数过多 | 按页面提示等待后重试 |
| `not_configured` | 服务端尚未初始化 | 先在服务端完成初始化向导 |
| `csrf_invalid` | 登录页过期 | 刷新页面/重试 |

### 4.5 配对成功的检查

- [ ] App「关于」显示 `0.4.2`。
- [ ] 管理页「设备」出现一条以手机型号命名的设备，平台显示为 `安卓`/`android`。
- [ ] 登录后能看到 `dsh` Web GUI，而不是停在登录页或错误页。
- [ ] 返回手势能弹出「应用菜单」。
- [ ] 顶部没有黄色 TLS 警告条（认证网关模式正常不应出现；出现说明 pin 或配置异常）。

### 4.6 配对失败的通用处理

1. 确认二维码未超过 120 秒、未被使用过；重新生成。
2. 确认手机与网关网络互通，且地址/端口正确。
3. 确认服务端「设备」数量未达上限（默认 8 台）；先吊销不用的旧设备。
4. 查看 App 弹窗中的错误码（§8.2）。
5. 若提示证书/指纹问题，先按 §6.3 处理，不要盲目重装 App。

### 4.7 旧直连模式（仅普通 dsh，不适用于认证网关）

如果服务端没有安装 `dsh-auth-gate`，仍可使用 v0.3.0 以来的直连模式：

1. 在 `dsh` 启动日志/管理方式中取得 Web GUI 入口地址，形如
   `https://<host>:3080/?token=<token>`。
2. 在 App 配对页填写：
   - **服务器地址**：只填 `scheme://host:port`，不要带路径、`?token=`；
   - **Token（可选）**：粘贴入口 URL 中的 `token`；也可以把完整入口 URL 粘贴到地址栏，
     App 会尝试提取 `token`。
3. 点「连接」。

直连模式的特点与限制：

- **fail-open TLS**：证书不受信任时 App 仍会连接，只在页面顶部显示黄色警告条；
  点开可看证书详情，存在中间人风险，只建议在可信网络使用。
- 没有 deviceToken、没有网关会话自动续期、没有「退出当前会话/忘记此设备」菜单；
  `dsh` 重启后入口 token 可能失效，需要重新配对。
- **不能**用直连模式替代认证网关配对：对认证网关直接填地址，登录会返回 `device_required`。
- 语音输入仍要求 `https://`；`http://` 页面无法调用麦克风。

---

## 5. 日常使用

### 5.1 启动与加载

- 已配对时，启动 App 会直接加载上次的服务器地址。
- **认证网关模式（v0.4.2）启动静默登录**：如果本机曾成功登录过且 deviceToken 可用，
  App 会先在后台 `POST /__auth/renew`，成功则注入 Set-Cookie 后直接加载 GUI；
  期间顶部显示「正在使用设备凭据登录…」，**不会先闪登录页**。
  - 401/设备失效 → 登录页 + 顶部「设备配对已失效，需要重新扫码配对」；
  - 网络/TLS 失败 → 登录页 + 顶部可点击重试提示；原生网络错误层的重试也会重试静默登录。
- 未触发静默登录时，认证网关模式仍会用保存的证书 pin 预检 `GET /__auth/whoami`：
  - 通过 → 加载 GUI；
  - 失败 → 显示「服务器证书已变更，连接已阻止；请重新扫码配对以确认新证书。」
    （注意：网络不可达/超时也会走到同一个错误层，见 §8.4）。
- 页面顶部有 3dp 进度条；加载完成后自动隐藏。

### 5.2 返回手势/返回键与「应用菜单」

v0.4.1 移除了 WebView 右上角的悬浮 ⋮ 按钮（v0.4.0 会遮挡 `dsh` GUI 右上角的
「打开右侧边栏」）。应用菜单改由**系统返回手势**（从屏幕左/右边缘向内滑）或**三键导航的
返回键**触发。

返回事件按以下优先级处理：

| 优先级 | 条件 | 行为 |
| --- | --- | --- |
| 1 | 错误覆盖层可见 | 关闭错误层；若渲染进程已死，重建 WebView |
| 2 | 输入法（软键盘）可见 | 收起键盘 |
| 3 | WebView 可以后退（`canGoBack()`） | 网页后退 |
| 4 | 应用菜单正在显示 | 关闭菜单（通常由 Dialog 自己消费返回事件） |
| 5 | 以上都不满足 | 弹出「应用菜单」 |

`dsh` 是单页应用（SPA），在根页面通常没有 WebView 历史，所以**每次在根页面按返回都会弹菜单**；
v0.4.1 起不再有“再按一次返回退出”，退出请用菜单里的「退出应用」。

> v0.4.2 只调整了认证网关模式的菜单项（拆分“退出当前会话/忘记此设备”），
> 返回手势/返回键状态机与 v0.4.1 完全一致。

> 菜单入口依赖系统返回手势/返回键。如果你在系统设置里关闭了手势导航，或用了会拦截边缘滑动的
> 手势 App，可能难以呼出菜单；可临时切换为三键导航，或使用系统返回键。App 内没有其他悬浮入口。

### 5.3 应用菜单各项

认证网关模式（v0.4.2）：

| 菜单项 | 行为 | 是否清数据 |
| --- | --- | --- |
| 刷新 | 关闭错误层并重新加载当前页 | 否 |
| 退出当前会话 | 通过 `POST /__auth/logout` 让服务端删除 session，清 session cookie，回到登录页；**保留** deviceToken 与设备绑定 cookie | 否（下次冷启动自动登录） |
| 忘记此设备（重新配对） | 二次确认后删除本机 deviceToken、该 host 全部 WebView cookie（含设备绑定）与配对 profile，回到配对页 | **是**（下次必须重新扫码，见 §7） |
| 诊断日志 | 打开内存诊断日志，可一键复制 | 否 |
| 关于 | 显示 App 名称、versionName、包名、Android 版本、当前服务器地址、设备有效期 | 否 |
| 退出应用 | 结束当前任务栈（`finishAffinity()`），不卸载、不清数据 | 否 |

直连模式（无认证网关）：刷新 / 重新配对 / 诊断日志 / 关于 / 退出应用；其中「重新配对」
清 WebView cookie（含设备绑定）、本地配对 profile 和当前设备令牌，回到配对页。

说明：

- 「退出当前会话」不是“退出 App”，也不会解除设备绑定；下次**冷启动**仍会用 deviceToken
  静默自动登录。当前进程内不会立刻把刚退出的会话建回来。
- 「忘记此设备」会删除本机凭据；服务端设备记录不会自动消失（可在管理页吊销/等待过期），
  但本机必须重新扫码才能再次登录。
- 如果退出会话请求失败/超时（约 4 秒兜底），App 会尽量清理本地 session 并回登录页；
  若页面仍异常，可用「刷新」或「忘记此设备」。
- 「退出应用」只结束界面，后台/系统仍可能保留进程；再次打开会重新加载。

### 5.4 麦克风（语音输入）

- 仅当页面是 `https://`（安全上下文）时可用；`http://` 时顶部会显示明文 HTTP 警告条。
- 页面调用 `getUserMedia` 时，App 只放行**当前页面同源**的音频采集请求，拒绝第三方 iframe。
- 首次使用会弹系统麦克风权限；允许后 WebView 获得音频流。
- 拒绝/未授权时 Toast 提示“未获得麦克风权限，语音输入不可用；可再次点击麦克风重试”；
  之后可到系统设置 → 应用 → DSH Mobile → 权限 → 麦克风重新允许。
- App 内置了 `getUserMedia` 兜底：遇到 `NotReadableError`/`OverconstrainedError` 会放宽约束
  重试一次；仍失败会写诊断日志。
- 若服务端/页面本身不支持录音，App 无法代为修复。

### 5.5 下载

- 页面中的附件/导出文件由 App 内下载器保存到系统「下载」目录（`Download/`）。
- Android 10+ 通过 MediaStore 写入，不需要存储权限；Android 9 及以下首次下载会请求
  `WRITE_EXTERNAL_STORAGE`。
- 下载文件名使用原文件名加 `-yyyyMMdd-HHmmss` 时间戳，避免重名覆盖。
- 下载会带上当前 WebView 的 Cookie 和 User-Agent；自签 HTTPS 也能下载。
- 仅支持 `http://` / `https://` 直链；`blob:`、`data:` 链接会提示“该链接暂不支持下载”。
- 没有下载队列/断点续传界面；失败会 Toast，超大文件受网络与系统限制。

### 5.6 文件选择/上传

- 网页触发文件选择（`<input type="file">`）时，App 打开系统文件选择器（支持多选）。
- 选择结果以 `content://` URI 交给网页，不需要存储权限；App 不允许 WebView 任意读取本地文件。
- 页面 `accept` 类型会被规范化为 MIME；无法识别时回退 `*/*`。
- 选择器打不开/取消时，网页收到空结果，不会崩溃。

### 5.7 剪贴板

- 网页复制优先使用浏览器原生 Clipboard API；不可用或失败时，App 通过
  `window.DSHNative.copyToClipboard` 原生桥兜底，并 Toast「已复制」。
- Android 13+ 系统会显示剪贴板访问提示，属正常。
- App 不读取剪贴板内容，只提供写入。

### 5.8 会话与设备有效期

服务端默认值（可在插件配置中修改）：

| 项目 | 默认值 | 行为 |
| --- | --- | --- |
| 登录会话 | 8 小时 | 过期后 App 自动用 deviceToken 续期（v0.4.2 前台每 ≥1 小时也会主动刷新） |
| 设备绑定 | 90 天 | 到期后 deviceToken 失效，必须重新扫码配对 |
| 设备数量 | 8 台 | 超过后配对返回 `device_limit`，需先吊销旧设备 |
| 配对票据 | 120 秒 | 一次性，过期需重新生成 |
| 登录失败锁 | 15 分钟内 5 次失败，锁 15 分钟 | 返回 `rate_limited` |

自动续期流程（v0.4.2）：

1. **冷启动**：曾登录过且 deviceToken 可用 → 后台 `POST /__auth/renew` → 成功注入 cookie
   → 直接加载 GUI（不闪登录页）；失败按 401/网络错误分别提示并回登录页。
2. **前台恢复**：GUI 已登录且距上次成功 renew 超过 1 小时 → 后台续期，更新 cookie 与
   服务端可选返回的 `deviceExpiresAt`，**不刷新页面**；401 时下一次前台/导航回登录页。
3. **会话过期兜底**：主文档 401，或加载到 `/__auth/login` 且本机曾成功登录过 → 后台续期；
   成功 → 注入新 session cookie → reload（或直接打开原目标页）。
4. **失败**：回登录页；401/`device_required`/`origin_changed` 会明确提示需要重新扫码配对。
5. 如果 deviceToken 过期/被吊销/`origin_changed`，登录页也会继续报错，需要重新配对。

> 续期只延长**会话**，不会延长 90 天的**设备**有效期。设备到期/被吊销后必须重新扫码。
> v0.4.2 的「关于」会显示「设备有效期至：yyyy-MM-dd HH:mm」；服务端未返回时显示「—」。

### 5.9 断网、后台与进程回收

- App 不内置前端资源，服务端不可达时页面无法使用；顶部进度条/错误层会提示网络错误。
- 退到后台后，系统可能回收 WebView/进程；再次打开会重新加载页面。
- WebSocket 断线不会自动弹重连；可点「刷新」或重新进入页面。
- 没有后台推送/常驻通知。
- 诊断日志只存在内存（最多 300 条），进程被杀后丢失；需要时及时复制。

---

## 6. 网络与证书配置

### 6.1 内网直连 `https://<host>:3443`

最简单的部署：

```text
手机 ──HTTPS(3443)──> dsh-auth-gate ──HTTPS/反代──> dsh (3080)
```

- 服务端 `publicUrl` 设为 `https://<host>:3443`，`publicFingerprint` 留空。
- 网关可以使用自签证书。App **不需要**把该 CA 安装到手机系统：
  - 扫码后 App 只信任二维码里的 leaf 证书 SHA-256；
  - 原生兑换/续期使用只接受该指纹的 `TrustManager`；
  - WebView 对该 host 的 SSL 错误做严格指纹比对，匹配才放行。
- 自签证书的主机名/SAN 不匹配也不影响 App，因为 pin 命中后不再做主机名校验。
  但用手机浏览器打开同一地址时仍会看到证书警告，这是浏览器行为，不是 App 故障。
- 手机和服务器需在同一网络或 VPN；防火墙放行 3443。

> 不要为了“让浏览器不报警”而在手机上随意安装来源不明的 CA 证书。App 不依赖系统信任，
> 安装未知 CA 反而扩大风险。

### 6.2 经 frp/Caddy 公网访问 `https://auth.example.com:8443`

典型拓扑：

```text
手机 ──HTTPS(8443, Caddy 证书)──> VPS Caddy
                                    │  HTTPS 回源（tls_insecure_skip_verify）
                                    ▼
                             frps ──> frpc ──> dsh-auth-gate (https://<host>:3443, 自签)
                                                        │
                                                        └──> dsh (https://<host>:3080，不暴露)
```

关键点：**手机看到的是 Caddy 的证书**，不是网关自签证书。因此服务端必须把
`publicFingerprint` 设为 Caddy 对外证书的 SHA-256，否则二维码里的 `fp` 仍是网关自签指纹，
App 会因 pin 不匹配而拒绝连接。

#### 6.2.1 服务端设置步骤

1. 在 frpc 中把网关 3443 映射到 frps（`local_ip` 填网关绑定的 LAN 地址，**不要填 127.0.0.1**）。
2. 在 Caddy 上用有效证书提供 `https://auth.example.com:8443`，反代到 frps 映射端口
   （HTTPS 回源；回源证书是网关自签，可跳过校验）。
3. Caddy 必须保留浏览器原始 Host（含端口），否则网关的 Origin 同源校验会拒绝 CSS/JS 请求，
   表现为登录后黑屏/白屏：

   ```caddyfile
   auth.example.com:8443 {
       tls admin@example.com

       reverse_proxy 127.0.0.1:18080 {
           # 关键：保留原始 Host（含 :8443），不要改写成上游 127.0.0.1:18080
           header_up Host {http.request.hostport}
           transport http {
               tls
               tls_insecure_skip_verify
           }
       }
   }
   ```

   以上仅为示例，端口/邮箱/上游地址按实际替换。

4. 打开管理页「设置」tab：
   - 把「对外访问地址（publicUrl）」设为 `https://auth.example.com:8443`；
   - 点「从对外地址自动读取」，让服务端连接该地址并读取 Caddy leaf 证书 SHA-256；
   - 或手工填入 64 位十六进制指纹；
   - 保存后确认「二维码当前生效指纹」等于 Caddy 证书指纹。
5. 重新生成配对二维码（旧的二维码仍带旧 `fp`，不能用）。
6. 手机扫码；确认对话框里的指纹应是 Caddy 的证书指纹。

`publicUrl` 变更会让已配对设备的 `origin` 不匹配，之后登录会返回 `origin_changed`；
**修改 publicUrl 后所有手机都要重新扫码配对**（见 §6.5）。

#### 6.2.2 验证 Caddy 证书指纹

如果不用「自动读取」，可在 VPS 上手工读取：

```bash
openssl s_client -connect auth.example.com:8443 -servername auth.example.com </dev/null 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256
```

输出形如：

```text
SHA256 Fingerprint=AA:BB:CC:...:FF
```

去掉冒号、转小写后是 64 位 hex，就是 App/二维码使用的 `fp`。也可以用：

```bash
openssl s_client -connect auth.example.com:8443 -servername auth.example.com </dev/null 2>/dev/null \
  | openssl x509 -outform der | sha256sum
```

直接得到无冒号的小写 SHA-256。

#### 6.2.3 证书续期（ACME/Let's Encrypt）

- Caddy/Certbot 自动续期会换 leaf 证书，`publicFingerprint` **不会自动更新**。
- 续期后必须：
  1. 在管理页「设置」重新点「从对外地址自动读取」，或填入新指纹；
  2. 重新生成配对二维码；
  3. **所有已配对手机重新扫码**。
- 为什么已配对手机也要重扫：App 每次冷启动/重建 WebView 时（同一 Activity 生命周期内会缓存
  一次预检结果）会用保存的旧指纹预检 `GET /__auth/whoami`；证书 leaf 变了，预检失败会显示
  `CERT_CHANGED`。即使手机系统信任 Caddy 的新证书，App 的原生 pin 预检仍按旧指纹比对。
- 建议在证书到期前完成更新，避免旧证书过期后再手忙脚乱。

#### 6.2.4 frp TCP 直通（另一种可选方式）

如果不用 Caddy 终止 TLS，而是 frp `type = tcp` 直接把 3443 映射到公网：

- 由网关自己终止 TLS；`publicFingerprint` 留空；
- 二维码指纹是网关自签证书；
- 手机浏览器访问公网地址可能因证书 SAN 不含该域名/IP 而告警，但 App 按 pin 可正常连接；
- 证书更换后同样需要重新生成二维码并重扫。

### 6.3 证书/指纹变更与 `CERT_CHANGED`

`CERT_CHANGED` 是 **App 本地的错误层**，不是服务端返回的错误码。触发条件包括：

| 触发场景 | 说明 |
| --- | --- |
| 网关自签证书被替换/重新生成 | 二维码里的旧指纹与新 leaf 不一致 |
| Caddy/ACME 轮换证书 | App 保存的是旧 leaf 指纹 |
| 改了 `publicFingerprint` 后用旧二维码配对新设备 | 二维码里的旧 `fp` 与新证书不一致，新设备会 pin 失败 |
| 修改 `publicUrl` 指向了另一张证书的入口 | 地址/证书都不匹配 |
| 本地保存的 pin 丢失/损坏 | App 按“证书变更”处理，拒绝 fail-open |
| pin 预检网络失败/超时 | 代码统一走 `CERT_CHANGED` 错误层，见 §8.4 |

> 修改 `publicUrl` 本身通常先表现为 `origin_changed`（设备绑定的 origin 不匹配）；如果新入口
> 换了证书、或旧地址已不可达，App 也可能显示 `CERT_CHANGED`。两者都意味着需要按新地址/新
> 证书重新扫码配对。

处理原则：

1. 先在服务器上确认当前证书指纹（`openssl` 或管理页「从对外地址自动读取」）。
2. 更新管理页的 `publicFingerprint`，保存。
3. **重新生成**配对二维码（旧二维码不会自动更新）。
4. 在 App 里重新扫码；核对确认框里的指纹与新证书一致。
5. 如果只是网络不通，不要急着重配；先按 §8.4 排查网络。

> 证书 pin 是 leaf 证书的 SHA-256，不是公钥 pin，也不是 CA 指纹。换证书 = 换指纹；
> 不能用“安装 CA”来绕过 App 的 pin。

### 6.4 直连模式的 fail-open 与网关模式的 fail-closed

| 模式 | TLS 策略 | 证书异常时 | 适用 |
| --- | --- | --- | --- |
| 认证网关（扫码） | **fail-closed + 指纹 pin** | 阻断，显示 `CERT_CHANGED` | 推荐；公网/不可信网络 |
| 旧直连（地址+token） | **fail-open** | 继续连接，只显示黄色警告条 | 可信内网、临时使用 |

直连模式出现黄色警告条时，点开可查看证书主体、颁发者、有效期、SHA-256 和错误类型，
可复制详情。请核对确实是你的服务器后再继续；在咖啡厅、酒店等不可信网络建议改用认证网关
或先连 VPN。

### 6.5 修改 `publicUrl` 的影响：`origin_changed`

服务端在配对时会把当前 `publicUrl` 写入设备记录的 `origin`。之后：

- 从**旧 origin** 登录或续期 → 返回 `origin_changed`；
- App 的自动续期会失败，回登录页；登录页仍会报 `origin_changed`；
- 必须重新扫码配对，让设备绑定到新 origin；
- 只改端口（同一 host）也会改变 origin（origin 包含端口），同样需要重配；
- 已有登录 session 在过期前可能仍有效，但到期后必须重配。

因此：**先确定最终对外地址，再批量配对手机**。如果确实要改 `publicUrl`，改完后：

1. 重新生成配对二维码；
2. 所有手机重新扫码；
3. 在管理页「设备」里吊销旧 origin 的设备记录（可选，避免占满 8 台）。

---

## 7. 重新配对、退出会话、换服务器与清数据

### 7.1 应用菜单「忘记此设备（重新配对）」

认证网关模式入口：返回手势/返回键 →「应用菜单」→「忘记此设备（重新配对）」，
会先弹出二次确认。直连模式对应旧菜单项「重新配对」。

确认后会：

- 删除当前 `host:port` 对应的加密 deviceToken（`DeviceTokenStore.clear`）；
- 清除**当前服务器**的 WebView Cookie（包括 `dsh_gate_device` 设备绑定和 session，
  `keepDeviceCookie = false`）；
- 清除本地配对信息：服务器地址、证书 pin、认证网关标记、首次登录/设备可信标记、
  设备有效期、旧直连 token；
- 重置续期/退出状态，回到「连接 DSH Host」配对页。

它**不会**：

- 调用服务端吊销接口；服务端「设备」列表里的旧记录仍然存在；
- 清理历史上配对过的其它服务器的加密 deviceToken 记录（App 同一时间只维护一个当前配对；
  如果之前切换过服务器，彻底清理请用系统“清除数据”/卸载）。

因此：

- 同一台手机重新配对后，管理页可能出现一条新设备记录，旧记录不会自动消失；
  反复重配可能占满 8 台，需要到「设备」tab 手动「吊销」旧记录。
- 重新配对后必须重新完成网关登录（用户名 + 密码；即使旧 session 还在，App 已清掉本地 Cookie）。
- **v0.4.2 起「忘记此设备」是唯一会删除本机设备凭据的菜单项**；只想结束当前登录会话请用
  下一节的「退出当前会话」。

### 7.2 应用菜单「退出当前会话」

仅在认证网关模式显示。行为：

1. 通过 WebView `POST /__auth/logout` 让服务端删除当前 session；
2. 清除 WebView 里的 session cookie；
3. 保留 `dsh_gate_device` 设备绑定 cookie 和本地 deviceToken；
4. 回到登录页；**下次冷启动 App 会用 deviceToken 静默 renew 自动登录**
   （v0.4.2；当前进程内不会立刻把刚退出的会话建回来）。

注意：

- 「退出当前会话」不是“退出 App”，也不会解除设备绑定/让设备从管理页消失；
- 如果退出请求失败，App 会用约 4 秒超时兜底回登录页；
- 如果之后登录报 `device_required`，说明设备 cookie 已丢失/被吊销，需要「忘记此设备」后
  重新扫码；
- 如果你要的是“这台手机以后必须重新扫码”，请用「忘记此设备」。

### 7.3 更换服务器

App 同一时间只保存一个配对。更换服务器最稳妥的步骤：

1. 在新服务器管理页生成配对二维码；
2. 在 App 里「应用菜单」→「忘记此设备（重新配对）」（清掉旧服务器本地配置）；
3. 用新服务器二维码扫码配对；
4. 用新服务器用户名 + 密码完成登录；
5. 回到旧服务器管理页，吊销这台手机的旧设备记录（如果不再使用）。

如果 App 当前已经是未配对状态，也可以直接扫码；但只要还有旧配对，就必须先「忘记此设备」
回到配对页。旧服务器的设备记录仍会保留，且本地历史 token 可能残留，建议先清理。

### 7.4 服务端吊销设备（手机丢失/不再使用）

在管理页操作：

1. 打开 `https://<host>:3443/__admin`（或侧边栏「认证管理」）；
2. 进入 **「设备」** tab；
3. 找到对应设备（按手机型号/平台/最后使用时间识别）；
4. 点「吊销」，确认。

效果：

- 该设备的所有会话立即失效；
- 该设备的 deviceToken 不能再续期；
- 手机下次打开会回登录页；用户名 + 密码登录会返回 `device_required`，必须重新扫码配对
  （如果这台手机还要用）。

如果手机丢失且怀疑密码/TOTP 也泄露：

- 在「账号」tab 修改密码，或重置 TOTP；
- 这会**撤销全部设备与会话**，所有手机都要重新扫码配对；
- 修改密码/重置 TOTP 需要当前密码确认（服务端安全策略）。

### 7.5 清除 App 数据

用于彻底清空本机配对/令牌/Cookie：

- 系统设置 → 应用 → DSH Mobile → 存储 → **清除数据**（不同系统文案可能是“清除存储空间”）；
- 或卸载 App；
- 或 ADB：

```bash
adb shell pm clear ai.deepseek.dsh.mobile
```

清除后 App 回到未配对状态，需要重新扫码；服务端设备记录不会自动删除，需在管理页吊销。

### 7.6 本地操作与服务端效果对照

| 操作 | 本地配对/profile | 本地设备令牌 | WebView 设备 cookie | 服务端设备记录 | 服务端 session |
| --- | --- | --- | --- | --- | --- |
| 应用菜单「忘记此设备」 | 清除当前 | 删除当前 host | 清除（含设备绑定） | **保留** | 保留到过期 |
| 应用菜单「退出当前会话」 | 保留 | 保留 | 保留 `dsh_gate_device`，清 session | 保留 | 删除当前 |
| 系统「清除数据」/卸载 | 全部清除 | 全部清除 | 清除 | **保留** | 保留到过期 |
| 管理页「吊销设备」 | 不变 | 不变（但已失效） | 不变（但服务端拒绝） | 删除该设备 | 该设备全部失效 |
| 服务端改密码/重置 TOTP | 不变 | 不变（但已失效） | 不变 | **全部删除** | 全部失效 |

---

## 8. 故障排查

### 8.1 快速定位顺序

1. 手机能否打开 `https://<host>:3443/__auth/login`？（网络/证书/端口）
2. 服务端 `curl -k https://<host>:3443/__auth/whoami` 是否 200？
3. 管理页设备列表是否有这台手机？设备是否被吊销/过期？
4. 错误码是什么（配对弹窗 / 登录页 / 诊断日志）？
5. 最近是否改过证书、`publicUrl`、`publicFingerprint`、密码或 TOTP？

### 8.2 配对阶段

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 点「扫码配对」没反应/直接退出 | 相机权限被拒；相机被其他应用占用；无相机 | 系统设置授予相机权限；关闭占用相机的应用；无相机改用直连模式 |
| 扫码一直识别不了 | 二维码太小/反光/屏幕亮度低；不是 QR；二维码已离开页面 | 调整距离/亮度；重新生成；确认是管理页的配对二维码 |
| 提示「配对失败」，无详情 | 二维码不是合法的 `/__auth/pair` 载荷（被系统相机/微信打开过、复制时截断、版本不对） | 重新用管理页生成，直接用 App 内扫码 |
| `HTTP 401 · invalid_ticket` | ticket 无效（二维码不完整/不属于该网关/已被使用） | 重新生成二维码；确认扫码的地址与当前服务器一致 |
| `HTTP 401 · expired_ticket` | 超过 120 秒 | 重新生成，尽快扫 |
| `HTTP 401 · device_limit` | 设备数达到上限（默认 8） | 管理页「设备」吊销不用的旧设备，再生成二维码 |
| `HTTP 429 · rate_limited` | 配对/兑换请求过于频繁 | 等待 `Retry-After`（通常 1 分钟）后重试 |
| `HTTP 401 · ...` 其他 | 服务端拒绝；可能是 origin/配置异常 | 看服务端日志 `/__admin` 审计；确认插件版本与配置 |
| `SocketTimeoutException` / `SSLHandshakeException` / `ConnectException` | 网络不通、端口错、TLS 握手失败、证书 pin 不匹配 | 先用手机浏览器打开网关；核对地址/端口/证书；见 §8.4 |
| 确认框里的证书指纹与预期不符 | 扫到了别的环境/旧二维码；代理证书未更新 | **取消**，重新生成正确二维码；检查 `publicFingerprint` |
| 配对成功但立刻 `CERT_CHANGED` | 兑换后 pin 预检失败：证书已变/网络中断/保存的 pin 异常 | 核对服务端证书；网络恢复后**从系统任务列表划掉 App（或强制停止）再重开**（预检失败时错误层的“重试”不会重新执行预检）；仍失败则重新扫码 |
| 相机权限提示后回到配对页 | 用户拒绝权限 | 系统设置 → 应用 → DSH Mobile → 权限 → 相机 → 允许 |

### 8.3 登录阶段

| 现象/错误码 | 可能原因 | 处理 |
| --- | --- | --- |
| `device_required` | WebView 没有设备绑定 cookie：未扫码、清了数据、cookie 被清、设备被吊销/过期 | 重新扫码配对；确认管理页设备存在且未过期 |
| `origin_changed` | 服务端 `publicUrl` 改了，设备 origin 不匹配 | 重新扫码；或由管理员把 publicUrl 完全改回原值（旧设备绑定可恢复匹配） |
| `invalid_credentials` | 用户名或密码错误 | 重新输入；注意大小写/输入法；忘记密码在服务端重置 |
| `rate_limited` | 同一 IP+用户名 15 分钟内失败 5 次，锁 15 分钟 | 等待后重试；不要连续试错 |
| `not_configured` | 服务端未初始化 | 用初始化链接完成管理员/TOTP 设置 |
| `csrf_invalid` | 登录页过期/被刷新 | 刷新页面重试 |
| 登录后黑屏/白屏 | 反代 Host/Origin 不对、静态资源被拒；WebView 渲染问题；服务端上游异常 | 见 §8.5 |
| 一直回登录页 | session 续期失败；设备被吊销/过期；`origin_changed` | 看诊断日志/服务端审计；必要时重新配对 |
| 登录页按钮无反应 | JS 被反代破坏/缓存；WebView 太旧 | 刷新；更新 Android System WebView；检查 Caddy Host |

### 8.4 无法连接 / 证书错误 / 网络错误

先区分是“网络不通”还是“证书 pin 不匹配”：

1. 用手机浏览器打开 `https://<host>:3443/__auth/login`：
   - 打不开/超时 → 网络、DNS、防火墙、frp/Caddy、端口问题；
   - 能打开但证书警告 → 浏览器正常；App 认证网关模式按 pin，不依赖系统信任。
2. 在服务器上执行：

   ```bash
   curl -k -sS https://<host>:3443/__auth/whoami
   curl -k -sS https://auth.example.com:8443/__auth/whoami   # 公网场景
   ```

   两个地址应返回 200 JSON；如果只有一个通，检查 frp/Caddy 和 `publicUrl`。
3. 检查防火墙/安全组：
   - 内网放行 3443；
   - 公网只放行 Caddy/frps 入口（如 8443），不要放行 3080。
4. 检查 frpc/Caddy 是否在运行；Caddy 的 Host 是否保留（见 §6.2.1）。
5. 如果错误层显示 `CERT_CHANGED`，但网络明明正常：
   - 对比管理页「二维码当前生效指纹」与服务器实际证书；
   - 证书换过 → 更新 `publicFingerprint`，重新生成二维码，重扫；
   - 没有换证书 → 检查 `publicUrl` 是否指向了另一个入口。

> App 的 pin 预检失败（包括 DNS 失败、连接超时）都会显示 `CERT_CHANGED` 文案。
> 不要只凭这句提示就断定证书坏了，先确认网络。预检失败时页面尚未加载，错误层的“重试”
> 只会重载空白页，不会重新执行 pin 预检；网络恢复后请从系统任务列表划掉 App（或强制停止）
> 再重开，或重新扫码。

### 8.5 页面黑屏/白屏

认证网关模式下，页面由 `dsh-auth-gate` 反代 `dsh`；黑屏/白屏常见原因：

| 原因 | 表现 | 处理 |
| --- | --- | --- |
| Caddy/nginx 改写 Host 或 Origin | 登录页能开，登录后黑屏；控制台/网络里 CSS/JS 返回 403 或 JSON | 保留原始 Host（含端口）；见 §6.2.1 的 `header_up Host {http.request.hostport}` |
| 回源用明文 HTTP | 网关配置要求 HTTPS；反代 502/连接失败 | Caddy 回源改 HTTPS，并允许自签回源证书 |
| 上游 `dsh` 未运行 | 网关 502/错误页 | 重启 `dsh`，检查 3080 |
| session/cookie 失效 | 页面跳登录页或空白 | 重新登录；必要时重新配对 |
| WebView 渲染进程崩溃 | App 显示“页面渲染进程已停止，请重试” | 点「重试」重建 WebView；仍频繁崩溃则更新 WebView/系统 |
| WebView 版本过旧 | 页面样式错乱、白屏、JS 报错 | 更新 Android System WebView / Chrome |
| 旧版 App 悬浮按钮遮挡 | v0.4.0 及更早右上角 ⋮ 压住 dsh 右侧栏按钮 | 升级到 v0.4.1；菜单改用返回手势 |

v0.4.1 起没有悬浮 ⋮ 按钮，不要等右上角按钮；从屏幕边缘内滑或按返回键。

### 8.6 其他常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 语音按钮提示不支持/无法录音 | 页面是 `http://`；未授予麦克风；页面不支持 | 改用 `https://` 网关；系统设置授予麦克风；看诊断日志 |
| 下载失败 | `blob:`/`data:` 链接；网络中断；Android 9 及以下未给存储权限 | 用 http(s) 直链；重试；授予存储权限；看诊断日志 |
| 文件选择器打不开 | 系统文件选择器异常/无可用应用 | 重试；检查系统组件；换用其他文件管理器 |
| 复制不生效 | 页面 Clipboard API 被策略禁止；原生桥异常 | 再点一次；看 Toast/诊断日志；Android 13 系统提示属正常 |
| 安装失败 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不同（debug/第三方/modified） | 卸载旧包（会丢数据）后安装官方 release，再重新配对 |
| 安装失败 `INSTALL_FAILED_VERSION_DOWNGRADE` | 新包 versionCode 更低 | 用 `adb install -r -d` 或先卸载（会丢数据） |
| 覆盖安装后要求重新配对 | 数据被清/签名变化/系统恢复 | 重新扫码；同签名正常覆盖不会清配对 |
| 返回手势呼不出菜单 | 系统关闭了返回手势；手势 App 拦截；三键导航被隐藏 | 改用系统返回键/三键导航；检查系统手势设置 |
| 频繁回登录页 | 设备被吊销/过期；`origin_changed`；session 续期失败 | 管理页看设备状态/审计；重新配对 |
| 时间/验证码错误（管理页） | 服务器时间不准 | 同步 NTP/chrony 后重新生成二维码 |

### 8.7 诊断日志：导出与提供给维护者

App 内：

1. 在 WebView 主界面用返回手势/返回键打开「应用菜单」；
2. 点「诊断日志」；
3. 点「复制」；
4. 粘贴到邮件/IM/Issue 中。

日志内容与限制：

- 内存环形缓冲，最多 **300 条**；进程被杀后丢失，不落盘；
- 每条包含时间、级别、来源、消息；
- 来源包括 WebView console、JS 桥、TLS pin、下载等；
- 写入时会对 URL 中的 `token=` 参数做 `<REDACTED>` 脱敏；
- **不会自动附带** App 版本、设备型号、服务端版本；请在反馈时另附「关于」里的
  versionName（`0.4.2`）和 Android 版本；
- 日志可能仍包含服务器地址、页面片段、错误堆栈；发送前请自行检查，
  **不要**附带密码、TOTP、deviceToken、Cookie、配对二维码或截图。

反馈时建议提供：

- App 版本（关于对话框）、手机型号/Android 版本；
- 服务端 `dsh` 与 `dsh-auth-gate` 版本；
- 出问题的地址类型（内网/公网，不要发真实 token）；
- 错误码原文（如 `HTTP 401 · origin_changed`）与操作步骤；
- 诊断日志复制内容；
- 服务端相关日志（注意脱敏）：

```bash
journalctl -u dsh -n 200 --no-pager | grep -iE 'auth-gate|pair|login|error'
```

- 管理页「审计」tab 的最近记录（同样不要发密码/TOTP）。

> 配对页同样可以用返回手势/返回键打开「应用菜单」→「诊断日志」。如果系统返回手势被
> 拦截，或 App 崩溃，可用 adb logcat：

```bash
adb logcat -s DSHMobile DSHAuthGate DSHQrScan DSHDeviceToken
```

---

## 9. 安全提示

1. **设备令牌/设备 cookie 等同账号凭据**。拿到 deviceToken 或 `dsh_gate_device` cookie
   的人可以在有效期内续期会话；不要外传、截图、提交到公开仓库或聊天记录。
2. **手机丢失先吊销**：管理页「设备」→ 吊销该设备；吊销会让它的所有会话立即失效。
   若怀疑密码/TOTP 也泄露，修改密码或重置 TOTP（会撤销全部设备，所有手机重新配对）。
3. **不要在不可信/共享/已 root 的设备上配对**。配对信息在应用私有存储中：
   - deviceToken 经 Android Keystore AES-256-GCM 加密；
   - 服务器地址与证书 pin 以明文保存在 SharedPreferences；
   - 已 root 设备可能被读取/导出，风险自担。
4. **只在公网暴露认证网关**。不要把 `dsh` 的 3080 直接映射到公网；用防火墙限制为
   网关/本机访问。frp/Caddy 只暴露 8443（或你的入口端口）。
5. **二维码一次性、120 秒有效**，但截图仍可能被他人抢兑。生成后立即扫；不要发群、
   不要存公共相册；怀疑泄露就重新生成（旧 ticket 过期作废）。
6. **TOTP 与密码分开保管**。TOTP 只用于管理页生成配对二维码；登录只需用户名 + 密码。
   不要用弱密码；服务端要求至少 10 位。
7. **App 的「退出应用」不等于退出登录**：它只结束界面。共享设备上请用「退出当前会话」或
   「忘记此设备」/清除数据；只想下次仍自动登录时用「退出当前会话」。
8. **证书 pin 是 leaf SHA-256**：换证书/换代理证书后必须重新配对；不要通过安装未知 CA、
   关闭校验或改用直连 fail-open 来“绕过”证书错误。
9. **直连模式 fail-open 有 MITM 风险**：证书不受信任时仍会连接，只显示警告条。不可信网络
   请使用认证网关模式（严格 pin）或先连 VPN。
10. **只从官方 Release 下载 APK 并校验 SHA-256/签名**。不要安装第三方修改版；它会接触你的
    服务器地址、密码和设备令牌。
11. App `allowBackup=false`，配对数据不进入系统备份；换机/恢复出厂后需重新配对。
12. 服务端管理密钥（`admin.key`）只保存在服务器（0600），不要写进 App、脚本或公开文档；
    管理页只通过 HTTPS 访问。

---

## 10. 升级与回滚

### 10.1 版本与资产

| 版本 | versionCode | APK | SHA-256 | 签名证书 SHA-256 |
| --- | --- | --- | --- | --- |
| v0.4.2（当前） | 9 | `DSH-Mobile-v0.4.2-release.apk` | `1ad91b691e183c03039c5f6e88a3639c9474a68a06b6b96f2620d3e9b61b3495` | `45dee26ac9aa785dda281970bc07072665624ef381616d310331f849216f62c8` |
| v0.4.1 | 8 | `DSH-Mobile-v0.4.1-release.apk` | `a9ad7979c1487e31b0c55ba374c39aed6e3f5b5990c3421ebf38748838848e80` | 同上 |
| v0.4.0 | 7 | `DSH-Mobile-v0.4.0-release.apk` | `5d19e16e9445fc54e94f82ba1e89f356ceed03df71c7dbc726847b16c35552c5` | 同上 |

v0.4.1 → v0.4.2 的主要变化：

- 认证网关模式冷启动静默登录：用 deviceToken 换会话后直接加载 GUI，不闪登录页；
- 前台恢复自动续期（距上次成功 renew ≥1 小时，不刷新页面）；401 下次前台/导航回登录页；
- 菜单拆分：「退出当前会话」（保留设备凭据，下次冷启动自动登录）与
  「忘记此设备（重新配对）」（删除本机 deviceToken/cookie/profile，必须重新扫码）；
- 「关于」显示设备有效期至（服务端未返回时「—」）；
- 版本号 `0.4.2` / versionCode `9`；
- 首次扫码配对后仍必须完成网关登录；直连模式与返回手势状态机不变。

v0.4.0 → v0.4.1 的主要变化：

- 移除 WebView 右上角悬浮 ⋮ 按钮（不再遮挡 `dsh` 右上角「打开右侧边栏」）；
- 应用菜单改由系统返回手势/返回键打开，新增「退出应用」；
- 返回状态机：错误层 → 键盘 → 网页后退 → 关闭菜单 → 弹出菜单；
- 关闭预测性返回（`enableOnBackInvokedCallback=false`），统一走 `OnBackPressedDispatcher`；
- 顶部警告条不再为悬浮按钮下移；
- 版本号 `0.4.1` / versionCode `8`；
- 配对、设备令牌、续期逻辑与 v0.4.0 一致。

### 10.2 覆盖升级

同签名（官方 v0.4.1 → v0.4.2，或 v0.4.0 → v0.4.2）：

1. 下载并校验新 APK（§2.2）。
2. 点击安装，系统显示「更新」；或：

   ```bash
   adb install -r DSH-Mobile-v0.4.2-release.apk
   ```

3. 数据保留：服务器地址、pin、加密 deviceToken、WebView Cookie 均保留；升级后无需重新配对。
4. 打开 App →「关于」确认版本为 `0.4.2`；已登录过的网关设备应静默进入 GUI，再试一次返回手势菜单。

### 10.3 回滚到旧版本

同签名旧版本可以覆盖安装，但 Android 默认阻止降级：

```bash
adb install -r -d DSH-Mobile-v0.4.0-release.apk
```

- `-d` 允许 versionCode 降低；仍需签名一致。
- 一般保留数据；v0.4.0/v0.4.1/v0.4.2 的配对数据结构兼容（v0.4.2 只新增非敏感的可选标记）。
- 部分厂商系统/安装器可能仍拒绝降级；此时只能卸载后安装（会清数据，需重新配对）。
- 回滚前建议确认新版本问题是否与 App 有关（也可能是服务端/网络），并记录配对信息。

**不同签名不能回滚/覆盖**：必须卸载（数据丢失）再安装。

### 10.4 升级后检查

- [ ] 「关于」显示 `0.4.2`；认证网关模式还会显示「设备有效期至：…」或「—」。
- [ ] 打开后直接进入 GUI，未要求重新配对/登录（同签名覆盖正常；首次配对设备除外）。
- [ ] 返回手势/返回键能弹出菜单（无悬浮 ⋮）。
- [ ] 认证网关模式菜单包含「退出当前会话」「忘记此设备（重新配对）」；
      直连模式保持「重新配对」。
- [ ] 语音/下载/剪贴板按需可用。
- [ ] 若提示 `CERT_CHANGED`，检查是否在升级期间更换了证书/代理（见 §6.3）。

---

## 11. 从源码构建（可选）

只有在你需要自行构建/修改客户端时才需要本节。普通用户直接用 Release APK 即可。

### 11.1 工具链

| 项目 | 要求 |
| --- | --- |
| JDK | 17 或更高（工程 `jvmTarget = 17`） |
| Android SDK | Platform 35（`compileSdk 35`） |
| Build-Tools | 35.0.0（或兼容版本，用于 `apksigner`/`aapt2`） |
| Gradle | 使用仓库自带 Wrapper（Gradle 8.10.2），无需本机安装 |
| 网络 | 首次构建需下载 Gradle 与依赖 |

设置环境变量（路径按实际替换）：

```bash
export JAVA_HOME=/path/to/jdk-17-or-21
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
```

### 11.2 `local.properties`

在仓库根目录创建/编辑 `local.properties`（该文件已被 `.gitignore` 忽略，**不要提交**）：

```properties
sdk.dir=/path/to/android-sdk
```

如果只构建 debug 包，到这里即可。

### 11.3 release 签名配置

签名材料与口令**不入库**。在 `local.properties` 中增加：

```properties
dsh.keystore.path=signing/dsh-mobile.jks
dsh.keystore.storePassword=<keystore 口令>
dsh.keystore.keyAlias=<key alias>
dsh.keystore.keyPassword=<key 口令>
```

- `dsh.keystore.path` 可省略，默认 `signing/dsh-mobile.jks`（相对仓库根目录）。
- 口令也可用环境变量 `DSH_KEYSTORE_PASSWORD` 兜底（store/key 口令相同时只需设置它）。
- 只有 keystore 文件存在、路径/alias/口令齐全时，Gradle 才创建 release signingConfig；
  否则仍可 `assembleRelease`，但产物是**未签名**的，不能直接覆盖安装官方包。

生成自己的 keystore 示例（**不要覆盖官方发布密钥**）：

```bash
keytool -genkeypair -v -keystore signing/dsh-mobile.jks \
  -alias dsh-mobile -keyalg RSA -keysize 2048 -validity 3650
```

> 发布用 release keystore 只保存在维护者本机 toolchain/构建环境（本地 `signing/`，已被
> `.gitignore` 忽略），**不随仓库或 Release 资产分发**。自建签名与官方签名不同，无法覆盖安装官方包；
> 用户需要先卸载官方包（数据丢失）再安装你的构建。

### 11.4 构建

在**仓库根目录**执行：

```bash
# debug 包
./gradlew assembleDebug

# release 包（需签名配置；未配置时产物未签名）
./gradlew assembleRelease
```

Windows 使用 `gradlew.bat`。

产物路径：

- `app/build/outputs/apk/debug/app-debug.apk`
- 已配置签名：`app/build/outputs/apk/release/app-release.apk`
- 未配置签名：通常为 `app/build/outputs/apk/release/app-release-unsigned.apk`

### 11.5 校验构建产物

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

sha256sum app/build/outputs/apk/release/app-release.apk
```

如果签名证书不是官方证书，产物只能用于自用/测试，不能作为官方升级包分发。

### 11.6 构建注意事项

- 不要提交 `local.properties`、`signing/`、`*.jks`、`*.keystore` 或任何口令。
- debug 包与 release 包签名不同，不能互相覆盖；测试时先卸载旧包。
- 修改 `versionCode`/`versionName` 时同步更新发布说明与校验文件。
- App 不打包前端资源；构建产物只是 WebView 外壳，功能仍取决于所连接的 `dsh` 服务端。

---

## 附录 A：地址与端口速查

| 用途 | 地址/端口 | 是否对公网 |
| --- | --- | --- |
| 认证网关（内网） | `https://<host>:3443` | 否（仅内网/VPN） |
| 认证网关（公网 Caddy） | `https://auth.example.com:8443` | 是（仅此入口） |
| `dsh` Web GUI 上游 | `https://<host>:3080` | **否** |
| 管理页 | `https://<host>:3443/__admin` 或侧边栏「认证管理」 | 仅管理员网络 |
| 配对二维码路径 | `/__auth/pair#v=1&t=...&fp=...` | 一次性 |
| 登录页 | `/__auth/login` | 经网关 |
| 续期接口 | `POST /__auth/renew` | App 原生调用 |
| 退出当前会话接口 | `POST /__auth/logout` | App WebView 调用（保留设备凭据） |

## 附录 B：错误码速查

| 错误码 | 出现位置 | 含义/处理 |
| --- | --- | --- |
| `invalid_ticket` | 配对兑换 | ticket 无效/已用/不完整；重新生成二维码 |
| `expired_ticket` | 配对兑换 | 超过 120 秒；重新生成 |
| `device_limit` | 配对兑换 | 设备数达上限；管理页吊销旧设备 |
| `rate_limited` | 配对/登录/续期/QR | 请求过频；等待后重试 |
| `device_required` | 登录/续期 | 无有效设备绑定；重新扫码 |
| `origin_changed` | 登录/续期 | `publicUrl` 改了；重新扫码 |
| `invalid_credentials` | 登录 | 用户名/密码错误 |
| `not_configured` | 登录 | 服务端未初始化 |
| `csrf_invalid` | 登录/管理页 | 页面过期；刷新 |
| `CERT_CHANGED` | App 本地错误层 | 证书 pin 不匹配/预检失败；更新证书指纹并重扫，或排查网络 |

## 附录 C：相关链接与文件

- 客户端仓库：<https://github.com/chjy090/DSH-Mobile>
- 客户端 Release：<https://github.com/chjy090/DSH-Mobile/releases>
- 认证网关插件：<https://github.com/chjy090/dsh-auth-gate>
- 插件 README（安装、初始化、frp、管理页、安全模型）：
  <https://github.com/chjy090/dsh-auth-gate/blob/main/README.md>
- 本仓库 `README.md`：构建、签名、功能概览
- 本文件：`docs/installation.md`

---

## 文档核对说明

- 本文按 DSH Mobile v0.4.2 源码（`MainActivity`、`WebHost`、`AuthGateClient`、`CertPin`、
  `DeviceTokenStore`、`QrPairPayload`、`QrScanActivity`、`SessionStore`、`Downloader`、
  `Diagnostics`）与 `dsh-auth-gate` v0.6.2 源码/README 核对。
- APK 的 SHA-256、签名证书、versionCode 与 ABI 已在构建机用 `sha256sum`、`apksigner`、
  `aapt2` 核对；真机上的权限弹窗、厂商设置路径和返回手势行为可能因 Android 版本/OEM 不同，
  请按实际界面操作。
- App 的配对、登录、启动静默登录、前台续期、退出会话/忘记设备、菜单和证书 pin 行为以本仓库当前源码为准；服务端行为
  （TOTP、设备有效期、限流、`origin_changed`）以 `dsh-auth-gate` 版本为准。
