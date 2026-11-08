package ru.oopssre.gremlinamp;
import org.json.*;
import java.util.*;
final class Continuation {
 static String label(String mode){return switch(mode){case "rhythm"->"по ритму";case "sound"->"по звучанию";case "series"->"по серии";case "library"->"по всей библиотеке";case "genre"->"по жанру";case "similar"->"похожие игры";default->"остановиться";};}
 static Set<String> genres(JSONObject game){Set<String> result=new HashSet<>();JSONObject terms=game.optJSONObject("terms");JSONObject g=terms==null?null:terms.optJSONObject("genre");if(g!=null)for(java.util.Iterator<String> it=g.keys();it.hasNext();)result.add(it.next());JSONObject p=game.optJSONObject("profile_genres");if(p!=null)for(java.util.Iterator<String> it=p.keys();it.hasNext();)result.add(it.next());return result;}
 static List<JSONObject> candidates(Library l,JSONObject current,Set<String> played)throws Exception {
  return candidates(l,current,played,"");
 }
 static List<JSONObject> candidates(Library l,JSONObject current,Set<String> played,String activeSeries)throws Exception {
  String mode=l.preferences.getString("after_album","stop");if(mode.equals("stop"))return Collections.emptyList();
  JSONArray rows=l.catalog(false).getJSONArray("games");List<JSONObject> all=new ArrayList<>();for(int i=0;i<rows.length();i++)all.add(rows.getJSONObject(i));
  all=l.enrichGames(all);JSONObject seed=current;for(JSONObject g:all)if(g.optString("id").equals(current.optString("id")))seed=g;
  Map<String,Integer> seriesOrder=new LinkedHashMap<>();
  try{
   JSONArray series=l.series(false);
   for(int i=0;i<series.length();i++){
    JSONObject item=series.getJSONObject(i);
    if(mode.equals("series")&&!activeSeries.isEmpty()&&!activeSeries.equals(item.optString("id")))continue;
    List<JSONObject> members=l.seriesGames(item,all);
    for(int start=0;start<members.size();start++)if(members.get(start).optString("id").equals(current.optString("id")))
     for(int offset=1;offset<members.size();offset++){
      String id=members.get((start+offset)%members.size()).optString("id");
      seriesOrder.putIfAbsent(id,seriesOrder.size());
     }
   }
  }catch(Exception ignored){}
  Set<String> sourceGenres=genres(seed);Map<String,Integer> scores=new HashMap<>();List<JSONObject> result=new ArrayList<>();String platform=l.preferences.getString("platform_filter",""),year=l.preferences.getString("year_filter","");
  for(JSONObject g:all){String id=g.optString("id");if(played.contains(id)||id.equals(seed.optString("id")))continue;
   if(!platform.isEmpty()&&!platform.equals(g.optString("platform_label")))continue;
   if(!year.isEmpty()&&!year.equals(g.has("year")?String.valueOf(g.optInt("year")):"Не указан"))continue;
   if(l.continueLocal()&&!l.fullyLocal(g))continue;
   int score=ContinuationRules.score(mode,seriesOrder.containsKey(id),sourceGenres,genres(g),g.optString("platform").equals(seed.optString("platform")));
   if(score>0){scores.put(id,score);result.add(g);}
  }
  result.sort((a,b)->{
   String aid=a.optString("id"),bid=b.optString("id");
   if(mode.equals("series"))return Integer.compare(seriesOrder.get(aid),seriesOrder.get(bid));
   int cmp=Integer.compare(scores.get(bid),scores.get(aid));
   if(cmp!=0)return cmp;
   cmp=Integer.compare(a.optInt("year",9999),b.optInt("year",9999));
   if(cmp!=0)return cmp;
   cmp=a.optString("title").compareToIgnoreCase(b.optString("title"));
   return cmp!=0?cmp:aid.compareTo(bid);
  });
  return result;
 }
}
