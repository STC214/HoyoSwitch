package dev.hoyoswitch.app;

import android.content.Context;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Captures and restores the StarRail state files. Every write is preceded by a byte-exact
 * safety snapshot and verified by re-reading the device; a failed verification rolls back.
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
        String id;
        String name;
        long createdAt;
        File dir;
        final Map<String, Entry> entries = new LinkedHashMap<>();

        Entry entry(String relative) {
            return entries.get(relative);
        }
    }

    private static final String MANIFEST = "manifest.json";
    private static final int VERSION = 1;

    /** What the device currently looks like, identified without displaying account ids. */
    static final class Current {
        boolean loggedIn;
        String mid = "";
        String note = "";
    }

    private final File root;

    SnapshotStore(Context context) {
        root = new File(context.getFilesDir(), "snapshots");
    }

    File fileOf(Snapshot snapshot, String relative) {
        return new File(new File(snapshot.dir, "files"), relative);
    }

    /**
     * Identifies the account the client is currently on. Hashes cannot be used here: the client
     * rewrites token, SDK prefs and the database timestamp on every launch. The account id is
     * taken from the widget cache, which the account module refreshes after each login.
     */
    Current detectCurrent() throws IOException {
        Current current = new Current();
        byte[] prefs = readDevice(StarRailProfile.path("shared_prefs/porte_sdk_account_PRODUCT.xml"));
        if (prefs == null) {
            current.note = "读不到登录状态文件";
            return current;
        }
        if (!"true".equals(preferenceValue(prefs, "boolean", "login_status"))) {
            return current;
        }
        current.loggedIn = true;
        byte[] widget = readDevice(StarRailProfile.path("shared_prefs/combo_widget.xml"));
        String account = widget == null ? null : preferenceValue(widget, "string", "account");
        if (account != null) {
            current.mid = accountField(account, "mid");
        }
        if (current.mid.isEmpty()) {
            current.note = "已登录，但读不到账号标识";
        }
        return current;
    }

    /** Account id stored in a snapshot's widget cache; empty when unreadable. */
    String accountMid(Snapshot snapshot) {
        try {
            byte[] widget = readLocal(fileOf(snapshot, "shared_prefs/combo_widget.xml"));
            String account = preferenceValue(widget, "string", "account");
            return account == null ? "" : accountField(account, "mid");
        } catch (IOException error) {
            return "";
        }
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

    String capture(String name) throws IOException {
        if (StarRailProfile.isRunning()) {
            throw new IOException("游戏正在运行，请先停止游戏再拍摄快照");
        }
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(root, id);
        int suffix = 0;
        while (dir.exists()) {
            suffix++;
            dir = new File(root, id + "-" + suffix);
        }
        if (!dir.mkdirs()) {
            throw new IOException("无法创建快照目录: " + dir);
        }
        Snapshot snapshot = new Snapshot();
        snapshot.id = dir.getName();
        snapshot.name = name == null || name.trim().isEmpty() ? snapshot.id : name.trim();
        snapshot.createdAt = System.currentTimeMillis();
        snapshot.dir = dir;
        try {
            for (String relative : StarRailProfile.FILES) {
                Entry entry = captureOne(snapshot, relative);
                snapshot.entries.put(relative, entry);
            }
            saveManifest(snapshot);
        } catch (IOException error) {
            deleteRecursively(dir);
            throw error;
        }
        return snapshot.id;
    }

    private Entry captureOne(Snapshot snapshot, String relative) throws IOException {
        Entry entry = new Entry();
        entry.path = relative;
        String path = StarRailProfile.path(relative);
        RootShell.Result content = RootShell.run("cat " + quote(path));
        if (content.code != 0) {
            android.util.Log.w("hoyoswitch", "cat failed for " + relative + " code=" + content.code
                    + " err=" + content.err);
            entry.present = false;
            return entry;
        }
        entry.present = true;
        entry.size = content.out.length;
        entry.sha256 = sha256(content.out);
        Entry metadata = stat(path);
        if (metadata != null) {
            entry.uid = metadata.uid;
            entry.gid = metadata.gid;
            entry.mode = metadata.mode;
            entry.context = metadata.context;
            entry.mtime = metadata.mtime;
        }
        File local = new File(new File(snapshot.dir, "files"), relative);
        File parent = local.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录: " + parent);
        }
        writeLocal(local, content.out);
        if (!sha256(readLocal(local)).equals(entry.sha256)) {
            throw new IOException("本地副本校验失败: " + relative);
        }
        return entry;
    }

    List<Snapshot> list() {
        List<Snapshot> snapshots = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) {
            return snapshots;
        }
        for (File child : children) {
            File manifest = new File(child, MANIFEST);
            if (!child.isDirectory() || !manifest.isFile()) {
                continue;
            }
            try {
                Snapshot snapshot = readManifest(child);
                snapshots.add(snapshot);
            } catch (IOException | JSONException ignored) {
                // A damaged snapshot is skipped here; verify() reports it when opened.
            }
        }
        snapshots.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return snapshots;
    }

    Snapshot load(String id) throws IOException {
        File dir = new File(root, id);
        try {
            return readManifest(dir);
        } catch (JSONException error) {
            throw new IOException("快照清单损坏: " + id, error);
        }
    }

    /** Restores the four switch files of {@code target}, with safety snapshot and rollback. */
    void switchTo(Snapshot target, boolean launchAfterwards, Progress progress) throws IOException {
        if (StarRailProfile.isRunning()) {
            throw new IOException("游戏正在运行，请先停止游戏再切换");
        }
        String safetyId = capture("切换前自动备份");
        progress.report("已保存切换前状态: " + safetyId);
        try {
            for (String relative : StarRailProfile.SWITCH_FILES) {
                Entry entry = target.entry(relative);
                if (entry == null || !entry.present) {
                    continue;
                }
                File local = fileOf(target, relative);
                byte[] data = readLocal(local);
                if (!sha256(data).equals(entry.sha256)) {
                    throw new IOException("快照文件已损坏: " + relative);
                }
                String path = StarRailProfile.path(relative);
                byte[] current = readDevice(path);
                if (current != null && sha256(current).equals(entry.sha256)) {
                    progress.report("跳过（已一致）: " + relative);
                    continue;
                }
                writeDevice(path, data);
                verifyWritten(path, entry);
                progress.report("已写入并校验: " + relative);
            }
            for (String relative : StarRailProfile.SWITCH_FILES) {
                Entry entry = target.entry(relative);
                if (entry == null || !entry.present) {
                    continue;
                }
                byte[] current = readDevice(StarRailProfile.path(relative));
                if (current == null || !sha256(current).equals(entry.sha256)) {
                    throw new IOException("切换后校验失败: " + relative);
                }
            }
            progress.report("切换完成，所有文件校验一致");
            if (launchAfterwards) {
                StarRailProfile.launch();
                progress.report("已请求启动游戏");
            }
        } catch (IOException failure) {
            Snapshot safety = load(safetyId);
            try {
                restoreAll(safety, progress);
                throw new IOException(failure.getMessage() + "；已回滚到切换前状态", failure);
            } catch (IOException rollbackFailure) {
                throw new IOException(failure.getMessage() + "；回滚失败: " + rollbackFailure.getMessage(),
                        failure);
            }
        }
    }

    /** Restores every present file of {@code snapshot}; used by rollback and by explicit restore. */
    void restoreAll(Snapshot snapshot, Progress progress) throws IOException {
        if (StarRailProfile.isRunning()) {
            throw new IOException("游戏正在运行，请先停止游戏再恢复");
        }
        for (Map.Entry<String, Entry> item : snapshot.entries.entrySet()) {
            Entry entry = item.getValue();
            if (!entry.present) {
                continue;
            }
            String path = StarRailProfile.path(entry.path);
            byte[] current = readDevice(path);
            if (current != null && sha256(current).equals(entry.sha256)) {
                continue;
            }
            byte[] data = readLocal(fileOf(snapshot, entry.path));
            if (!sha256(data).equals(entry.sha256)) {
                throw new IOException("快照文件已损坏: " + entry.path);
            }
            writeDevice(path, data);
            verifyWritten(path, entry);
            progress.report("已恢复: " + entry.path);
        }
    }

    private void verifyWritten(String path, Entry entry) throws IOException {
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
        RootShell.Result result = RootShell.run("cat " + quote(path));
        return result.code == 0 ? result.out : null;
    }

    private void writeDevice(String path, byte[] data) throws IOException {
        RootShell.Result result = RootShell.run("cat > " + quote(path), data);
        if (result.code != 0) {
            throw new IOException("写入失败: " + path + (result.err.isEmpty() ? "" : " -> " + result.err));
        }
    }

    private void saveManifest(Snapshot snapshot) throws IOException {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("id", snapshot.id);
            root.put("name", snapshot.name);
            root.put("createdAt", snapshot.createdAt);
            root.put("package", StarRailProfile.PACKAGE);
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

    private Snapshot readManifest(File dir) throws IOException, JSONException {
        Snapshot snapshot = new Snapshot();
        snapshot.dir = dir;
        snapshot.id = dir.getName();
        JSONObject root = new JSONObject(new String(readLocal(new File(dir, MANIFEST)),
                StandardCharsets.UTF_8));
        snapshot.name = root.optString("name", snapshot.id);
        snapshot.createdAt = root.optLong("createdAt", dir.lastModified());
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
