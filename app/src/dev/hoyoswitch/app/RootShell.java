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
                Collector stdout = new Collector(process.getInputStream());
                Collector stderr = new Collector(process.getErrorStream());
                Thread outThread = new Thread(stdout, "su-probe-out");
                Thread errThread = new Thread(stderr, "su-probe-err");
                outThread.setDaemon(true);
                errThread.setDaemon(true);
                outThread.start();
                errThread.start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    failures.append(candidate).append(":timeout ");
                    continue;
                }
                byte[] out = stdout.await(outThread, 3000);
                stderr.await(errThread, 3000);
                int exit = process.exitValue();
                if (exit == 0 && new String(out, StandardCharsets.UTF_8).contains("uid=0")) {
                    resolved = candidate;
                    lastError = "";
                    return candidate;
                }
                failures.append(candidate).append(":exit").append(exit).append(' ');
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
        Collector stdout = new Collector(process.getInputStream());
        Collector stderr = new Collector(process.getErrorStream());
        Thread outThread = new Thread(stdout, "rootshell-out");
        Thread errThread = new Thread(stderr, "rootshell-err");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();
        try {
            if (input != null) {
                // Write stdin on its own thread: a child that never reads would otherwise block
                // this thread before the timeout below can ever run.
                Thread writer = new Thread(() -> {
                    try (OutputStream stdin = process.getOutputStream()) {
                        stdin.write(input);
                        stdin.flush();
                    } catch (IOException ignored) {
                        // The command may have exited before reading stdin; exit code reports it.
                    }
                }, "rootshell-in");
                writer.setDaemon(true);
                writer.start();
            } else {
                process.getOutputStream().close();
            }
            // Wait with a deadline first: draining before waitFor blocks forever when the child
            // hangs, which used to leave operations stuck until the app was killed.
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("root command timed out: " + command);
            }
            byte[] out = stdout.await(outThread, 5000);
            byte[] err = stderr.await(errThread, 5000);
            return new Result(process.exitValue(), out,
                    new String(err, StandardCharsets.UTF_8).trim());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", error);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Reads a process stream on its own thread so a chatty child cannot block the caller. */
    private static final class Collector implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private IOException failure;

        Collector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream input = stream) {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException error) {
                failure = error;
            }
        }

        byte[] await(Thread thread, long timeoutMillis) throws IOException, InterruptedException {
            thread.join(timeoutMillis);
            if (thread.isAlive()) {
                throw new IOException("root command output timed out: " + thread.getName());
            }
            if (failure != null) {
                throw new IOException("root command output read failed: " + thread.getName(), failure);
            }
            return buffer.toByteArray();
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
}
