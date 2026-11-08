package ru.oopssre.gremlinamp;
import org.json.*;import java.util.*;import java.io.*;import java.nio.file.*;
public class SimilarityChecks {
 static int count;static void ok(boolean value){count++;if(!value)throw new AssertionError("check "+count);}
 static String a="a".repeat(64)+":0",b="b".repeat(64)+":1",c="c".repeat(64)+":0";
 static JSONObject ref(String game,String track,int index)throws Exception{return new JSONObject().put("game_id",game).put("track_id",track).put("index",index);}
 static JSONObject row(JSONObject... refs)throws Exception{return new JSONObject().put("refs",new JSONArray(Arrays.asList(refs))).put("neighbors",new JSONObject());}
 static JSONObject track(String key,String id)throws Exception{return new JSONObject().put("id",id).put("subsong",Integer.parseInt(key.split(":")[1])).put("backend","gme").put("asset",new JSONObject().put("sha256",key.split(":")[0]).put("format","nsf").put("bytes",3).put("url","../files/a.nsf")).put("duration_known",true).put("play_ms",60000).put("backend","gme");}
 public static void main(String[] args)throws Exception{
  ok(SimilarityRules.mode("rhythm"));ok(SimilarityRules.mode("sound"));ok(!SimilarityRules.mode("similar"));ok(SimilarityRules.validKey(b));ok(!SimilarityRules.validKey("../file"));
  JSONObject source=row();source.getJSONObject("neighbors").put("rhythm",new JSONArray().put(new JSONObject().put("key",c).put("distance",.5)).put(new JSONObject().put("key",b).put("distance",.1)).put(new JSONObject().put("key",b).put("distance",.2)).put(new JSONObject().put("key",a).put("distance",-.1)));
  SimilarityStore.data.put(a,source);SimilarityStore.data.put(b,row(ref("g1","with,comma",0)));SimilarityStore.data.put(c,row(ref("g2","other",0)));
  List<SimilarityRules.Target> targets=SimilarityRules.targets(source,"rhythm",Set.of(a),SimilarityStore.data::get);
  ok(targets.size()==2);ok(targets.get(0).key.equals(b));ok(SimilarityRules.matches(targets.get(0),track(b,"with,comma"),0));ok(!SimilarityRules.matches(targets.get(0),track(c,"with,comma"),0));ok(!SimilarityRules.matches(targets.get(0),track(b,"changed"),0));
  ok(SimilarityRules.targets(source,"rhythm",Set.of(b),SimilarityStore.data::get).size()==1);ok(SimilarityRules.targets(source,"sound",Set.of(),SimilarityStore.data::get).isEmpty());
  SimilarityAvailability state=SimilarityAvailability.evaluate(false,true,false,false,false);ok(!state.rhythm&&!state.sound);ok(state.soundReason.contains("не загружен"));
  state=SimilarityAvailability.evaluate(true,false,false,false,false);ok(!state.sound&&state.soundReason.contains("выберите"));
  state=SimilarityAvailability.evaluate(true,true,false,false,false);ok(!state.rhythm&&state.rhythmReason.contains("не проанализирована"));
  state=SimilarityAvailability.evaluate(true,true,true,false,true);ok(!state.rhythm&&state.sound);ok(state.rhythmReason.contains("ритмических"));
  state=SimilarityAvailability.evaluate(true,true,true,true,false);ok(state.rhythm&&!state.sound);ok(state.soundReason.contains("звучанию"));
  state=SimilarityAvailability.evaluate(true,true,true,true,true);ok(state.enabled("rhythm")&&state.enabled("sound"));ok(state.reason("rhythm").isEmpty()&&state.enabled("series"));
  File root=Files.createTempDirectory("similarity-test").toFile();Library l=new Library(new android.content.Context(root));l.preferences.edit().putBoolean("continue_local",true).putBoolean("hide_unknown",false).apply();
  JSONObject g1=new JSONObject().put("id","g1").put("title","Game 1").put("platform_label","NES").put("manifest","music/sets/g1.json"),g2=new JSONObject().put("id","g2").put("title","Game 2").put("platform_label","SNES").put("manifest","music/sets/g2.json");
  Library.atomic(l.jsonFile(MusicContract.INDEX),ContractFixtures.index(g1,g2).toString().getBytes());
  JSONObject t1=track(b,"with,comma"),t2=track(c,"other");for(JSONObject[] pair:new JSONObject[][]{{g1,t1},{g2,t2}}){JSONObject g=pair[0],t=pair[1];Library.atomic(l.jsonFile(Library.resolve(Library.BASE,g.getString("manifest"))),new JSONObject().put("tracks",new JSONArray().put(t)).toString().getBytes());Library.atomic(l.assetFile(t.getJSONObject("asset")),new byte[]{1,2,3});}
  MusicSimilarity.Selection chosen=MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(a),true,false,()->true);ok(chosen.game.getString("id").equals("g1"));
  chosen=MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(a,b),true,false,()->true);ok(chosen.game.getString("id").equals("g2"));
  l.preferences.edit().putString("platform_filter","SNES").apply();chosen=MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(),true,false,()->true);ok(chosen.game.getString("id").equals("g2"));
  l.preferences.edit().putString("platform_filter","").putString("year_filter","1992").apply();ok(MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(),true,false,()->true)==null);
  l.preferences.edit().putString("year_filter","").apply();l.assetFile(t1.getJSONObject("asset")).delete();chosen=MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(),true,false,()->true);ok(chosen.game.getString("id").equals("g2"));
  boolean cancelled=false;try{MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(),true,false,()->false);}catch(InterruptedIOException e){cancelled=true;}ok(cancelled);
  JSONObject changed=new JSONObject().put("tracks",new JSONArray().put(track(b,"changed")));Library.atomic(l.jsonFile(Library.resolve(Library.BASE,g1.getString("manifest"))),changed.toString().getBytes());chosen=MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(),false,true,()->true);ok(chosen.game.getString("id").equals("g1"));
  SimilarityStore.data.remove(c);ok(MusicSimilarity.next(l,track(a,"seed"),"rhythm",Set.of(),true,false,()->true)==null);
  if(args.length>0){JSONObject fixture=new JSONObject(Files.readString(Path.of(args[0])));ok(fixture.getInt("schema_version")==1);JSONObject tracks=fixture.getJSONObject("tracks");String key=tracks.keys().next();List<SimilarityRules.Target> actual=SimilarityRules.targets(tracks.getJSONObject(key),"sound",Set.of(key),tracks::optJSONObject);ok(!actual.isEmpty());}
  System.out.println(count+" similarity checks passed: contract, history, filters, local-only, stale manifests and cancellation");
 }
}
