package cn.coco.ssrelay;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class MainActivity extends Activity {
    private static final int PICK_KEY=10;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout page, tabs;
    private TextView status;
    private EditText ssPassword, fingerprint, host, sshPort, user, remotePort, localPort, bind, localBind;
    private Spinner ssMethod;
    private int currentTab;
    private final Runnable refresh=new Runnable() {
        @Override public void run() { if(status!=null) status.setText(RelayService.status(MainActivity.this)); handler.postDelayed(this,1000); }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
        showTab(0);
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    private GradientDrawable box(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String s,int size,boolean bold) {
        TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(28,37,58));
        if(bold) v.setTypeface(null,Typeface.BOLD); return v;
    }
    private void pad(View v,int top) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); page.addView(v,p); }
    private Button button(String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v->action.run()); return b;
    }
    private EditText field(String label,String value,int type) {
        pad(text(label,14,true),16);
        EditText e=new EditText(this); e.setSingleLine(true); e.setText(value); e.setTextSize(16); e.setInputType(type);
        e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(box(Color.WHITE,10)); pad(e,6); return e;
    }
    private void showTab(int tab) {
        currentTab=tab; status=null;
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(244,247,251));
        ScrollView scroll=new ScrollView(this); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(24),dp(20),dp(24)); scroll.addView(page);
        pad(text("Shadowsocks SSH Relay",24,true),0);
        TextView subtitle=text("安卓 Shadowsocks 服务 · SSH 反向映射",14,false); subtitle.setTextColor(Color.rgb(101,114,138)); pad(subtitle,5);
        if(tab==0) servicePage(); else if(tab==1) keyPage(); else settingsPage();
        tabs=new LinearLayout(this); tabs.setBackgroundColor(Color.WHITE); tabs.setPadding(dp(8),dp(5),dp(8),dp(9)); root.addView(tabs);
        String[] labels={"服务","密钥","设置"};
        for(int i=0;i<3;i++) { final int index=i; Button b=button(labels[i],()->showTab(index));
            b.setTextColor(i==tab?Color.rgb(20,84,190):Color.rgb(93,104,123));
            b.setBackground(box(i==tab?Color.rgb(229,239,255):Color.WHITE,12));
            tabs.addView(b,new LinearLayout.LayoutParams(0,dp(50),1)); }
        setContentView(root);
    }
    private void servicePage() {
        pad(text("服务状态",20,true),26);
        status=text(RelayService.status(this),16,true); status.setPadding(dp(16),dp(18),dp(16),dp(18)); status.setBackground(box(Color.WHITE,14)); pad(status,12);
        Config c=Config.load(this);
        pad(text("远程地址："+(c.host.isEmpty()?"未配置":c.host+":"+c.remotePort),16,false),18);
        pad(text("本地 SS："+c.localBind+":"+c.localPort+" · "+c.method+" · TCP",14,false),8);
        pad(button("启动服务",()->{ try { Config.load(this).validate(); startForegroundService(new Intent(this,RelayService.class).setAction(RelayService.START)); Toast.makeText(this,"正在启动",Toast.LENGTH_SHORT).show(); }
            catch(Exception e) { alert(e.getMessage()); } }),22);
        pad(button("停止服务",()->startService(new Intent(this,RelayService.class).setAction(RelayService.STOP))),8);
        pad(button("复制 ss:// 链接",()->{
            Config cfg=Config.load(this);
            if(cfg.password.isEmpty()) { alert("先在密钥页设置 SS 密码"); return; }
            if(cfg.host.isEmpty()) { alert("先在设置页填写远程 SSH 主机或 IP"); return; }
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString((cfg.method+":"+cfg.password).getBytes(StandardCharsets.UTF_8));
            String uri="ss://"+token+"@"+cfg.host+":"+cfg.remotePort+"#ShadowsocksSSHRelay";
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Shadowsocks",uri));
            Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();
        }),8);
        info("SSH -R 只映射 TCP；此版本不支持 Shadowsocks UDP。远程公网访问还取决于 SSHD 的 GatewayPorts、防火墙与路由器端口转发。",22);
    }
    private void keyPage() {
        Config c=Config.load(this);
        pad(text("SS 密码",20,true),26);
        pad(text("加密方式",14,true),16);
        String[] methods={"aes-256-gcm","aes-128-gcm"};
        ssMethod=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,methods);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ssMethod.setAdapter(adapter); ssMethod.setSelection(c.method.equals("aes-128-gcm")?1:0); pad(ssMethod,6);
        ssPassword=field("Shadowsocks 密码",c.password,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pad(button("生成随机密码",()->{
            byte[] b=new byte[32]; new SecureRandom().nextBytes(b);
            ssPassword.setText(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        }),10);
        pad(text("SSH 私钥",20,true),27);
        File key=new File(getFilesDir(),"ssh_private_key");
        pad(text(key.isFile()?"已导入私钥（仅存于应用私有目录）":"尚未导入私钥",14,false),8);
        pad(button("导入 SSH 私钥文件",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i,PICK_KEY);
        }),9);
        fingerprint=field("SSH 主机公钥 SHA256 指纹",c.fingerprint,android.text.InputType.TYPE_CLASS_TEXT);
        info("请从可信渠道核对 SSH 主机公钥指纹后填写。更换主机或重装 SSH 后，需要重新核对。SSH 私钥目前只支持无口令文件。",12);
        pad(button("保存密钥设置",()->{
            Config old=Config.load(this);
            new Config(old.host,old.sshPort,old.user,old.remotePort,old.localPort,old.bind,
                    old.localBind,ssMethod.getSelectedItem().toString(),ssPassword.getText().toString(),fingerprint.getText().toString().trim()).save(this);
            Toast.makeText(this,"已保存；运行中的服务需重启",Toast.LENGTH_SHORT).show();
        }),18);
    }
    private void settingsPage() {
        Config c=Config.load(this);
        pad(text("SSH 与端口",20,true),26);
        host=field("远程 SSH 主机 / IP",c.host,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        sshPort=field("远程 SSH 端口",String.valueOf(c.sshPort),android.text.InputType.TYPE_CLASS_NUMBER);
        user=field("SSH 用户",c.user,android.text.InputType.TYPE_CLASS_TEXT);
        remotePort=field("远程映射端口",String.valueOf(c.remotePort),android.text.InputType.TYPE_CLASS_NUMBER);
        localPort=field("安卓本地 SS 端口",String.valueOf(c.localPort),android.text.InputType.TYPE_CLASS_NUMBER);
        localBind=field("安卓本地监听地址",c.localBind,android.text.InputType.TYPE_CLASS_TEXT);
        bind=field("远程绑定地址",c.bind,android.text.InputType.TYPE_CLASS_TEXT);
        info("本地监听地址默认 127.0.0.1，仅供 SSH 转发访问。设为 0.0.0.0 时，同一局域网中的其他设备也可能直接连到手机 SS 端口。",10);
        info("0.0.0.0 表示请求 SSH 服务端对外监听；服务器必须允许 GatewayPorts clientspecified 或 yes。127.0.0.1 仅供服务器本机访问。",14);
        pad(button("保存设置",()->{
            try {
                Config old=Config.load(this);
                Config updated=new Config(host.getText().toString().trim(),Integer.parseInt(sshPort.getText().toString()),
                        user.getText().toString().trim(),Integer.parseInt(remotePort.getText().toString()),
                        Integer.parseInt(localPort.getText().toString()),bind.getText().toString().trim(),
                        localBind.getText().toString().trim(),old.method,old.password,old.fingerprint);
                updated.save(this); Toast.makeText(this,"已保存；运行中的服务需重启",Toast.LENGTH_SHORT).show();
            } catch(Exception e) { alert("请检查端口和地址："+e.getMessage()); }
        }),18);
    }
    private void info(String s,int top) { TextView v=text(s,14,false); v.setTextColor(Color.rgb(92,103,124)); v.setLineSpacing(dp(3),1); pad(v,top); }
    private void alert(String s) { new AlertDialog.Builder(this).setMessage(s).setPositiveButton("知道了",null).show(); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_KEY || resultCode!=RESULT_OK || data==null) return;
        try(InputStream in=getContentResolver().openInputStream(data.getData());
            OutputStream out=openFileOutput("ssh_private_key",MODE_PRIVATE)) {
            if(in==null) throw new IOException("无法读取文件");
            byte[] b=new byte[4096]; int n,total=0;
            while((n=in.read(b))!=-1) { total+=n; if(total>65536) throw new IOException("私钥文件超过 64 KiB"); out.write(b,0,n); }
            Toast.makeText(this,"私钥已导入",Toast.LENGTH_SHORT).show(); showTab(1);
        } catch(Exception e) { deleteFile("ssh_private_key"); alert("导入失败："+e.getMessage()); }
    }
}
