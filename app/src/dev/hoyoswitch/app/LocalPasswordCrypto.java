package dev.hoyoswitch.app;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Per-installation encryption for manually entered password notes, not game snapshots. */
final class LocalPasswordCrypto {
    private static final byte[] MAGIC = {'H', 'P', 'W', '1'};
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Object KEY_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path keyFile;

    LocalPasswordCrypto(Path keyFile) {
        this.keyFile = keyFile;
    }

    static boolean matches(byte[] data) {
        return data.length >= MAGIC.length
                && Arrays.equals(MAGIC, Arrays.copyOf(data, MAGIC.length));
    }

    byte[] encrypt(byte[] plain) throws IOException {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            byte[] body = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plain);
            byte[] out = Arrays.copyOf(MAGIC, MAGIC.length + NONCE_BYTES + body.length);
            System.arraycopy(nonce, 0, out, MAGIC.length, NONCE_BYTES);
            System.arraycopy(body, 0, out, MAGIC.length + NONCE_BYTES, body.length);
            return out;
        } catch (GeneralSecurityException error) {
            throw new IOException("密码加密失败", error);
        }
    }

    byte[] decrypt(byte[] data) throws IOException {
        int start = MAGIC.length + NONCE_BYTES;
        if (!matches(data) || data.length < start + TAG_BITS / 8) {
            throw new IOException("密码数据格式错误或已截断");
        }
        byte[] nonce = Arrays.copyOfRange(data, MAGIC.length, start);
        try {
            return cipher(Cipher.DECRYPT_MODE, nonce).doFinal(data, start, data.length - start);
        } catch (GeneralSecurityException error) {
            throw new IOException("密码完整性校验失败", error);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws IOException, GeneralSecurityException {
        byte[] bytes = key(mode == Cipher.ENCRYPT_MODE);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(bytes, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(MAGIC);
            return cipher;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] key(boolean create) throws IOException {
        synchronized (KEY_LOCK) {
            try (InputStream input = Files.newInputStream(keyFile)) {
                return readKey(input);
            } catch (NoSuchFileException missing) {
                if (!create) {
                    throw new IOException("应用本地密码密钥缺失", missing);
                }
            }

            Path temp = Files.createTempFile(keyFile.getParent(), "password-key-", ".tmp");
            byte[] bytes = new byte[KEY_BYTES];
            boolean published = false;
            try {
                RANDOM.nextBytes(bytes);
                try (FileOutputStream output = new FileOutputStream(temp.toFile())) {
                    output.write(bytes);
                    output.getFD().sync();
                }
                Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
                published = true;
                return bytes;
            } finally {
                if (!published) {
                    Arrays.fill(bytes, (byte) 0);
                    Files.deleteIfExists(temp);
                }
            }
        }
    }

    private static byte[] readKey(InputStream input) throws IOException {
        byte[] bytes = new byte[KEY_BYTES];
        boolean valid = false;
        try {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw new IOException("应用本地密码密钥已损坏");
                }
                offset += read;
            }
            if (input.read() != -1) {
                throw new IOException("应用本地密码密钥已损坏");
            }
            valid = true;
            return bytes;
        } finally {
            if (!valid) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }
}
