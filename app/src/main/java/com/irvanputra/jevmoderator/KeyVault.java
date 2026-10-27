package com.irvanputra.jevmoderator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the user's API key encrypted with an Android Keystore key, in app-private storage. */
final class KeyVault {
    private static final String ALIAS = "audience_moderator_key_v1", PREF = "vault", FIELD = "k";
    private final SharedPreferences sp;
    KeyVault(Context c) { sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    boolean has() { return sp.contains(FIELD); }
    void save(String secret) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(secret.trim().getBytes("UTF-8"));
        sp.edit().putString(FIELD, Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
    }
    String load() throws Exception {
        String v = sp.getString(FIELD, null); if (v == null) return null;
        String[] p = v.split(":");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), "UTF-8");
    }
    void clear() { sp.edit().remove(FIELD).apply(); }
}
