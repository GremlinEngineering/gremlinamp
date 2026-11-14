package ru.oopssre.gremlinamp;

import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONObject;

/** Persists just the weather fields used by the tracker. */
final class TeahouseWeather {
    static final long REFRESH_MILLIS = 10 * 60_000L;
    static final String URL = "https://games.oopssre.ru/assets/arcade/views/dynamic/sunken-teahouse/devices/phone_1440x3120/current.json";
    private static final String KEY = "tracker_teahouse_weather";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);
    private static final int MAX_BYTES = 512 * 1024;

    private final SharedPreferences prefs;
    private volatile Snapshot snapshot;

    private record Snapshot(Instant at, int code, double windMs, double clouds, double precipitation,
                            EarthTracker.Weather sound) {
        static Snapshot of(Instant at, int code, double windMs, double clouds, double precipitation) {
            if (code < 0 || code > 99 || at.isAfter(Instant.now().plusSeconds(300)))
                throw new IllegalArgumentException("Недопустимый срез погоды");
            return new Snapshot(at, code, windMs, clouds, precipitation,
                    new EarthTracker.Weather(code, windMs * 3.6, clouds, precipitation));
        }

        String encode() { return at + "|" + code + "|" + windMs + "|" + clouds + "|" + precipitation; }

        static Snapshot decode(String record) {
            String[] fields = record.split("\\|", -1);
            if (fields.length != 5) return null;
            return of(Instant.parse(fields[0]), Integer.parseInt(fields[1]), Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3]), Double.parseDouble(fields[4]));
        }
    }

    TeahouseWeather(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            snapshot = Snapshot.decode(prefs.getString(KEY, ""));
        } catch (RuntimeException invalid) {
            snapshot = null;
        }
    }

    EarthTracker.Weather sound() {
        Snapshot current = snapshot;
        return current == null ? EarthTracker.Weather.UNKNOWN : current.sound;
    }

    String description() {
        Snapshot current = snapshot;
        if (current == null) return "Затонувшая чайная · жду первый срез\nНейтральный эмбиент";
        String details = "";
        if (Double.isFinite(current.clouds)) details += "Облака " + Math.round(current.clouds) + "%";
        if (Double.isFinite(current.windMs))
            details += (details.isEmpty() ? "" : " · ") + String.format(Locale.ROOT, "Ветер %.1f м/с", current.windMs);
        return "Затонувшая чайная · срез " + TIME.withZone(ZoneId.systemDefault()).format(current.at)
                + (details.isEmpty() ? "" : "\n" + details);
    }

    void refresh() throws Exception {
        HttpURLConnection connection = NetworkPolicy.openHttp(new URL(URL));
        connection.setConnectTimeout(7_000);
        connection.setReadTimeout(7_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IOException("Срез «Затонувшей чайной» недоступен");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted() || body.size() + read > MAX_BYTES)
                        throw new IOException("Срез погоды прерван");
                    body.write(buffer, 0, read);
                }
                accept(new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)));
            }
        } finally {
            connection.disconnect();
        }
    }

    synchronized void accept(JSONObject document) throws Exception {
        if (document.getInt("schema") != 1 || !"sunken-teahouse".equals(document.getString("theme_id"))
                || !"phone_1440x3120".equals(document.getString("device")))
            throw new IOException("Срез получен от другой коллекции");
        JSONObject inputs = document.getJSONObject("inputs");
        JSONObject weather = inputs.getJSONObject("weather");
        Instant at = OffsetDateTime.parse(inputs.optString("at", document.getString("at"))).toInstant();
        Snapshot next = Snapshot.of(at,
                weather.getInt("weather_code"), measurement(weather, "wind_speed_10m", 150),
                measurement(weather, "cloud_cover", 100), measurement(weather, "precipitation", 500));
        Snapshot old = snapshot;
        if (Thread.currentThread().isInterrupted() || old != null && next.at.isBefore(old.at)) return;
        String record = next.encode();
        if (old != null && record.equals(old.encode())) return;
        prefs.edit().putString(KEY, record).apply();
        snapshot = next;
    }

    private static double measurement(JSONObject weather, String name, double max) {
        double value = weather.optDouble(name);
        return value >= 0 && value <= max ? value : Double.NaN;
    }
}
