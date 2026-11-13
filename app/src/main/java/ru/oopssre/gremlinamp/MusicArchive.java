package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class MusicArchive {
    private static final Object ACTIVE_LOCK = new Object();
    private static final Map<File, File> ACTIVE = new HashMap<>();
    private static final int MAX_ENTRIES = 250_000;
    private final Library library;
    private final File root;
    private File indexedFolder;
    private JSONObject cachedIndex;

    MusicArchive(Library library) {
        this.library = library;
        root = new File(library.root, "archives");
    }

    static final class Receipt {
        final String url, sha;
        final long bytes;

        Receipt(JSONObject json) throws Exception {
            JSONObject archive = json.getJSONObject("archive");
            url = Library.resolve(Library.BASE, archive.getString("url"));
            MusicContract.archivePath(url);
            sha = archive.getString("sha256");
            bytes = archive.getLong("bytes");
            if (!sha.matches("[0-9a-f]{64}") || bytes <= 0) throw new IOException("Неверная квитанция ZIP");
        }
    }

    static final class Integrity extends IOException {
        Integrity() { super("Размер или SHA-256 ZIP не совпадает с квитанцией"); }
    }

    static File validator(File zip) { return new File(zip.getPath() + ".etag"); }

    File active() {
        synchronized (ACTIVE_LOCK) {
            if (ACTIVE.containsKey(root)) return ACTIVE.get(root);
            File folder = null;
            try {
                String hash = new String(Files.readAllBytes(new File(root, "active").toPath()), StandardCharsets.US_ASCII);
                if (hash.matches("[0-9a-f]{64}")) {
                    File candidate = new File(root, hash);
                    if (new File(candidate, "game-index.json").isFile()) folder = candidate;
                }
            } catch (IOException ignored) { }
            ACTIVE.put(root, folder);
            return folder;
        }
    }

    JSONObject index() throws Exception {
        synchronized (ACTIVE_LOCK) {
            File folder = active();
            if (folder == null) return null;
            if (!folder.equals(indexedFolder)) {
                cachedIndex = Library.readObject(new File(folder, "game-index.json"));
                indexedFolder = folder;
            }
            return cachedIndex;
        }
    }

    private File file(File folder, String url) throws IOException {
        return new File(folder, MusicContract.archivePath(url));
    }

    boolean hasPlaylist(String url) throws Exception {
        synchronized (ACTIVE_LOCK) {
            File folder = active();
            return folder != null && file(folder, url).isFile();
        }
    }

    JSONObject playlist(String url) throws Exception {
        synchronized (ACTIVE_LOCK) {
            File folder = active();
            if (folder == null) return null;
            File f;
            try { f = file(folder, url); } catch (IOException e) { return null; }
            return f.isFile() ? Library.readObject(f).put("_url", url) : null;
        }
    }

    private File matchingAsset(JSONObject manifest, JSONObject asset) throws Exception {
        File folder = active();
        if (folder == null) return null;
        File source;
        try {
            source = file(folder, Library.resolve(manifest.getString("_url"), asset.getString("url")));
        } catch (IOException e) {
            return null;
        }
        return source.isFile() && source.length() == asset.getLong("bytes") ? source : null;
    }

    boolean contains(JSONObject manifest, JSONObject asset) throws Exception {
        synchronized (ACTIVE_LOCK) { return matchingAsset(manifest, asset) != null; }
    }

    // Keep the files returned to the player independent of an archive switch.
    File cacheAsset(JSONObject manifest, JSONObject asset, File target) throws Exception {
        target.getParentFile().mkdirs();
        File staged = File.createTempFile("archive-asset-", ".tmp", target.getParentFile());
        try {
            synchronized (ACTIVE_LOCK) {
                File source = matchingAsset(manifest, asset);
                if (source == null) return null;
                Files.delete(staged.toPath());
                try { Files.createLink(staged.toPath(), source.toPath()); }
                catch (IOException | UnsupportedOperationException e) { Files.copy(source.toPath(), staged.toPath()); }
            }
            if (!Library.hashFile(staged, library).equals(asset.getString("sha256"))) return null;
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    void update(Library.Progress progress) throws Exception {
        root.mkdirs();
        for (int attempt = 0; attempt < 2; attempt++) {
            library.checkCancelled();
            Receipt receipt = library.archiveReceipt();
            File active = active();
            if (active != null && active.getName().equals(receipt.sha)) {
                clean(receipt.sha);
                discardTransfers(null);
                library.preferences.edit().putBoolean("archive_update_pending", false).apply();
                library.downloadSummary = "Офлайн-библиотека уже актуальна";
                return;
            }
            if (!pending() && !library.preferences.edit().putBoolean("archive_update_pending", true).commit())
                throw new IOException("Не удалось сохранить состояние загрузки ZIP");
            library.checkCancelled();
            File zip = new File(root, "download-" + receipt.sha + ".part");
            discardTransfers(zip);
            // Staging directories from an interrupted process are never published.
            clean(active == null ? "" : active.getName());
            if (zip.length() > receipt.bytes) discardTransfer(zip);
            try {
                if (zip.length() < receipt.bytes) library.transferArchive(receipt, zip, progress);
                progress.update("Проверяю SHA-256 архива…");
                if (zip.length() != receipt.bytes || !Library.hashFile(zip, library).equals(receipt.sha)) throw new Integrity();
                progress.update("Распаковываю библиотеку…");
                install(zip, receipt.sha);
                library.preferences.edit().putBoolean("archive_update_pending", false).apply();
                library.downloadSummary = "Офлайн-библиотека обновлена";
                Library.revision.incrementAndGet();
                discardTransfer(zip);
                return;
            } catch (Integrity e) {
                discardTransfer(zip);
                if (attempt == 1) throw e;
                progress.update("Архив изменился. Повторяю проверку квитанции…");
            }
        }
    }

    private static void discardTransfer(File zip) throws IOException {
        Files.deleteIfExists(zip.toPath());
        Files.deleteIfExists(validator(zip).toPath());
    }

    private void discardTransfers(File keep) throws IOException {
        File[] files = root.listFiles(file -> file.isFile() && file.getName().startsWith("download-"));
        if (files == null) return;
        for (File file : files) {
            if (keep == null || !file.equals(keep) && !file.equals(validator(keep)))
                Files.deleteIfExists(file.toPath());
        }
    }

    boolean pending() { return library.preferences.getBoolean("archive_update_pending", false); }

    boolean pendingUnpaused() { return pending() && !library.preferences.getBoolean("archive_paused", false); }

    boolean hasDownload() { return active() != null || pending(); }

    void clear() throws IOException {
        synchronized (ACTIVE_LOCK) {
            Files.deleteIfExists(new File(root, "active").toPath());
            ACTIVE.put(root, null);
            indexedFolder = null;
            cachedIndex = null;
        }
        Library.revision.incrementAndGet();
        removeChecked(root);
        Files.createDirectories(root.toPath());
    }

    private void install(File zip, String sha) throws Exception {
        File staging = Files.createTempDirectory(root.toPath(), "extract-").toFile();
        try {
            try (ZipFile archive = new ZipFile(zip)) {
                if (archive.size() > MAX_ENTRIES) throw new IOException("Слишком много файлов в библиотеке");
                for (Enumeration<? extends ZipEntry> it = archive.entries(); it.hasMoreElements();) {
                    library.checkCancelled();
                    ZipEntry entry = it.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                    File target = new File(staging, MusicContract.safePath(name));
                    if (entry.isDirectory()) {
                        Files.createDirectories(target.toPath());
                        continue;
                    }
                    Files.createDirectories(target.getParentFile().toPath());
                    long remaining = entry.getSize();
                    if (remaining < 0 || remaining > staging.getUsableSpace()) throw new IOException("Недостаточно места для библиотеки");
                    try (InputStream input = archive.getInputStream(entry);
                         java.io.OutputStream output = Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            library.checkCancelled();
                            remaining -= n;
                            if (remaining < 0) throw new IOException("Неверный размер файла в ZIP");
                            output.write(buffer, 0, n);
                        }
                    }
                    if (remaining != 0) throw new IOException("Неполный файл в ZIP");
                }
            }
            JSONObject catalog = MusicContract.catalog(Library.readObject(new File(staging, "game-index.json")));
            JSONArray games = catalog.getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                library.checkCancelled();
                JSONObject game = games.getJSONObject(i);
                String url = game.getString("manifest");
                JSONObject playlist = Library.readObject(file(staging, url));
                JSONArray tracks = playlist.getJSONArray("tracks");
                if (playlist.getInt("schema_version") != 1 || !playlist.getString("id").equals(game.getString("id"))
                        || tracks.length() != game.getInt("track_count"))
                    throw new IOException("Плейлист в ZIP не соответствует каталогу");
                for (int t = 0; t < tracks.length(); t++) {
                    library.checkCancelled();
                    for (JSONObject asset : library.assets(tracks.getJSONObject(t))) {
                        File audio = file(staging, Library.resolve(url, asset.getString("url")));
                        if (!audio.isFile() || audio.length() != asset.getLong("bytes"))
                            throw new IOException("В ZIP отсутствует файл плейлиста");
                        if (!asset.getString("sha256").matches("[0-9a-f]{64}"))
                            throw new IOException("Неверная подпись файла в плейлисте");
                    }
                }
            }
            library.checkCancelled();
            synchronized (ACTIVE_LOCK) {
                library.checkCancelled();
                File folder = new File(root, sha);
                if (folder.exists()) remove(folder);
                Files.move(staging.toPath(), folder.toPath(), StandardCopyOption.ATOMIC_MOVE);
                Library.atomic(new File(root, "active"), sha.getBytes(StandardCharsets.US_ASCII));
                ACTIVE.put(root, folder);
            }
            clean(sha);
        } finally {
            remove(staging);
        }
    }

    private void clean(String keep) {
        File[] folders = root.listFiles(File::isDirectory);
        if (folders != null) for (File dir : folders)
            if (!dir.getName().equals(keep)) remove(dir);
    }

    static void removeChecked(File folder) throws IOException {
        remove(folder);
        if (folder.exists()) throw new IOException("Не удалось удалить все загруженные файлы");
    }

    static void remove(File folder) {
        File[] children = folder.listFiles();
        if (children != null) for (File child : children) remove(child);
        folder.delete();
    }
}
