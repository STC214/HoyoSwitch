package dev.hoyoswitch.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** Standalone JVM test: compile with RootShell.java and run this class; no su is invoked. */
public final class RootShellPipeTest {
    public static void main(String[] args) throws Exception {
        Class<?> collector = Class.forName(RootShell.class.getName() + "$Collector");
        Constructor<?> create = collector.getDeclaredConstructor(InputStream.class);
        Method await = collector.getDeclaredMethod("await", Thread.class, long.class);
        create.setAccessible(true);
        await.setAccessible(true);

        Runnable broken = (Runnable) create.newInstance(new InputStream() {
            private boolean returnedByte;

            @Override
            public int read() throws IOException {
                if (returnedByte) {
                    throw new IOException("fixture pipe failure");
                }
                returnedByte = true;
                return 42;
            }

            @Override
            public int read(byte[] bytes) throws IOException {
                bytes[0] = (byte) read();
                return 1;
            }
        });
        expectFailure(await, broken, start(broken), 3000, "read failed");

        CountDownLatch release = new CountDownLatch(1);
        Runnable blocked = (Runnable) create.newInstance(new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return -1;
            }
        });
        Thread reader = start(blocked);
        try {
            expectFailure(await, blocked, reader, 20, "timed out");
        } finally {
            release.countDown();
            reader.join(3000);
        }

        byte[] payload = { 0, 42, (byte) 255 };
        Runnable complete = (Runnable) create.newInstance(new ByteArrayInputStream(payload));
        byte[] result = (byte[]) await.invoke(complete, start(complete), 3000L);
        if (!Arrays.equals(payload, result)) {
            throw new AssertionError("normal EOF lost bytes");
        }
        System.out.println("RootShell pipe regression: PASS");
    }

    private static Thread start(Runnable collector) {
        Thread reader = new Thread(collector, "test-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static void expectFailure(Method await, Runnable collector, Thread reader,
                                      long timeout, String message) throws Exception {
        try {
            await.invoke(collector, reader, timeout);
            throw new AssertionError("expected: " + message);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (!(cause instanceof IOException) || !cause.getMessage().contains(message)) {
                throw error;
            }
        }
    }
}
