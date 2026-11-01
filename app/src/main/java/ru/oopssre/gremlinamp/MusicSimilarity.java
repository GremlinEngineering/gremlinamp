package ru.oopssre.gremlinamp;
import org.json.*;import java.util.*;import java.nio.file.*;import java.io.*;import java.util.function.BooleanSupplier;
final class MusicSimilarity {
 static final class Selection {final JSONObject game,manifest;final int index;Selection(JSONObject g,JSONObject m,int i){game=g;manifest=m;index=i;}}
 static JSONObject cached(Library l,String url)throws Exception{File f=l.jsonFile(url);return f.isFile()?new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8)):null;}
 static Selection next(Library l,JSONObject seed,String mode,Set<String> played,boolean localOnly,boolean preview,BooleanSupplier active)throws Exception {
  String source=SimilarityRules.key(seed);Set<String> excluded=new HashSet<>(played);excluded.add(source);
  List<SimilarityRules.Target> targets=SimilarityStore.targets(l,source,mode,excluded);
  JSONObject catalog=cached(l,Library.BASE+"assets/gallery-music.json");Map<String,JSONObject> games=new LinkedHashMap<>();JSONArray rows=catalog==null?null:catalog.optJSONArray("games");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject g=rows.getJSONObject(i);games.put(g.optString("id"),new JSONObject(g.toString()));}for(JSONObject g:l.savedGames())games.putIfAbsent(g.optString("id"),g);
  JSONObject stats=cached(l,Library.BASE+"assets/statistics.json");JSONArray editions=stats==null?null:stats.optJSONArray("editions");if(editions!=null)for(int i=0;i<editions.length();i++){JSONObject e=editions.getJSONObject(i),g=games.get("game-ra-"+e.optLong("ra_id"));if(g==null)for(JSONObject candidate:games.values())if(!candidate.optString("href").isEmpty()&&candidate.optString("href").equals(e.optString("href"))){g=candidate;break;}if(g!=null&&!e.isNull("year"))g.put("year",e.optInt("year"));}
  Map<String,JSONObject> manifests=new HashMap<>();String platform=l.preferences.getString("platform_filter",""),year=l.preferences.getString("year_filter","");
  for(SimilarityRules.Target target:targets){if(!active.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException();JSONObject game=games.get(target.game);if(game==null)continue;
   if(!platform.isEmpty()&&!platform.equals(game.optString("platform_label")))continue;if(!year.isEmpty()&&!year.equals(game.has("year")?String.valueOf(game.optInt("year")):"Не указан"))continue;
   try{JSONObject manifest;if(manifests.containsKey(target.game))manifest=manifests.get(target.game);else{manifest=localOnly||preview?l.cachedManifest(game):l.manifest(game,false);manifests.put(target.game,manifest);}if(manifest==null)continue;JSONArray tracks=manifest.getJSONArray("tracks");for(int i=0;i<tracks.length();i++){JSONObject t=tracks.getJSONObject(i);if(SimilarityRules.matches(target,t,i)&&!l.hiddenShort(t)&&(!localOnly||l.available(t.getJSONObject("asset"))))return new Selection(game,manifest,i);}}
   catch(IOException|JSONException ignored){}
  }
  return null;
 }
}
