# Shadowsocks SSH Relay

Android 8.0+ 应用：在手机上运行 Shadowsocks TCP 服务端，再通过 SSH 反向端口转发，将服务映射到远程服务器。界面只有 **服务** 和 **设置** 两页。

`SS 客户端 → 远程服务器:映射端口 → SSH 隧道 → 手机本地 SS 端口 → 手机网络出口`

## 使用

1. 在远程服务器上，把一把 SSH 密钥的**公钥**加入登录用户的 `~/.ssh/authorized_keys`。
2. 在应用“设置”页填写 SSH 地址（如 `pve.example.com:2200`）、用户名、远程端口、手机 SS 端口、加密方式和密码。远程及手机监听地址收在“高级选项”中。手机上的**无口令私钥**可从文件导入，也可直接粘贴；私钥保存后只显示状态，不回显内容。
3. 在“服务”页启动。首次连接时，应用显示服务器的 SSH 主机公钥 SHA256 指纹。请通过可信渠道核对后再确认。应用会记住该主机和端口的指纹；若以后发生变化，会拒绝连接。可在“设置”页清除旧信任，核对新指纹后重新确认。
4. 在“服务”页复制 `ss://` 链接，导入另一台设备的 Shadowsocks 客户端。

这里有两对不同概念：**登录密钥**由手机上的私钥和服务器 `authorized_keys` 中的对应公钥组成；**主机指纹**用于手机核验服务器身份，无需提前手填，也不是登录所需的第二把密钥。

远程公网访问取决于 SSH 服务端的 `GatewayPorts`、防火墙和路由器端口转发。映射使用 SSH `-R`，只支持 TCP，不支持 Shadowsocks UDP 或 Android VPN/TUN。代理出口是手机网络。

SSH 断线后应用会自动重试，并继续使用配置的域名建立新连接；DDNS 指向新 IP 后，恢复速度受 DNS 缓存和重试间隔影响。Android 系统若结束前台服务，则需要手动重新启动。

停止服务时，应用会先撤销当前 SSH 会话的远程转发，再断开 SSH 并关闭手机上的 SS 端口。同一台 SSH 服务器的同一远程端口只能由一个转发会话占用；若另一台设备仍占用该端口，应用会显示“转发失败”并自动重试。请停止另一台设备的转发，或为两台设备设置不同的远程端口。SSH 服务端禁止远程转发时，也可能出现相同的失败提示。

## 构建

用 Android Studio 打开项目并构建 `app`。需要 JDK 17 和 Android SDK 35；命令行：

```sh
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。推送到 `main` 后，GitHub Actions 的 [Build Android APK](https://github.com/flik6/shadowsocks-ssh-relay/actions/workflows/android.yml) 会构建并上传 `shadowsocks-ssh-relay-debug-apk`。

应用使用 [mwiede/JSch](https://github.com/mwiede/jsch) 建立 SSH 隧道。SSH 私钥由 Android Keystore 中的密钥以 AES-GCM 加密后保存在应用私有目录；连接时只在内存中解密，不写入明文临时文件。旧版应用的明文私钥会在升级后迁移为加密文件并删除旧文件。粘贴私钥不会自动清除系统剪贴板。SS 密码目前保存在应用私有 SharedPreferences 中。服务运行时保持前台通知并尝试断线重连。更改连接配置后需停止再启动服务。
