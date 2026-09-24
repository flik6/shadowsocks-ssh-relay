package cn.coco.ssrelay;

import android.app.*;
import android.content.*;
import android.os.*;
import com.jcraft.jsch.*;
import java.io.File;

public final class RelayService extends Service {
    static final String START="cn.coco.ssrelay.START", STOP="cn.coco.ssrelay.STOP";
    private static final String CHANNEL="relay";
    private volatile boolean running;
    private volatile Session session;
    private volatile ShadowsocksServer server;
    private Thread thread;

    static String status(Context c) { return c.getSharedPreferences("status",MODE_PRIVATE).getString("message","已停止"); }
    static boolean isRunning(Context c) { return c.getSharedPreferences("status",MODE_PRIVATE).getBoolean("running",false); }
    private void status(String message) {
        getSharedPreferences("status",MODE_PRIVATE).edit().putString("message",message).apply();
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null && running) nm.notify(1,notification(message));
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Shadowsocks SSH Relay 服务",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) { stopRelay(); stopSelf(); return START_NOT_STICKY; }
        if(!running) {
            running=true;
            getSharedPreferences("status",MODE_PRIVATE).edit().putBoolean("running",true).apply();
            startForeground(1,notification("正在启动"));
            thread=new Thread(this::runRelay,"ss-relay"); thread.start();
        }
        return START_NOT_STICKY;
    }
    private Notification notification(String message) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop=new Intent(this,RelayService.class).setAction(STOP);
        PendingIntent stopAction=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Shadowsocks SSH Relay").setContentText(message).setContentIntent(content)
                .addAction(android.R.drawable.ic_media_pause,"停止",stopAction).setOngoing(true).build();
    }
    private void runRelay() {
        try {
            Config cfg=Config.load(this); cfg.validate();
            File key=new File(getFilesDir(),"ssh_private_key");
            if(!key.isFile()) throw new IllegalArgumentException("请先在设置页导入 SSH 私钥");
            server=new ShadowsocksServer(cfg.localBind,cfg.localPort,cfg.method,cfg.password);
            int retry=0;
            while(running) {
                try {
                    status("本地 SS 已启动，正在连接 SSH…");
                    JSch jsch=new JSch();
                    jsch.addIdentity(key.getAbsolutePath());
                    HostTrust trust=new HostTrust(this,cfg.host,cfg.sshPort);
                    jsch.setHostKeyRepository(trust);
                    Session s=jsch.getSession(cfg.user,cfg.host,cfg.sshPort);
                    session=s;
                    s.setConfig("StrictHostKeyChecking","yes");
                    s.setConfig("server_host_key","rsa-sha2-512,rsa-sha2-256");
                    s.setServerAliveInterval(15000); s.setServerAliveCountMax(2);
                    s.connect(12000);
                    s.setPortForwardingR(cfg.bind,cfg.remotePort,"127.0.0.1",cfg.localPort);
                    retry=0;
                    status("SSH 转发已建立；远端实际监听地址请在服务器核对（端口 "+cfg.remotePort+"）");
                    while(running && s.isConnected()) Thread.sleep(2000);
                } catch(Exception e) {
                    if(running) {
                        HostTrust trust=new HostTrust(this,cfg.host,cfg.sshPort);
                        if(!trust.pending().isEmpty())
                            status(trust.changed()?"SSH 主机密钥已变化，请核对服务器":"等待确认 SSH 服务器指纹");
                        else status("连接失败："+e.getMessage()+"；稍后重试");
                    }
                } finally {
                    Session s=session; session=null;
                    if(s!=null) s.disconnect();
                }
                if(running) Thread.sleep(Math.min(60000,5000L*(1L<<Math.min(4,retry++))));
            }
        } catch(Exception e) { status("启动失败："+e.getMessage()); }
        finally {
            running=false;
            getSharedPreferences("status",MODE_PRIVATE).edit().putBoolean("running",false).apply();
            ShadowsocksServer s=server; server=null; if(s!=null) s.close();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    private void stopRelay() {
        running=false;
        getSharedPreferences("status",MODE_PRIVATE).edit().putBoolean("running",false).apply();
        Session s=session; if(s!=null) s.disconnect();
        ShadowsocksServer ss=server; if(ss!=null) ss.close();
        if(thread!=null) thread.interrupt();
        status("已停止");
    }
    @Override public void onDestroy() { if(running) stopRelay(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

}
