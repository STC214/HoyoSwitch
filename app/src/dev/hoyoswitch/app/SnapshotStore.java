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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String MANIFEST = "manifest.json";
    private static final String HISTORY = ".history";
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
        if (!game.supported) {
            throw new IOException(game.label + " 尚未适配，不能拍摄快照");
        }
        File base = dirFor(game);
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
        // The account database and its journal/WAL/SHM are read together so a checkpoint that
        // lands between reads cannot leave the main file and sidecars inconsistent.
        List<String> dbGroup = new ArrayList<>();
        if (game.accountDb != null && game.files.contains(game.accountDb)) {
            dbGroup.add(game.accountDb);
            for (String sideSuffix : new String[]{"-journal", "-wal", "-shm"}) {
                String rel = game.accountDb + sideSuffix;
                if (game.files.contains(rel)) {
                    dbGroup.add(rel);
                }
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Entry>> futures = new ArrayList<>();
        List<String> singles = new ArrayList<>();
        boolean complete = false;
        try {
            for (String relative : game.files) {
                if (dbGroup.contains(relative)) {
                    continue;
                }
                boolean stable = live && game.switchFiles.contains(relative);
                singles.add(relative);
                futures.add(pool.submit(() -> captureOne(game, snapshot, relative, stable)));
            }
            for (int index = 0; index < singles.size(); index++) {
                snapshot.entries.put(singles.get(index), futures.get(index).get());
            }
            if (!dbGroup.isEmpty()) {
                Map<String, byte[]> group = readDbGroup(game, dbGroup, live);
                for (String relative : dbGroup) {
                    byte[] bytes = group.get(relative);
                    if (bytes == null || bytes.length == 0) {
                        Entry missing = new Entry();
                        missing.path = relative;
                        missing.present = false;
                        snapshot.entries.put(relative, missing);
                    } else {
                        snapshot.entries.put(relative, storeEntry(snapshot, relative, bytes));
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
        return storeEntry(snapshot, relative, bytes);
    }

    /** Encrypts {@code bytes} into the snapshot dir and fills the entry metadata + hash. */
    private Entry storeEntry(Snapshot snapshot, String relative, byte[] bytes) throws IOException {
        Entry entry = new Entry();
        entry.path = relative;
        entry.present = true;
        entry.size = bytes.length;
        entry.sha256 = sha256(bytes);
        Entry metadata = stat(snapshot.game.path(relative));
        if (metadata != null) {
            entry.uid = metadata.uid;
            entry.gid = metadata.gid;
            entry.mode = metadata.mode;
            entry.context = metadata.context;
            entry.mtime = metadata.mtime;
        }
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
        StringBuilder script = new StringBuilder();
        for (String relative : relatives) {
            String path = game.path(relative);
            script.append("printf '\\n--HSN1DB--").append(relative).append("\\n'; ");
            script.append("if [ -e ").append(quote(path)).append(" ]; then cat ")
                    .append(quote(path)).append("; fi; ");
        }
        RootShell.Result result = RootShell.run(script.toString());
        if (result.code != 0) {
            throw new IOException("读取账号数据库失败(" + result.code + "): " + result.err);
        }
        return new String(result.out, StandardCharsets.ISO_8859_1);
    }

    private Map<String, byte[]> splitDbGroup(String data, List<String> relatives) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int index = 0; index < relatives.size(); index++) {
            String relative = relatives.get(index);
            String marker = "\n--HSN1DB--" + relative + "\n";
            int start = data.indexOf(marker);
            if (start < 0) {
                out.put(relative, new byte[0]);
                continue;
            }
            start += marker.length();
            int end = data.length();
            for (int other = index + 1; other < relatives.size(); other++) {
                int candidate = data.indexOf("\n--HSN1DB--" + relatives.get(other) + "\n", start);
                if (candidate >= 0 && candidate < end) {
                    end = candidate;
                }
            }
            out.put(relative, data.substring(start, end).getBytes(StandardCharsets.ISO_8859_1));
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

    private List<Snapshot> listLocked(GameProfile game) {
        List<Snapshot> snapshots = new ArrayList<>();
        File[] children = dirFor(game).listFiles();
        if (children == null) {
            return snapshots;
        }
        for (File child : children) {
            if (!child.isDirectory() || HISTORY.equals(child.getName())
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
        try {
            return readManifest(game, new File(dirFor(game), id));
        } catch (JSONException error) {
            throw new IOException("快照清单损坏: " + id, error);
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
                String mid = dbCurrentMid(db, readDevice(dbPath + "-journal"),
                        readDevice(dbPath + "-wal"), readDevice(dbPath + "-shm"));
                if (mid != null) {
                    // SQLite answered: either a concrete mid, or a genuinely empty table. An empty
                    // table means logged out even when stale token keys remain in the prefs.
                    if (!mid.isEmpty()) {
                        current.loggedIn = true;
                        current.mid = mid;
                    }
                    return current;
                }
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
                String mid = dbCurrentMid(readSnapshot(snapshot, game.accountDb),
                        snapshotBytes(snapshot, game.accountDb + "-journal"),
                        snapshotBytes(snapshot, game.accountDb + "-wal"),
                        snapshotBytes(snapshot, game.accountDb + "-shm"));
                if (mid != null) {
                    // "" means the table is empty: no account, do not trust stale token keys.
                    return mid;
                }
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
     * Reads the newest row with a real SQLite query so deleted rows in freelist pages cannot be
     * mistaken for the current account. Falls back to a byte scan only if SQLite cannot open it.
     */
    private String dbCurrentMid(byte[] db) {
        return dbCurrentMid(db, null, null, null);
    }

    private String dbCurrentMid(byte[] db, byte[] journal, byte[] wal, byte[] shm) {
        File dir = new File(context.getCacheDir(), "dbscan-" + System.nanoTime());
        boolean queried = false;
        try {
            if (!dir.mkdirs()) {
                return null;
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
                        return mid;
                    }
                }
                // Query succeeded with zero rows: the table is genuinely empty. Never fall back to
                // a byte scan here, or deleted rows in freelist pages could be mistaken for a user.
                return "";
            } finally {
                database.close();
            }
        } catch (Exception error) {
            Log.w("hoyoswitch", "sqlite account scan failed: " + error);
        } finally {
            deleteRecursively(dir);
        }
        // queried == true means the table was genuinely empty (returned above); reaching here
        // means SQLite could not read the file, which the caller treats as "undetermined".
        return queried ? "" : null;
    }

    private static void writeIfPresent(File file, byte[] data) throws IOException {
        if (data != null) {
            Files.write(file.toPath(), data);
        }
    }

    private static String regexMid(byte[] db) {
        String text = new String(db, StandardCharsets.ISO_8859_1);
        Matcher matcher = ACCOUNT_ROW.matcher(text);
        String bestMid = "";
        long bestTimestamp = -1;
        while (matcher.find()) {
            try {
                long timestamp = Long.parseLong(matcher.group(3));
                if (timestamp > bestTimestamp) {
                    bestTimestamp = timestamp;
                    bestMid = matcher.group(1);
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed rows.
            }
        }
        return bestMid;
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
        return name.trim();
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
        if (expectedMid != null) {
            String before = currentMidOrEmpty(game);
            if (before != null && !before.equals(expectedMid)) {
                throw new IOException("当前账号已变化，未更新该快照");
            }
        }
        String tempId = captureLocked(game, target.name);
        if (expectedMid != null) {
            String after = currentMidOrEmpty(game);
            if (after != null && !after.equals(expectedMid)) {
                deleteRecursively(new File(dirFor(game), tempId));
                throw new IOException("拍摄期间账号发生变化，已取消本次更新");
            }
        }
        File temp = new File(dirFor(game), tempId);
        File archived = archive(target.dir);
        if (archived == null) {
            // Never delete the current snapshot unless a history copy really exists.
            deleteRecursively(temp);
            throw new IOException("无法归档旧快照，已中止更新: " + target.id);
        }
        deleteRecursively(target.dir);
        if (!temp.renameTo(target.dir)) {
            //noinspection ResultOfMethodCallIgnored
            archived.renameTo(target.dir);
            deleteRecursively(temp);
            throw new IOException("无法替换旧快照目录: " + target.id);
        }
        try {
            Snapshot refreshed = readManifest(target.game, target.dir);
            refreshed.id = target.id;
            refreshed.name = target.name;
            refreshed.lastUsedAt = target.lastUsedAt;
            refreshed.loginName = target.loginName;
            refreshed.passwordEnc = target.passwordEnc;
            saveManifest(refreshed);
            // Keep the caller's object in sync; a later rename() must write the new hashes.
            target.entries.clear();
            target.entries.putAll(refreshed.entries);
            target.createdAt = refreshed.createdAt;
            target.lastUsedAt = refreshed.lastUsedAt;
        } catch (JSONException error) {
            throw new IOException("更新后的快照清单损坏", error);
        }
        return target.id;
    }

    private File archive(File dir) {
        return archive(dir, null);
    }

    /** Moves {@code dir} into the history folder, never pruning {@code keep}. */
    private File archive(File dir, File keep) {
        File history = new File(dir.getParentFile(), HISTORY);
        if (!history.isDirectory() && !history.mkdirs()) {
            return null;
        }
        File destination = new File(history, dir.getName() + "-" + System.currentTimeMillis());
        if (!dir.renameTo(destination)) {
            return null;
        }
        // Keep three versions per snapshot; the folder is shared by every account.
        pruneHistory(history, dir.getName(), keep);
        return destination;
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
                delete(snapshot);
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
        File[] children = base.listFiles();
        long cutoff = System.currentTimeMillis() - 3 * 60 * 1000L;
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory() || HISTORY.equals(child.getName())) {
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
            if (moveToHistory(historyDir, primary, snapshot)) {
                migrateHistory(historyDir, primary, snapshot);
                delete(snapshot);
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

    private boolean moveToHistory(File historyDir, Snapshot primary, Snapshot duplicate) {
        if (!historyDir.isDirectory() && !historyDir.mkdirs()) {
            return false;
        }
        File destination = new File(historyDir, primary.id + "-" + duplicate.createdAt);
        if (destination.exists()) {
            destination = new File(historyDir, primary.id + "-" + System.currentTimeMillis());
        }
        if (!duplicate.dir.renameTo(destination)) {
            return false;
        }
        pruneHistory(historyDir, primary.id, null);
        return true;
    }

    /** Moves the merged card's own history under the primary id so it is not deleted with it. */
    private void migrateHistory(File historyDir, Snapshot primary, Snapshot duplicate) {
        File[] versions = historyDir.listFiles();
        if (versions == null) {
            return;
        }
        for (File version : versions) {
            if (!isHistoryOf(version.getName(), duplicate.id)) {
                continue;
            }
            String suffix = version.getName().substring(duplicate.id.length() + 1);
            File target = new File(historyDir, primary.id + "-" + suffix);
            if (target.exists()) {
                target = new File(historyDir,
                        primary.id + "-" + System.currentTimeMillis() + "-" + suffix);
            }
            //noinspection ResultOfMethodCallIgnored
            version.renameTo(target);
        }
    }

    /** True when {@code name} is exactly {@code id + "-" + digits}; avoids id-prefix collisions. */
    private static boolean isHistoryOf(String name, String id) {
        if (!name.startsWith(id + "-")) {
            return false;
        }
        String suffix = name.substring(id.length() + 1);
        if (suffix.isEmpty()) {
            return false;
        }
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Keeps the newest versions of one snapshot, always sparing {@code keep}. */
    private void pruneHistory(File historyDir, String id, File keep) {
        File[] versions = historyDir.listFiles();
        if (versions == null) {
            return;
        }
        List<File> mine = new ArrayList<>();
        boolean keepPresent = false;
        for (File version : versions) {
            if (!isHistoryOf(version.getName(), id)) {
                continue;
            }
            if (version.equals(keep)) {
                keepPresent = true;
            } else {
                mine.add(version);
            }
        }
        int allowed = keepPresent ? HISTORY_KEEP - 1 : HISTORY_KEEP;
        if (mine.size() <= allowed) {
            return;
        }
        mine.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
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
        File[] versions = history.listFiles();
        if (versions == null) {
            return entries;
        }
        String prefix = snapshot.id + "-";
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        for (File version : versions) {
            if (!version.isDirectory() || !isHistoryOf(version.getName(), snapshot.id)) {
                continue;
            }
            HistoryEntry entry = new HistoryEntry();
            entry.dir = version;
            String suffix = version.getName().substring(prefix.length());
            try {
                entry.time = Long.parseLong(suffix);
            } catch (NumberFormatException error) {
                entry.time = version.lastModified();
            }
            entry.label = format.format(new Date(entry.time));
            entries.add(entry);
        }
        entries.sort((left, right) -> Long.compare(right.time, left.time));
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

    void delete(Snapshot snapshot) {
        synchronized (LOCK) {
            deleteLocked(snapshot);
        }
    }

    private void deleteLocked(Snapshot snapshot) {
        deleteRecursively(snapshot.dir);
        File history = new File(snapshot.dir.getParentFile(), HISTORY);
        File[] versions = history.listFiles();
        if (versions == null) {
            return;
        }
        String prefix = snapshot.id + "-";
        for (File version : versions) {
            if (isHistoryOf(version.getName(), snapshot.id)) {
                deleteRecursively(version);
            }
        }
    }

    /** Rolls a snapshot back to one archived version; the current version is archived first. */
    void restoreHistory(Snapshot snapshot, HistoryEntry entry) throws IOException {
        synchronized (LOCK) {
            restoreHistoryLocked(snapshot, entry);
        }
    }

    private void restoreHistoryLocked(Snapshot snapshot, HistoryEntry entry) throws IOException {
        String currentName = snapshot.name;
        Snapshot candidate;
        try {
            // Validate the archived manifest before the current version is touched.
            candidate = readManifest(snapshot.game, entry.dir);
        } catch (JSONException error) {
            throw new IOException("历史版本清单损坏，未回滚", error);
        }
        File archived = archive(snapshot.dir, entry.dir);
        if (archived == null) {
            throw new IOException("无法归档当前版本，已中止回滚");
        }
        if (!entry.dir.renameTo(snapshot.dir)) {
            //noinspection ResultOfMethodCallIgnored
            archived.renameTo(snapshot.dir);
            throw new IOException("无法回滚到该历史版本");
        }
        try {
            candidate.id = snapshot.id;
            candidate.name = currentName;
            candidate.lastUsedAt = snapshot.lastUsedAt;
            candidate.loginName = snapshot.loginName;
            candidate.passwordEnc = snapshot.passwordEnc;
            candidate.dir = snapshot.dir;
            saveManifest(candidate);
            adopt(snapshot, candidate);
        } catch (IOException | RuntimeException error) {
            // Undo the directory swap so a failed save cannot leave a broken current card.
            //noinspection ResultOfMethodCallIgnored
            snapshot.dir.renameTo(entry.dir);
            //noinspection ResultOfMethodCallIgnored
            archived.renameTo(snapshot.dir);
            throw new IOException("回滚失败，已恢复原版本", error);
        }
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
            switchToLocked(target, launchAfterwards, progress);
        }
    }

    private void switchToLocked(Snapshot target, boolean launchAfterwards, Progress progress)
            throws IOException {
        GameProfile game = target.game;
        if (game.isRunning()) {
            throw new IOException("游戏正在运行，请先停止游戏再切换");
        }
        String safetyId = snapshotCurrentAccount(game, progress);
        if (safetyId.equals(target.id)) {
            progress.report("当前已经是这个账号，无需切换");
            if (launchAfterwards) {
                game.launch();
            }
            return;
        }
        try {
            // A partial snapshot must never be applied: all switch files have to be present.
            for (String relative : game.switchFiles) {
                Entry entry = target.entry(relative);
                if (entry == null || !entry.present) {
                    throw new IOException("目标快照缺少必要文件: " + relative);
                }
            }
            for (String relative : game.switchFiles) {
                Entry entry = target.entry(relative);
                byte[] data = readSnapshot(target, relative);
                if (!sha256(data).equals(entry.sha256)) {
                    throw new IOException("快照文件已损坏: " + relative);
                }
                String path = game.path(relative);
                byte[] current = readDevice(path);
                if (current != null && sha256(current).equals(entry.sha256)) {
                    ensureMetadata(path, entry);
                    progress.report("跳过（已一致）: " + relative);
                } else {
                    writeDevice(path, data);
                    verifyWritten(path, entry);
                    ensureMetadata(path, entry);
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
                game.launch();
                progress.report("已请求启动游戏");
            }
        } catch (IOException failure) {
            Snapshot safety = load(game, safetyId);
            String suffix;
            try {
                restoreAll(safety, progress);
                suffix = "；已回滚到切换前状态";
            } catch (IOException rollbackFailure) {
                suffix = "；回滚失败: " + rollbackFailure.getMessage();
            }
            throw new IOException(failure.getMessage() + suffix, failure);
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
                byte[] data = readSnapshot(target, relative);
                if (!sha256(data).equals(entry.sha256)) {
                    throw new IOException("快照文件已损坏: " + relative);
                }
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
        return RootShell.run("test -e " + quote(path)).code == 0;
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
            ensureMetadata(path, entry);
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
        Entry info = stat(path);
        if (info == null) {
            throw new IOException("无法读取文件属性: " + entry.path);
        }
        boolean sameOwner = info.uid.equals(entry.uid) && info.gid.equals(entry.gid)
                && info.mode.equals(entry.mode);
        if (!sameOwner) {
            RootShell.checked("chown " + entry.uid + ":" + entry.gid + " " + quote(path)
                    + "; chmod " + entry.mode + " " + quote(path));
        }
        if (!entry.context.isEmpty() && !info.context.equals(entry.context)) {
            RootShell.run("chcon " + quote(entry.context) + " " + quote(path));
        }
        Entry after = stat(path);
        if (after == null || !after.uid.equals(entry.uid) || !after.gid.equals(entry.gid)
                || !after.mode.equals(entry.mode)) {
            throw new IOException("属主或权限无法恢复: " + entry.path);
        }
        if (!entry.context.isEmpty() && !after.context.isEmpty()
                && !after.context.equals(entry.context)) {
            throw new IOException("SELinux 上下文无法恢复: " + entry.path);
        }
    }

    private Entry stat(String path) throws IOException {
        RootShell.Result result = RootShell.run(
                "stat -c '%u %g %a %s %Y' " + quote(path) + "; ls -Z " + quote(path));
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
        entry.size = Long.parseLong(fields[3]);
        entry.mtime = Long.parseLong(fields[4]);
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
        File manifest = new File(snapshot.dir, MANIFEST);
        File temp = new File(snapshot.dir, MANIFEST + ".tmp");
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
