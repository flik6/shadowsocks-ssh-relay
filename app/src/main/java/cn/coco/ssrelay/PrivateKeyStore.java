package cn.coco.ssrelay;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the SSH private key encrypted at rest with an Android Keystore key. */
final class PrivateKeyStore {
    private static final String ALIAS="ssrelay_ssh_private_key_v1";
    private static final String ENCRYPTED_FILE="ssh_private_key.enc";
    private static final String LEGACY_FILE="ssh_private_key";
    private static final int MAX_KEY_BYTES=65536;

    private PrivateKeyStore() { }
    private static File encrypted(Context c) { return new File(c.getNoBackupFilesDir(),ENCRYPTED_FILE); }
    private static File legacy(Context c) { return new File(c.getFilesDir(),LEGACY_FILE); }
    static boolean hasKey(Context c) { return encrypted(c).isFile() || legacy(c).isFile(); }
    static boolean hasEncryptedKey(Context c) { return encrypted(c).isFile(); }

    static synchronized void migrate(Context c) throws Exception {
        File old=legacy(c);
        if(!old.isFile()) return;
        byte[] plain=readLimited(new FileInputStream(old),MAX_KEY_BYTES);
        try {
            if(!encrypted(c).isFile()) save(c,plain);
            else {
                // Check the encrypted copy before deleting the only plaintext copy.
                byte[] test=readEncrypted(c);
                Arrays.fill(test,(byte)0);
            }
            if(old.isFile() && !old.delete()) throw new java.io.IOException("无法删除旧版明文私钥");
        } finally {
            Arrays.fill(plain,(byte)0);
        }
    }

    static synchronized void save(Context c,byte[] plain) throws Exception {
        if(plain.length==0 || plain.length>MAX_KEY_BYTES) throw new IllegalArgumentException("私钥文件大小无效");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,secretKey());
        byte[] iv=cipher.getIV();
        byte[] ciphertext=cipher.doFinal(plain);
        AtomicFile file=new AtomicFile(encrypted(c));
        FileOutputStream out=null;
        try {
            out=file.startWrite();
            out.write(1);
            out.write(iv.length);
            out.write(iv);
            out.write(ciphertext);
            file.finishWrite(out);
        } catch(Exception e) {
            if(out!=null) file.failWrite(out);
            throw e;
        } finally {
            Arrays.fill(ciphertext,(byte)0);
        }
        File old=legacy(c);
        if(old.isFile() && !old.delete()) throw new java.io.IOException("已加密保存，但无法删除旧版明文私钥");
    }

    static synchronized byte[] read(Context c) throws Exception {
        migrate(c);
        return readEncrypted(c);
    }

    private static byte[] readEncrypted(Context c) throws Exception {
        AtomicFile file=new AtomicFile(encrypted(c));
        byte[] data=readLimited(file.openRead(),MAX_KEY_BYTES+64);
        if(data.length<30 || data[0]!=1 || (data[1]&0xff)!=12)
            throw new java.io.IOException("加密私钥格式无效，请重新导入");
        byte[] iv=Arrays.copyOfRange(data,2,14);
        byte[] ciphertext=Arrays.copyOfRange(data,14,data.length);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,iv));
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(data,(byte)0);
            Arrays.fill(ciphertext,(byte)0);
        }
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing=store.getKey(ALIAS,null);
        if(existing instanceof SecretKey) return (SecretKey)existing;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static byte[] readLimited(InputStream input,int limit) throws Exception {
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n,total=0;
            while((n=in.read(buffer))!=-1) {
                total+=n;
                if(total>limit) throw new java.io.IOException("私钥文件超过 64 KiB");
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        }
    }
}
