package cn.coco.ssrelay;

import android.content.Context;
import android.content.SharedPreferences;

final class Config {
    final String host, user, bind, localBind, method, password;
    final int sshPort, remotePort, localPort;
    Config(String host, int sshPort, String user, int remotePort, int localPort,
           String bind, String localBind, String method, String password) {
        this.host=host; this.sshPort=sshPort; this.user=user; this.remotePort=remotePort;
        this.localPort=localPort; this.bind=bind; this.localBind=localBind; this.method=method;
        this.password=password;
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("config", Context.MODE_PRIVATE); }
    static Config load(Context c) {
        SharedPreferences p=prefs(c);
        return new Config(p.getString("host",""), p.getInt("sshPort",22),
                p.getString("user",""), p.getInt("remotePort",61000),
                p.getInt("localPort",8388), p.getString("bind","0.0.0.0"),
                p.getString("localBind","127.0.0.1"),p.getString("method","aes-256-gcm"),
                p.getString("password",""));
    }
    void save(Context c) {
        prefs(c).edit().putString("host",host).putInt("sshPort",sshPort).putString("user",user)
                .putInt("remotePort",remotePort).putInt("localPort",localPort).putString("bind",bind)
                .putString("localBind",localBind).putString("method",method)
                .putString("password",password).apply();
    }
    void validate() {
        if(host.trim().isEmpty() || user.trim().isEmpty()) throw new IllegalArgumentException("请填写 SSH 主机和用户名");
        if(sshPort<1 || sshPort>65535 || remotePort<1 || remotePort>65535 || localPort<1 || localPort>65535)
            throw new IllegalArgumentException("端口必须在 1–65535 之间");
        if(password.isEmpty()) throw new IllegalArgumentException("请在设置页填写 SS 密码");
        if(!bind.equals("0.0.0.0") && !bind.equals("127.0.0.1")) throw new IllegalArgumentException("远程绑定地址只支持 0.0.0.0 或 127.0.0.1");
        if(!localBind.equals("0.0.0.0") && !localBind.equals("127.0.0.1")) throw new IllegalArgumentException("本地绑定地址只支持 0.0.0.0 或 127.0.0.1");
        if(!method.equals("aes-128-gcm") && !method.equals("aes-256-gcm")) throw new IllegalArgumentException("不支持的 SS 加密方式");
    }
}
