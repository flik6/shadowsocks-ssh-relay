# Shadowsocks SSH Relay

Android 8.0+ 应用：在手机上运行 Shadowsocks TCP 服务端，再通过 SSH 反向端口转发，将服务映射到远程服务器。界面只有 **服务** 和 **设置** 两页。

`SS 客户端 → 远程服务器:映射端口 → SSH 隧道 → 手机本地 SS 端口 → 手机网络出口`

## 使用

1. 在远程服务器上，把一把 SSH 密钥的**公钥**加入登录用户的 `~/.ssh/authorized_keys`。
2. 在应用“设置”页填写 SSH 主机、端口、用户名、远程映射端口、本地 SS 端口、监听地址、SS 加密方式和密码；只需向手机导入对应的**无口令私钥**。保存设置。
3. 在“服务”页启动。首次连接时，应用显示服务器的 SSH 主机公钥 SHA256 指纹。请通过可信渠道核对后再确认。应用会记住该主机和端口的指纹；若以后发生变化，会拒绝连接。可在“设置”页清除旧信任，核对新指纹后重新确认。
4. 在“服务”页复制 `ss://` 链接，导入另一台设备的 Shadowsocks 客户端。

这里有两对不同概念：**登录密钥**由手机上的私钥和服务器 `authorized_keys` 中的对应公钥组成；**主机指纹**用于手机核验服务器身份，无需提前手填，也不是登录所需的第二把密钥。

远程公网访问取决于 SSH 服务端的 `GatewayPorts`、防火墙和路由器端口转发。映射使用 SSH `-R`，只支持 TCP，不支持 Shadowsocks UDP 或 Android VPN/TUN。代理出口是手机网络。

## 构建

用 Android Studio 打开项目并构建 `app`。需要 JDK 17 和 Android SDK 35；命令行：

```sh
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。推送到 `main` 后，GitHub Actions 的 [Build Android APK](https://github.com/flik6/shadowsocks-ssh-relay/actions/workflows/android.yml) 会构建并上传 `shadowsocks-ssh-relay-debug-apk`。

应用使用 [mwiede/JSch](https://github.com/mwiede/jsch) 建立 SSH 隧道。私钥保存在应用私有目录，SS 密码保存在应用私有 SharedPreferences 中。服务运行时保持前台通知并尝试断线重连。更改配置后需停止再启动服务。
