# Shadowsocks SSH Relay

一个 Android 8.0+ 的 Shadowsocks TCP 服务端与 SSH 反向端口映射应用。底部是 **服务 / 密钥 / 设置** 三个标签。远程 SSH 主机/IP 与端口、远程映射端口、本地 SS 端口与监听地址均可在手机上修改。首次使用时需要填写自己的 SSH 主机、用户名与主机指纹。

## 工作方式

`SS 客户端 → 你的服务器:远程端口 → SSH 远程端口转发 → 安卓 本地 SS 端口 → 安卓网络出口`

实现的是经典 Shadowsocks AEAD **TCP** 服务端；可在手机上选择 `aes-256-gcm` 或 `aes-128-gcm`，修改 SS 密码。SSH `-R` 只能承载 TCP；这个版本不支持 Shadowsocks UDP，也不提供 Android VPN/TUN 功能。

## 使用

1. 在“密钥”页选择 SS 加密方式，生成或填写 SS 密码，导入一把**无口令** SSH 私钥，并核对 SSH 主机 SHA256 指纹后保存。建议为本应用创建单独的 SSH 私钥，勿直接复制日常使用的 SSH 主钥。
2. 在“设置”页修改 SSH 主机/IP、SSH 端口、远程映射端口、本地 SS 端口、本地与远程绑定地址。修改配置后需停止再启动服务。
3. 在“服务”页启动。通知栏会显示状态；可以复制 `ss://` 链接给标准 Shadowsocks 客户端。
4. 在另一设备上通过 SS 客户端测试 TCP 网页访问。

SSH 主机指纹默认留空，需从可信渠道核对后填写。更换目标或重装 SSH 后，重新核对新指纹再更新。

公网访问取决于 SSH 服务端 `GatewayPorts`、防火墙和公网入口的 TCP 端口转发。若服务器位于内网，还需要把公网端口转发到 SSH 实际登录的那台机器。

## 构建

用 Android Studio 打开此目录并构建 `app`。需要 Android SDK 35 和 JDK 17。命令行可用项目自带的 Gradle 8.9 wrapper：

```sh
./gradlew assembleDebug
```

输出为 `app/build/outputs/apk/debug/app-debug.apk`。应用使用 [mwiede/JSch](https://github.com/mwiede/jsch) 建立 SSH 隧道，Shadowsocks TCP AEAD 服务端代码在 `ShadowsocksServer.java`。发布前应在实际安卓设备上执行端到端测试。

推送到 `main` 后，GitHub Actions 的 **Build Android APK** 工作流会自动构建调试版 APK，并上传为 `shadowsocks-ssh-relay-debug-apk` 构建产物；也可在 Actions 页面手动运行。

本次交付已检查 Java 语法与 AndroidManifest XML；由于当前工作机没有 JDK / Android SDK，尚未编译 APK，也尚未在安卓设备上验证运行。

## 限制

- SSH 私钥保存在 Android 应用私有目录中；SS 密码保存在应用私有 SharedPreferences 中。不要备份或分享应用数据。
- 当前只支持无口令 SSH 私钥；无密码 SSH 登录依赖该私钥在远端 `authorized_keys` 中已授权。
- 手机网络切换、后台省电策略或系统终止前台服务可能中断隧道。服务运行期间会自动重连；系统终止服务后需要手动重新启动。
- 代理出口是安卓设备的网络，而非远程 SSH 服务器的网络。
