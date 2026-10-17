package com.javid.downloader;

import android.content.Context;
import android.util.Base64;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The Telegram bot token never ships with the APK or leaves the Android Keystore
 * storage unencrypted. The AES key is non-exportable and device-bound.
 */
public final class BotSecrets {
    private static final String ALIAS = "javid_telegram_bot_aes_v1";
    private static final String PREFS = "javid_bot_secure";
    private static final String KEY = "token_encrypted";
    private BotSecrets() { }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    public static void save(Context context, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] joined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(ciphertext, 0, joined, iv.length, ciphertext.length);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, Base64.encodeToString(joined, Base64.NO_WRAP)).apply();
    }

    public static String read(Context context) throws Exception {
        String b64 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "");
        if (b64.isEmpty()) return "";
        byte[] joined = Base64.decode(b64, Base64.NO_WRAP);
        if (joined.length < 29) throw new IllegalStateException("encrypted token invalid");
        byte[] iv = new byte[12];
        System.arraycopy(joined, 0, iv, 0, iv.length);
        byte[] body = new byte[joined.length - iv.length];
        System.arraycopy(joined, iv.length, body, 0, body.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    }

    public static boolean hasToken(Context context) {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").isEmpty();
    }
}
