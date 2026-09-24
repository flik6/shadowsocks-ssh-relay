package cn.coco.ssrelay;

import android.app.*;
import android.content.*;
import android.os.*;
import com.jcraft.jsch.*;
import java.io.File;
import java.security.MessageDigest;
import java.util.Base64;

public final class RelayService extends Service {
    static final String START="cn.coco.ssrelay.START", STOP="cn.coco.ssrelay.STOP";
    private static final String CHANNEL="relay";
    private volatile boolean running;
    private volatile Session session;
    private volatile ShadowsocksServer server;
    private Thread thread;

    static String status(Context c) { return c.getSharedPreferences("status",MODE_PRIVATE).getString("message","已停止"); }
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
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Shadowsocks SSH Relay").setContentText(message).setContentIntent(content)
                .addAction(android.R.drawable.ic_media_pause,"停止",stopAction).setOngoing(true).build();
    }
    private void runRelay() {
        try {
            Config cfg=Config.load(this); cfg.validate();
            File key=new File(getFilesDir(),"ssh_private_key");
            if(!key.isFile()) throw new IllegalArgumentException("请先在“密钥”页导入 SSH 私钥");
            server=new ShadowsocksServer(cfg.localBind,cfg.localPort,cfg.method,cfg.password);
            int retry=0;
            while(running) {
                try {
                    status("本地 SS 已启动，正在连接 SSH…");
                    JSch jsch=new JSch();
                    jsch.addIdentity(key.getAbsolutePath());
                    jsch.setHostKeyRepository(new PinnedHostKey(cfg.fingerprint));
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
                    if(running) status("连接失败："+e.getMessage()+"；稍后重试");
                } finally {
                    Session s=session; session=null;
                    if(s!=null) s.disconnect();
                }
                if(running) Thread.sleep(Math.min(60000,5000L*(1L<<Math.min(4,retry++))));
            }
        } catch(Exception e) { status("启动失败："+e.getMessage()); }
        finally {
            running=false;
            ShadowsocksServer s=server; server=null; if(s!=null) s.close();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    private void stopRelay() {
        running=false;
        Session s=session; if(s!=null) s.disconnect();
        ShadowsocksServer ss=server; if(ss!=null) ss.close();
        if(thread!=null) thread.interrupt();
        status("已停止");
    }
    @Override public void onDestroy() { if(running) stopRelay(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class PinnedHostKey implements HostKeyRepository {
        private final String expected;
        PinnedHostKey(String expected) { this.expected=expected.trim(); }
        @Override public int check(String host,byte[] key) {
            try {
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(key);
                String actual="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(digest);
                return expected.equals(actual)?OK:CHANGED;
            } catch(Exception e) { return CHANGED; }
        }
        @Override public void add(HostKey hostkey,UserInfo ui) { }
        @Override public void remove(String host,String type) { }
        @Override public void remove(String host,String type,byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "Pinned SHA-256"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host,String type) { return new HostKey[0]; }
    }
}
