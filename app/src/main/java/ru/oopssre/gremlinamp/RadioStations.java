package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class RadioStations {
    static final String PERSONAL_ASSET = "personal-radio.json";
    static final String PERSONAL_MODE = "personal_radio";

    record Station(String title, String url, boolean personal, String credit) {
        Station(String title, String url, boolean personal) { this(title, url, personal, ""); }
    }

    static List<Station> available(android.content.Context context, boolean personal) {
        List<Station> stations = new ArrayList<>();
        stations.add(new Station("RolandRadio · Amstrad CPC", "https://streaming.rolandradio.net/rolandradio", false));
        stations.add(new Station("Kohina · игры и демосцена", "https://kohina.brona.dk/icecast/stream.ogg", false));
        stations.add(new Station("9128 Live", "https://streams.radio.co/s0aa1e6f4a/listen", false));
        stations.add(new Station("Ambient Sleeping Pill", "https://radio.stereoscenic.com/asp-s", false));
        stations.add(new Station("MUGEN Radio", "https://mugenradio.com/hls/live.m3u8", false,
                "MUGEN Radio / mugenradio.com · ИИ · CC BY-NC 4.0"));
        if (personal) {
            try (InputStream input = context.getAssets().open(PERSONAL_ASSET)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n; (n = input.read(buffer)) != -1; ) bytes.write(buffer, 0, n);
                stations.addAll(readPersonal(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
            } catch (Exception ignored) { }
        }
        return stations;
    }

    static List<Station> readPersonal(String json) throws Exception {
        JSONArray rows = new JSONArray(json);
        List<Station> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String title = row.getString("title").trim();
            URI url = new URI(row.getString("url"));
            if (!title.isEmpty() && "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                    && url.getUserInfo() == null && url.getFragment() == null)
                result.add(new Station(title, url.toASCIIString(), true));
        }
        return result;
    }

    static boolean acceptsCode(android.content.Context context, String code) {
        try (InputStream input = context.getAssets().open("personal-radio.sha256")) {
            byte[] bytes = new byte[67];
            int length = 0;
            while (length < bytes.length) {
                int count = input.read(bytes, length, bytes.length - length);
                if (count < 0) break;
                length += count;
            }
            if (length == bytes.length) return false;
            String expected = new String(bytes, 0, length, StandardCharsets.US_ASCII).trim();
            return expected.matches("[a-f0-9]{64}")
                    && expected.equals(Library.hash(code.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return false;
        }
    }
}
