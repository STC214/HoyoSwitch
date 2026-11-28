package dev.hoyoswitch.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts snapshot payloads with an AES-256 key held by the Android Keystore, so copied files
 * are not readable as plain XML or SQLite. The key never leaves the secure hardware.
 */
final class SnapshotCrypto {
    private static final String ALIAS = "hoyoswitch-snapshot-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] MAGIC = {'H', 'S', 'N', '1'};
    private static final Object KEY_LOCK = new Object();
    private static volatile SecretKey cachedKey;

    private SnapshotCrypto() {
    }

    static boolean isEncrypted(byte[] data) {
        if (data.length < MAGIC.length) {
            return false;
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (data[index] != MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    static byte[] encrypt(byte[] plain) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                // The format stores a fixed-size IV; anything else could never be decrypted.
                throw new IOException("不支持的加密 IV 长度: "
                        + (iv == null ? "null" : String.valueOf(iv.length)));
            }
            byte[] body = cipher.doFinal(plain);
            byte[] out = new byte[MAGIC.length + IV_BYTES + body.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(iv, 0, out, MAGIC.length, IV_BYTES);
            System.arraycopy(body, 0, out, MAGIC.length + IV_BYTES, body.length);
            return out;
        } catch (GeneralSecurityException error) {
            throw new IOException("加密快照失败", error);
        }
    }

    static byte[] decrypt(byte[] data) throws IOException {
        if (!isEncrypted(data)) {
            throw new IOException("不是加密的快照数据");
        }
        if (data.length < MAGIC.length + IV_BYTES + TAG_BITS / 8) {
            throw new IOException("加密快照数据被截断");
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(data, MAGIC.length, iv, 0, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(data, MAGIC.length + IV_BYTES, data.length - MAGIC.length - IV_BYTES);
        } catch (GeneralSecurityException error) {
            throw new IOException("解密快照失败", error);
        }
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        synchronized (KEY_LOCK) {
            if (cachedKey != null) {
                return cachedKey;
            }
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey existing;
            try {
                existing = (SecretKey) store.getKey(ALIAS, null);
            } catch (UnrecoverableKeyException error) {
                // Do not regenerate: that would silently make every stored snapshot unreadable.
                throw new IOException("快照密钥不可用（可能已被系统失效），无法读取已有快照", error);
            }
            if (existing != null) {
                cachedKey = existing;
                return existing;
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
            cachedKey = (SecretKey) store.getKey(ALIAS, null);
            return cachedKey;
        }
    }
}
