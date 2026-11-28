package dev.hoyoswitch.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DisplayReadabilityCacheTest {
    public static void main(String[] args) throws InterruptedException {
        DisplayReadabilityCache cache = new DisplayReadabilityCache();
        int[] reads = {0};
        check(cache.read("game/a", "v1", () -> { reads[0]++; return true; }));
        check(cache.read("game/a", "v1", () -> { throw new AssertionError("unexpected recheck"); }));
        check(!cache.read("game/a", "v2", () -> { reads[0]++; return false; }));
        check(!cache.read("game/a", "v2", () -> { throw new AssertionError("failure not cached"); }));
        check(cache.read("other/a", "v2", () -> { reads[0]++; return true; }));
        cache.clear();
        check(cache.read("game/a", "v2", () -> { reads[0]++; return true; }));
        check(reads[0] == 4);
        check(cache.read("changed", "v1", () -> { cache.clear(); return true; }));
        check(!cache.read("changed", "v1", () -> false));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleared = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            cache.read("slow", "v1", () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return true;
            });
            finished.countDown();
        });
        reader.setDaemon(true);
        reader.start();
        check(started.await(3, TimeUnit.SECONDS));
        Thread invalidator = new Thread(() -> { cache.clear(); cleared.countDown(); });
        invalidator.setDaemon(true);
        invalidator.start();
        try {
            check(cleared.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        check(finished.await(3, TimeUnit.SECONDS));
        check(!cache.read("slow", "v1", () -> false));
        System.out.println("Display readability cache: PASS");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Display cache policy failed");
        }
    }
}
