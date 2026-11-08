package ru.oopssre.gremlinamp;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

record TvRelease(long build, String version, String apkUrl) {
    static final String CHANNEL = "/assets/arcade/music-app/tv/";
    static final String MANIFEST = "https://games.oopssre.ru" + CHANNEL + "release.json";
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    static TvRelease parse(String body) throws IOException {
        try {
            JSONObject json = new JSONObject(body);
            Object build = json.get("build");
            String version = json.getString("version").trim();
            String url = UpdateRules.apkUrl(json.getJSONObject("apk").getString("url"));
            String path = URI.create(url).getRawPath();
            if (!(build instanceof Integer || build instanceof Long)
                    || ((Number) build).longValue() <= 0 || version.isEmpty() || version.length() > 80
                    || !path.startsWith(CHANNEL)
                    || !path.substring(CHANNEL.length()).matches("[a-zA-Z0-9._-]+\\.apk")) {
                throw new IOException("Некорректный релиз GremlinAmp TV");
            }
            return new TvRelease(((Number) build).longValue(), version, url);
        } catch (Exception error) {
            throw new IOException("Некорректный релиз GremlinAmp TV", error);
        }
    }

    static String checkDay(long now) {
        var local = Instant.ofEpochMilli(now).atZone(MOSCOW);
        return local.toLocalTime().isBefore(LocalTime.of(7, 0)) ? "" : local.toLocalDate().toString();
    }
}
