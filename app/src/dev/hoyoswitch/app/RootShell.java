package dev.hoyoswitch.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper around {@code su -c}; binary safe in both directions. */
final class RootShell {
    static final class Result {
        final int code;
        final byte[] out;
        final String err;

        Result(int code, byte[] out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        String text() {
            return new String(out, StandardCharsets.UTF_8).trim();
        }
    }

    private static final long TIMEOUT_SECONDS = 90;
    /** Some ROMs do not expose su through PATH for app processes. */
    private static final List<String> CANDIDATES = Arrays.asList(
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/magisk/su");

    private static String resolved;
    private static String lastError = "";
    private static Boolean useNsenter;

    private RootShell() {
    }

    /**
     * App processes live in a per-app mount namespace where other packages' data is hidden.
     * Entering init's namespace through nsenter restores the real view; probe once.
     */
    private static synchronized boolean nsenterMode(String su) {
        if (useNsenter == null) {
            try {
                useNsenter = exec(su, "nsenter -t 1 -m -- true", null).code == 0;
            } catch (IOException error) {
                useNsenter = false;
            }
        }
        return useNsenter;
    }

    private static String wrap(String command) {
        return "nsenter -t 1 -m -- sh -c " + singleQuote(command);
    }

    private static String singleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static String lastError() {
        return lastError;
    }

    private static synchronized String su() throws IOException {
        if (resolved != null) {
            return resolved;
        }
        StringBuilder failures = new StringBuilder();
        for (String candidate : CANDIDATES) {
            try {
                Process process = new ProcessBuilder(candidate, "-c", "id").start();
                process.getOutputStream().close();
                byte[] out = drain(process.getInputStream());
                drain(process.getErrorStream());
                if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
                        && new String(out, StandardCharsets.UTF_8).contains("uid=0")) {
                    resolved = candidate;
                    lastError = "";
                    return candidate;
                }
                failures.append(candidate).append(":exit").append(process.exitValue()).append(' ');
            } catch (IOException error) {
                failures.append(candidate).append(":missing ");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        lastError = "找不到可用的 su（" + failures.toString().trim() + "）";
        throw new IOException(lastError);
    }

    static Result run(String command) throws IOException {
        return run(command, null);
    }

    static Result run(String command, byte[] input) throws IOException {
        String su = su();
        String effective = nsenterMode(su) ? wrap(command) : command;
        return exec(su, effective, input);
    }

    private static Result exec(String su, String command, byte[] input) throws IOException {
        Process process = new ProcessBuilder(su, "-c", command).start();
        try {
            if (input != null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(input);
                    stdin.flush();
                }
            } else {
                process.getOutputStream().close();
            }
            byte[] stdout = drain(process.getInputStream());
            String stderr = new String(drain(process.getErrorStream()), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("root command timed out: " + command);
            }
            return new Result(process.exitValue(), stdout, stderr);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", error);
        }
    }

    static Result checked(String command) throws IOException {
        Result result = run(command);
        if (result.code != 0) {
            throw new IOException("root command failed (" + result.code + "): " + command
                    + (result.err.isEmpty() ? "" : " -> " + result.err));
        }
        return result;
    }

    static boolean isRooted() {
        try {
            return su() != null;
        } catch (IOException error) {
            lastError = error.getMessage();
            return false;
        }
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream input = stream) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }
}
