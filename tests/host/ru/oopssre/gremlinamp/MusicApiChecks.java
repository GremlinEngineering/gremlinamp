package ru.oopssre.gremlinamp;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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
            Files.write(output.toPath(), downloads.remove());
        }
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
                    && Files.readAllBytes(input.get(1).toPath())[0] == 4,"worker receives dependencies under virtual names");
            check(MusicContract.archivePath(Library.resolve(local.getString("_url"), primary.getString("url"))).equals(entry),
                    "offline URI decodes the name and ignores v");
            check(MusicContract.trackIndex(local.getJSONArray("tracks"),"chosen") == 0,"stable selected track ID");
            check(MusicContract.decoder(track).equals("site"),"MiniGSF routes to the supplied decoder");
            check(MusicContract.decoder(new JSONObject().put("backend","native").put("asset",new JSONObject().put("format","tmc"))).equals("site"),
                    "custom board format routes to supplied decoder");

            byte[] invalid = archive(Map.of("../escape",new byte[]{0},"game-index.json",json(ContractFixtures.index(one))));
            library.receipts.add(invalid);library.downloads.add(invalid);
            rejected = false;
            try { library.downloadArchive(s -> {}); } catch (IOException e) { rejected = true; }
            check(rejected && original.equals(library.archive.active()),"unsafe ZIP leaves old active library untouched");

            byte[] second = archive(Map.of("game-index.json",json(ContractFixtures.index(one)),
                    "package-alpha/playlist-11.json",json(manifest),entry,chip,
                    "package-alpha/sound/Library.gsflib",libraryBytes,"GAMES.tsv",new byte[]{7}));
            int before = library.queries;
            library.downloads.add(second);
            byte[] unavailable = archive(Map.of("unpublished",new byte[]{7}));
            library.receipts.add(unavailable); library.receipts.add(second); library.downloads.add(second);
            library.downloadArchive(s -> {});
            check(library.queries == before + 2 && library.archive.active().getName().equals(Library.hash(second)),
                    "archive mismatch re-fetches the receipt and succeeds");
            check(!original.exists() && input.get(0).isFile(),"old ZIP deleted after switch, existing decoder file survives");

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
            Library.atomic(legacy,new byte[]{8});
            library.clearLegacyMusic();
            check(!legacy.exists() && library.archive.active()!=null && input.get(0).isFile(),
                    "explicit cleanup removes only old cached audio");
            System.out.println(checks + " music API v1 checks passed");
        } finally {
            MusicArchive.remove(root);
        }
    }
}
