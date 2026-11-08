package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class MusicContract {
    static final String INDEX = Library.BASE + "music/game-index.json";
    static final String RECEIPT = Library.BASE + "music/library.json";
    // Kept in sync with the attached site's decoders.js by tools/import-site-engine.py.
    private static final String SITE_FORMATS = "a26 a5m a5z a78 acm acz agm agz ay bms bmz c3m c3z dcm dcz dkm dsf e6m e6z f3m f3z g0m g0z gbm gbs gbz gsf gym hes it ivm ivz jgm jgz k0m k0z kim kiz kscc kss kssx lsm lsz m2s m2z mdm mdz minidsf minigsf minipsf minissf miniusf mod mrm mtm nfm nfz ngl ngz nsf nsfe nws nwz psf psid rsid s3m s98 sap sgc sid spc spt spz ssf stm stz svm svz tmc usf vgm vgz vxm vxz wsm wsr wsz xa xm";
    private static final Set<String> NATIVE = new HashSet<>(Arrays.asList(SITE_FORMATS.split(" ")));

    static JSONObject catalog(JSONObject index) throws Exception {
        if (index.getInt("schema_version") != 1) throw new IOException("Версия музыкального каталога не поддерживается");
        JSONObject entries = index.getJSONObject("games");
        JSONArray games = new JSONArray(), series = new JSONArray();
        for (Iterator<String> ids = entries.keys(); ids.hasNext();) {
            String id = ids.next();
            JSONObject row = new JSONObject(entries.getJSONObject(id).toString());
            row.getString("title");
            row.getString("platform");
            row.getInt("track_count");
            row.getJSONArray("ra_ids");
            row.put("_music_contract",1).put("id", id).put("manifest", Library.resolve(INDEX, row.getString("playlist")));
            archivePath(row.getString("manifest"));
            row.put("platform_label", row.optString("platform_label", row.getString("platform")));
            games.put(row);
        }
        JSONObject groups = index.getJSONObject("series");
        for (Iterator<String> ids = groups.keys(); ids.hasNext();) {
            String id = ids.next();
            JSONObject row = new JSONObject(groups.getJSONObject(id).toString());
            row.getString("title");
            row.getJSONArray("game_ids");
            series.put(row.put("id", id));
        }
        archivePath(Library.resolve(INDEX, index.getString("archive")));
        return new JSONObject().put("games", games).put("series", series)
                .put("by_ra_id", index.getJSONObject("by_ra_id"));
    }

    static JSONObject streamingGallery(JSONObject gallery, JSONArray groups) throws Exception {
        if (gallery.getInt("schema_version") != 1) throw new IOException("Версия медиатеки не поддерживается");
        JSONArray games = new JSONArray(), series = new JSONArray();
        java.util.Map<String, String> byHref = new java.util.HashMap<>();
        Set<String> known = new HashSet<>();
        JSONArray rows = gallery.getJSONArray("games");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if ("theme".equals(row.optString("category")) || row.optInt("track_count") <= 0) continue;
            String id = row.getString("id"), url = Library.resolve(Library.BASE, row.getString("manifest"));
            archivePath(url);
            known.add(id);
            if (!row.optString("href").isEmpty()) byHref.put(row.optString("href"), id);
            games.put(new JSONObject(row.toString()).put("manifest", url));
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            JSONArray members = group.optJSONArray("members");
            if (members == null || group.optBoolean("personal")) continue;
            JSONArray ids = new JSONArray();
            Set<String> used = new HashSet<>();
            for (int j = 0; j < members.length(); j++) {
                JSONObject member = members.getJSONObject(j);
                String id = byHref.get(member.optString("href"));
                if (id == null) id = "game-ra-" + member.optLong("ra_id");
                if (known.contains(id) && used.add(id)) ids.put(id);
            }
            if (ids.length() > 0) series.put(new JSONObject().put("id", group.getString("id"))
                    .put("title", group.getString("name")).put("game_ids", ids));
        }
        return new JSONObject().put("games", games).put("series", series);
    }

    static String archivePath(String url) throws IOException {
        String path = URI.create(url).getPath();
        if (path == null || !path.startsWith("/music/")) throw new IOException("Файл вне музыкального каталога");
        return safePath(path.substring("/music/".length()));
    }

    static String safePath(String path) throws IOException {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.indexOf('|') >= 0 || path.matches("^[A-Za-z]:.*"))
            throw new IOException("Недопустимый путь в музыкальном пакете");
        for (String component : path.split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw new IOException("Недопустимый путь в музыкальном пакете");
        return path;
    }

    static String packageId(JSONObject manifest, JSONObject game) {
        JSONObject entry = manifest.optJSONObject("game");
        return entry == null ? game.optString("id") : entry.optString("id", game.optString("id"));
    }

    static int trackIndex(JSONArray tracks, String id) {
        for (int i = 0; i < tracks.length(); i++)
            if (id.equals(tracks.optJSONObject(i).optString("id"))) return i;
        return -1;
    }

    static JSONObject wav(JSONObject track) {
        JSONObject render = track.optJSONObject("browser_render");
        if (render == null || track.optJSONObject("asset") == null
                || !Arrays.asList("psf2", "minipsf2").contains(track.optJSONObject("asset").optString("format").toLowerCase(Locale.ROOT)))
            return null;
        JSONObject nested = render.optJSONObject("asset");
        JSONObject asset = nested == null ? render : nested;
        return "wav".equalsIgnoreCase(asset.optString("format", "wav")) && asset.has("url")
                && asset.has("sha256") && asset.has("bytes") ? asset : null;
    }

    static String decoder(JSONObject track) {
        JSONObject asset = track.optJSONObject("asset");
        if (asset == null) return "";
        String format = asset.optString("format", track.optString("format")).toLowerCase(Locale.ROOT);
        String backend = track.optString("backend");
        if (backend.equals("media") && format.equals("wav")) return "wav";
        if (backend.equals("native")) {
            if (wav(track) != null) return "fallback";
            return NATIVE.contains(format.replaceFirst("^\\.", "")) ? "site" : "";
        }
        if (backend.equals("gme")) return "gme";
        // Downloaded playlists written by older clients retain their existing media backend.
        if (backend.equals("media") && Arrays.asList("mp3", "ogg", "flac", "m4a", "aac").contains(format)) return "media";
        return "";
    }

    static String streamingDecoder(JSONObject track) {
        String engine = decoder(track);
        return switch (engine) {
            case "gme" -> NATIVE.contains(track.optJSONObject("asset").optString("format").toLowerCase(Locale.ROOT)) ? "site" : "";
            case "media" -> "";
            default -> engine;
        };
    }

    static double gain(JSONObject track) {
        JSONObject playback = track.optJSONObject("playback");
        double db = track.optDouble("gain_db", playback == null ? 0 : playback.optDouble("gain_db", 0));
        return Double.isFinite(db) ? Math.pow(10, Math.max(-60, Math.min(0, db)) / 20.0) : 1;
    }

    static String raQuery(String query) {
        String value = query.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("(?:ra[:# ]*)?[0-9]+")) return "";
        try { return Long.toString(Long.parseLong(value.replaceFirst("^ra[:# ]*", ""))); }
        catch (NumberFormatException e) { return ""; }
    }

    static String unsupported(JSONObject track) {
        return "Формат " + track.optString("format", track.optJSONObject("asset") == null
                ? "?" : track.optJSONObject("asset").optString("format", "?")) + " не поддерживается";
    }
}
