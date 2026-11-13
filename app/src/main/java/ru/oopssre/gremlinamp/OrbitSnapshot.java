package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

record OrbitSnapshot(OrbitSnapshot.Ephemeris moon, OrbitSnapshot.Wind wind,
                     OrbitSnapshot.Flux flux, OrbitSnapshot.Flare flare) {
    static final OrbitSnapshot EMPTY = new OrbitSnapshot(null, null, null, null);
    static final long HOUR = 3600;

    record Position(long time, double x, double y, double z) {
        double distance() { return Math.sqrt(x * x + y * y + z * z); }
    }

    record Ephemeris(long retrieved, List<Position> points) {
        long end() { return points.get(points.size() - 1).time; }

        Position at(long time) {
            Position previous = points.get(0);
            if (time <= previous.time) return previous;
            for (int i = 1; i < points.size(); i++) {
                Position next = points.get(i);
                if (time <= next.time) {
                    double fraction = (time - previous.time) / (double) (next.time - previous.time);
                    return new Position(time, previous.x + (next.x - previous.x) * fraction,
                            previous.y + (next.y - previous.y) * fraction, previous.z + (next.z - previous.z) * fraction);
                }
                previous = next;
            }
            return previous;
        }
    }

    record Wind(long time, double speed, double density, String source) {}
    record Flux(long time, double value, int satellite) {}
    record Flare(long peak, String classification) {}
    record Signal(float pan, float distance, float elevation, float speed, float density, float xray,
                  long flare, float flareLevel) {
        static final Signal EMPTY = new Signal(0, .5f, 0, .25f, .2f, .1f, 0, 0);
    }

    Signal signal(long now) {
        Position p = moon == null ? null : moon.at(now);
        boolean recentFlare = flare != null && now >= flare.peak && now - flare.peak <= HOUR;
        return new Signal(p == null ? 0 : (float) (p.y / p.distance()),
                p == null ? .5f : unit((p.distance() - 350_000) / 60_000),
                p == null ? 0 : (float) (p.z / p.distance()),
                wind == null ? .25f : unit((wind.speed - 250) / 550),
                wind == null ? .2f : unit(Math.log1p(wind.density) / 3.5),
                flux == null ? .1f : unit((Math.log10(flux.value) + 8) / 4),
                recentFlare ? flare.peak : 0, recentFlare ? switch (flare.classification.charAt(0)) {
                    case 'X' -> 1f;
                    case 'M' -> .75f;
                    case 'C' -> .48f;
                    default -> .28f;
                } : 0);
    }

    private static float unit(double n) { return (float) Math.max(0, Math.min(1, n)); }

    OrbitSnapshot withMoon(Ephemeris value) { return new OrbitSnapshot(value, wind, flux, flare); }
    OrbitSnapshot withWind(Wind value) {
        return value == null || wind != null && value.time < wind.time ? this : new OrbitSnapshot(moon, value, flux, flare);
    }
    OrbitSnapshot withFlux(Flux value) {
        return value == null || flux != null && value.time < flux.time ? this : new OrbitSnapshot(moon, wind, value, flare);
    }
    OrbitSnapshot withFlare(Flare value) {
        return value == null || flare != null && value.peak < flare.peak ? this : new OrbitSnapshot(moon, wind, flux, value);
    }

    static OrbitSnapshot read(JSONObject data) throws JSONException {
        if (data.getInt("schema") != 1) throw new JSONException("Unknown orbit archive schema");
        JSONObject m = data.getJSONObject("moon");
        JSONArray table = m.getJSONArray("points");
        List<Position> points = new ArrayList<>();
        for (int i = 0; i < table.length(); i++) {
            JSONArray row = table.getJSONArray(i);
            points.add(new Position(row.getLong(0), row.getDouble(1), row.getDouble(2), row.getDouble(3)));
        }
        Ephemeris ephemeris = ephemeris(m.getLong("retrieved"), points);
        JSONObject w = data.getJSONObject("wind"), x = data.getJSONObject("flux"), f = data.optJSONObject("flare");
        Wind wind = wind(w.getLong("time"), w.getDouble("speed"), w.getDouble("density"), w.getString("source"));
        Flux flux = flux(x.getLong("time"), x.getDouble("value"), x.getInt("satellite"));
        Flare flare = f == null ? null : flare(f.getLong("peak"), f.getString("classification"));
        if (wind == null || flux == null || f != null && flare == null) throw new JSONException("Invalid orbit archive data");
        return new OrbitSnapshot(ephemeris, wind, flux, flare);
    }

    JSONObject write() throws JSONException {
        JSONArray points = new JSONArray();
        for (Position p : moon.points) points.put(new JSONArray().put(p.time).put(p.x).put(p.y).put(p.z));
        JSONObject data = new JSONObject().put("schema", 1)
                .put("moon", new JSONObject().put("retrieved", moon.retrieved).put("points", points))
                .put("wind", new JSONObject().put("time", wind.time).put("speed", wind.speed)
                        .put("density", wind.density).put("source", wind.source))
                .put("flux", new JSONObject().put("time", flux.time).put("value", flux.value).put("satellite", flux.satellite));
        if (flare != null) data.put("flare", new JSONObject().put("peak", flare.peak).put("classification", flare.classification));
        return data;
    }

    static Ephemeris parseMoon(JSONObject data, long now) throws JSONException {
        JSONObject signature = data.getJSONObject("signature");
        String version = signature.getString("version");
        if (!"NASA/JPL Horizons API".equals(signature.getString("source")) || !version.matches("1\\.[23]"))
            throw new JSONException("Unknown JPL Horizons response");
        String result = data.getString("result");
        int start = result.indexOf("$$SOE"), end = result.indexOf("$$EOE");
        if (start < 0 || end <= start + 5) throw new JSONException("Missing lunar ephemeris");
        List<Position> points = new ArrayList<>();
        for (String line : result.substring(start + 5, end).trim().split("\\R")) {
            String[] c = line.split(",");
            if (c.length < 5) throw new JSONException("Invalid lunar ephemeris row");
            points.add(new Position(Math.round((Double.parseDouble(c[0]) - 2_440_587.5) * 86400),
                    Double.parseDouble(c[2]), Double.parseDouble(c[3]), Double.parseDouble(c[4])));
        }
        Ephemeris moon = ephemeris(now, points);
        if (moon.at(now).time != now || moon.end() - now < 20 * HOUR) throw new JSONException("Incomplete lunar window");
        return moon;
    }

    private static Ephemeris ephemeris(long retrieved, List<Position> points) throws JSONException {
        if (retrieved <= 0 || points.size() < 24 || points.size() > 100)
            throw new JSONException("Invalid lunar ephemeris size");
        long previous = points.get(0).time - HOUR;
        for (Position p : points) {
            double distance = p.distance();
            if (p.time <= 0 || p.time - previous != HOUR || !(distance >= 300_000 && distance <= 450_000))
                throw new JSONException("Invalid lunar position");
            previous = p.time;
        }
        return new Ephemeris(retrieved, Collections.unmodifiableList(points));
    }

    static Wind parseWind(JSONArray data, long now) {
        Wind latest = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null || !row.optBoolean("active") || row.optInt("overall_quality", 0) != 0) continue;
            long time = time(row.optString("time_tag"));
            if (time > now + 300 || latest != null && time <= latest.time) continue;
            Wind value = wind(time, row.optDouble("proton_speed"), row.optDouble("proton_density"), row.optString("source"));
            if (value != null) latest = value;
        }
        return latest;
    }

    static Flux parseFlux(JSONArray data, long now) {
        Flux latest = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null || !"0.1-0.8nm".equals(row.optString("energy"))) continue;
            long time = time(row.optString("time_tag"));
            if (time > now + 300 || latest != null && time <= latest.time) continue;
            Flux value = flux(time, row.optDouble("flux"), row.optInt("satellite"));
            if (value != null) latest = value;
        }
        return latest;
    }

    static Flare parseFlare(JSONArray data, long now) {
        Flare latest = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            long time = time(row.optString("max_time"));
            if (time > now + 300 || latest != null && time <= latest.peak) continue;
            Flare value = flare(time, row.optString("max_class"));
            if (value != null) latest = value;
        }
        return latest;
    }

    private static Wind wind(long time, double speed, double density, String source) {
        return time > 0 && speed > 0 && speed < 3000 && density >= 0 && density < 1000
                && source.matches("[A-Za-z0-9_-]{1,24}") ? new Wind(time, speed, density, source) : null;
    }

    private static Flux flux(long time, double value, int satellite) {
        return time > 0 && value > 0 && value < .1 && satellite > 0 ? new Flux(time, value, satellite) : null;
    }

    private static Flare flare(long peak, String classification) {
        return peak > 0 && classification.matches("[ABCMX][0-9]{1,3}(\\.[0-9]+)?") ? new Flare(peak, classification) : null;
    }

    private static long time(String value) {
        try { return Instant.parse(value.endsWith("Z") ? value : value + "Z").getEpochSecond(); }
        catch (RuntimeException malformed) { return 0; }
    }
}
