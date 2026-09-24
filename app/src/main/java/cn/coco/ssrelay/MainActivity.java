package cn.coco.ssrelay;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class MainActivity extends Activity {
    private static final int PICK_KEY=10;
    private static final int BG=0xff0b1425, SURFACE=0xff152238, LINE=0xff26384f;
    private static final int WHITE=0xfff4f8ff, MUTED=0xff9fb0c7, BLUE=0xff438dff;
    private static final int TEAL=0xff43dccd, AMBER=0xffffc76b;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private TextView statusText, stateLabel, actionButton, keyState, trustState;
    private EditText host, sshPort, user, remotePort, localPort, bind, localBind, ssPassword;
    private Spinner ssMethod;
    private boolean trustDialogOpen;
    private String shownChangedFingerprint="";
    private final Runnable refresh=new Runnable() {
        @Override public void run() { updateStatus(); checkHostTrust(); handler.postDelayed(this,1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
        showTab(0);
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private GradientDrawable outline(int color,int stroke,int radius) {
        GradientDrawable d=shape(color,radius); d.setStroke(dp(1),stroke); return d;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private void add(LinearLayout parent,View view,int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); parent.addView(view,p);
    }
    private TextView action(String label,boolean primary,Runnable run) {
        TextView v=text(label,16,primary?BG:WHITE,true); v.setGravity(Gravity.CENTER); v.setMinHeight(dp(54));
        v.setBackground(primary?shape(TEAL,16):outline(SURFACE,LINE,16));
        v.setOnClickListener(x->run.run()); return v;
    }
    private LinearLayout card() {
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20),dp(20),dp(20),dp(20)); c.setBackground(outline(SURFACE,LINE,20)); return c;
    }
    private EditText field(LinearLayout into,String title,String value,String hint,int type) {
        add(into,text(title,13,MUTED,true),16);
        EditText e=new EditText(this); e.setSingleLine(true); e.setText(value); e.setHint(hint);
        e.setTextSize(16); e.setTextColor(WHITE); e.setHintTextColor(0xff71849d);
        e.setInputType(type); e.setPadding(dp(14),dp(12),dp(14),dp(12));
        e.setBackground(outline(BG,LINE,12)); add(into,e,7); return e;
    }
    private void section(String title,String subtitle,int top) {
        add(page,text(title,21,WHITE,true),top);
        add(page,text(subtitle,13,MUTED,false),5);
    }
    private void note(LinearLayout into,String value,int top) {
        TextView v=text(value,13,MUTED,false); v.setLineSpacing(dp(3),1); add(into,v,top);
    }
    private void showTab(int tab) {
        statusText=null; stateLabel=null; actionButton=null; keyState=null; trustState=null;
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(22),dp(20),dp(28)); scroll.addView(page);
        header(tab);
        if(tab==0) servicePage(); else settingsPage();
        LinearLayout nav=new LinearLayout(this); nav.setPadding(dp(16),dp(8),dp(16),dp(12));
        nav.setBackground(outline(0xff101c30,LINE,0));
        for(int i=0;i<2;i++) {
            final int index=i;
            TextView item=text(i==0?"◉  服务":"⚙  设置",15,i==tab?TEAL:MUTED,true);
            item.setGravity(Gravity.CENTER); item.setMinHeight(dp(48));
            item.setBackground(shape(i==tab?0xff1b3446:0xff101c30,13));
            item.setOnClickListener(v->showTab(index));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            p.setMargins(dp(4),0,dp(4),0); nav.addView(item,p);
        }
        root.addView(nav); setContentView(root); updateStatus();
    }
    private void header(int tab) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.app_logo);
        row.addView(logo,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout titles=new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2); tp.leftMargin=dp(11); row.addView(titles,tp);
        add(titles,text("SS · SSH Relay",19,WHITE,true),0);
        add(titles,text("手机出口 · 安全转发",12,MUTED,false),3);
        add(page,row,0);
        add(page,text(tab==0?"服务概览":"连接设置",30,WHITE,true),28);
        add(page,text(tab==0?"管理手机上的 Shadowsocks 服务和 SSH 隧道":"配置远程服务器、SS 参数和 SSH 身份",14,MUTED,false),6);
    }
    private void servicePage() {
        LinearLayout hero=card();
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        stateLabel=text("●  已停止",14,MUTED,true); row.addView(stateLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView tcp=text("TCP",12,TEAL,true); tcp.setPadding(dp(10),dp(5),dp(10),dp(5));
        tcp.setBackground(shape(0xff183743,9)); row.addView(tcp); add(hero,row,0);
        add(hero,text("连接状态",13,MUTED,false),25);
        statusText=text("已停止",21,WHITE,true); statusText.setLineSpacing(dp(3),1); add(hero,statusText,7);
        note(hero,"启动后保持前台通知，断线时自动重连。",13); add(page,hero,24);
        Config c=Config.load(this);
        section("转发路径","从远程入口到手机网络出口",28);
        LinearLayout route=card();
        routeLine(route,"远程入口",c.host.isEmpty()?"尚未配置":c.host+":"+c.remotePort,BLUE);
        add(route,text("↓  SSH 加密隧道",13,TEAL,true),14);
        routeLine(route,"手机 SS",c.localBind+":"+c.localPort,TEAL);
        add(route,text(c.method+"  ·  TCP",13,MUTED,false),17); add(page,route,13);
        actionButton=action("启动服务",true,()->{
            if(RelayService.isRunning(this)) {
                startService(new Intent(this,RelayService.class).setAction(RelayService.STOP));
                handler.postDelayed(this::updateStatus,250);
            } else {
                try {
                    Config.load(this).validate();
                    if(!new File(getFilesDir(),"ssh_private_key").isFile()) throw new IllegalArgumentException("请先在设置页导入 SSH 私钥");
                    startForegroundService(new Intent(this,RelayService.class).setAction(RelayService.START));
                    handler.postDelayed(this::updateStatus,250);
                } catch(Exception e) { alert(e.getMessage()); }
            }
        });
        add(page,actionButton,26);
        add(page,action("复制 ss:// 连接链接",false,this::copySsLink),10);
        LinearLayout tip=card(); add(tip,text("使用提示",15,WHITE,true),0);
        note(tip,"远程端口能否从公网访问，还取决于 SSH 服务端 GatewayPorts、防火墙和路由器端口转发。当前版本仅支持 TCP。",8);
        add(page,tip,24);
    }
    private void routeLine(LinearLayout into,String name,String value,int color) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text("●",16,color,true));
        LinearLayout column=new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.leftMargin=dp(11); row.addView(column,cp);
        add(column,text(name,12,MUTED,false),0); add(column,text(value,17,WHITE,true),3); add(into,row,0);
    }
    private void settingsPage() {
        Config c=Config.load(this);
        section("SSH 服务器","连接目标与远程映射",25);
        LinearLayout ssh=card();
        host=field(ssh,"主机 / IP",c.host,"example.com",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        sshPort=field(ssh,"SSH 端口",String.valueOf(c.sshPort),"22",InputType.TYPE_CLASS_NUMBER);
        user=field(ssh,"SSH 用户名",c.user,"root",InputType.TYPE_CLASS_TEXT);
        remotePort=field(ssh,"远程映射端口",String.valueOf(c.remotePort),"61000",InputType.TYPE_CLASS_NUMBER);
        bind=field(ssh,"远程监听地址",c.bind,"0.0.0.0",InputType.TYPE_CLASS_TEXT);
        note(ssh,"0.0.0.0 请求对外监听；服务器需允许 GatewayPorts。",14); add(page,ssh,13);
        section("Shadowsocks","手机上的服务端配置",28);
        LinearLayout ss=card();
        localPort=field(ss,"本地 SS 端口",String.valueOf(c.localPort),"8388",InputType.TYPE_CLASS_NUMBER);
        localBind=field(ss,"本地监听地址",c.localBind,"127.0.0.1",InputType.TYPE_CLASS_TEXT);
        add(ss,text("加密方式",13,MUTED,true),17);
        ssMethod=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"aes-256-gcm","aes-128-gcm"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ssMethod.setAdapter(adapter); ssMethod.setSelection(c.method.equals("aes-128-gcm")?1:0);
        ssMethod.setBackground(outline(BG,LINE,12)); ssMethod.setPadding(dp(12),dp(5),dp(8),dp(5)); add(ss,ssMethod,7);
        ssPassword=field(ss,"SS 密码",c.password,"输入或生成强密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        add(ss,action("生成随机密码",false,()->{
            byte[] random=new byte[32]; new SecureRandom().nextBytes(random);
            ssPassword.setText(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        }),12);
        note(ss,"本地默认只监听 127.0.0.1，由 SSH 隧道访问。",15); add(page,ss,13);
        section("SSH 身份与信任","手机仅需导入 SSH 私钥",28);
        LinearLayout security=card();
        keyState=text("",14,WHITE,true); add(security,keyState,0); updateKeyState();
        add(security,action("导入 SSH 私钥",false,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_KEY);
        }),13);
        note(security,"对应的公钥需提前放在服务器的 authorized_keys 中；手机上不必导入公钥。暂支持无口令私钥。",13);
        View divider=new View(this); divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams div=new LinearLayout.LayoutParams(-1,dp(1)); div.topMargin=dp(22); security.addView(divider,div);
        trustState=text("",14,WHITE,true); add(security,trustState,20); updateTrustState();
        note(security,"首次连接时会显示服务器指纹，核对后确认；以后自动校验。",9);
        add(security,action("清除已信任指纹",false,()->{
            Config config=Config.load(this);
            if(config.host.isEmpty()) { alert("请先保存 SSH 主机"); return; }
            new AlertDialog.Builder(this).setTitle("清除服务器信任？")
                .setMessage("下次连接会重新要求核对服务器指纹。")
                .setNegativeButton("取消",null)
                .setPositiveButton("清除",(d,w)->{ new HostTrust(this,config.host,config.sshPort).clear(); updateTrustState(); shownChangedFingerprint=""; })
                .show();
        }),13);
        add(page,security,13);
        add(page,action("保存全部设置",true,this::saveSettings),25);
        note(page,"修改配置后，请停止并重新启动服务。",12);
    }
    private void updateKeyState() {
        if(keyState!=null) keyState.setText(new File(getFilesDir(),"ssh_private_key").isFile()?"●  已导入私钥":"○  尚未导入私钥");
    }
    private void updateTrustState() {
        if(trustState==null) return;
        Config cfg=Config.load(this);
        String known=new HostTrust(this,cfg.host,cfg.sshPort).trusted();
        trustState.setText(known.isEmpty()?"○  尚未信任 SSH 服务器":"●  已信任服务器\n"+known);
    }
    private void saveSettings() {
        try {
            Config updated=new Config(host.getText().toString().trim(),parsePort(sshPort),user.getText().toString().trim(),
                parsePort(remotePort),parsePort(localPort),bind.getText().toString().trim(),
                localBind.getText().toString().trim(),ssMethod.getSelectedItem().toString(),ssPassword.getText().toString());
            if(!updated.bind.equals("0.0.0.0")&&!updated.bind.equals("127.0.0.1")) throw new IllegalArgumentException("远程监听地址只支持 0.0.0.0 或 127.0.0.1");
            if(!updated.localBind.equals("0.0.0.0")&&!updated.localBind.equals("127.0.0.1")) throw new IllegalArgumentException("本地监听地址只支持 0.0.0.0 或 127.0.0.1");
            updated.save(this); updateTrustState();
            Toast.makeText(this,"设置已保存；运行中的服务需重启",Toast.LENGTH_LONG).show();
        } catch(Exception e) { alert("请检查设置："+e.getMessage()); }
    }
    private int parsePort(EditText field) {
        int port=Integer.parseInt(field.getText().toString().trim());
        if(port<1||port>65535) throw new IllegalArgumentException("端口需在 1–65535 之间");
        return port;
    }
    private void updateStatus() {
        if(statusText==null) return;
        String message=RelayService.status(this); boolean running=RelayService.isRunning(this);
        statusText.setText(message);
        stateLabel.setText(running?"●  "+(message.contains("已建立")?"转发在线":"正在连接"):"●  已停止");
        stateLabel.setTextColor(running?(message.contains("已建立")?TEAL:AMBER):MUTED);
        actionButton.setText(running?"停止服务":"启动服务");
        actionButton.setBackground(running?outline(SURFACE,LINE,16):shape(TEAL,16));
        actionButton.setTextColor(running?WHITE:BG);
    }
    private void checkHostTrust() {
        Config cfg=Config.load(this);
        if(cfg.host.isEmpty()||trustDialogOpen||!RelayService.isRunning(this)) return;
        HostTrust trust=new HostTrust(this,cfg.host,cfg.sshPort);
        String pending=trust.pending(); if(pending.isEmpty()) return;
        if(trust.changed()) {
            if(pending.equals(shownChangedFingerprint)) return;
            shownChangedFingerprint=pending; trustDialogOpen=true;
            new AlertDialog.Builder(this).setTitle("SSH 服务器密钥已变化")
                .setMessage("服务器返回的指纹与之前信任的不一致。请核对服务器后，在设置页清除已信任指纹，再重新连接。\n\n当前指纹：\n"+pending)
                .setPositiveButton("知道了",(d,w)->trustDialogOpen=false)
                .setOnDismissListener(d->trustDialogOpen=false).show();
            return;
        }
        trustDialogOpen=true;
        new AlertDialog.Builder(this).setTitle("确认 SSH 服务器身份")
            .setMessage("请通过可信渠道核对 "+cfg.host+":"+cfg.sshPort+" 的 SSH 主机公钥指纹：\n\n"+pending+"\n\n只有指纹一致才继续。")
            .setNegativeButton("取消连接",(d,w)->{
                trust.dismissPending();
                startService(new Intent(this,RelayService.class).setAction(RelayService.STOP));
            })
            .setPositiveButton("已核对，信任",(d,w)->{
                trust.acceptPending(); updateTrustState();
                Toast.makeText(this,"已信任服务器，正在重连",Toast.LENGTH_SHORT).show();
            })
            .setOnDismissListener(d->trustDialogOpen=false).show();
    }
    private void copySsLink() {
        Config cfg=Config.load(this);
        if(cfg.password.isEmpty()||cfg.host.isEmpty()) { alert("请先在设置页填写 SSH 主机和 SS 密码"); return; }
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString((cfg.method+":"+cfg.password).getBytes(StandardCharsets.UTF_8));
        String uri="ss://"+token+"@"+cfg.host+":"+cfg.remotePort+"#ShadowsocksSSHRelay";
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Shadowsocks",uri));
        Toast.makeText(this,"连接链接已复制",Toast.LENGTH_SHORT).show();
    }
    private void alert(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("知道了",null).show(); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_KEY||resultCode!=RESULT_OK||data==null) return;
        try(InputStream in=getContentResolver().openInputStream(data.getData())) {
            if(in==null) throw new IOException("无法读取文件");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int count,total=0;
            while((count=in.read(buffer))!=-1) { total+=count; if(total>65536) throw new IOException("私钥文件超过 64 KiB"); bytes.write(buffer,0,count); }
            byte[] key=bytes.toByteArray();
            if(!new String(key,StandardCharsets.US_ASCII).contains("PRIVATE KEY-----")) throw new IOException("文件不是 PEM/OpenSSH 私钥");
            try(OutputStream out=openFileOutput("ssh_private_key",MODE_PRIVATE)) { out.write(key); }
            updateKeyState(); Toast.makeText(this,"私钥已导入",Toast.LENGTH_SHORT).show();
        } catch(Exception e) { alert("导入失败："+e.getMessage()); }
    }
}
