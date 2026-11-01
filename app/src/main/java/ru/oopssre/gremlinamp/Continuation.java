package ru.oopssre.gremlinamp;
import org.json.*;
import java.util.*;
final class Continuation {
 static String label(String mode){return switch(mode){case "rhythm"->"по ритму";case "sound"->"по звучанию";case "series"->"по серии";case "genre"->"по жанру";case "similar"->"похожие игры";default->"остановиться";};}
 static Set<String> genres(JSONObject game){Set<String> result=new HashSet<>();JSONObject terms=game.optJSONObject("terms");JSONObject g=terms==null?null:terms.optJSONObject("genre");if(g!=null)for(java.util.Iterator<String> it=g.keys();it.hasNext();)result.add(it.next());JSONObject p=game.optJSONObject("profile_genres");if(p!=null)for(java.util.Iterator<String> it=p.keys();it.hasNext();)result.add(it.next());return result;}
 static List<JSONObject> candidates(Library l,JSONObject current,Set<String> played)throws Exception {
  String mode=l.preferences.getString("after_album","stop");if(mode.equals("stop"))return Collections.emptyList();
  JSONArray rows=l.catalog(false).getJSONArray("games");List<JSONObject> all=new ArrayList<>();for(int i=0;i<rows.length();i++)all.add(rows.getJSONObject(i));
  all=l.enrichGames(all);JSONObject seed=current;for(JSONObject g:all)if(g.optString("id").equals(current.optString("id")))seed=g;
  Set<String> sameSeries=new HashSet<>();try{JSONArray ss=l.series(false);for(int i=0;i<ss.length();i++){List<JSONObject> members=l.seriesGames(ss.getJSONObject(i),all);if(members.stream().anyMatch(g->g.optString("id").equals(current.optString("id"))))for(JSONObject g:members)sameSeries.add(g.optString("id"));}}catch(Exception ignored){}
  Set<String> sourceGenres=genres(seed);Map<String,Integer> scores=new HashMap<>();List<JSONObject> result=new ArrayList<>();String platform=l.preferences.getString("platform_filter",""),year=l.preferences.getString("year_filter","");
  for(JSONObject g:all){String id=g.optString("id");if(played.contains(id)||id.equals(seed.optString("id")))continue;
   if(!platform.isEmpty()&&!platform.equals(g.optString("platform_label")))continue;
   if(!year.isEmpty()&&!year.equals(g.has("year")?String.valueOf(g.optInt("year")):"Не указан"))continue;
   if(l.preferences.getBoolean("continue_local",true)&&!l.fullyLocal(g))continue;
   int score=ContinuationRules.score(mode,sameSeries.contains(id),sourceGenres,genres(g),g.optString("platform").equals(seed.optString("platform")));
   if(score>0){scores.put(id,score);result.add(g);}
  }
  final int currentYear=seed.optInt("year",0);
  result.sort((a,b)->{int cmp=Integer.compare(scores.get(b.optString("id")),scores.get(a.optString("id")));if(cmp!=0)return cmp;int ay=a.optInt("year",9999),by=b.optInt("year",9999);if(mode.equals("series")){int ar=ay>=currentYear?0:1,br=by>=currentYear?0:1;if(ar!=br)return Integer.compare(ar,br);}cmp=Integer.compare(ay,by);return cmp!=0?cmp:a.optString("title").compareToIgnoreCase(b.optString("title"));});return result;
 }
}
