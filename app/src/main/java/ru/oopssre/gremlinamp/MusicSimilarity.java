package ru.oopssre.gremlinamp;
import org.json.*;import java.util.*;import java.io.*;import java.util.function.BooleanSupplier;
final class MusicSimilarity {
 static final class Selection {final JSONObject game,manifest;final int index;Selection(JSONObject g,JSONObject m,int i){game=g;manifest=m;index=i;}}
 static Selection next(Library l,JSONObject seed,String mode,Set<String> played,boolean localOnly,boolean preview,BooleanSupplier active)throws Exception {
  String source=SimilarityRules.key(seed);Set<String> excluded=new HashSet<>(played);excluded.add(source);
  if(l.streaming&&!preview){
   if(source.isEmpty())throw new IOException("Для этой композиции анализ недоступен");
   SimilarityStore.ensureReadable(l);
   if(!active.getAsBoolean())throw new InterruptedIOException();
  }
  List<SimilarityRules.Target> targets=SimilarityStore.targets(l,source,mode,excluded);
  JSONObject catalog=l.catalog(false);
  Map<String,JSONObject> games=new LinkedHashMap<>();
  List<JSONObject> rows=new ArrayList<>();JSONArray index=catalog.getJSONArray("games");
  for(int i=0;i<index.length();i++)rows.add(index.getJSONObject(i));
  Map<String,JSONObject> manifests=new HashMap<>();String platform=l.preferences.getString("platform_filter",""),year=l.preferences.getString("year_filter","");
  if(!platform.isEmpty()||!year.isEmpty())rows=l.enrichGames(rows);
  for(JSONObject game:rows)games.put(game.optString("id"),game);
  for(SimilarityRules.Target target:targets){if(!active.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException();JSONObject game=games.get(target.game);if(game==null)continue;
   if(!platform.isEmpty()&&!platform.equals(game.optString("platform_label")))continue;if(!year.isEmpty()&&!year.equals(game.has("year")?String.valueOf(game.optInt("year")):"Не указан"))continue;
   try{JSONObject manifest;if(manifests.containsKey(target.game))manifest=manifests.get(target.game);else{manifest=localOnly?l.localManifest(game):preview?l.cachedManifest(game):l.manifest(game,false);manifests.put(target.game,manifest);}if(manifest==null)continue;JSONArray tracks=manifest.getJSONArray("tracks");int i=SimilarityRules.trackIndex(target,tracks);if(i<0)continue;JSONObject t=tracks.getJSONObject(i);String decoder=l.streaming?MusicContract.streamingDecoder(t):MusicContract.decoder(t);if(!l.hiddenShort(t)&&!decoder.isEmpty()&&(!localOnly||l.playableLocal(manifest,t)))return new Selection(game,manifest,i);}
   catch(IOException|JSONException ignored){}
  }
  return null;
 }
}
