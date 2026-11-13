package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class OrbitData {
    static final long REFRESH_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final String NOAA = "https://services.swpc.noaa.gov/json/";
    private static final String JPL = "https://ssd.jpl.nasa.gov/api/horizons.api?format=json";
    private static final String USER_AGENT = "GremlinAmp Android/0.2.16 (+https://games.oopssre.ru/)";
    private static final int ARCHIVE_LIMIT = 32768;
    private static final java.time.format.DateTimeFormatter UTC =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    interface Archive { byte[] load() throws IOException; }
    private final File cache;
    private final Archive archive;
    private volatile OrbitSnapshot snapshot = OrbitSnapshot.EMPTY;
    private volatile String status = "Загружаю архив";
    private volatile String offlineStatus = "загружаю архив";
    private boolean loaded;
    private long nextMoonAttempt;

    OrbitData(File directory, Archive archive) {
        cache = new File(directory, "orbits-v1.json");
        this.archive = archive;
    }

    synchronized void load() {
        if (loaded) return;
        try {
            snapshot = OrbitSnapshot.read(new JSONObject(new String(archive.load(), StandardCharsets.UTF_8)));
            status = "Встроенный архив";
            offlineStatus = "встроенный архив";
        } catch (Exception invalid) {
            status = "Архив недоступен";
            offlineStatus = "архив недоступен";
        }
        try {
            if (cache.isFile() && cache.length() <= ARCHIVE_LIMIT) {
                OrbitSnapshot stored = OrbitSnapshot.read(new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8)));
                OrbitSnapshot current = snapshot;
                if (current.moon() == null || stored.moon().retrieved() >= current.moon().retrieved())
                    current = current.withMoon(stored.moon());
                snapshot = current.withWind(stored.wind()).withFlux(stored.flux()).withFlare(stored.flare());
                status = "Последний сохранённый срез";
                offlineStatus = "последний срез";
            }
        } catch (Exception invalid) {
            // A damaged writable cache falls back to the built-in data.
        }
        loaded = true;
    }

    OrbitSnapshot current() { return snapshot; }

    void refresh(boolean network) {
        load();
        if (!network) return;
        OrbitSnapshot previous = snapshot, updated = previous;
        long now = Instant.now().getEpochSecond();
        int available = 0;
        status = "Проверяю источники";
        try {
            OrbitSnapshot.Wind wind = OrbitSnapshot.parseWind(new JSONArray(fetch(NOAA + "rtsw/rtsw_wind_1m.json", 8_000_000)), now);
            if (wind != null && now - wind.time() <= 30 * 60 && (previous.wind() == null || wind.time() >= previous.wind().time())) available++;
            updated = updated.withWind(wind);
        } catch (Exception ignored) {}
        try {
            OrbitSnapshot.Flux flux = OrbitSnapshot.parseFlux(new JSONArray(fetch(NOAA + "goes/primary/xrays-6-hour.json", 500_000)), now);
            if (flux != null && now - flux.time() <= 30 * 60 && (previous.flux() == null || flux.time() >= previous.flux().time())) available++;
            updated = updated.withFlux(flux);
        } catch (Exception ignored) {}
        try {
            OrbitSnapshot.Flare flare = OrbitSnapshot.parseFlare(new JSONArray(fetch(NOAA + "goes/primary/xray-flares-7-day.json", 500_000)), now);
            updated = updated.withFlare(flare);
            available++;
        } catch (Exception ignored) {}
        OrbitSnapshot.Ephemeris moon = updated.moon();
        if (now >= nextMoonAttempt && (moon == null || now - moon.retrieved() >= 24 * OrbitSnapshot.HOUR
                || moon.end() - now < 2 * OrbitSnapshot.HOUR || moon.at(now).time() != now)) {
            nextMoonAttempt = now + 30 * 60;
            try {
                updated = updated.withMoon(OrbitSnapshot.parseMoon(new JSONObject(fetch(moonUrl(now), 80_000)), now));
            } catch (Exception ignored) {}
        }
        if (!updated.equals(previous) && !Thread.currentThread().isInterrupted()) {
            snapshot = updated;
            if (updated.moon() != null && updated.wind() != null && updated.flux() != null) {
                Path pending = null;
                try {
                    pending = Files.createTempFile(cache.getParentFile().toPath(), "orbits-", ".part");
                    Files.write(pending, updated.write().toString().getBytes(StandardCharsets.UTF_8));
                    if (Thread.currentThread().isInterrupted()) return;
                    Files.move(pending, cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    offlineStatus = "последний срез";
                } catch (Exception failure) {
                    status = "Срез загружен · не удалось сохранить";
                    offlineStatus = "срез в памяти";
                    return;
                } finally {
                    if (pending != null) pending.toFile().delete();
                }
            }
        }
        status = switch (available) {
            case 3 -> "Онлайн · проверка каждые 10 мин";
            case 0 -> "Источники недоступны · сохранённые данные";
            default -> "Часть источников недоступна · сохранённые данные";
        };
    }

    String describe(long now, boolean network) {
        OrbitSnapshot current = snapshot;
        String header = network ? status : "Офлайн · " + offlineStatus;
        OrbitSnapshot.Position p = current.moon() == null ? null : current.moon().at(now);
        String moon = p == null ? "—" : stamp(p.time()) + (p.time() == now ? "" : " · архив");
        OrbitSnapshot.Wind w = current.wind();
        String wind = w == null ? "—" : Math.round(w.speed()) + " км/с · " + stamp(w.time()) + age(now, w.time());
        OrbitSnapshot.Flux x = current.flux();
        String goes = x == null ? "GOES  —" : "GOES-" + x.satellite() + "  " + stamp(x.time()) + age(now, x.time());
        OrbitSnapshot.Flare f = current.flare();
        String flare = f == null ? "" : "\nПик " + f.classification() + "  " + stamp(f.peak());
        return header + "\nВремя данных · UTC\nЛуна · JPL  " + moon
                + "\nВетер · NOAA  " + wind + "\n" + goes + flare;
    }

    private static String stamp(long time) { return UTC.format(Instant.ofEpochSecond(time)); }
    private static String age(long now, long time) { return now - time > 30 * 60 ? " · архив" : ""; }

    private static String moonUrl(long now) throws IOException {
        LocalDate today = Instant.ofEpochSecond(now).atOffset(ZoneOffset.UTC).toLocalDate();
        String query = "COMMAND='301'&CENTER='500@399'&EPHEM_TYPE=VECTORS&VEC_TABLE=1&CSV_FORMAT=YES"
                + "&OUT_UNITS=KM-S&REF_PLANE=ECLIPTIC&TIME_TYPE=UT&START_TIME='" + today.minusDays(1)
                + "'&STOP_TIME='" + today.plusDays(2) + "'&STEP_SIZE='1 h'";
        StringBuilder url = new StringBuilder(JPL);
        for (String field : query.split("&")) {
            int equals = field.indexOf('=');
            url.append('&').append(field, 0, equals + 1)
                    .append(URLEncoder.encode(field.substring(equals + 1), StandardCharsets.UTF_8.name()).replace("+", "%20"));
        }
        return url.toString();
    }

    private static String fetch(String address, int limit) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Orbit refresh cancelled");
        HttpURLConnection connection = NetworkPolicy.openHttp(new URL(address));
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Orbit source unavailable");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] bytes = new byte[8192];
                for (int count; (count = input.read(bytes)) >= 0;) {
                    if (Thread.currentThread().isInterrupted() || body.size() + count > limit) throw new IOException("Orbit response rejected");
                    body.write(bytes, 0, count);
                }
                return new String(body.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
