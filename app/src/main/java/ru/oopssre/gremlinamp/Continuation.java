package ru.oopssre.gremlinamp;
import org.json.*;
import java.util.*;
final class Continuation {
 record Choice(JSONObject game,String seriesId,String seriesTitle) {}
 static String label(String mode){return switch(mode){case "rhythm"->"по ритму";case "sound"->"по звучанию";case "series"->"по серии";case "library"->"по всей библиотеке";case "genre"->"по жанру";case "similar"->"похожие игры";default->"остановиться";};}
 static Set<String> genres(JSONObject game){Set<String> result=new HashSet<>();JSONObject terms=game.optJSONObject("terms");JSONObject g=terms==null?null:terms.optJSONObject("genre");if(g!=null)for(java.util.Iterator<String> it=g.keys();it.hasNext();)result.add(it.next());JSONObject p=game.optJSONObject("profile_genres");if(p!=null)for(java.util.Iterator<String> it=p.keys();it.hasNext();)result.add(it.next());return result;}
 static List<JSONObject> candidates(Library l,JSONObject current,Set<String> played)throws Exception {
  return candidates(l,current,played,"");
 }
 static List<JSONObject> candidates(Library l,JSONObject current,Set<String> played,String activeSeries)throws Exception {
  List<JSONObject> result=new ArrayList<>();
  for(Choice choice:choices(l,current,played,activeSeries,l.preferences.getString("after_album","stop")))result.add(choice.game());
  return result;
 }
 static List<Choice> choices(Library l,JSONObject current,Set<String> played,String activeSeries,String mode)throws Exception {
  if(mode.equals("stop"))return Collections.emptyList();
  JSONObject catalog=l.catalog(false);
  JSONArray rows=catalog.getJSONArray("games");List<JSONObject> all=new ArrayList<>();for(int i=0;i<rows.length();i++)all.add(rows.getJSONObject(i));
  String platform=l.preferences.getString("platform_filter",""),year=l.preferences.getString("year_filter","");
  if(mode.equals("genre")||mode.equals("similar")||!platform.isEmpty()||!year.isEmpty())all=l.enrichGames(all);
  JSONObject seed=current;for(JSONObject g:all)if(g.optString("id").equals(current.optString("id")))seed=g;
  Map<String,Integer> seriesOrder=new LinkedHashMap<>();
  Map<String,JSONObject> seriesForGame=new HashMap<>();
  if(mode.equals("series")||mode.equals("similar")){
   JSONArray series=catalog.getJSONArray("series");List<JSONObject> groups=new ArrayList<>();
   for(int i=0;i<series.length();i++)groups.add(series.getJSONObject(i));
   groups.sort(Comparator.comparing(item->item.optString("id")));
   for(JSONObject item:groups){
    if(mode.equals("series")&&!activeSeries.isEmpty()&&!activeSeries.equals(item.optString("id")))continue;
    List<JSONObject> members=l.seriesGames(item,all);
    for(int start=0;start<members.size();start++)if(members.get(start).optString("id").equals(current.optString("id")))
     for(int offset=1;offset<members.size();offset++){
      String id=members.get((start+offset)%members.size()).optString("id");
      seriesOrder.putIfAbsent(id,seriesOrder.size());
      seriesForGame.putIfAbsent(id,item);
     }
   }
  }
  Set<String> sourceGenres=genres(seed);Map<String,Integer> scores=new HashMap<>();List<JSONObject> result=new ArrayList<>();
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
  List<Choice> choices=new ArrayList<>();
  for(JSONObject game:result){
   JSONObject group=mode.equals("series")?seriesForGame.get(game.optString("id")):null;
   choices.add(new Choice(game,group==null?"":group.optString("id"),group==null?"":group.optString("title")));
  }
  return choices;
 }
}
