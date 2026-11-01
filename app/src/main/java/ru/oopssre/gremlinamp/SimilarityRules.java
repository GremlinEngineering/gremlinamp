package ru.oopssre.gremlinamp;
import org.json.*;import java.util.*;
final class SimilarityRules {
 interface Rows {JSONObject get(String key)throws Exception;}
 static final class Target {final String key,game,id;final int index;Target(String k,JSONObject ref){key=k;game=ref.optString("game_id");id=ref.isNull("track_id")?"":ref.optString("track_id");index=ref.optInt("index",-1);}}
 static boolean mode(String s){return s.equals("rhythm")||s.equals("sound");}
 static boolean validKey(String s){return s!=null&&s.matches("[a-fA-F0-9]{64}:[0-9]{1,5}");}
 static String key(JSONObject track){JSONObject asset=track.optJSONObject("asset");return asset==null?"":asset.optString("sha256")+":"+track.optInt("subsong",0);}
 static List<Target> targets(JSONObject source,String mode,Set<String> played,Rows rows)throws Exception{
  List<Target> result=new ArrayList<>();JSONObject modes=source.optJSONObject("neighbors");JSONArray neighbours=modes==null?null:modes.optJSONArray(mode);if(neighbours==null)return result;
  List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<Math.min(256,neighbours.length());i++){JSONObject n=neighbours.optJSONObject(i);if(n!=null&&validKey(n.optString("key"))&&Double.isFinite(n.optDouble("distance",Double.NaN))&&n.optDouble("distance")>=0)ordered.add(n);}
  ordered.sort(Comparator.comparingDouble(n->n.optDouble("distance")));Set<String> visited=new HashSet<>();
  for(JSONObject n:ordered){String key=n.optString("key");if(played.contains(key)||!visited.add(key))continue;JSONObject target=rows.get(key);JSONArray refs=target==null?null:target.optJSONArray("refs");if(refs==null)continue;for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);if(ref==null||ref.optString("game_id").isEmpty())continue;result.add(new Target(key,ref));}}
  return result;
 }
 static boolean matches(Target target,JSONObject track,int index){return target.key.equals(key(track))&&(!target.id.isEmpty()?target.id.equals(track.optString("id")):target.index==index);}
}
