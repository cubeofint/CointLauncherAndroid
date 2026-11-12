package net.kdt.pojavlaunch.coint.auth;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import com.google.gson.Gson;
import java.io.*;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-bound encrypted session in internal storage excluded from Android backup. */
public final class CointSessionStore {
    private static final String ALIAS = "coint.session.v1";
    private final AtomicFile file;
    public CointSessionStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "coint-session.v1"));
    }
    public synchronized GravitAuth.Session load() throws IOException {
        if (!file.getBaseFile().exists()) return null;
        byte[] plain = null;
        try {
            byte[] encoded = file.readFully();
            if (encoded.length < 29 || encoded.length > 256 * 1024) throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encoded, 0, 12));
            plain = cipher.doFinal(encoded, 12, encoded.length - 12);
            GravitAuth.Session session = new Gson().fromJson(new String(plain, java.nio.charset.StandardCharsets.UTF_8), GravitAuth.Session.class);
            GravitAuth.validate(session);
            return session;
        } catch (GeneralSecurityException | RuntimeException invalid) { throw new IOException("Cannot read COINT session"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    public synchronized void save(GravitAuth.Session session) throws IOException {
        GravitAuth.validate(session);
        FileOutputStream out = null;
        byte[] plain = new Gson().toJson(session).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(plain);
            out = file.startWrite();
            out.write(cipher.getIV());
            out.write(encrypted);
            file.finishWrite(out);
        } catch (GeneralSecurityException | IOException error) {
            if (out != null) file.failWrite(out);
            throw new IOException("Cannot save COINT session");
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    public synchronized void clear() throws IOException {
        file.delete();
        if (file.getBaseFile().exists()) throw new IOException("Cannot remove COINT session");
    }
    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
