package cn.coco.ssrelay;

import android.app.*;
import android.content.*;
import android.os.*;
import com.jcraft.jsch.*;
import java.util.Arrays;

public final class RelayService extends Service {
    static final String START="cn.coco.ssrelay.START", STOP="cn.coco.ssrelay.STOP";
    private static final String CHANNEL="relay";
    private volatile boolean running;
    private Session session;
    private ShadowsocksServer server;
    private Thread thread;
    private int generation;
    private boolean forwardingActive;
    private String forwardingBind;
    private int forwardingPort;

    static String status(Context c) { return c.getSharedPreferences("status",MODE_PRIVATE).getString("message","已停止"); }
    static boolean isRunning(Context c) { return c.getSharedPreferences("status",MODE_PRIVATE).getBoolean("running",false); }
    private void status(String message) {
        if(message.equals(status(this))) return;
        getSharedPreferences("status",MODE_PRIVATE).edit().putString("message",message).apply();
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null && running) nm.notify(1,notification(message));
    }
    private synchronized boolean active(int id) { return running && generation==id; }
    private synchronized void statusIfActive(int id,String message) { if(active(id)) status(message); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Shadowsocks SSH Relay 服务",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public synchronized int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) { stopRelay(); stopSelfResult(startId); return START_NOT_STICKY; }
        if(!running) {
            running=true;
            int id=++generation;
            getSharedPreferences("status",MODE_PRIVATE).edit().putBoolean("running",true).apply();
            startForeground(1,notification("正在启动"));
            status("正在启动");
            thread=new Thread(() -> runRelay(id,startId),"ss-relay");
            thread.start();
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
    private void runRelay(int id,int startId) {
        ShadowsocksServer localServer=null;
        try {
            Config cfg=Config.load(this); cfg.validate();
            PrivateKeyStore.migrate(this);
            if(!PrivateKeyStore.hasKey(this)) throw new IllegalArgumentException("请先在设置页导入或粘贴 SSH 私钥");
            localServer=new ShadowsocksServer(cfg.localBind,cfg.localPort,cfg.method,cfg.password);
            synchronized(this) {
                if(!active(id)) return;
                server=localServer;
            }
            int retry=0;
            boolean portConflict=false;
            while(active(id)) {
                byte[] keyBytes=null;
                Session s=null;
                boolean forwarded=false;
                try {
                    if(!portConflict) statusIfActive(id,"本地 SS 已启动，正在连接 SSH…");
                    JSch jsch=new JSch();
                    keyBytes=PrivateKeyStore.read(this);
                    jsch.addIdentity("saved-ssh-key",keyBytes,null,null);
                    HostTrust trust=new HostTrust(this,cfg.host,cfg.sshPort);
                    jsch.setHostKeyRepository(trust);
                    s=jsch.getSession(cfg.user,cfg.host,cfg.sshPort);
                    synchronized(this) {
                        if(!active(id)) throw new InterruptedException("服务已停止");
                        session=s;
                    }
                    s.setConfig("StrictHostKeyChecking","yes");
                    s.setConfig("server_host_key","rsa-sha2-512,rsa-sha2-256");
                    s.setServerAliveInterval(15000); s.setServerAliveCountMax(2);
                    s.connect(12000);
                    if(!active(id)) throw new InterruptedException("服务已停止");
                    s.setPortForwardingR(cfg.bind,cfg.remotePort,"127.0.0.1",cfg.localPort);
                    forwarded=true;
                    synchronized(this) {
                        if(active(id)) {
                            forwardingActive=true;
                            forwardingBind=cfg.bind;
                            forwardingPort=cfg.remotePort;
                        }
                    }
                    if(!active(id)) throw new InterruptedException("服务已停止");
                    retry=0;
                    portConflict=false;
                    statusIfActive(id,"SSH 转发已建立；远端实际监听地址请在服务器核对（端口 "+cfg.remotePort+"）");
                    while(active(id) && s.isConnected()) Thread.sleep(2000);
                    if(active(id)) statusIfActive(id,"SSH 已断开，正在等待重连");
                } catch(Exception e) {
                    if(active(id)) {
                        HostTrust trust=new HostTrust(this,cfg.host,cfg.sshPort);
                        if(!trust.pending().isEmpty()) {
                            portConflict=false;
                            statusIfActive(id,trust.changed()?"SSH 主机密钥已变化，请核对服务器":"等待确认 SSH 服务器指纹");
                        } else if(e.getMessage()!=null && e.getMessage().contains("remote port forwarding failed")) {
                            portConflict=true;
                            statusIfActive(id,"远程端口 "+cfg.remotePort+" 转发失败；检查端口占用或 SSH 服务端转发权限");
                        } else {
                            portConflict=false;
                            statusIfActive(id,"连接失败："+e.getMessage()+"；稍后重试");
                        }
                    }
                } finally {
                    if(s!=null) {
                        if(forwarded && s.isConnected()) {
                            try { s.delPortForwardingR(cfg.bind,cfg.remotePort); }
                            catch(JSchException ignored) { }
                        }
                        s.disconnect();
                    }
                    synchronized(this) {
                        if(session==s) {
                            session=null;
                            forwardingActive=false;
                            forwardingBind=null;
                            forwardingPort=0;
                        }
                    }
                    if(keyBytes!=null) Arrays.fill(keyBytes,(byte)0);
                }
                if(active(id)) {
                    try { Thread.sleep(Math.min(60000,5000L*(1L<<Math.min(4,retry++)))); }
                    catch(InterruptedException ignored) { }
                }
            }
        } catch(Exception e) { statusIfActive(id,"启动失败："+e.getMessage()); }
        finally {
            if(localServer!=null) localServer.close();
            synchronized(this) {
                if(server==localServer) server=null;
                if(thread==Thread.currentThread()) thread=null;
                if(generation==id) {
                    running=false;
                    getSharedPreferences("status",MODE_PRIVATE).edit().putBoolean("running",false).apply();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf(startId);
                }
            }
        }
    }
    private void stopRelay() {
        Session s;
        ShadowsocksServer ss;
        Thread worker;
        boolean forwarded;
        String bind;
        int port;
        synchronized(this) {
            generation++;
            running=false;
            getSharedPreferences("status",MODE_PRIVATE).edit().putBoolean("running",false).apply();
            s=session; session=null;
            ss=server; server=null;
            worker=thread; thread=null;
            forwarded=forwardingActive;
            bind=forwardingBind;
            port=forwardingPort;
            forwardingActive=false;
            forwardingBind=null;
            forwardingPort=0;
            status("已停止");
        }
        if(s!=null) {
            if(forwarded && s.isConnected()) {
                try { s.delPortForwardingR(bind,port); }
                catch(JSchException ignored) { }
            }
            s.disconnect();
        }
        if(ss!=null) ss.close();
        if(worker!=null) worker.interrupt();
    }
    @Override public void onDestroy() { if(running) stopRelay(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
