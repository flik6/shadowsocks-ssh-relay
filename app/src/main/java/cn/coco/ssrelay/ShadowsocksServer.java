package cn.coco.ssrelay;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** TCP-only Shadowsocks AEAD AES-GCM server. The SSH reverse forward carries TCP only. */
final class ShadowsocksServer implements Closeable {
    private static final int MAX_CHUNK=0x3fff;
    private final byte[] masterKey;
    private final int keyLength;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Set<Socket> active=ConcurrentHashMap.newKeySet();
    private final Map<String,Boolean> salts=Collections.synchronizedMap(new LinkedHashMap<String,Boolean>(8192,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> eldest) { return size()>8192; }
    });
    private final ServerSocket listener;
    private volatile boolean closed;

    ShadowsocksServer(String bind,int port,String method,String password) throws Exception {
        keyLength=method.equals("aes-128-gcm")?16:32;
        masterKey=deriveMasterKey(password,keyLength);
        listener=new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName(bind),port));
        workers.execute(this::acceptLoop);
    }
    private void acceptLoop() {
        while(!closed) {
            try {
                Socket socket=listener.accept();
                if(active.size()>=64) { socket.close(); continue; }
                active.add(socket);
                workers.execute(() -> handle(socket));
            } catch(IOException e) { if(!closed) continue; }
        }
    }
    private void handle(Socket client) {
        Socket target=null;
        try {
            client.setSoTimeout(30000);
            InputStream raw=client.getInputStream();
            byte[] salt=readFully(raw,keyLength);
            String saltId=Base64.getEncoder().encodeToString(salt);
            synchronized(salts) {
                if(salts.containsKey(saltId)) throw new IOException("replayed salt");
                salts.put(saltId,Boolean.TRUE);
            }
            SSInput in=new SSInput(raw,hkdf(masterKey,salt,keyLength));
            int type=in.read();
            String host;
            if(type==1) host=InetAddress.getByAddress(readFully(in,4)).getHostAddress();
            else if(type==4) host=InetAddress.getByAddress(readFully(in,16)).getHostAddress();
            else if(type==3) { int len=in.read(); if(len<1) throw new IOException("bad host"); host=new String(readFully(in,len),StandardCharsets.UTF_8); }
            else throw new IOException("unsupported address type");
            byte[] pb=readFully(in,2);
            int port=((pb[0]&255)<<8)|(pb[1]&255);
            if(port==0) throw new IOException("bad target port");
            target=new Socket();
            target.connect(new InetSocketAddress(host,port),10000);
            client.setSoTimeout(0);
            target.setSoTimeout(0);
            final Socket remote=target;
            SSOutput out=new SSOutput(client.getOutputStream(),masterKey,keyLength);
            workers.execute(() -> {
                try { copy(remote.getInputStream(),out); }
                catch(IOException ignored) { }
                finally { closeQuietly(remote); closeQuietly(client); }
            });
            copy(in,target.getOutputStream());
        } catch(Exception ignored) { }
        finally { closeQuietly(target); closeQuietly(client); active.remove(client); }
    }
    private static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] b=new byte[8192]; int n;
        while((n=in.read(b))!=-1) { out.write(b,0,n); out.flush(); }
    }
    private static void closeQuietly(Socket s) { if(s!=null) try { s.close(); } catch(IOException ignored) {} }
    @Override public void close() {
        closed=true;
        try { listener.close(); } catch(IOException ignored) {}
        for(Socket s:active) closeQuietly(s);
        workers.shutdownNow();
    }
    private static byte[] readFully(InputStream in,int count) throws IOException {
        byte[] b=new byte[count]; int off=0,n;
        while(off<count) { n=in.read(b,off,count-off); if(n<0) throw new EOFException(); off+=n; }
        return b;
    }
    private static byte[] deriveMasterKey(String password,int keyLength) throws Exception {
        byte[] p=password.getBytes(StandardCharsets.UTF_8), key=new byte[keyLength], previous=new byte[0];
        MessageDigest md=MessageDigest.getInstance("MD5"); int off=0;
        while(off<key.length) {
            md.update(previous); previous=md.digest(p);
            int n=Math.min(previous.length,key.length-off);
            System.arraycopy(previous,0,key,off,n); off+=n;
        }
        return key;
    }
    private static byte[] hkdf(byte[] key,byte[] salt,int keyLength) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt,"HmacSHA1")); byte[] prk=mac.doFinal(key);
        mac.init(new SecretKeySpec(prk,"HmacSHA1"));
        byte[] info="ss-subkey".getBytes(StandardCharsets.US_ASCII);
        byte[] out=new byte[keyLength], last=new byte[0]; int off=0;
        for(byte counter=1;off<keyLength;counter++) {
            mac.update(last); mac.update(info); mac.update(counter); last=mac.doFinal();
            int n=Math.min(last.length,keyLength-off); System.arraycopy(last,0,out,off,n); off+=n;
        }
        return out;
    }
    private static byte[] crypt(byte[] key,byte[] nonce,byte[] data,int mode) throws IOException {
        try {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            byte[] result=c.doFinal(data);
            for(int i=0;i<nonce.length;i++) if(++nonce[i]!=0) break;
            return result;
        } catch(Exception e) { throw new IOException("Shadowsocks AEAD authentication failed",e); }
    }
    private static final class SSInput extends InputStream {
        private final InputStream raw; private final byte[] key,nonce=new byte[12];
        private byte[] chunk=new byte[0]; private int at;
        SSInput(InputStream raw,byte[] key) { this.raw=raw; this.key=key; }
        @Override public int read() throws IOException { byte[] b=new byte[1]; return read(b,0,1)<0?-1:b[0]&255; }
        @Override public int read(byte[] b,int off,int len) throws IOException {
            if(len==0) return 0;
            while(at>=chunk.length) {
                byte[] encryptedLen;
                try { encryptedLen=readFully(raw,18); } catch(EOFException e) { return -1; }
                byte[] plainLen=crypt(key,nonce,encryptedLen,Cipher.DECRYPT_MODE);
                int n=((plainLen[0]&255)<<8)|(plainLen[1]&255);
                if(n<1 || n>MAX_CHUNK) throw new IOException("invalid Shadowsocks chunk size");
                chunk=crypt(key,nonce,readFully(raw,n+16),Cipher.DECRYPT_MODE); at=0;
            }
            int n=Math.min(len,chunk.length-at); System.arraycopy(chunk,at,b,off,n); at+=n; return n;
        }
    }
    private static final class SSOutput extends OutputStream {
        private final OutputStream raw; private final byte[] key,nonce=new byte[12];
        SSOutput(OutputStream raw,byte[] master,int keyLength) throws Exception {
            this.raw=raw;
            byte[] salt=new byte[keyLength]; new SecureRandom().nextBytes(salt);
            key=hkdf(master,salt,keyLength); raw.write(salt);
        }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
        @Override public void write(byte[] b,int off,int len) throws IOException {
            while(len>0) {
                int n=Math.min(len,MAX_CHUNK);
                raw.write(crypt(key,nonce,new byte[]{(byte)(n>>>8),(byte)n},Cipher.ENCRYPT_MODE));
                raw.write(crypt(key,nonce,Arrays.copyOfRange(b,off,off+n),Cipher.ENCRYPT_MODE));
                off+=n; len-=n;
            }
        }
        @Override public void flush() throws IOException { raw.flush(); }
    }
}
