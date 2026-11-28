package dev.hoyoswitch.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public final class LocalPasswordCryptoTest {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Path key = dir.resolve("password-notes.key");
        byte[] password = "fixture-密码-123".getBytes(StandardCharsets.UTF_8);
        if (args.length > 1 && "write".equals(args[1])) {
            Files.write(dir.resolve("record.bin"), new LocalPasswordCrypto(key).encrypt(password));
            System.out.println("Password fixture persisted: PASS");
            return;
        }
        if (args.length > 1 && "read".equals(args[1])) {
            byte[] record = Files.readAllBytes(dir.resolve("record.bin"));
            check(Arrays.equals(new LocalPasswordCrypto(key).decrypt(record), password),
                    "separate process could not read stored password");
            System.out.println("Cross-process password read: PASS");
            return;
        }
        LocalPasswordCrypto first = new LocalPasswordCrypto(key);
        byte[] saved = first.encrypt(password);
        check(LocalPasswordCrypto.matches(saved), "missing format marker");
        check(!LocalPasswordCrypto.matches(new byte[] {'H', 'S', 'N', '1'}), "legacy marker matched");
        check(!Arrays.equals(first.encrypt(password), saved), "nonces repeated");

        LocalPasswordCrypto reopened = new LocalPasswordCrypto(key);
        check(Arrays.equals(reopened.decrypt(saved), password), "reopen lost the password");
        byte[] tampered = saved.clone();
        tampered[tampered.length - 1] ^= 1;
        expectFailure(() -> reopened.decrypt(tampered), "accepted tampered record");
        expectFailure(() -> reopened.decrypt(Arrays.copyOf(saved, 15)), "accepted truncated record");

        Path other = dir.resolve("other.key");
        LocalPasswordCrypto different = new LocalPasswordCrypto(other);
        different.encrypt(password);
        expectFailure(() -> different.decrypt(saved), "accepted the wrong installation key");

        Path preserved = dir.resolve("preserved.key");
        Files.move(key, preserved);
        expectFailure(() -> reopened.decrypt(saved), "read succeeded without a key");
        check(!Files.exists(key), "read silently generated a new key");
        Files.write(key, new byte[] {1});
        expectFailure(() -> reopened.encrypt(password), "overwrote a corrupt key");
        check(Files.size(key) == 1, "corrupt key was replaced");
        Files.write(key, new byte[33]);
        expectFailure(() -> reopened.decrypt(saved), "accepted a key with trailing data");
        expectFailure(() -> reopened.encrypt(password), "overwrote an oversized key");
        check(Files.size(key) == 33, "oversized key was replaced");
        System.out.println("Local password encryption regression: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(IoOperation operation, String message) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private interface IoOperation {
        void run() throws IOException;
    }
}
