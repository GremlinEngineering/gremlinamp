package ru.oopssre.gremlinamp;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class Library {
 static final String BASE="https://games.oopssre.ru/";
 final File root;final android.content.SharedPreferences preferences; volatile boolean cancel;
 interface Progress {void update(String message);}
 Library(Context c){preferences=c.getSharedPreferences("player",0);root=new File(c.getFilesDir(),"library");root.mkdirs();}
 static String resolve(String base,String path) throws IOException {
  URI u=URI.create(base).resolve(path).normalize();
  if(!"https".equals(u.getScheme())||!"games.oopssre.ru".equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null)throw new IOException("Недопустимая ссылка");
  return u.toString();
 }
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
 static String hash(byte[] b)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(b));}
 static byte[] request(String url,int limit) throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
  try {if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Слишком большой файл");out.write(b,0,n);}return out.toByteArray();}
  }finally{c.disconnect();}
 }
 static void atomic(File f,byte[] b)throws Exception {f.getParentFile().mkdirs();File tmp=new File(f+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(b);out.getFD().sync();}Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);}
 JSONObject json(String url,boolean refresh)throws Exception{
  File f=new File(root,"json/"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
  if(refresh||!f.isFile())try{byte[] b=request(url,16*1024*1024);JSONObject j=new JSONObject(new String(b,java.nio.charset.StandardCharsets.UTF_8));atomic(f,b);return j;}catch(Exception e){if(!f.isFile())throw e;}
  return new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8));
 }
 JSONObject catalog(boolean refresh)throws Exception {return json(BASE+"assets/gallery-music.json",refresh);}
 JSONObject manifest(JSONObject game,boolean refresh)throws Exception {
  String url=resolve(BASE,game.getString("manifest"));JSONObject m=json(url,refresh);m.put("_url",url);return m;
 }
 File assetFile(JSONObject asset)throws Exception {
  String h=asset.getString("sha256"),fmt=asset.getString("format");
  if(!h.matches("[0-9a-f]{64}")||!fmt.matches("[a-z0-9]{1,8}"))throw new IOException("Неверный идентификатор файла");
  return new File(root,"files/"+h+"."+fmt);
 }
 boolean available(JSONObject a)throws Exception {File f=assetFile(a);return f.isFile()&&f.length()==a.getLong("bytes");}
 static final Object FILE_LOCK=new Object();
 File fetch(JSONObject m,JSONObject a)throws Exception {synchronized(FILE_LOCK){return fetchLocked(m,a);}}
 private File fetchLocked(JSONObject m,JSONObject a)throws Exception {
  File f=assetFile(a);if(available(a))return f;
  long expected=a.getLong("bytes");if(expected<0||expected>256L*1024*1024)throw new IOException("Недопустимый размер");
  String url=resolve(m.getString("_url"),a.getString("url"));f.getParentFile().mkdirs();File part=new File(f+".part");
  long offset=part.isFile()?part.length():0;if(offset>expected){part.delete();offset=0;}
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
  if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
  try{int status=c.getResponseCode();
   if(status==200)offset=0;
   else if(status!=206||offset==0||!String.valueOf(c.getHeaderField("Content-Range")).startsWith("bytes "+offset+"-"))throw new IOException("HTTP/Range "+status);
   try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part,offset>0)){
    byte[] b=new byte[32768];int n;long size=offset;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();size+=n;if(size>expected)throw new IOException("Неверный размер");out.write(b,0,n);}out.getFD().sync();}
  }finally{c.disconnect();}
  MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(part)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
  if(part.length()!=expected||!hex(d.digest()).equals(a.getString("sha256"))){part.delete();throw new IOException("Проверка SHA-256 не пройдена");}
  Files.move(part.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);return f;
 }
 byte[] cachedDocument(String url,boolean refresh)throws Exception {
  File f=new File(root,"documents/"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".cache");
  if(refresh||!f.isFile())try{byte[] b=request(url,16*1024*1024);atomic(f,b);return b;}catch(Exception e){if(!f.isFile())throw e;}
  return Files.readAllBytes(f.toPath());
 }
 List<JSONObject> enrichGames(List<JSONObject> games)throws Exception {
  JSONArray editions=json(BASE+"assets/statistics.json",true).getJSONArray("editions");Map<String,JSONObject> byId=new HashMap<>();
  for(int i=0;i<editions.length();i++){JSONObject e=editions.getJSONObject(i);long ra=e.optLong("ra_id",0);if(ra>0)byId.put("game-ra-"+ra,e);}
  List<JSONObject> result=new ArrayList<>();for(JSONObject item:games){JSONObject g=new JSONObject(item.toString()),e=byId.get(item.getString("id"));if(e!=null){if(!e.isNull("year"))g.put("year",e.getInt("year"));g.put("terms",e.optJSONObject("terms"));}result.add(g);}return result;
 }
 JSONArray series(boolean refresh)throws Exception {
  JSONArray rows=json(BASE+"assets/statistics.json",refresh).getJSONArray("series");
  // The site already publishes membership and chronological edition order.
  for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);row.put("title",row.getString("name"));}
  return rows;
 }
 List<JSONObject> seriesGames(JSONObject series,List<JSONObject> games)throws Exception {
  Map<String,JSONObject> byId=new HashMap<>();for(JSONObject g:games)byId.put(g.getString("id"),g);
  List<JSONObject> result=new ArrayList<>();Set<String> seen=new HashSet<>();JSONArray members=series.getJSONArray("members");
  for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);long ra=member.optLong("ra_id",0);String id="game-ra-"+ra;if(ra>0&&byId.containsKey(id)&&seen.add(id))result.add(byId.get(id));}
  return result;
 }
 static android.graphics.Bitmap artwork(File f){android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(f.toString(),o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1024)o.inSampleSize*=2;o.inJustDecodeBounds=false;return android.graphics.BitmapFactory.decodeFile(f.toString(),o);}
 JSONObject pictureRecord(JSONObject item,boolean isSeries)throws Exception {
  JSONArray rows=new JSONArray(new String(cachedDocument(BASE+"assets/gallery-"+(isSeries?"series":"games")+".json",false),java.nio.charset.StandardCharsets.UTF_8));
  for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String id=isSeries?r.optString("id"):"game-"+r.optString("id");if(id.equals(item.optString("id")))return r;}return null;
 }
 File picture(JSONObject item,boolean isSeries,boolean preview)throws Exception {
  String id=item.getString("id");File legacy=new File(root,"covers/"+id+".img");if(!isSeries&&legacy.isFile())return legacy;JSONObject r=pictureRecord(item,isSeries);if(!id.matches("[a-zA-Z0-9_-]+"))throw new IOException("Неверный ID");
  File missing=new File(root,"missing");if(r==null)return missing;JSONObject views=r.optJSONObject("views"),poster=views==null?null:views.optJSONObject("poster");
  String src=poster!=null?poster.optString("src"):r.optString("src");if(preview){if(poster!=null&&!poster.optString("preview").isEmpty())src=poster.optString("preview");else if(!r.optString("preview").isEmpty())src=r.optString("preview");}
  if(src.isEmpty())return missing;String url=resolve(BASE,src);File f=new File(root,"art/"+(isSeries?"series-":"game-")+id+"-"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".img");if(!f.isFile())atomic(f,request(url,16*1024*1024));return f;
 }
 File cover(JSONObject game)throws Exception{return picture(game,false,false);}
 File measurementFile(JSONObject t)throws Exception {return new File(root,"timings/v1-"+t.getJSONObject("asset").getString("sha256")+"-"+t.optInt("subsong")+".json");}
 JSONObject timing(JSONObject t)throws Exception {File f=measurementFile(t);return f.isFile()?new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8)):null;}
 long knownDuration(JSONObject t){try{JSONObject measured=timing(t);if(measured!=null&&measured.optLong("ended_ms",-1)>=0)return measured.optLong("sound_ms",0);return t.optBoolean("duration_known")?t.optLong("play_ms",0):-1;}catch(Exception e){return -1;}}
 long displayDuration(JSONObject t){long known=knownDuration(t);if(known<0)return -1;try{JSONObject measured=timing(t);if(measured!=null&&measured.optLong("ended_ms",-1)>=0)return known;}catch(Exception ignored){}return PlaybackRules.total(known,t.optLong("fade_ms",0));}
 static final Object MEASURE_LOCK=new Object();
 void measureTrack(JSONObject t)throws Exception {synchronized(MEASURE_LOCK){measureLocked(t);}}
 private void measureLocked(JSONObject t)throws Exception {
  if(!t.optString("backend").equals("gme")||t.optBoolean("duration_known")||measurementFile(t).isFile()||!available(t.getJSONObject("asset")))return;
  long[] r=Gme.measure(assetFile(t.getJSONObject("asset")).toString(),t.optInt("subsong"),180000);
  JSONObject value=new JSONObject().put("sound_ms",r[0]).put("ended_ms",r[1]).put("scan_limit_ms",r[2]).put("method","gme-silence-rms16-22050-v1");atomic(measurementFile(t),value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 int shortThreshold(){return preferences.getInt("short_seconds",0)*1000;}
 boolean hiddenShort(JSONObject t){long d=knownDuration(t);String mode=preferences.getString("unknown_duration","show");return !PlaybackRules.include(d,shortThreshold(),mode);}
 long remaining(List<JSONObject> games)throws Exception {
  Set<String> seen=new HashSet<>();long total=0;
  for(JSONObject g:games){JSONArray ts=manifest(g,false).getJSONArray("tracks");for(int i=0;i<ts.length();i++){JSONObject a=ts.getJSONObject(i).getJSONObject("asset");if(seen.add(a.getString("sha256"))&&!available(a))total+=a.getLong("bytes");}}return total;
 }
 void download(List<JSONObject> games,Progress progress)throws Exception {
  cancel=false;int count=0;for(JSONObject g:games){JSONObject m=manifest(g,false);JSONArray ts=m.getJSONArray("tracks");
   for(int i=0;i<ts.length();i++){if(cancel)throw new InterruptedIOException("Загрузка остановлена");JSONObject a=ts.getJSONObject(i).getJSONObject("asset");progress.update(g.getString("title")+" · "+(i+1)+"/"+ts.length());fetch(m,a);if(cancel)throw new InterruptedIOException("Загрузка остановлена");progress.update("Проверяю длительность · "+g.getString("title")+" · "+(i+1));measureTrack(ts.getJSONObject(i));}
   try{cover(g);}catch(Exception ignored){}atomic(new File(root,"complete/"+g.getString("id")),new byte[]{1});count++;progress.update("Сохранено коллекций: "+count+"/"+games.size());}
  try{Set<String> selected=new HashSet<>();for(JSONObject g:games)selected.add(g.getString("id"));JSONArray ss=series(false);for(int i=0;i<ss.length();i++){if(cancel)break;JSONObject sr=ss.getJSONObject(i);boolean related=false;JSONArray members=sr.optJSONArray("members");if(members!=null)for(int k=0;k<members.length();k++)if(selected.contains("game-ra-"+members.getJSONObject(k).optLong("ra_id")))related=true;if(related){progress.update("Сохраняю постер серии · "+sr.optString("title"));try{picture(sr,true,false);}catch(Exception ignored){}}}}catch(Exception ignored){}
 }
}
