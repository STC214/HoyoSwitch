package dev.hoyoswitch.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-game snapshots of the state files. Payloads are encrypted with a Keystore key, every write
 * is preceded by a byte-exact safety snapshot and verified by re-reading the device.
 */
final class SnapshotStore {
    static final class Entry {
        String path;
        boolean present;
        long size;
        String sha256 = "";
        String uid = "";
        String gid = "";
        String mode = "";
        String context = "";
        long mtime;
    }

    static final class Snapshot {
        GameProfile game;
        String id;
        String name;
        long createdAt;
        long lastUsedAt;
        String loginName = "";
        String passwordEnc = "";
        File dir;
        final Map<String, Entry> entries = new LinkedHashMap<>();

        Entry entry(String relative) {
            return entries.get(relative);
        }
    }

    /** What the device currently looks like, identified without displaying account ids. */
    static final class Current {
        boolean loggedIn;
        String mid = "";
        String note = "";
    }

    /** A batch of display data read while no snapshot directory can be replaced. */
    static final class RefreshData {
        final Current current;
        final List<Snapshot> snapshots;
        final Map<String, String> midById;
        final Map<String, Boolean> readableById;
        final String recoveryError;

        RefreshData(Current current, List<Snapshot> snapshots, Map<String, String> midById,
                    Map<String, Boolean> readableById, String recoveryError) {
            this.current = current;
            this.snapshots = java.util.Collections.unmodifiableList(snapshots);
            this.midById = java.util.Collections.unmodifiableMap(midById);
            this.readableById = java.util.Collections.unmodifiableMap(readableById);
            this.recoveryError = recoveryError;
        }
    }

    private static final String MANIFEST = SnapshotSwap.MANIFEST;
    private static final String HISTORY = SnapshotSwap.HISTORY;
    private static final Pattern HISTORY_SUFFIX = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final int HISTORY_KEEP = 3;
    private static final int VERSION = 2;

    /** Process-wide lock: Activity, floating service and background threads share snapshots. */
    static final Object LOCK = new Object();

    /** Self-test hook: fail verification after this many successful writes; -1 disables it. */
    static volatile int failAfterWrites = -1;
    /** Diagnostics for the rollback self test: proves the fault fired and a write happened. */
    static volatile int injectedFailures;
    static volatile int deviceWrites;

    private final File root;
    private final Context context;

    SnapshotStore(Context context) {
        this.context = context;
        root = new File(context.getFilesDir(), "snapshots");
        migrateLegacyLayout();
    }

    /**
     * Snapshots used to live directly under {@code snapshots/}. Move them into the per-game
     * directory once, so older captures keep showing up in their game section.
     */
    private void migrateLegacyLayout() {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory() || !new File(child, MANIFEST).isFile()) {
                continue;
            }
            String pkg = GameProfile.STARRAIL.pkg;
            try {
                JSONObject manifest = new JSONObject(new String(readLocal(new File(child, MANIFEST)),
                        StandardCharsets.UTF_8));
                pkg = manifest.optString("package", pkg);
            } catch (IOException | JSONException ignored) {
                // Fall back to the only game that had snapshots before the split.
            }
            File destination = new File(new File(root, pkg), child.getName());
            File parent = destination.getParentFile();
            if (parent != null && (parent.isDirectory() || parent.mkdirs())
                    && !destination.exists()) {
                //noinspection ResultOfMethodCallIgnored
                child.renameTo(destination);
            }
        }
    }

    private File dirFor(GameProfile game) {
        return new File(root, game.pkg);
    }

    private File storedFile(Snapshot snapshot, String relative) {
        return new File(new File(snapshot.dir, "files"), relative + ".enc");
    }

    private File legacyFile(Snapshot snapshot, String relative) {
        return new File(new File(snapshot.dir, "files"), relative);
    }

    /** Reads a stored payload, decrypting it; plaintext files from older builds still work. */
    private byte[] readSnapshot(Snapshot snapshot, String relative) throws IOException {
        File encrypted = storedFile(snapshot, relative);
        if (encrypted.isFile()) {
            return SnapshotCrypto.decrypt(readLocal(encrypted));
        }
        File plain = legacyFile(snapshot, relative);
        if (plain.isFile()) {
            return readLocal(plain);
        }
        throw new IOException("快照文件缺失: " + relative);
    }

    String capture(GameProfile game, String name) throws IOException {
        synchronized (LOCK) {
            return captureLocked(game, name);
        }
    }

    private String captureLocked(GameProfile game, String name) throws IOException {
        return captureLocked(game, name, dirFor(game));
    }

    private String captureLocked(GameProfile game, String name, File base) throws IOException {
        requireNoPendingSwitch(game);
        if (!game.supported) {
            throw new IOException(game.label + " 尚未适配，不能拍摄快照");
        }
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("无法创建快照目录: " + base);
        }
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(base, id);
        int suffix = 0;
        while (dir.exists()) {
            suffix++;
            dir = new File(base, id + "-" + suffix);
        }
        if (!dir.mkdirs()) {
            throw new IOException("无法创建快照目录: " + dir);
        }
        Snapshot snapshot = new Snapshot();
        snapshot.game = game;
        snapshot.id = dir.getName();
        snapshot.name = name == null || name.trim().isEmpty() ? snapshot.id : name.trim();
        snapshot.createdAt = System.currentTimeMillis();
        snapshot.dir = dir;
        boolean live = game.isRunning();
        long started = System.currentTimeMillis();
        // Snapshot the account identity before and after: if the client switches accounts while
        // the files are being read, the resulting mix must not be saved as a valid snapshot.
        String beforeMid = live ? currentMidOrEmpty(game) : null;
        // Each file needs its own root call; run them in parallel instead of one after another.
        // Every database (account db and mihoyo_account) is read together with its journal/WAL/SHM
        // so a checkpoint landing between reads cannot leave the main file and sidecars inconsistent.
        List<List<String>> dbGroups = new ArrayList<>();
        Set<String> grouped = new HashSet<>();
        for (String relative : game.files) {
            if (grouped.contains(relative) || !isDatabase(relative)) {
                continue;
            }
            List<String> group = new ArrayList<>();
            group.add(relative);
            grouped.add(relative);
            for (String sideSuffix : new String[]{"-journal", "-wal", "-shm"}) {
                String side = relative + sideSuffix;
                if (game.files.contains(side)) {
                    group.add(side);
                    grouped.add(side);
                }
            }
            dbGroups.add(group);
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Entry>> futures = new ArrayList<>();
        List<String> singles = new ArrayList<>();
        boolean complete = false;
        try {
            for (String relative : game.files) {
                if (grouped.contains(relative)) {
                    continue;
                }
                boolean stable = live && game.switchFiles.contains(relative);
                singles.add(relative);
                futures.add(pool.submit(() -> captureOne(game, snapshot, relative, stable)));
            }
            for (int index = 0; index < singles.size(); index++) {
                snapshot.entries.put(singles.get(index), futures.get(index).get());
            }
            for (List<String> group : dbGroups) {
                Map<String, byte[]> read = readDbGroup(game, group, live);
                for (String relative : group) {
                    byte[] bytes = read.get(relative);
                    if (bytes == null) {
                        Entry missing = new Entry();
                        missing.path = relative;
                        missing.present = false;
                        snapshot.entries.put(relative, missing);
                    } else {
                        snapshot.entries.put(relative, storeEntry(snapshot, relative, bytes, false));
                    }
                }
            }
            saveManifest(snapshot);
            String afterMid = live ? currentMidOrEmpty(game) : null;
            if (beforeMid != null && afterMid != null && !beforeMid.equals(afterMid)) {
                throw new IOException("拍摄期间账号发生变化，已取消本次快照");
            }
            complete = true;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("拍摄失败: " + cause, cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("拍摄被中断", error);
        } finally {
            if (!complete) {
                // Cancel first: deleting the directory while workers still write would leave files.
                for (Future<Entry> future : futures) {
                    future.cancel(true);
                }
            }
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            if (!complete) {
                deleteRecursively(dir);
            }
        }
        Log.i("hoyoswitch", "capture " + snapshot.id + " took "
                + (System.currentTimeMillis() - started) + " ms");
        return snapshot.id;
    }

    private Entry captureOne(GameProfile game, Snapshot snapshot, String relative, boolean stable)
            throws IOException {
        String path = game.path(relative);
        RootShell.Result content = RootShell.run(
                "if [ -e " + quote(path) + " ]; then cat " + quote(path) + "; else exit 3; fi");
        if (content.code == 3) {
            Entry entry = new Entry();
            entry.path = relative;
            entry.present = false;
            return entry;
        }
        if (content.code != 0) {
            // Missing files are handled above; any other failure means the capture is unusable.
            throw new IOException("读取失败(" + content.code + "): " + relative
                    + (content.err.isEmpty() ? "" : " -> " + content.err));
        }
        byte[] bytes = stable ? stableRead(path, content.out) : content.out;
        return storeEntry(snapshot, relative, bytes, true);
    }

    /** Encrypts {@code bytes} into the snapshot dir and fills the entry metadata + hash. */
    private Entry storeEntry(Snapshot snapshot, String relative, byte[] bytes, boolean checkSourceSize)
            throws IOException {
        Entry entry = new Entry();
        entry.path = relative;
        entry.present = true;
        entry.size = bytes.length;
        entry.sha256 = sha256(bytes);
        Entry metadata = stat(snapshot.game.path(relative));
        requireRestoreMetadata(metadata, relative);
        // Database groups already validate each framed length during the group read.
        if (checkSourceSize && metadata.size != bytes.length) {
            throw new IOException("读取字节数与源文件大小不一致: " + relative
                    + " (" + bytes.length + "/" + metadata.size + ")");
        }
        entry.uid = metadata.uid;
        entry.gid = metadata.gid;
        entry.mode = metadata.mode;
        entry.context = metadata.context;
        entry.mtime = metadata.mtime;
        File local = storedFile(snapshot, relative);
        File parent = local.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录: " + parent);
        }
        writeLocal(local, SnapshotCrypto.encrypt(bytes));
        if (!sha256(SnapshotCrypto.decrypt(readLocal(local))).equals(entry.sha256)) {
            throw new IOException("本地副本校验失败: " + relative);
        }
        return entry;
    }

    /**
     * Reads the account database together with its journal/WAL/SHM in one pass and, while the game
     * runs, re-reads until two consecutive passes agree, so a checkpoint landing between the files
     * cannot produce a snapshot whose main file and sidecars are inconsistent.
     */
    private Map<String, byte[]> readDbGroup(GameProfile game, List<String> relatives, boolean stable)
            throws IOException {
        String first = readDbGroupOnce(game, relatives);
        if (stable) {
            boolean settled = false;
            for (int attempt = 0; attempt < 3 && !settled; attempt++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
                String again = readDbGroupOnce(game, relatives);
                if (first.equals(again)) {
                    settled = true;
                } else {
                    first = again;
                }
            }
            if (!settled) {
                throw new IOException("账号数据库在拍摄期间持续变化，请停止游戏后重试");
            }
        }
        return splitDbGroup(first, relatives);
    }

    private String readDbGroupOnce(GameProfile game, List<String> relatives) throws IOException {
        StringBuilder script = new StringBuilder("f=0; ");
        for (String relative : relatives) {
            String path = game.path(relative);
            script.append("printf '\\n--HSN1DB--").append(relative).append("\\n'; ");
            script.append("if [ -e ").append(quote(path)).append(" ]; then printf 'P'; stat -c %s ")
                    .append(quote(path)).append(" || f=1; cat ").append(quote(path))
                    .append(" || f=1; else printf 'A'; fi; ");
        }
        script.append("exit $f");
        RootShell.Result result = RootShell.run(script.toString());
        if (result.code != 0) {
            throw new IOException("读取数据库失败(" + result.code + "): " + result.err);
        }
        return new String(result.out, StandardCharsets.ISO_8859_1);
    }

    /** Parses the length-prefixed framing; content bytes may contain anything, even markers. */
    private Map<String, byte[]> splitDbGroup(String data, List<String> relatives)
            throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int cursor = 0;
        for (String relative : relatives) {
            String marker = "\n--HSN1DB--" + relative + "\n";
            if (!data.startsWith(marker, cursor)) {
                throw new IOException("数据库分帧缺少预期条目: " + relative);
            }
            int pos = cursor + marker.length();
            if (pos >= data.length()) {
                throw new IOException("数据库分帧截断: " + relative);
            }
            if (data.charAt(pos) == 'A') {
                out.put(relative, null);
                cursor = pos + 1;
                continue;
            }
            if (data.charAt(pos) != 'P') {
                throw new IOException("数据库分帧状态无效: " + relative);
            }
            // 'P' then decimal size then newline, then exactly that many bytes.
            pos++;
            int newline = data.indexOf('\n', pos);
            if (newline < 0) {
                throw new IOException("数据库分帧缺少长度分隔符: " + relative);
            }
            long size;
            try {
                size = Long.parseLong(data.substring(pos, newline).trim());
            } catch (NumberFormatException error) {
                throw new IOException("数据库分帧长度无效: " + relative, error);
            }
            int contentStart = newline + 1;
            if (size < 0 || size > data.length() - contentStart) {
                throw new IOException("数据库分帧长度越界: " + relative);
            }
            int contentEnd = contentStart + (int) size;
            out.put(relative, data.substring(contentStart, contentEnd)
                    .getBytes(StandardCharsets.ISO_8859_1));
            cursor = contentEnd;
        }
        if (cursor != data.length()) {
            throw new IOException("数据库分帧包含多余数据");
        }
        return out;
    }

    /**
     * The game keeps rewriting some of these files while it runs. Re-read until two consecutive
     * reads agree, so a capture taken during play does not archive a half-written file.
     */
    private byte[] stableRead(String path, byte[] first) throws IOException {
        byte[] candidate = first;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            RootShell.Result again = RootShell.run(
                    "if [ -e " + quote(path) + " ]; then cat " + quote(path)
                            + "; else exit 3; fi");
            if (again.code == 3) {
                break;
            }
            if (again.code != 0) {
                throw new IOException("读取失败(" + again.code + "): " + path);
            }
            if (Arrays.equals(candidate, again.out)) {
                return candidate;
            }
            candidate = again.out;
        }
        Log.w("hoyoswitch", "unstable read while game running: " + path);
        throw new IOException("文件在拍摄期间持续变化，请停止游戏后重试: " + path);
    }

    List<Snapshot> list(GameProfile game) {
        synchronized (LOCK) {
            return listLocked(game);
        }
    }

    /** Worker only: identity, readability and manifest must come from the same snapshot version. */
    RefreshData readRefreshData(GameProfile game, boolean cleanUp) {
        synchronized (LOCK) {
            String recoveryError = "";
            try {
                recoverPendingSwitch(game, message -> Log.i("hoyoswitch", message));
            } catch (IOException error) {
                recoveryError = "上次切换未恢复：" + error.getMessage();
            }
            if (cleanUp && recoveryError.isEmpty()) {
                cleanupLocked(game);
            }
            Current current = null;
            if (recoveryError.isEmpty()) {
                try {
                    current = detectCurrentLocked(game);
                } catch (IOException ignored) {
                    // No confirmed device state to display.
                }
            }
            boolean needsIdentity = current != null && current.loggedIn && !current.mid.isEmpty();
            List<Snapshot> snapshots = listLocked(game);
            Map<String, String> midById = new LinkedHashMap<>();
            Map<String, Boolean> readableById = new LinkedHashMap<>();
            for (Snapshot snapshot : snapshots) {
                if (needsIdentity) {
                    midById.put(snapshot.id, accountMid(snapshot));
                }
                readableById.put(snapshot.id, decryptable(snapshot));
            }
            return new RefreshData(current, snapshots, midById, readableById, recoveryError);
        }
    }

    /** Card actions may update their handle in place; do not give them the cached display object. */
    static Snapshot copyForAction(Snapshot source) {
        Snapshot copy = new Snapshot();
        copy.game = source.game;
        copy.id = source.id;
        copy.dir = source.dir;
        adopt(copy, source);
        return copy;
    }

    private List<Snapshot> listLocked(GameProfile game) {
        List<Snapshot> snapshots = new ArrayList<>();
        File base = dirFor(game);
        recoverSwaps(base);
        File[] children = base.listFiles();
        if (children == null) {
            return snapshots;
        }
        for (File child : children) {
            if (!child.isDirectory() || HISTORY.equals(child.getName())
                    || SnapshotSwap.STAGING.equals(child.getName())
                    || SnapshotSwap.recordFor(base, child.getName()).exists()
                    || !new File(child, MANIFEST).isFile()) {
                continue;
            }
            try {
                snapshots.add(readManifest(game, child));
            } catch (IOException | JSONException ignored) {
                // Damaged snapshots are skipped here.
            }
        }
        snapshots.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return snapshots;
    }

    Snapshot load(GameProfile game, String id) throws IOException {
        synchronized (LOCK) {
            try {
                File base = dirFor(game);
                recoverSwap(base, id);
                return readManifest(game, new File(base, id));
            } catch (JSONException error) {
                throw new IOException("快照清单损坏: " + id, error);
            }
        }
    }

    /**
     * Identifies the account the client is currently on. Hashes cannot be used here: the client
     * rewrites token, SDK prefs and the database timestamp on every launch.
     */
    Current detectCurrent(GameProfile game) throws IOException {
        synchronized (LOCK) {
            return detectCurrentLocked(game);
        }
    }

    private Current detectCurrentLocked(GameProfile game) throws IOException {
        Current current = new Current();
        if (!game.supported) {
            return current;
        }
        // Games without a combo widget identify the account from the token prefs key.
        if (game.accountDb != null) {
            String dbPath = game.path(game.accountDb);
            byte[] db = readDevice(dbPath);
            if (db != null) {
                List<String> candidates = dbCandidateMids(db, readDevice(dbPath + "-journal"),
                        readDevice(dbPath + "-wal"), readDevice(dbPath + "-shm"));
                // A deleted account leaves no token, so the byte-scan fallback must not report a
                // remnant that no longer has a live token; keep scanning to the next candidate.
                for (String candidate : candidates) {
                    if (hasLiveToken(game, candidate)) {
                        current.loggedIn = true;
                        current.mid = candidate;
                        break;
                    }
                }
                return current;
            }
            // DB missing or unreadable: fall back to the token key as a best effort.
            if (game.tokenPrefs != null) {
                byte[] token = readDevice(game.path(game.tokenPrefs));
                if (token != null) {
                    String mid = tokenMid(token);
                    if (!mid.isEmpty()) {
                        current.loggedIn = true;
                        current.mid = mid;
                        return current;
                    }
                }
            }
            return current;
        }
        String statusPath = game.loginPrefs;
        if (statusPath == null) {
            return current;
        }
        byte[] prefs = readDevice(game.path(statusPath));
        if (prefs == null) {
            current.note = "读不到登录状态文件";
            return current;
        }
        if (!"true".equals(preferenceValue(prefs, "boolean", "login_status"))) {
            return current;
        }
        current.loggedIn = true;
        if (game.identityPrefs != null) {
            byte[] widget = readDevice(game.path(game.identityPrefs));
            String account = widget == null ? null : preferenceValue(widget, "string", "account");
            if (account != null) {
                current.mid = accountField(account, "mid");
            }
        }
        if (current.mid.isEmpty()) {
            current.note = "已登录，但读不到账号标识";
        }
        return current;
    }

    /** Account id stored in a snapshot's widget cache or token prefs key; empty when unreadable. */
    String accountMid(Snapshot snapshot) {
        try {
            return readAccountMid(snapshot);
        } catch (IOException error) {
            return "";
        }
    }

    private String readAccountMid(Snapshot snapshot) throws IOException {
        GameProfile game = snapshot.game;
        if (game.accountDb != null) {
            try {
                List<String> candidates = dbCandidateMids(readSnapshot(snapshot, game.accountDb),
                        snapshotBytes(snapshot, game.accountDb + "-journal"),
                        snapshotBytes(snapshot, game.accountDb + "-wal"),
                        snapshotBytes(snapshot, game.accountDb + "-shm"));
                // Apply the same liveness check as device detection: a snapshot of an account that
                // was deleted (no token) must not be identified by its byte-scan remnant.
                for (String candidate : candidates) {
                    if (hasSnapshotToken(snapshot, candidate)) {
                        return candidate;
                    }
                }
                // The DB was readable but has no live account: do not trust stale token keys.
                return "";
            } catch (IOException ignored) {
                // Fall through to the token key only when the DB could not be read.
            }
        }
        if (game.tokenPrefs != null) {
            Entry tokenEntry = snapshot.entry(game.tokenPrefs);
            if (tokenEntry == null || !tokenEntry.present) {
                return "";
            }
            return tokenMid(readSnapshot(snapshot, game.tokenPrefs));
        }
        if (game.identityPrefs != null) {
            Entry widgetEntry = snapshot.entry(game.identityPrefs);
            if (widgetEntry == null || !widgetEntry.present) {
                // Logged-out captures legitimately have no account widget.
                return "";
            }
            byte[] widget = readSnapshot(snapshot, game.identityPrefs);
            String account = preferenceValue(widget, "string", "account");
            return account == null ? "" : accountField(account, "mid");
        }
        return "";
    }

    /**
     * SQLite stores each account row as {@code <mid><aid><type><13-digit timestamp><blob>}; the
     * newest timestamp is the account the game is currently on.
     */
    private String currentMidOrEmpty(GameProfile game) {
        try {
            Current current = detectCurrentLocked(game);
            return current.loggedIn ? current.mid : "";
        } catch (IOException error) {
            return null;
        }
    }

    private static final Pattern ACCOUNT_ROW =
            Pattern.compile("([0-9a-z_]+_mhy)(\\d+)(\\d{13})");

    /**
     * Candidate account ids, newest first. Uses a real SQLite query when possible (so deleted rows
     * in freelist pages are never returned); otherwise falls back to a byte scan whose results are
     * later filtered against a live token. An empty list means the table is genuinely empty or the
     * file could not be read at all.
     */
    private List<String> dbCandidateMids(byte[] db, byte[] journal, byte[] wal, byte[] shm) {
        File dir = new File(context.getCacheDir(), "dbscan-" + System.nanoTime());
        boolean queried = false;
        try {
            if (!dir.mkdirs()) {
                return Collections.emptyList();
            }
            File main = new File(dir, "account.db");
            Files.write(main.toPath(), db);
            writeIfPresent(new File(dir, "account.db-journal"), journal);
            writeIfPresent(new File(dir, "account.db-wal"), wal);
            writeIfPresent(new File(dir, "account.db-shm"), shm);
            SQLiteDatabase database = SQLiteDatabase.openDatabase(main.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            try (Cursor cursor = database.rawQuery(
                    "SELECT mid FROM porte_account_table ORDER BY timestamp DESC LIMIT 1", null)) {
                queried = true;
                if (cursor.moveToFirst()) {
                    String mid = cursor.getString(0);
                    if (mid != null && !mid.isEmpty()) {
                        return Collections.singletonList(mid);
                    }
                }
                // Query succeeded with zero rows: the table is genuinely empty.
                return Collections.emptyList();
            } finally {
                database.close();
            }
        } catch (Exception error) {
            Log.w("hoyoswitch", "sqlite account scan failed: " + error);
        } finally {
            deleteRecursively(dir);
        }
        // queried == true means the table was genuinely empty (returned above). Otherwise SQLite
        // could not read the file (Android's SQLite only supports UTF-8; these account DBs are
        // UTF-16), so scan the raw bytes; the caller filters them against a live token.
        return queried ? Collections.<String>emptyList() : regexMid(db);
    }

    private static void writeIfPresent(File file, byte[] data) throws IOException {
        if (data != null) {
            Files.write(file.toPath(), data);
        }
    }

    private static List<String> regexMid(byte[] db) {
        // SQLite's text encoding lives at offset 56-59 (1=UTF-8, 2=UTF-16LE, 3=UTF-16BE); the BOM
        // at the start of the header is a fallback. Android's SQLite cannot open UTF-16 databases,
        // so fall back to a byte-level scan of the account row.
        int encoding = db.length > 59
                ? (db[56] & 0xFF) << 24 | (db[57] & 0xFF) << 16
                | (db[58] & 0xFF) << 8 | (db[59] & 0xFF)
                : 0;
        boolean le = encoding == 2
                || (db.length >= 2 && db[0] == (byte) 0xFF && db[1] == (byte) 0xFE);
        boolean be = encoding == 3
                || (db.length >= 2 && db[0] == (byte) 0xFE && db[1] == (byte) 0xFF);
        return scanRows(db, le, be);
    }

    /**
     * Scans the raw bytes for {@code <mid>_mhy<digits...><13-digit timestamp>} without assuming any
     * 2-byte alignment, so records stored at odd offsets are still found. Returns the distinct mids
     * ordered by descending timestamp (so the newest is first), or an empty list when none is found.
     */
    private static List<String> scanRows(byte[] db, boolean le, boolean be) {
        byte[] needle = le ? new byte[]{'_', 0, 'm', 0, 'h', 0, 'y', 0}
                : be ? new byte[]{0, '_', 0, 'm', 0, 'h', 0, 'y'}
                : new byte[]{'_', 'm', 'h', 'y'};
        int step = (le || be) ? 2 : 1;
        Map<String, Long> byMid = new LinkedHashMap<>();
        int index = indexOf(db, needle, 0);
        while (index >= 0) {
            StringBuilder mid = new StringBuilder("_mhy");
            int p = index - step;
            while (p >= 0) {
                char c = charAt(db, p, le, be);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '_') {
                    mid.insert(0, c);
                    p -= step;
                } else {
                    break;
                }
            }
            StringBuilder digits = new StringBuilder();
            int q = index + needle.length;
            while (q + (le || be ? 1 : 0) < db.length) {
                char c = charAt(db, q, le, be);
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                    q += step;
                } else {
                    break;
                }
            }
            if (digits.length() >= 13) {
                try {
                    long timestamp = Long.parseLong(digits.substring(digits.length() - 13));
                    Long previous = byMid.get(mid.toString());
                    if (previous == null || timestamp > previous) {
                        byMid.put(mid.toString(), timestamp);
                    }
                } catch (NumberFormatException ignored) {
                    // Skip malformed digits.
                }
            }
            index = indexOf(db, needle, index + 1);
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(byMid.entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : entries) {
            result.add(entry.getKey());
        }
        return result;
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static char charAt(byte[] db, int p, boolean le, boolean be) {
        if (!le && !be) {
            return (char) (db[p] & 0xFF);
        }
        if (le) {
            return (char) ((db[p] & 0xFF) | ((db[p + 1] & 0xFF) << 8));
        }
        return (char) (((db[p] & 0xFF) << 8) | (db[p + 1] & 0xFF));
    }

    private byte[] snapshotBytes(Snapshot snapshot, String relative) {
        Entry entry = snapshot.entry(relative);
        if (entry == null || !entry.present) {
            return null;
        }
        try {
            return readSnapshot(snapshot, relative);
        } catch (IOException error) {
            return null;
        }
    }

    /** Genshin stores one token per account under {@code <mid>_mhy_porte_cookie_token_key}. */
    private static String tokenMid(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        int suffix = text.indexOf("_mhy_porte_cookie_token_key");
        if (suffix <= 0) {
            return "";
        }
        int nameStart = text.lastIndexOf("name=\"", suffix);
        if (nameStart < 0) {
            return "";
        }
        String name = text.substring(nameStart + "name=\"".length(), suffix);
        int quote = name.indexOf('"');
        if (quote >= 0) {
            name = name.substring(0, quote);
        }
        // The key is "<mid>_mhy_porte_cookie_token_key", so the token's mid ends with "_mhy".
        return name.trim() + "_mhy";
    }

    /** True when {@code xml} holds a token key for {@code mid}. */
    private static boolean hasTokenKey(byte[] xml, String mid) {
        if (mid == null || mid.isEmpty()) {
            return false;
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        // The key is "<mid>_porte_cookie_token_key"; mid already ends with "_mhy".
        return text.contains("name=\"" + mid + "_porte_cookie_token_key\"");
    }

    /**
     * Whether {@code mid} still has a live token. A deleted account leaves no token, so a byte-scan
     * remnant in the account database (which cannot be told apart from a live row without SQLite)
     * is rejected when its token is gone.
     */
    private boolean hasLiveToken(GameProfile game, String mid) {
        if (game.tokenPrefs == null) {
            return true;
        }
        try {
            byte[] token = readDevice(game.path(game.tokenPrefs));
            return token != null && hasTokenKey(token, mid);
        } catch (IOException error) {
            return false;
        }
    }

    /** Like {@link #hasLiveToken} but checks the snapshot's own stored token prefs. */
    private boolean hasSnapshotToken(Snapshot snapshot, String mid) {
        if (snapshot.game.tokenPrefs == null) {
            return true;
        }
        Entry tokenEntry = snapshot.entry(snapshot.game.tokenPrefs);
        if (tokenEntry == null || !tokenEntry.present) {
            return false;
        }
        try {
            return hasTokenKey(readSnapshot(snapshot, snapshot.game.tokenPrefs), mid);
        } catch (IOException error) {
            return false;
        }
    }

    /**
     * Re-captures the device state into an existing snapshot, keeping its id and name. The previous
     * version is archived first so an update is never a one-way change.
     */
    String updateSnapshot(Snapshot target) throws IOException {
        synchronized (LOCK) {
            return updateSnapshotLocked(target, null);
        }
    }

    private String updateSnapshotLocked(Snapshot target, String expectedMid) throws IOException {
        GameProfile game = target.game;
        Snapshot current = reload(target);
        if (expectedMid != null) {
            String before = currentMidOrEmpty(game);
            if (before != null && !before.equals(expectedMid)) {
                throw new IOException("当前账号已变化，未更新该快照");
            }
        }
        File base = dirFor(game);
        File staging = new File(base, SnapshotSwap.STAGING);
        String tempId = captureLocked(game, current.name, staging);
        File temp = new File(staging, tempId);
        boolean published = false;
        try {
            if (expectedMid != null) {
                String after = currentMidOrEmpty(game);
                if (after != null && !after.equals(expectedMid)) {
                    throw new IOException("拍摄期间账号发生变化，已取消本次更新");
                }
            }
            Snapshot refreshed = readManifest(game, temp);
            refreshed.id = target.id;
            refreshed.name = current.name;
            refreshed.lastUsedAt = current.lastUsedAt;
            refreshed.loginName = current.loginName;
            refreshed.passwordEnc = current.passwordEnc;
            // Finish the replacement manifest while the live snapshot is still untouched.
            saveManifest(refreshed);
            File archived = archiveDestination(target.dir);
            SnapshotSwap.begin(target.dir, temp, archived).publish(SnapshotStore::pruneSwapHistory);
            published = true;
            adopt(target, refreshed);
        } catch (JSONException error) {
            throw new IOException("待更新的快照清单损坏", error);
        } finally {
            if (!published && !SnapshotSwap.recordFor(base, target.id).exists()) {
                deleteRecursively(temp);
            }
        }
        return target.id;
    }

    private File archiveDestination(File dir) throws IOException {
        File history = new File(dir.getParentFile(), HISTORY);
        if (!history.isDirectory() && !history.mkdirs()) {
            throw new IOException("无法创建历史快照目录: " + history);
        }
        return availableHistoryPath(history, dir.getName(),
                Long.toString(System.currentTimeMillis()));
    }

    private static void recoverSwap(File base, String id) throws IOException {
        File record = SnapshotSwap.recordFor(base, id);
        if (record.exists()) {
            SnapshotSwap.recover(record, SnapshotStore::pruneSwapHistory);
        }
    }

    private static void pruneSwapHistory(String id, File archive) {
        pruneHistory(archive.getParentFile(), id, archive);
    }

    private static boolean recoverSwaps(File base) {
        File[] records = base.listFiles(SnapshotSwap::isRecord);
        if (records == null) {
            return !base.exists();
        }
        boolean allRecovered = true;
        for (File record : records) {
            try {
                SnapshotSwap.recover(record, SnapshotStore::pruneSwapHistory);
            } catch (IOException error) {
                allRecovered = false;
                Log.w("hoyoswitch", "快照交换尚未恢复: " + record.getName(), error);
            }
        }
        return allRecovered;
    }

    /** Keeps the newest {@code keep} snapshots with the given name, deleting the rest. */
    private void pruneNamed(GameProfile game, String name, int keep) {
        int seen = 0;
        for (Snapshot snapshot : list(game)) {
            if (!name.equals(snapshot.name)) {
                continue;
            }
            seen++;
            if (seen > keep) {
                deleteForCleanup(snapshot);
            }
        }
    }

    /**
     * Housekeeping: removes abandoned directories that never got a manifest (interrupted
     * captures), trims snapshots created by the self tests, and collapses snapshots of the same
     * account into a single card.
     */
    void cleanup(GameProfile game) {
        synchronized (LOCK) {
            cleanupLocked(game);
        }
    }

    private void cleanupLocked(GameProfile game) {
        File base = dirFor(game);
        if (PendingSwitch.exists(base) || !recoverSwaps(base)) {
            return; // No housekeeping may remove a directory still referenced by a swap.
        }
        deleteRecursively(new File(base, SnapshotSwap.STAGING));
        File[] children = base.listFiles();
        long cutoff = System.currentTimeMillis() - 3 * 60 * 1000L;
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory() || HISTORY.equals(child.getName())
                        || SnapshotSwap.STAGING.equals(child.getName())) {
                    continue;
                }
                if (!new File(child, MANIFEST).isFile() && child.lastModified() < cutoff) {
                    deleteRecursively(child);
                }
            }
        }
        dedupeAccounts(game);
        pruneNamed(game, "回滚自检基线", 1);
        pruneNamed(game, "切换前自动备份", 2);
    }

    /**
     * One card per account: keeps the newest snapshot of each account id and folds older ones into
     * its history, so nothing is lost while the list stops growing duplicates.
     */
    void dedupeAccounts(GameProfile game) {
        List<Snapshot> snapshots = list(game);
        Map<String, Snapshot> keepByAccount = new LinkedHashMap<>();
        File historyDir = new File(dirFor(game), HISTORY);
        for (Snapshot snapshot : snapshots) {
            String mid;
            try {
                mid = readAccountMid(snapshot);
            } catch (IOException error) {
                // Never delete here: a key can be temporarily unavailable, and the card may still
                // be inspected or deleted by hand. Deleting silently destroyed valid snapshots.
                Log.i("hoyoswitch", "skip unreadable snapshot " + snapshot.id + ": "
                        + error.getMessage());
                continue;
            }
            if (mid.isEmpty()) {
                continue;
            }
            Snapshot primary = keepByAccount.get(mid);
            if (primary == null) {
                keepByAccount.put(mid, snapshot);
                continue;
            }
            if (isSessionName(primary.name)) {
                // Safety captures must not become the card's permanent name.
                renameSafely(primary, isSessionName(snapshot.name) ? primary.id : snapshot.name);
            }
            if (primary.loginName.isEmpty() && !snapshot.loginName.isEmpty()) {
                primary.loginName = snapshot.loginName;
                primary.passwordEnc = snapshot.passwordEnc;
                try {
                    saveManifest(primary);
                } catch (IOException ignored) {
                    // Cosmetic; the merge below still has to happen.
                }
            }
            primary.lastUsedAt = Math.max(primary.lastUsedAt, snapshot.lastUsedAt);
            if (!mergeHistory(historyDir, primary, snapshot)) {
                Log.w("hoyoswitch", "历史合并未完成，保留 " + snapshot.id + " 待下次重试");
            }
        }
        for (Snapshot primary : keepByAccount.values()) {
            if (isSessionName(primary.name)) {
                renameSafely(primary, primary.id);
            }
        }
    }

    private void renameSafely(Snapshot snapshot, String name) {
        try {
            rename(snapshot, name);
        } catch (IOException ignored) {
            // A stale name is cosmetic; the merge below still has to happen.
        }
    }

    private static boolean isSessionName(String name) {
        return name.equals("切换前自动备份") || name.equals("回滚自检基线");
    }

    static boolean mergeHistory(File historyDir, Snapshot primary, Snapshot duplicate) {
        if (!historyDir.isDirectory() && !historyDir.mkdirs()) {
            return false;
        }
        // Keep the duplicate card visible and defer pruning until all of its history is moved.
        if (!migrateHistory(historyDir, primary, duplicate)) {
            return false;
        }
        File destination = availableHistoryPath(historyDir, primary.id, Long.toString(duplicate.createdAt));
        if (!duplicate.dir.renameTo(destination)) {
            return false;
        }
        pruneHistory(historyDir, primary.id, null);
        return true;
    }

    /** Moves the merged card's own history under the primary id; false if any move failed. */
    private static boolean migrateHistory(File historyDir, Snapshot primary, Snapshot duplicate) {
        File[] versions = historyDir.listFiles();
        if (versions == null) {
            return false;
        }
        boolean allMoved = true;
        for (File version : versions) {
            if (!version.isDirectory() || !isHistoryOf(version.getName(), duplicate.id)) {
                continue;
            }
            String suffix = version.getName().substring(duplicate.id.length() + 1);
            File target = availableHistoryPath(historyDir, primary.id, suffix);
            if (!version.renameTo(target)) {
                allMoved = false;
            }
        }
        return allMoved;
    }

    private static File availableHistoryPath(File historyDir, String id, String suffix) {
        String prefix = id + "-";
        File target = new File(historyDir, prefix + suffix);
        String timestamp = historyTimestamp(suffix);
        long sequence = 1;
        while (target.exists()) {
            target = new File(historyDir, prefix + timestamp + "." + sequence++);
        }
        return target;
    }

    /** Database paths: the account db ends in .db, mihoyo_account has no extension. */
    private static boolean isDatabase(String relative) {
        return relative.endsWith(".db") || relative.equals("databases/mihoyo_account");
    }

    /** Accepts legacy timestamps and the optional sequence used for a same-time collision. */
    private static boolean isHistoryOf(String name, String id) {
        return name.startsWith(id + "-") && HISTORY_SUFFIX.matcher(name.substring(id.length() + 1)).matches();
    }

    private static String historyTimestamp(String suffix) {
        int separator = suffix.indexOf('.');
        return separator < 0 ? suffix : suffix.substring(0, separator);
    }

    private static long historyTime(File version, String id) {
        String suffix = version.getName().substring(id.length() + 1);
        try {
            return Long.parseLong(historyTimestamp(suffix));
        } catch (NumberFormatException error) {
            return version.lastModified();
        }
    }

    private static long historySequence(File version) {
        String name = version.getName();
        int separator = name.lastIndexOf('.');
        try {
            return separator < 0 ? 0 : Long.parseLong(name.substring(separator + 1));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /** The UI and retention policy must agree on which version is newer. */
    private static List<File> historyVersions(File historyDir, String id) {
        File[] versions = historyDir.listFiles();
        List<File> mine = new ArrayList<>();
        if (versions != null) {
            for (File version : versions) {
                if (version.isDirectory() && isHistoryOf(version.getName(), id)) {
                    mine.add(version);
                }
            }
        }
        mine.sort((left, right) -> {
            int byTime = Long.compare(historyTime(right, id), historyTime(left, id));
            if (byTime != 0) {
                return byTime;
            }
            int bySequence = Long.compare(historySequence(right), historySequence(left));
            return bySequence != 0 ? bySequence : right.getName().compareTo(left.getName());
        });
        return mine;
    }

    /** Keeps the newest versions of one snapshot, always sparing {@code keep}. */
    private static void pruneHistory(File historyDir, String id, File keep) {
        List<File> mine = historyVersions(historyDir, id);
        int allowed = mine.remove(keep) ? HISTORY_KEEP - 1 : HISTORY_KEEP;
        for (int index = allowed; index < mine.size(); index++) {
            deleteRecursively(mine.get(index));
        }
    }

    static final class HistoryEntry {
        File dir;
        long time;
        String label;
    }

    /** Archived versions of one snapshot, newest first. */
    List<HistoryEntry> history(Snapshot snapshot) {
        List<HistoryEntry> entries = new ArrayList<>();
        File history = new File(snapshot.dir.getParentFile(), HISTORY);
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        for (File version : historyVersions(history, snapshot.id)) {
            HistoryEntry entry = new HistoryEntry();
            entry.dir = version;
            entry.time = historyTime(version, snapshot.id);
            entry.label = format.format(new Date(entry.time));
            entries.add(entry);
        }
        return entries;
    }

    void rename(Snapshot snapshot, String name) throws IOException {
        synchronized (LOCK) {
            renameLocked(snapshot, name);
        }
    }

    private void renameLocked(Snapshot snapshot, String name) throws IOException {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) {
            throw new IOException("名称不能为空");
        }
        Snapshot fresh = reload(snapshot);
        fresh.name = cleaned;
        saveManifest(fresh);
        adopt(snapshot, fresh);
    }

    /** Reads the manifest from disk so a stale in-memory object cannot overwrite newer data. */
    private Snapshot reload(Snapshot snapshot) throws IOException {
        try {
            recoverSwap(dirFor(snapshot.game), snapshot.id);
            return readManifest(snapshot.game, snapshot.dir);
        } catch (JSONException error) {
            throw new IOException("快照清单损坏: " + snapshot.id, error);
        }
    }

    private static void adopt(Snapshot target, Snapshot fresh) {
        target.name = fresh.name;
        target.createdAt = fresh.createdAt;
        target.lastUsedAt = fresh.lastUsedAt;
        target.loginName = fresh.loginName;
        target.passwordEnc = fresh.passwordEnc;
        target.entries.clear();
        target.entries.putAll(fresh.entries);
    }

    /** Stores the card's account name and password; the password is Keystore-encrypted. */
    void setCredentials(Snapshot snapshot, String loginName, String password) throws IOException {
        synchronized (LOCK) {
            setCredentialsLocked(snapshot, loginName, password);
        }
    }

    private void setCredentialsLocked(Snapshot snapshot, String loginName, String password)
            throws IOException {
        Snapshot fresh = reload(snapshot);
        fresh.loginName = loginName == null ? "" : loginName.trim();
        if (password == null || password.isEmpty()) {
            fresh.passwordEnc = "";
        } else {
            fresh.passwordEnc = Base64.encodeToString(
                    SnapshotCrypto.encrypt(password.getBytes(StandardCharsets.UTF_8)),
                    Base64.NO_WRAP);
        }
        saveManifest(fresh);
        adopt(snapshot, fresh);
    }

    /** Decrypts the stored password; empty when none was saved. */
    String password(Snapshot snapshot) throws IOException {
        if (snapshot.passwordEnc.isEmpty()) {
            return "";
        }
        try {
            byte[] raw = Base64.decode(snapshot.passwordEnc, Base64.NO_WRAP);
            return new String(SnapshotCrypto.decrypt(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IOException("密码数据已损坏", error);
        }
    }

    void delete(Snapshot snapshot) throws IOException {
        synchronized (LOCK) {
            deleteLocked(snapshot);
        }
    }

    private void deleteForCleanup(Snapshot snapshot) {
        try {
            delete(snapshot);
        } catch (IOException error) {
            Log.w("hoyoswitch", "快照清理未完成: " + snapshot.id, error);
        }
    }

    private void deleteLocked(Snapshot snapshot) throws IOException {
        requireNoPendingSwitch(snapshot.game);
        recoverSwap(dirFor(snapshot.game), snapshot.id);
        File history = new File(snapshot.dir.getParentFile(), HISTORY);
        File[] versions = history.listFiles();
        if (versions == null && history.exists()) {
            throw new IOException("无法读取快照历史目录，请重试: " + history);
        }
        if (versions != null) {
            for (File version : versions) {
                if (isHistoryOf(version.getName(), snapshot.id)) {
                    deleteChecked(version);
                }
            }
        }
        deleteChecked(snapshot.dir);
    }

    /** Rolls a snapshot back to one archived version; the current version is archived first. */
    void restoreHistory(Snapshot snapshot, HistoryEntry entry) throws IOException {
        synchronized (LOCK) {
            restoreHistoryLocked(snapshot, entry);
        }
    }

    private void restoreHistoryLocked(Snapshot snapshot, HistoryEntry entry) throws IOException {
        requireNoPendingSwitch(snapshot.game);
        Snapshot current = reload(snapshot);
        Snapshot candidate;
        try {
            // A valid manifest alone does not prove the archived payloads are still intact.
            candidate = readManifest(snapshot.game, entry.dir);
        } catch (JSONException error) {
            throw new IOException("历史版本清单损坏，未回滚", error);
        }
        try {
            verifyRestoreSnapshot(candidate);
        } catch (IOException error) {
            throw new IOException("历史版本校验失败，当前版本未改动: " + error.getMessage(), error);
        }
        candidate.id = snapshot.id;
        candidate.name = current.name;
        candidate.lastUsedAt = current.lastUsedAt;
        candidate.loginName = current.loginName;
        candidate.passwordEnc = current.passwordEnc;
        saveManifest(candidate, SnapshotSwap.PREPARED_MANIFEST);
        File archived = archiveDestination(snapshot.dir);
        SnapshotSwap.begin(snapshot.dir, entry.dir, archived).publish(SnapshotStore::pruneSwapHistory);
        adopt(snapshot, candidate);
    }

    /**
     * Saves the current device state before a switch. When the logged-in account already has a
     * card, that card is refreshed in place (old content goes to history) so switching does not
     * spawn a new card every time.
     */
    private String snapshotCurrentAccount(GameProfile game, Progress progress) throws IOException {
        Current current = detectCurrent(game);
        Snapshot existing = current.loggedIn && !current.mid.isEmpty()
                ? findByAccount(game, current.mid) : null;
        if (existing != null) {
            updateSnapshot(existing);
            progress.report("已更新当前账号快照: " + existing.name);
            return existing.id;
        }
        String id = capture(game, "切换前自动备份");
        progress.report("已保存切换前状态: " + id);
        return id;
    }

    /** Snapshot of the account that is logged in right now, or null when unknown. */
    Snapshot currentAccount(GameProfile game) throws IOException {
        Current current = detectCurrent(game);
        if (!current.loggedIn || current.mid.isEmpty()) {
            return null;
        }
        return findByAccount(game, current.mid);
    }

    /**
     * Captures the device state into the current account's existing card, creating one only when
     * the account has no card yet. Keeps one card per account with a stable id.
     */
    String captureAccount(GameProfile game, String name) throws IOException {
        synchronized (LOCK) {
            Snapshot existing = currentAccount(game);
            if (existing == null) {
                return captureLocked(game, name);
            }
            updateSnapshotLocked(existing, null);
            if (name != null && !name.trim().isEmpty()) {
                renameLocked(existing, name);
            }
            return existing.id;
        }
    }

    /** Detects the current account and refreshes its card in one locked transaction. */
    String updateCurrentAccount(GameProfile game) throws IOException {
        synchronized (LOCK) {
            Current current = detectCurrentLocked(game);
            if (!current.loggedIn) {
                throw new IOException("当前未登录，没有可更新的账号快照");
            }
            if (current.mid.isEmpty()) {
                throw new IOException("读不到当前账号标识，无法确认是哪个快照");
            }
            Snapshot matched = findByAccount(game, current.mid);
            if (matched == null) {
                throw new IOException("当前账号不在" + game.label + "的任何快照中，请先拍一张快照");
            }
            return updateSnapshotLocked(matched, current.mid);
        }
    }

    /** Refreshes one card only when that same account is still logged in (atomic check+write). */
    String updateAccountIfCurrent(Snapshot target) throws IOException {
        synchronized (LOCK) {
            GameProfile game = target.game;
            Current current = detectCurrentLocked(game);
            if (!current.loggedIn) {
                throw new IOException("当前未登录，无法更新该快照");
            }
            if (current.mid.isEmpty()) {
                throw new IOException("读不到当前账号标识，无法确认就是这个账号");
            }
            adopt(target, reload(target));
            if (!current.mid.equals(accountMid(target))) {
                throw new IOException("当前登录的不是这个账号，未更新快照");
            }
            return updateSnapshotLocked(target, current.mid);
        }
    }

    /** True when the payloads can actually be decrypted with the current Keystore key. */
    boolean decryptable(Snapshot snapshot) {
        for (String relative : snapshot.game.files) {
            if (!relative.endsWith(".xml")) {
                continue;
            }
            Entry entry = snapshot.entry(relative);
            if (entry == null || !entry.present) {
                continue;
            }
            try {
                readSnapshot(snapshot, relative);
                return true;
            } catch (IOException error) {
                return false;
            }
        }
        return true;
    }

    private Snapshot findByAccount(GameProfile game, String mid) {
        for (Snapshot snapshot : list(game)) {
            if (mid.equals(accountMid(snapshot))) {
                return snapshot;
            }
        }
        return null;
    }

    /** Restores the switch files of {@code target}, with safety snapshot and rollback. */
    void switchTo(Snapshot target, boolean launchAfterwards, Progress progress) throws IOException {
        synchronized (LOCK) {
            requireFreshSwitchTarget(target);
            verifySwitchPayloads(target);
            switchToLocked(target, launchAfterwards, progress);
        }
    }

    /** UI entry: check the confirmed snapshot before interrupting the running game. */
    void stopAndSwitchTo(Snapshot target, Progress progress) throws IOException {
        synchronized (LOCK) {
            requireFreshSwitchTarget(target);
            verifySwitchPayloads(target);
            if (target.game.isRunning()) {
                target.game.forceStop();
                progress.report("已停止" + target.game.label);
            }
            switchToLocked(target, true, progress);
        }
    }

    private void requireFreshSwitchTarget(Snapshot target) throws IOException {
        Snapshot fresh = reload(target);
        if (target.createdAt != fresh.createdAt
                || !target.entries.keySet().equals(fresh.entries.keySet())) {
            throw new IOException("目标快照已变化，请刷新后重新确认");
        }
        for (Entry before : target.entries.values()) {
            Entry now = fresh.entry(before.path);
            if (before.present != now.present || !before.sha256.equals(now.sha256)) {
                throw new IOException("目标快照已变化，请刷新后重新确认");
            }
        }
        adopt(target, fresh);
    }

    private void verifySwitchPayloads(Snapshot target) throws IOException {
        for (String relative : target.game.switchFiles) {
            Entry entry = target.entry(relative);
            if (entry == null || !entry.present) {
                throw new IOException("目标快照缺少必要文件: " + relative);
            }
            readVerifiedSwitchPayload(target, entry);
            if (relative.endsWith(".db")) {
                for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
                    Entry sidecar = target.entry(relative + suffix);
                    if (sidecar != null && sidecar.present) {
                        readVerifiedSwitchPayload(target, sidecar);
                    }
                }
            }
        }
    }

    private byte[] readVerifiedSwitchPayload(Snapshot target, Entry entry) throws IOException {
        requireRestoreMetadata(entry, entry.path);
        byte[] data = readSnapshot(target, entry.path);
        if (data.length != entry.size || !sha256(data).equals(entry.sha256)) {
            throw new IOException("快照文件已损坏: " + entry.path);
        }
        return data;
    }

    private static void requireRestoreMetadata(Entry metadata, String relative) throws IOException {
        if (metadata == null || !metadata.uid.matches("[0-9]+") || !metadata.gid.matches("[0-9]+")
                || !metadata.mode.matches("[0-7]{1,4}")) {
            throw new IOException("文件属主或权限缺失、无效: " + relative);
        }
    }

    /** Validate all entries and payloads before replacing a live state or saved version. */
    private void verifyRestoreSnapshot(Snapshot snapshot) throws IOException {
        for (String relative : snapshot.game.files) {
            if (snapshot.entry(relative) == null) {
                throw new IOException("快照缺少条目: " + relative);
            }
        }
        for (Entry entry : snapshot.entries.values()) {
            if (entry.present) {
                readVerifiedSwitchPayload(snapshot, entry);
            }
        }
    }

    private void requireNoPendingSwitch(GameProfile game) throws IOException {
        if (PendingSwitch.exists(dirFor(game))) {
            throw new IOException("上次切换尚未恢复，请先停止游戏后刷新");
        }
    }

    private void recoverPendingSwitch(GameProfile game, Progress progress) throws IOException {
        PendingSwitch.recover(dirFor(game), id -> {
            if (game.isRunning()) {
                throw new IOException("请先停止游戏，再刷新以恢复切换前的状态");
            }
            Snapshot safety = load(game, id);
            verifyRestoreSnapshot(safety);
            restoreAllLocked(safety, progress);
        });
    }

    void launchGame(GameProfile game) throws IOException {
        synchronized (LOCK) {
            requireNoPendingSwitch(game);
            game.launch();
        }
    }

    private void switchToLocked(Snapshot target, boolean launchAfterwards, Progress progress)
            throws IOException {
        GameProfile game = target.game;
        if (game.isRunning()) {
            throw new IOException("游戏正在运行，请先停止游戏再切换");
        }
        recoverPendingSwitch(game, progress);
        String safetyId = snapshotCurrentAccount(game, progress);
        if (safetyId.equals(target.id)) {
            progress.report("当前已经是这个账号，无需切换");
            if (launchAfterwards) {
                game.launch();
            }
            return;
        }
        Snapshot safety = load(game, safetyId);
        verifyRestoreSnapshot(safety);
        File base = dirFor(game);
        PendingSwitch.begin(base, safetyId);
        try {
            for (String relative : game.switchFiles) {
                Entry entry = target.entry(relative);
                byte[] data = readVerifiedSwitchPayload(target, entry);
                String path = game.path(relative);
                byte[] current = readDevice(path);
                if (current != null && sha256(current).equals(entry.sha256)) {
                    ensureMetadata(path, entry);
                    progress.report("跳过（已一致）: " + relative);
                } else {
                    writeDevice(path, data);
                    verifyWritten(path, entry);
                    progress.report("已写入并校验: " + relative);
                }
                if (relative.endsWith(".db")) {
                    switchSidecars(target, relative, progress);
                }
            }
            for (String relative : game.switchFiles) {
                Entry entry = target.entry(relative);
                byte[] current = readDevice(game.path(relative));
                if (current == null || !sha256(current).equals(entry.sha256)) {
                    throw new IOException("切换后校验失败: " + relative);
                }
            }
            PendingSwitch.commit(base);
        } catch (IOException failure) {
            String suffix;
            try {
                recoverPendingSwitch(game, progress);
                suffix = "；已回滚到切换前状态";
            } catch (IOException rollbackFailure) {
                suffix = "；回滚失败: " + rollbackFailure.getMessage();
            }
            throw new IOException(failure.getMessage() + suffix, failure);
        }
        progress.report("切换完成，所有文件校验一致");
        target.lastUsedAt = System.currentTimeMillis();
        try {
            Snapshot fresh = reload(target);
            fresh.lastUsedAt = target.lastUsedAt;
            saveManifest(fresh);
            adopt(target, fresh);
        } catch (IOException ignored) {
            // The switch already succeeded; a stale timestamp is cosmetic.
        }
        pruneNamed(game, "切换前自动备份", 3);
        if (launchAfterwards) {
            try {
                game.launch();
                progress.report("游戏启动成功");
            } catch (IOException launchFailure) {
                throw new IOException("账号切换已完成，但游戏启动失败；请手动重试: "
                        + launchFailure.getMessage(), launchFailure);
            }
        }
    }

    /** Keeps SQLite main file and its journal/WAL/SHM sidecars consistent with the target. */
    private void switchSidecars(Snapshot target, String dbRelative, Progress progress)
            throws IOException {
        GameProfile game = target.game;
        for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
            String relative = dbRelative + suffix;
            Entry entry = target.entry(relative);
            String path = game.path(relative);
            if (entry != null && entry.present) {
                byte[] data = readVerifiedSwitchPayload(target, entry);
                writeDevice(path, data);
                verifyWritten(path, entry);
                progress.report("已写入并校验: " + relative);
            } else if (exists(path)) {
                if (suffix.equals("-wal") || suffix.equals("-shm")) {
                    // Deleting uncheckpointed WAL data would lose the account's latest state.
                    throw new IOException("当前数据库存在未合并的 WAL，请先启动并正常退出游戏后再切换");
                }
                RootShell.Result removed = RootShell.run("rm -f " + quote(path));
                if (removed.code != 0 || exists(path)) {
                    throw new IOException("无法移除旧边车文件: " + relative);
                }
                progress.report("已移除旧边车: " + relative);
            }
        }
    }

    private boolean exists(String path) throws IOException {
        RootShell.Result result = RootShell.run("test -e " + quote(path));
        if (result.code != 0 && result.code != 1) {
            throw new IOException("检查文件是否存在失败: " + path + " (" + result.code + ")");
        }
        return result.code == 0;
    }

    /** Restores owner, mode and SELinux context when the bytes already match. */
    private void ensureMetadata(String path, Entry entry) throws IOException {
        Entry now = stat(path);
        if (now == null) {
            throw new IOException("无法读取文件属性: " + entry.path);
        }
        if (!entry.uid.equals(now.uid) || !entry.gid.equals(now.gid)) {
            runChecked("chown " + entry.uid + ":" + entry.gid + " " + quote(path));
        }
        if (!entry.mode.equals(now.mode)) {
            runChecked("chmod " + entry.mode + " " + quote(path));
        }
        if (!entry.context.isEmpty() && !entry.context.equals(now.context)) {
            RootShell.Result result = RootShell.run(
                    "chcon " + quote(entry.context) + " " + quote(path));
            if (result.code != 0) {
                throw new IOException("无法恢复 SELinux 上下文: " + entry.path + " " + result.err);
            }
        }
        Entry after = stat(path);
        if (after == null || !entry.uid.equals(after.uid) || !entry.gid.equals(after.gid)
                || !entry.mode.equals(after.mode)
                || (!entry.context.isEmpty() && !entry.context.equals(after.context))) {
            throw new IOException("无法恢复文件属性: " + entry.path);
        }
    }

    private static void runChecked(String command) throws IOException {
        RootShell.Result result = RootShell.run(command);
        if (result.code != 0) {
            throw new IOException("命令失败(" + result.code + "): " + command + " " + result.err);
        }
    }

    /** Restores every present file of {@code snapshot}; used by rollback. */
    void restoreAll(Snapshot snapshot, Progress progress) throws IOException {
        synchronized (LOCK) {
            requireNoPendingSwitch(snapshot.game);
            verifyRestoreSnapshot(snapshot);
            restoreAllLocked(snapshot, progress);
        }
    }

    private void restoreAllLocked(Snapshot snapshot, Progress progress) throws IOException {
        GameProfile game = snapshot.game;
        if (game.isRunning()) {
            throw new IOException("游戏正在运行，请先停止游戏再恢复");
        }
        for (Map.Entry<String, Entry> item : snapshot.entries.entrySet()) {
            Entry entry = item.getValue();
            String path = game.path(entry.path);
            if (!entry.present) {
                // Restore the captured state exactly: a file created after the snapshot must go.
                if (exists(path)) {
                    RootShell.Result removed = RootShell.run("rm -f " + quote(path));
                    if (removed.code != 0 || exists(path)) {
                        throw new IOException("无法移除多余文件: " + entry.path);
                    }
                    progress.report("已移除多余文件: " + entry.path);
                }
                continue;
            }
            byte[] current = readDevice(path);
            if (current != null && sha256(current).equals(entry.sha256)) {
                ensureMetadata(path, entry);
                continue;
            }
            byte[] data = readSnapshot(snapshot, entry.path);
            if (!sha256(data).equals(entry.sha256)) {
                throw new IOException("快照文件已损坏: " + entry.path);
            }
            writeDevice(path, data);
            verifyWritten(path, entry);
            progress.report("已恢复: " + entry.path);
        }
    }

    private void verifyWritten(String path, Entry entry) throws IOException {
        if (failAfterWrites > 0) {
            failAfterWrites--;
            if (failAfterWrites == 0) {
                failAfterWrites = -1;
                injectedFailures++;
                throw new IOException("自检：注入的校验失败");
            }
        }
        byte[] actual = readDevice(path);
        if (actual == null || !sha256(actual).equals(entry.sha256)) {
            throw new IOException("读回内容与快照不一致: " + entry.path);
        }
        ensureMetadata(path, entry);
    }

    private Entry stat(String path) throws IOException {
        RootShell.Result result = RootShell.run(
                "stat -c '%u %g %a %s %Y' " + quote(path) + " && ls -Z " + quote(path));
        if (result.code != 0) {
            return null;
        }
        String[] lines = result.text().split("\\r?\\n");
        if (lines.length == 0) {
            return null;
        }
        String[] fields = lines[0].trim().split("\\s+");
        if (fields.length != 5) {
            return null;
        }
        Entry entry = new Entry();
        entry.uid = fields[0];
        entry.gid = fields[1];
        entry.mode = fields[2];
        try {
            entry.size = Long.parseLong(fields[3]);
            entry.mtime = Long.parseLong(fields[4]);
        } catch (NumberFormatException error) {
            throw new IOException("文件属性格式无效: " + path, error);
        }
        for (int index = 1; index < lines.length; index++) {
            for (String token : lines[index].trim().split("\\s+")) {
                if (token.startsWith("u:") && token.chars().filter(ch -> ch == ':').count() >= 2) {
                    entry.context = token;
                    break;
                }
            }
            if (!entry.context.isEmpty()) {
                break;
            }
        }
        return entry;
    }

    private byte[] readDevice(String path) throws IOException {
        RootShell.Result result = RootShell.run(
                "if [ -e " + quote(path) + " ]; then cat " + quote(path) + "; else exit 3; fi");
        if (result.code == 3) {
            return null;
        }
        if (result.code != 0) {
            throw new IOException("读取失败(" + result.code + "): " + path);
        }
        return result.out;
    }

    /** Exposed for the on-device self test, which compares device bytes with a snapshot. */
    byte[] readDeviceForTest(String path) throws IOException {
        return readDevice(path);
    }

    private void writeDevice(String path, byte[] data) throws IOException {
        RootShell.Result result = RootShell.run("cat > " + quote(path), data);
        if (result.code != 0) {
            throw new IOException("写入失败: " + path + (result.err.isEmpty() ? "" : " -> " + result.err));
        }
        deviceWrites++;
    }

    private void saveManifest(Snapshot snapshot) throws IOException {
        saveManifest(snapshot, MANIFEST);
    }

    private void saveManifest(Snapshot snapshot, String fileName) throws IOException {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("id", snapshot.id);
            root.put("name", snapshot.name);
            root.put("createdAt", snapshot.createdAt);
            root.put("package", snapshot.game.pkg);
            root.put("encrypted", true);
            if (!snapshot.loginName.isEmpty()) {
                root.put("loginName", snapshot.loginName);
            }
            if (!snapshot.passwordEnc.isEmpty()) {
                root.put("passwordEnc", snapshot.passwordEnc);
            }
            if (snapshot.lastUsedAt > 0) {
                root.put("lastUsedAt", snapshot.lastUsedAt);
            }
            JSONArray files = new JSONArray();
            for (Entry entry : snapshot.entries.values()) {
                JSONObject item = new JSONObject();
                item.put("path", entry.path);
                item.put("present", entry.present);
                item.put("size", entry.size);
                item.put("sha256", entry.sha256);
                item.put("uid", entry.uid);
                item.put("gid", entry.gid);
                item.put("mode", entry.mode);
                item.put("context", entry.context);
                item.put("mtime", entry.mtime);
                files.put(item);
            }
            root.put("files", files);
        } catch (JSONException error) {
            throw new IOException("生成快照清单失败", error);
        }
        File manifest = new File(snapshot.dir, fileName);
        File temp = new File(snapshot.dir, fileName + ".tmp");
        writeLocal(temp, root.toString().getBytes(StandardCharsets.UTF_8));
        if (!temp.renameTo(manifest)) {
            throw new IOException("无法写入快照清单: " + manifest);
        }
    }

    private Snapshot readManifest(GameProfile game, File dir) throws IOException, JSONException {
        Snapshot snapshot = new Snapshot();
        snapshot.game = game;
        snapshot.dir = dir;
        snapshot.id = dir.getName();
        JSONObject root = new JSONObject(new String(readLocal(new File(dir, MANIFEST)),
                StandardCharsets.UTF_8));
        String pkg = root.optString("package", "");
        if (!pkg.isEmpty() && !pkg.equals(game.pkg)) {
            throw new IOException("快照属于 " + pkg + "，不属于 " + game.pkg);
        }
        snapshot.name = root.optString("name", snapshot.id);
        snapshot.createdAt = root.optLong("createdAt", dir.lastModified());
        snapshot.lastUsedAt = root.optLong("lastUsedAt", 0);
        snapshot.loginName = root.optString("loginName", "");
        snapshot.passwordEnc = root.optString("passwordEnc", "");
        JSONArray files = root.getJSONArray("files");
        for (int index = 0; index < files.length(); index++) {
            JSONObject item = files.getJSONObject(index);
            Entry entry = new Entry();
            entry.path = item.getString("path");
            entry.present = item.getBoolean("present");
            entry.size = item.optLong("size");
            entry.sha256 = item.optString("sha256");
            entry.uid = item.optString("uid");
            entry.gid = item.optString("gid");
            entry.mode = item.optString("mode");
            entry.context = item.optString("context");
            entry.mtime = item.optLong("mtime");
            snapshot.entries.put(entry.path, entry);
        }
        return snapshot;
    }

    interface Progress {
        void report(String message);
    }

    private static String preferenceValue(byte[] xml, String tag, String name) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && tag.equals(parser.getName())
                        && name.equals(parser.getAttributeValue(null, "name"))) {
                    if ("boolean".equals(tag)) {
                        return parser.getAttributeValue(null, "value");
                    }
                    return parser.nextText();
                }
            }
        } catch (XmlPullParserException error) {
            throw new IOException("解析偏好文件失败", error);
        }
        return null;
    }

    private static String accountField(String json, String field) {
        try {
            String value = new JSONObject(json).optString(field, "");
            return value == null ? "" : value;
        } catch (JSONException error) {
            return "";
        }
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static byte[] readLocal(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeLocal(File file, byte[] data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
            output.getFD().sync();
        }
    }

    static void deleteChecked(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("无法读取待删除目录，请重试: " + file);
            }
            File manifest = null;
            for (File child : children) {
                if (MANIFEST.equals(child.getName())) {
                    manifest = child;
                } else {
                    deleteChecked(child);
                }
            }
            // Keep the card discoverable if deleting one of its payloads fails.
            if (manifest != null) {
                deleteChecked(manifest);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("未能删除快照文件或目录，请重试: " + file);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
