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
    private static final Set<String> LIBVGM = new HashSet<>(Arrays.asList("vgm", "vgz", "s98", "tmc"));
    private static final Set<String> GME = new HashSet<>(Arrays.asList("ay", "gym", "hes", "kss", "kssx", "kscc", "nsf", "nsfe", "sap", "spc"));
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
        String format = decoderFormat(track, asset);
        String backend = track.optString("backend");
        if (backend.equals("media") && format.equals("wav")) return "wav";
        if (backend.equals("native") || backend.equals("gme")) {
            if (wav(track) != null) return "fallback";
            if (LIBVGM.contains(format)) return "vgm";
            if (format.equals("sgc") || format.equals("gbs")) return "gme-plus";
            if (format.equals("gsf") || format.equals("minigsf")) return "gsf";
            if (format.equals("psf") || format.equals("minipsf")) return "psf";
            if (format.equals("dsf") || format.equals("minidsf")) return "dsf";
            if (format.equals("ssf") || format.equals("minissf")) return "ssf";
            if (format.equals("usf") || format.equals("miniusf")) return "usf";
            if (format.equals("xa")) return "xa";
            if (Arrays.asList("sid", "psid", "rsid").contains(format)) return "sid";
            if (format.equals("stm")) return "stm";
            if (Arrays.asList("mod", "xm", "it", "s3m", "mtm").contains(format)) return "openmpt";
            if (Arrays.asList("agm", "agz", "gbm", "gbz", "ivm", "ivz", "jgm", "jgz", "mdm", "mdz",
                    "stz", "svm", "svz", "vxm", "vxz", "wsm", "wsz", "nfm", "nfz", "a5m", "a5z").contains(format))
                return "machine";
            if (GME.contains(format)) return "gme";
            if (!board(format).isEmpty()) return "board";
            return "";
        }
        // Downloaded playlists written by older clients retain their existing media backend.
        if (backend.equals("media") && Arrays.asList("mp3", "ogg", "flac", "m4a", "aac").contains(format)) return "media";
        return "";
    }

    static String decoderFormat(JSONObject track, JSONObject asset) {
        return asset.optString("format", track.optString("format")).toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
    }

    static String board(String format) {
        return switch (format) {
            case "a26", "a78", "wsr" -> format;
            case "acm", "acz" -> "acm";
            case "bms", "bmz" -> "bms";
            case "c3m", "c3z" -> "cps3";
            case "dkm" -> "dk";
            case "dcm", "dcz" -> "dcs";
            case "g0m", "g0z" -> "g0";
            case "k0m", "k0z" -> "k0";
            case "e6m", "e6z" -> "e6m";
            case "f3m", "f3z" -> "f3";
            case "kim", "kiz" -> "ki";
            case "lsm", "lsz" -> "lsm";
            case "m2s", "m2z" -> "m2s";
            case "mrm" -> "mario";
            case "ngl", "ngz" -> "ngl";
            case "nws", "nwz" -> "nws";
            case "snes-registers" -> "theme-snes";
            case "spt", "spz" -> "spt";
            default -> "";
        };
    }

    static String board(JSONObject track) { return board(decoderFormat(track, track.optJSONObject("asset"))); }

    static boolean decoderNeedsInputs(String decoder) { return Arrays.asList("gsf", "psf", "dsf", "ssf", "usf").contains(decoder); }

    static String streamingDecoder(JSONObject track) {
        String engine = decoder(track);
        return engine.equals("media") ? "" : engine;
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
