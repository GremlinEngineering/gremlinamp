package ru.oopssre.gremlinamp;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class MusicApiChecks {
    private static int checks;

    private static void check(boolean condition, String title) {
        checks++;
        if (!condition) throw new AssertionError(title);
    }

    private static byte[] json(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }

    private static JSONObject game(String id, String platform, String playlist, long... ids) throws Exception {
        return new JSONObject().put("id", id).put("platform", platform).put("title", id).put("manifest", "music/" + playlist)
                .put("ra_ids", new JSONArray(ids));
    }

    private static JSONObject asset(String format, String path, String url, byte[] bytes) throws Exception {
        return new JSONObject().put("format", format).put("path", path).put("url", url)
                .put("bytes", bytes.length).put("sha256", Library.hash(bytes));
    }

    private static JSONObject playlist(JSONObject game, JSONObject... tracks) throws Exception {
        return new JSONObject().put("schema_version", 1).put("id", game.getString("id")).put("game", game)
                .put("tracks", new JSONArray(List.of(tracks)));
    }

    private static byte[] archive(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static class Transport extends Library {
        JSONObject remoteIndex;
        final Deque<byte[]> receipts = new ArrayDeque<>(), downloads = new ArrayDeque<>();
        int queries;
        File retainedDuringTransfer;
        Transport(File folder) { super(new Context(folder)); }
        @Override JSONObject onlineJson(String url) throws Exception {
            if (url.equals(MusicContract.INDEX) && remoteIndex != null) return remoteIndex;
            throw new IOException("HTTP 404");
        }
        @Override MusicArchive.Receipt archiveReceipt() throws Exception {
            queries++;
            byte[] bytes = receipts.remove();
            return new MusicArchive.Receipt(new JSONObject().put("archive", new JSONObject().put("url", "/music/library.zip")
                    .put("bytes", bytes.length).put("sha256", Library.hash(bytes))));
        }
        @Override void transferArchive(MusicArchive.Receipt receipt, File output, Progress progress) throws Exception {
            if (retainedDuringTransfer != null) {
                check(retainedDuringTransfer.isDirectory() && retainedDuringTransfer.equals(archive.active()),
                        "old archive remains active during ZIP transfer");
                retainedDuringTransfer = null;
            }
            Files.write(output.toPath(), downloads.remove());
        }
    }

    private static final class Response extends HttpURLConnection {
        final byte[] body;
        final int status;
        final long declared;
        final Map<String,String> headers;

        Response(byte[] body, int status, long declared, Map<String,String> headers) throws IOException {
            super(new URL(Library.BASE+"music/library.zip"));
            this.body = body;
            this.status = status;
            this.declared = declared;
            this.headers = headers;
        }

        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return declared; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
    }

    private static final class RangeTransport extends Library {
        byte[] contents;
        Response response;
        RangeTransport(Context context, byte[] contents) {
            super(context);
            this.contents = contents;
        }
        @Override MusicArchive.Receipt archiveReceipt() throws Exception {
            return new MusicArchive.Receipt(new JSONObject().put("archive", new JSONObject()
                    .put("url", "/music/library.zip").put("bytes", contents.length)
                    .put("sha256", Library.hash(contents))));
        }
        @Override HttpURLConnection openArchiveConnection(MusicArchive.Receipt receipt) { return response; }
        @Override void requireNetwork() {}
    }

    private static void checkResume(File root, byte[] old, byte[] updated) throws Exception {
        Context context = new Context(root);
        RangeTransport initial = new RangeTransport(context, old);
        initial.response = new Response(old, 200, old.length, Map.of("ETag", "\"old\""));
        initial.downloadArchive(s -> {});
        File installed = initial.archive.active();

        int cut = updated.length / 2;
        initial.contents = updated;
        initial.response = new Response(java.util.Arrays.copyOf(updated, cut), 200, updated.length, Map.of("ETag", "\"new\""));
        try {
            initial.downloadArchive(s -> {});
            throw new AssertionError("truncated response must remain pending");
        } catch (IOException expected) {
            check(initial.archive.pendingUnpaused(), "an interrupted ZIP is eligible for background retry");
            check(installed.equals(initial.archive.active()) && installed.isDirectory(), "old ZIP survives interruption");
        }
        initial.pausePending();
        check(initial.archive.pending() && !initial.archive.pendingUnpaused(), "explicit pause keeps ZIP retry data");

        RangeTransport restarted = new RangeTransport(context, updated);
        restarted.requestArchive();
        restarted.response = new Response(java.util.Arrays.copyOfRange(updated, cut, updated.length), 206,
                updated.length - cut, Map.of("Content-Range", "bytes "+cut+"-"+(updated.length-1)+"/"+updated.length));
        restarted.downloadArchive(s -> {});
        check(("bytes="+cut+"-").equals(restarted.response.getRequestProperty("Range")), "retry requests the saved byte offset");
        check("\"new\"".equals(restarted.response.getRequestProperty("If-Range")), "retry carries the saved validator");
        check(restarted.archive.active().getName().equals(Library.hash(updated)) && !installed.exists()
                && !restarted.archive.pending(), "verified replacement is activated, then old ZIP is removed");

        restarted.contents = old;
        restarted.response = new Response(java.util.Arrays.copyOf(old, cut), 200, old.length, Map.of("ETag", "\"old\""));
        try { restarted.downloadArchive(s -> {}); }
        catch (IOException expected) { check(restarted.archive.pending(), "old version remains pending after a second interruption"); }
        restarted.response = new Response(old, 200, old.length, Map.of("ETag", "\"old\""));
        restarted.downloadArchive(s -> {});
        check(restarted.archive.active().getName().equals(Library.hash(old)), "200 on resume replaces the partial prefix");

        restarted.contents = updated;
        restarted.response = new Response(java.util.Arrays.copyOf(updated, cut), 200, updated.length, Map.of("ETag", "\"new\""));
        try { restarted.downloadArchive(s -> {}); }
        catch (IOException expected) { check(restarted.archive.pending(), "new version remains pending after interruption"); }
        byte[] newer = archive(Map.of("game-index.json", json(ContractFixtures.index()), "GAMES.tsv", new byte[]{9}));
        restarted.contents = newer;
        restarted.response = new Response(newer, 200, newer.length, Map.of("ETag", "\"newer\""));
        restarted.downloadArchive(s -> {});
        check(restarted.response.getRequestProperty("Range") == null, "changed SHA starts a fresh ZIP on the same URL");
        check(restarted.archive.active().getName().equals(Library.hash(newer)), "new ZIP verified and activated");
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("gremlin-music-v1").toFile();
        try {
            Transport library = new Transport(root);
            Library.atomic(library.jsonFile(Library.BASE + "assets/gallery-music.json"), json(new JSONObject().put("games", new JSONArray())));
            boolean rejected = false;
            try { library.catalog(false); } catch (IOException e) { rejected = e.getMessage().contains("недоступен"); }
            check(rejected, "404 on first launch does not load the old catalogue");

            JSONObject one = game("package-alpha", "arcade", "package-alpha/playlist-11.json", 12574);
            JSONObject other = game("package-beta", "nes", "package-beta/playlist-22.json", 12574);
            JSONObject weekly = game("game-2026-10-W41", "arcade", "game-2026-10-W41/playlist-33.json");
            library.remoteIndex = ContractFixtures.group(ContractFixtures.index(one, other, weekly),
                    "ordered", weekly.getString("id"), one.getString("id"), other.getString("id"));
            JSONObject catalogue = library.catalog(true);
            JSONArray rows = catalogue.getJSONArray("games");
            List<JSONObject> games = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) games.add(rows.getJSONObject(i));
            check(catalogue.getJSONObject("by_ra_id").getJSONArray(MusicContract.raQuery("RA:12574")).length() == 2,
                    "RA ID retains multiple versions for platform selection");
            List<JSONObject> ordered = library.seriesGames(catalogue.getJSONArray("series").getJSONObject(0), games);
            check(ordered.get(0).getString("id").equals("game-2026-10-W41"), "series uses builder order, including non-RA ID");
            check(ordered.get(1).getString("manifest").equals(MusicContract.INDEX.replace("game-index.json","package-alpha/playlist-11.json")),
                    "playlist resolves from the index");
            library.remoteIndex = null;
            check(library.catalog(true).getJSONArray("games").length() == 3, "404 uses checked v1 cache");

            byte[] chip = {1,2,3}, libraryBytes = {4,5,6};
            String entry = "package-alpha/main % tone.minigsf";
            JSONObject dep = asset("gsflib","sound/Library.gsflib","sound/Library.gsflib?v=5",libraryBytes);
            JSONObject primary = asset("minigsf","main % tone.minigsf","main%20%25%20tone.minigsf?v=2",chip)
                    .put("dependencies", new JSONArray().put(dep));
            JSONObject track = new JSONObject().put("backend","native").put("id","chosen").put("asset",primary);
            JSONObject manifest = playlist(one,track);
            byte[] old = archive(Map.of("game-index.json",json(ContractFixtures.index(one)),
                    "package-alpha/playlist-11.json",json(manifest),entry,chip,
                    "package-alpha/sound/Library.gsflib",libraryBytes));
            library.receipts.add(old);library.downloads.add(old);
            library.downloadArchive(s -> {});
            File original = library.archive.active();
            check(original != null && original.getName().equals(Library.hash(old)), "verified archive activated");
            JSONObject local = library.localManifest(one);
            check(library.playableLocal(local,track),"main file and dependency available offline");
            List<File> input = new ArrayList<>();
            JSONObject descriptor = library.decoderInput(local,track,input);
            check(descriptor.getJSONArray("paths").getString(1).equals("sound/Library.gsflib")
                    && Files.readAllBytes(input.get(1).toPath())[0] == 4,"decoder receives dependencies under virtual names");
            check(MusicContract.archivePath(Library.resolve(local.getString("_url"), primary.getString("url"))).equals(entry),
                    "offline URI decodes the name and ignores v");
            check(MusicContract.trackIndex(local.getJSONArray("tracks"),"chosen") == 0,"stable selected track ID");
            check(MusicContract.decoder(track).equals("gsf"),"MiniGSF routes to the native decoder");
            check(MusicContract.decoder(new JSONObject().put("backend","native").put("asset",new JSONObject().put("format","tmc"))).equals("vgm"),
                    "TMC routes to native libvgm");

            JSONObject registers = asset("snes-registers","theme.snes-registers","theme.snes-registers",chip);
            check(library.assetFile(registers).getName().endsWith(".snes-registers"),
                    "SNES register dumps use the offline asset cache");

            library.receipts.add(old);
            library.downloadArchive(s -> {});
            check(original.equals(library.archive.active()), "unchanged receipt leaves the current copy in place");
            library.retainedDuringTransfer = original;
            byte[] invalid = archive(Map.of("../escape",new byte[]{0},"game-index.json",json(ContractFixtures.index(one))));
            library.receipts.add(invalid);library.downloads.add(invalid);
            rejected = false;
            try { library.downloadArchive(s -> {}); } catch (IOException e) { rejected = true; }
            check(rejected && original.equals(library.archive.active()) && library.archive.pending(),
                    "unsafe replacement preserves the offline library and a retryable update");

            byte[] second = archive(Map.of("game-index.json",json(ContractFixtures.index(one)),
                    "package-alpha/playlist-11.json",json(manifest),entry,chip,
                    "package-alpha/sound/Library.gsflib",libraryBytes,"GAMES.tsv",new byte[]{7}));
            int before = library.queries;
            library.downloads.add(new byte[]{7});
            byte[] unavailable = archive(Map.of("unpublished",new byte[]{7}));
            library.receipts.add(unavailable); library.receipts.add(second); library.downloads.add(second);
            library.downloadArchive(s -> {});
            check(library.queries == before + 2 && library.archive.active().getName().equals(Library.hash(second)),
                    "archive mismatch re-fetches the receipt and succeeds");
            check(!original.exists() && input.get(0).isFile(),"individual decoder cache survives ZIP replacement");
            checkResume(new File(root,"range"),old,second);

            byte[] wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short)1).putShort((short)1).putInt(44100).putInt(88200)
                    .putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4)
                    .putShort((short)500).putShort((short)-500).array();
            JSONObject render = asset("wav","render.wav","render.wav?v=3",wav);
            JSONObject psf = new JSONObject().put("backend","native").put("asset",new JSONObject().put("format","psf2")).put("browser_render",render);
            check(MusicContract.decoder(psf).equals("fallback") && library.playbackAsset(psf).getString("url").equals(render.getString("url")),
                    "PSF2 keeps original identity while selecting its WAV");
            File audio = new File(root,"render.wav");
            Files.write(audio.toPath(),wav);
            try (WavPcm decoder = new WavPcm(audio)) {
                short[] pcm = new short[4];
                decoder.render(pcm);
                check(pcm[0] == 500 && pcm[1] == 500 && pcm[2] == -500 && decoder.ended(),"WAV PCM delivers stereo frames");
            }
            check(MusicContract.decoder(new JSONObject().put("backend","native").put("asset",new JSONObject().put("format","xyz"))).isEmpty(),
                    "unknown format is visible as unsupported");
            File legacy = new File(library.root,"files/legacy.nsf");
            File poster = new File(library.root,"art/cover.img");
            File onlineIndex = library.jsonFile(MusicContract.INDEX);
            Library.atomic(legacy,new byte[]{8});
            Library.atomic(poster,new byte[]{9});
            one.put("_music_contract",1);
            library.markComplete(one,local);
            library.queueDownloads(List.of(one));
            library.trackSeries("ordered");
            library.preferences.edit().putBoolean("follow_all_music",true).putBoolean("auto_music",true).apply();
            check(library.hasDownloadedMusic() && library.pending(one) && library.subscribed(one),
                    "clear action sees both downloaded and pending music");
            library.clearDownloadedMusic();
            check(!legacy.exists() && !poster.exists() && !input.get(0).exists() && !input.get(1).exists(),
                    "clear action removes legacy audio, downloaded assets, dependencies and artwork");
            check(library.archive.active()==null && !library.archive.hasDownload() && !library.hasDownloadedMusic(),
                    "clear action removes full library and its retry state");
            check(!library.pending(one) && !library.subscribed(one) && library.savedGames().isEmpty(),
                    "clear action removes individual offline states and queued requests");
            check(!library.preferences.getBoolean("follow_all_music",true)
                    && library.preferences.getStringSet("saved_series",java.util.Collections.emptySet()).isEmpty(),
                    "cleared downloads will not return automatically");
            check(onlineIndex.isFile() && library.preferences.getBoolean("auto_music",false),
                    "catalog cache and automatic-update setting survive deletion");
            library.clearDownloadedMusic();
            System.out.println(checks + " music API v1 checks passed");
        } finally {
            MusicArchive.remove(root);
        }
    }
}
