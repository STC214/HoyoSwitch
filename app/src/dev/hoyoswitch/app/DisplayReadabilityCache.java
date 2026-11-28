package dev.hoyoswitch.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** UI hints only. Never use this cache to validate a write or restore operation. */
final class DisplayReadabilityCache {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long generation;

    boolean read(String id, String signature, BooleanSupplier check) {
        final long request;
        synchronized (this) {
            Entry entry = entries.get(id);
            if (entry != null && entry.signature.equals(signature)) {
                return entry.readable;
            }
            request = generation;
        }
        boolean readable = check.getAsBoolean();
        synchronized (this) {
            if (request == generation) {
                if (entries.size() >= 256) {
                    entries.clear();
                }
                entries.put(id, new Entry(signature, readable));
            }
        }
        return readable;
    }

    synchronized void clear() {
        ++generation;
        entries.clear();
    }

    private static final class Entry {
        final String signature;
        final boolean readable;

        Entry(String signature, boolean readable) {
            this.signature = signature;
            this.readable = readable;
        }
    }
}
