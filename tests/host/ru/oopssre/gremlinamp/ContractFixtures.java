package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

final class ContractFixtures {
    static JSONObject index(JSONObject... games) throws Exception {
        JSONObject packages = new JSONObject(), byRa = new JSONObject();
        for (JSONObject game : games) {
            String id = game.getString("id");
            String path = MusicContract.archivePath(Library.resolve(Library.BASE, game.getString("manifest")));
            JSONArray ids = game.optJSONArray("ra_ids");
            if (ids == null) {
                ids = new JSONArray();
                if (id.startsWith("game-ra-")) ids.put(Long.parseLong(id.substring(8)));
            }
            for (int i = 0; i < ids.length(); i++) {
                String ra = String.valueOf(ids.getLong(i));
                JSONArray matches = byRa.optJSONArray(ra);
                if (matches == null) byRa.put(ra, matches = new JSONArray());
                matches.put(id);
            }
            packages.put(id, new JSONObject(game.toString()).put("playlist", path)
                    .put("track_count", game.optInt("track_count", 1)).put("ra_ids", ids)
                    .put("platform", game.optString("platform", "nes")));
        }
        return new JSONObject().put("schema_version", 1).put("games", packages).put("by_ra_id", byRa)
                .put("series", new JSONObject()).put("archive", "library.zip");
    }

    static JSONObject group(JSONObject index, String id, String... games) throws Exception {
        index.getJSONObject("series").put(id, new JSONObject().put("title", id).put("game_ids", new JSONArray(List.of(games))));
        return index;
    }
}
