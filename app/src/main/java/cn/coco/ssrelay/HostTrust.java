package cn.coco.ssrelay;

import android.content.Context;
import android.content.SharedPreferences;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import java.security.MessageDigest;
import java.util.Base64;

/** Stores a separately verified SSH server key for each host and port. */
final class HostTrust implements HostKeyRepository {
    private final SharedPreferences prefs;
    private final String id;

    HostTrust(Context context, String host, int port) {
        prefs = context.getSharedPreferences("trusted_hosts", Context.MODE_PRIVATE);
        id = host.trim().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    String trusted() { return prefs.getString("trusted:" + id, ""); }
    String pending() { return prefs.getString("pending:" + id, ""); }
    boolean changed() { return prefs.getBoolean("changed:" + id, false); }

    void acceptPending() {
        if (changed() || pending().isEmpty()) return;
        prefs.edit().putString("trusted:" + id, pending())
                .remove("pending:" + id).remove("changed:" + id).apply();
    }

    void dismissPending() {
        prefs.edit().remove("pending:" + id).remove("changed:" + id).apply();
    }

    void clear() {
        prefs.edit().remove("trusted:" + id).remove("pending:" + id)
                .remove("changed:" + id).apply();
    }

    @Override public int check(String host, byte[] key) {
        try {
            String actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(key));
            String known = trusted();
            if (actual.equals(known)) {
                dismissPending();
                return OK;
            }
            prefs.edit().putString("pending:" + id, actual)
                    .putBoolean("changed:" + id, !known.isEmpty()).apply();
            return known.isEmpty() ? NOT_INCLUDED : CHANGED;
        } catch (Exception e) {
            return CHANGED;
        }
    }

    @Override public void add(HostKey hostkey, UserInfo userInfo) { }
    @Override public void remove(String host, String type) { }
    @Override public void remove(String host, String type, byte[] key) { }
    @Override public String getKnownHostsRepositoryID() { return "Verified SSH host keys"; }
    @Override public HostKey[] getHostKey() { return new HostKey[0]; }
    @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
}
