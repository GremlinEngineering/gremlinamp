package ru.oopssre.gremlinamp;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

class Library {
 static final String BASE="https://games.oopssre.ru/";
 final Context context;final boolean streaming;final File root;final MusicArchive archive;final android.content.SharedPreferences preferences; volatile boolean cancel;
 volatile String downloadSummary="";
 private final Set<HttpURLConnection> transfers=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private final Map<String,Map<String,JSONObject>> pictureIndexes=new HashMap<>();
 private long pictureRevision=-1;
 static final java.util.concurrent.atomic.AtomicLong ART_REVISION=new java.util.concurrent.atomic.AtomicLong();
 interface Progress {void update(String message);}
 Library(Context c){this(c,false);}
 Library(Context c,boolean streaming){
  this.streaming=streaming;context=c.getApplicationContext();preferences=c.getSharedPreferences("player",0);
  root=new File(c.getFilesDir(),"library");if(!streaming)root.mkdirs();archive=new MusicArchive(this);
 }
 static String resolve(String base,String path) throws IOException {
  URI u=URI.create(base).resolve(path).normalize();
  if(!"https".equals(u.getScheme())||!"games.oopssre.ru".equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null)throw new IOException("Недопустимая ссылка");
  return u.toString();
 }
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
 static String hash(byte[] b)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(b));}
 static byte[] request(String url,int limit) throws Exception {
  HttpURLConnection c=NetworkPolicy.openHttp(new URL(url));c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
  try {if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Слишком большой файл");out.write(b,0,n);}return out.toByteArray();}
  }finally{c.disconnect();}
 }
 static void atomic(File f,byte[] b)throws Exception {
  f.getParentFile().mkdirs();
  File tmp=File.createTempFile("write-",".tmp",f.getParentFile());
  try{
   try(FileOutputStream out=new FileOutputStream(tmp)){out.write(b);out.getFD().sync();}
   Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(tmp.toPath());}
 }
 static JSONObject readObject(File f)throws Exception{return new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8));}
 static String hashFile(File file)throws Exception{return hashFile(file,null);}
 static String hashFile(File file,Library owner)throws Exception{
  MessageDigest hash=MessageDigest.getInstance("SHA-256");
  try(InputStream in=new FileInputStream(file)){
   byte[] buffer=new byte[64*1024];int n;
   while((n=in.read(buffer))!=-1){if(owner!=null)owner.checkCancelled();hash.update(buffer,0,n);}
  }
  return hex(hash.digest());
 }
 JSONObject json(String url,boolean refresh)throws Exception{
  File f=new File(root,"json/"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
  if(refresh||!f.isFile())try{requireNetwork();byte[] b=request(url,16*1024*1024);JSONObject j=new JSONObject(new String(b,java.nio.charset.StandardCharsets.UTF_8));atomic(f,b);return j;}catch(Exception e){if(!f.isFile())throw e;}
  return new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8));
 }
 JSONObject onlineJson(String url)throws Exception{
  requireNetwork();
  return new JSONObject(new String(request(url,16*1024*1024),java.nio.charset.StandardCharsets.UTF_8));
 }
 JSONObject index(boolean refresh,boolean strict)throws Exception {
  File cache=jsonFile(MusicContract.INDEX);
  Exception failure=null;
  if(!refresh){
   JSONObject offline=archive.index();
   if(offline!=null&&!networkAllowed())return offline;
  }
  if(refresh||!cache.isFile())try{
   JSONObject index=onlineJson(MusicContract.INDEX);
   MusicContract.catalog(index);
   atomic(cache,index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
   return index;
  }catch(Exception e){if(strict)throw e;failure=e;}
  if(cache.isFile())try{
   JSONObject index=readObject(cache);MusicContract.catalog(index);return index;
  }catch(Exception e){failure=e;}
  JSONObject offline=archive.index();
  if(offline!=null)return offline;
  throw new IOException("Музыкальный каталог пока недоступен",failure);
 }
 JSONObject catalog(boolean refresh)throws Exception{
  if(!streaming)return MusicContract.catalog(index(refresh,false));
  try{return MusicContract.catalog(onlineJson(MusicContract.INDEX));}
  catch(IOException e){if(!"HTTP 404".equals(e.getMessage()))throw e;}
  JSONObject gallery=onlineJson(BASE+"assets/gallery-music.json");
  JSONArray series=new JSONArray();
  try{series=onlineJson(BASE+"assets/statistics.json").getJSONArray("series");}catch(Exception ignored){}
  return MusicContract.streamingGallery(gallery,series);
 }
 JSONObject manifest(JSONObject game,boolean refresh)throws Exception {
  String url=resolve(BASE,game.getString("manifest"));
  if(streaming){
   JSONObject manifest=onlineJson(url);
   if(manifest.optInt("schema_version")!=1)throw new IOException("Версия музыкального альбома не поддерживается");
   return manifest.put("_url",url);
  }
  JSONObject packaged=archive.playlist(url);
  if(packaged!=null)return packaged;
  return json(url,refresh).put("_url",url);
 }
 MusicArchive.Receipt archiveReceipt()throws Exception{return new MusicArchive.Receipt(onlineJson(MusicContract.RECEIPT));}
 void downloadArchive(Progress progress)throws Exception{
  DOWNLOAD_LOCK.lockInterruptibly();
  try{archive.update(progress);}finally{DOWNLOAD_LOCK.unlock();}
 }
 HttpURLConnection openArchiveConnection(MusicArchive.Receipt receipt)throws IOException {
  return NetworkPolicy.openHttp(new URL(receipt.url));
 }
 void transferArchive(MusicArchive.Receipt receipt,File zip,Progress progress)throws Exception{
  checkCancelled();
  requireNetwork();
  long offset=zip.length();
  if(receipt.bytes-offset>zip.getParentFile().getUsableSpace())throw new IOException("Недостаточно места для ZIP");
  File validator=MusicArchive.validator(zip);
  String previous=offset>0&&validator.isFile()?new String(Files.readAllBytes(validator.toPath()),java.nio.charset.StandardCharsets.US_ASCII):"";
  HttpURLConnection connection=openArchiveConnection(receipt);
  connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
  connection.setRequestProperty("Accept-Encoding","identity");
  if(offset>0){
   connection.setRequestProperty("Range","bytes="+offset+"-");
   if(strongEtag(previous))connection.setRequestProperty("If-Range",previous);
  }
  transfers.add(connection);
  try{
   int status=connection.getResponseCode();
   String etag=connection.getHeaderField("ETag");
   long end=receipt.bytes;
   if(status==200){
    if(offset>0&&strongEtag(previous)&&strongEtag(etag)&&!previous.equals(etag))throw new MusicArchive.Integrity();
    offset=0;
   }
   else if(status==206&&offset>0){
    end=archiveRangeEnd(connection.getHeaderField("Content-Range"),offset,receipt.bytes);
    if(strongEtag(previous)&&etag!=null&&!previous.equals(etag))throw new MusicArchive.Integrity();
   }else if(status==416)throw new MusicArchive.Integrity();
   else throw new IOException("HTTP "+status);
   long declared=connection.getContentLengthLong();
   if(declared>=0&&declared!=end-offset)throw new MusicArchive.Integrity();
   checkCancelled();
   try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(zip,offset>0)){
    if(strongEtag(etag))atomic(validator,etag.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    else if(offset==0)Files.deleteIfExists(validator.toPath());
    byte[] buffer=new byte[64*1024];int n;long count=offset,last=offset;
    progress.update((offset>0?"Докачиваю ZIP · ":"Скачиваю ZIP · ")+archivePercent(count,receipt.bytes));
    while((n=in.read(buffer))!=-1){
     checkCancelled();requireNetwork();
     if(n>end-count)throw new MusicArchive.Integrity();
     out.write(buffer,0,n);
     count+=n;
     if(count-last>=4*1024*1024){last=count;progress.update("Скачиваю ZIP · "+archivePercent(count,receipt.bytes));}
    }
    out.getFD().sync();
    if(count!=receipt.bytes)throw new IOException("ZIP получен не полностью · "+archivePercent(count,receipt.bytes));
   }
  }finally{transfers.remove(connection);connection.disconnect();}
 }
 private static String archivePercent(long count,long total){return (int)(100.0*count/total)+"%";}
 private static boolean strongEtag(String value){return value!=null&&value.matches("\"[^\\p{Cntrl}\"]*\"");}
 private static long archiveRangeEnd(String value,long offset,long total)throws IOException{
  java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(value==null?"":value);
  if(range.matches())try{
   long start=Long.parseLong(range.group(1)),end=Long.parseLong(range.group(2)),size=Long.parseLong(range.group(3));
   if(start==offset&&size==total&&end>=start&&end<total)return end+1;
  }catch(NumberFormatException ignored){}
  throw new MusicArchive.Integrity();
 }
 File assetFile(JSONObject asset)throws Exception {
  String h=asset.getString("sha256"),fmt=asset.getString("format");
  if(!h.matches("[0-9a-f]{64}")||!fmt.matches("[a-z0-9]{1,16}(?:-[a-z0-9]{1,16})?"))throw new IOException("Неверный идентификатор файла");
  return new File(root,"v1-files/"+h+"."+fmt);
 }
 boolean available(JSONObject a)throws Exception {File f=assetFile(a);return f.isFile()&&f.length()==a.getLong("bytes");}
 boolean available(JSONObject manifest,JSONObject asset)throws Exception {
  return available(asset)||archive.contains(manifest,asset);
 }
 JSONObject playbackAsset(JSONObject track) throws Exception {
  JSONObject render=MusicContract.decoder(track).equals("fallback")?MusicContract.wav(track):null;
  return render==null?track.getJSONObject("asset"):new JSONObject(render.toString()).put("format","wav");
 }
 boolean playableLocal(JSONObject manifest,JSONObject track)throws Exception{
  String decoder=MusicContract.decoder(track);
  if(decoder.isEmpty())return false;
  if(MusicContract.decoderNeedsInputs(decoder))return savedTrack(manifest,track);
  return available(manifest,playbackAsset(track));
 }
 List<JSONObject> assets(JSONObject track)throws Exception{
  JSONObject original=track.getJSONObject("asset");
  List<JSONObject> assets=new ArrayList<>();assets.add(original);
  JSONArray dependencies=original.optJSONArray("dependencies");
  if(dependencies!=null)for(int i=0;i<dependencies.length();i++){
   JSONObject dependency=new JSONObject(dependencies.getJSONObject(i).toString());
   String path=MusicContract.safePath(dependency.getString("path"));
   if(!dependency.has("format")){int dot=path.lastIndexOf('.');dependency.put("format",dot<0?"bin":path.substring(dot+1).toLowerCase(Locale.ROOT));}
   assets.add(dependency);
  }
  if(MusicContract.wav(track)!=null)assets.add(playbackAsset(track));
  return assets;
 }
 JSONObject decoderInput(JSONObject manifest,JSONObject track,List<File> files)throws Exception{
  return decoderInput(manifest,track,asset->files.add(fetch(manifest,asset)));
 }
 private interface AssetReader {void read(JSONObject asset)throws Exception;}
 private JSONObject decoderInput(JSONObject manifest,JSONObject track,AssetReader reader)throws Exception{
  JSONArray paths=new JSONArray();
  List<JSONObject> inputs=assets(track);
  Set<String> names=new HashSet<>();
  for(int i=0;i<inputs.size();i++){
   JSONObject asset=inputs.get(i);
   String path=asset.optString("path");
   if(i==0&&path.isEmpty()){
    String url=resolve(manifest.getString("_url"),asset.getString("url"));
    String decoded=URI.create(url).getPath();
    path=track.optString("original_filename",decoded.substring(decoded.lastIndexOf('/')+1));
   }
   path=MusicContract.safePath(path);
   if(!names.add(path))throw new IOException("Повторяющийся путь библиотеки декодера");
   paths.put(path);
   reader.read(asset);
  }
  return new JSONObject().put("paths",paths).put("track",track).put("rate",44100)
   .put("renderMs",PlaybackRules.total(track.optLong("play_ms",120000),track.optLong("fade_ms",0)));
 }
 record StreamInputs(List<byte[]> buffers,JSONObject descriptor) {}
 StreamInputs streamInputs(JSONObject manifest,JSONObject track,String decoder,StreamCache.Track lease)throws Exception{
  if(MusicContract.decoderNeedsInputs(decoder)){
   List<byte[]> buffers=new ArrayList<>();
   JSONObject descriptor=decoderInput(manifest,track,asset->buffers.add(bufferedAsset(manifest,asset,lease)));
   return new StreamInputs(buffers,descriptor);
  }
  return new StreamInputs(Collections.singletonList(bufferedAsset(manifest,playbackAsset(track),lease)),null);
 }
 private byte[] bufferedAsset(JSONObject manifest,JSONObject asset,StreamCache.Track lease)throws Exception{
  checkCancelled();
  return lease.get(asset.getString("sha256"),asset.getLong("bytes"),()->streamAsset(manifest,asset));
 }
 private byte[] streamAsset(JSONObject manifest,JSONObject asset)throws Exception{
  requireNetwork();checkCancelled();
  long size=asset.getLong("bytes");
  String expected=asset.getString("sha256");
  if(size<=0||size>StreamCache.LIMIT||!expected.matches("[a-f0-9]{64}"))throw new IOException("Трек превышает лимит буфера ТВ или повреждён");
  String url=resolve(manifest.getString("_url"),asset.getString("url"));
  HttpURLConnection connection=NetworkPolicy.openHttp(new URL(url));
  connection.setConnectTimeout(12000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
  transfers.add(connection);
  try{
   checkCancelled();
   int status=connection.getResponseCode();
   if(status!=200)throw new IOException("HTTP "+status);
   byte[] data=new byte[(int) size];
   try(InputStream input=connection.getInputStream()){
    int offset=0;
    while(offset<data.length){
     checkCancelled();int n=input.read(data,offset,Math.min(32768,data.length-offset));
     if(n<0)throw new EOFException("Трек передан не полностью");
     offset+=n;
    }
    if(input.read()!=-1)throw new IOException("Неверный размер трека");
   }
   checkCancelled();
   if(!hash(data).equals(expected))throw new IOException("Проверка SHA-256 не пройдена");
   return data;
  }finally{transfers.remove(connection);connection.disconnect();}
 }
 boolean savedTrack(JSONObject manifest,JSONObject track)throws Exception{
  for(JSONObject asset:assets(track))if(!available(manifest,asset))return false;
  return true;
 }
 private static final Map<String,java.util.concurrent.locks.ReentrantLock> FILE_LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
 File fetch(JSONObject m,JSONObject a)throws Exception {
  if(available(a))return assetFile(a);
  java.util.concurrent.locks.ReentrantLock lock=FILE_LOCKS.computeIfAbsent(a.getString("sha256")+"."+a.getString("format"),k->new java.util.concurrent.locks.ReentrantLock());
  lock.lockInterruptibly();try{return fetchLocked(m,a);}finally{lock.unlock();}
 }
 void cancelTransfers(){for(HttpURLConnection connection:transfers)connection.disconnect();}
 private File fetchLocked(JSONObject m,JSONObject a)throws Exception {
  File f=assetFile(a);if(available(a))return f;
  File packaged=archive.cacheAsset(m,a,f);if(packaged!=null)return packaged;
  requireNetwork();long expected=a.getLong("bytes");if(expected<0||expected>256L*1024*1024)throw new IOException("Недопустимый размер");
  String url=resolve(m.getString("_url"),a.getString("url"));f.getParentFile().mkdirs();File part=new File(f+".part");
  long offset=part.isFile()?part.length():0;if(offset>expected){part.delete();offset=0;}
  HttpURLConnection c=NetworkPolicy.openHttp(new URL(url));c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);transfers.add(c);
  if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
  try{int status=c.getResponseCode();
   if(status==200)offset=0;
   else if(status!=206||offset==0||!String.valueOf(c.getHeaderField("Content-Range")).startsWith("bytes "+offset+"-"))throw new IOException("HTTP/Range "+status);
   try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part,offset>0)){
    byte[] b=new byte[32768];int n;long size=offset;while((n=in.read(b))!=-1){if(cancel||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Загрузка остановлена");requireNetwork();size+=n;if(size>expected)throw new IOException("Неверный размер");out.write(b,0,n);}out.getFD().sync();}
  }finally{transfers.remove(c);c.disconnect();}
  MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(part)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
  if(part.length()!=expected||!hex(d.digest()).equals(a.getString("sha256"))){part.delete();throw new IOException("Проверка SHA-256 не пройдена");}
  Files.move(part.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);return f;
 }
 byte[] cachedDocument(String url,boolean refresh)throws Exception {
  File f=new File(root,"documents/"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".cache");
  if(refresh||!f.isFile())try{requireNetwork();byte[] b=request(url,16*1024*1024);atomic(f,b);return b;}catch(Exception e){if(!f.isFile())throw e;}
  return Files.readAllBytes(f.toPath());
 }
 JSONArray trackSearch(){
  JSONArray result=new JSONArray();
  try{
   JSONObject gallery=json(BASE+"assets/gallery-music.json",false);
   Map<String,String> manifests=new HashMap<>();
   JSONArray games=gallery.getJSONArray("games");
   for(int i=0;i<games.length();i++){
    JSONObject game=games.getJSONObject(i);
    if(!game.optString("category").equals("theme"))
     manifests.put(game.getString("id"),resolve(BASE,game.getString("manifest")));
   }
   JSONArray tracks=gallery.getJSONArray("tracks");
   for(int i=0;i<tracks.length();i++){
    JSONObject row=tracks.getJSONObject(i);String manifest=manifests.get(row.optString("game_id"));
    if(manifest!=null)result.put(row.put("_manifest",manifest));
   }
  }catch(Exception ignored){}
  return result;
 }
 List<JSONObject> enrichGames(List<JSONObject> games)throws Exception {
  Map<String,JSONObject> gallery=new HashMap<>();
  try{
   JSONArray rows=json(BASE+"assets/gallery-music.json",false).getJSONArray("games");
   for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);gallery.put(row.getString("id"),row);}
  }catch(Exception ignored){}
  Map<Long,List<JSONObject>> byRa=new HashMap<>();Map<String,JSONObject> byHref=new HashMap<>();
  try{
   JSONArray editions=json(BASE+"assets/statistics.json",false).getJSONArray("editions");
   for(int i=0;i<editions.length();i++){
    JSONObject e=editions.getJSONObject(i);long ra=e.optLong("ra_id",0);
    if(ra>0)byRa.computeIfAbsent(ra,key->new ArrayList<>()).add(e);
    if(!e.optString("href").isEmpty())byHref.put(e.optString("href"),e);
   }
  }catch(Exception ignored){}
  List<JSONObject> result=new ArrayList<>();
  for(JSONObject item:games){
   JSONObject g=new JSONObject(item.toString()),meta=gallery.get(g.getString("id"));
   if(meta!=null)for(String key:new String[]{"href","platform_label"})if(meta.has(key))g.put(key,meta.get(key));
   JSONObject e=byHref.get(g.optString("href"));
   JSONArray ids=g.optJSONArray("ra_ids");
   if(e==null&&ids!=null)for(int i=0;i<ids.length()&&e==null;i++){
    List<JSONObject> versions=byRa.get(ids.getLong(i));
    if(versions!=null&&versions.size()==1)e=versions.get(0);
   }
   if(e!=null){if(!e.isNull("year"))g.put("year",e.getInt("year"));g.put("terms",e.optJSONObject("terms"));g.put("profile_genres",e.optJSONObject("profile_genres"));}
   result.add(g);
  }
  return result;
 }
 boolean continueLocal(){return !streaming&&preferences.getBoolean("continue_local",true);}
 JSONArray series(boolean refresh)throws Exception{return catalog(refresh).getJSONArray("series");}
 List<JSONObject> seriesGames(JSONObject series,List<JSONObject> games)throws Exception {
  Map<String,JSONObject> byId=new HashMap<>();for(JSONObject g:games)byId.put(g.getString("id"),g);
  List<JSONObject> result=new ArrayList<>();JSONArray ids=series.getJSONArray("game_ids");
  for(int i=0;i<ids.length();i++){JSONObject game=byId.get(ids.getString(i));if(game!=null)result.add(game);}
  return result;
 }
 static android.graphics.Bitmap artwork(File f){android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(f.toString(),o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1024)o.inSampleSize*=2;o.inJustDecodeBounds=false;return android.graphics.BitmapFactory.decodeFile(f.toString(),o);}
 synchronized JSONObject pictureRecord(JSONObject item,boolean isSeries)throws Exception {
  if(pictureRevision!=ART_REVISION.get()){pictureIndexes.clear();pictureRevision=ART_REVISION.get();}
  String kind=isSeries?"series":"games";Map<String,JSONObject> index=pictureIndexes.get(kind);
  if(index==null){
   JSONArray rows=new JSONArray(new String(cachedDocument(BASE+"assets/gallery-"+kind+".json",false),java.nio.charset.StandardCharsets.UTF_8));index=new HashMap<>();
   for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String id=r.optString("id");index.put("id:"+(isSeries?id:"game-"+id),r);if(!r.optString("href").isEmpty())index.put("href:"+r.optString("href"),r);if(!isSeries&&id.matches("(?:series-|want-|recommend-)?ra-[0-9]+"))index.putIfAbsent("ra:"+id.substring(id.lastIndexOf("ra-")+3),r);}
   pictureIndexes.put(kind,index);
  }
  JSONObject record=index.get("href:"+item.optString("href"));if(record==null)record=index.get("id:"+item.optString("id"));
  if(record==null&&!isSeries){
   JSONArray ids=item.optJSONArray("ra_ids");
   if(ids!=null)for(int i=0;i<ids.length()&&record==null;i++)record=index.get("ra:"+ids.optLong(i));
   else if(item.optString("id").matches("game-ra-[0-9]+"))record=index.get("ra:"+item.optString("id").substring(8));
  }
  return record;
 }
 String pictureSource(JSONObject record,boolean preview){
  JSONObject views=record.optJSONObject("views"),poster=views==null?null:views.optJSONObject("poster");
  if(preview){if(poster!=null&&!poster.optString("preview").isEmpty())return poster.optString("preview");if(!record.optString("preview").isEmpty())return record.optString("preview");}
  return poster!=null?poster.optString("src"):record.optString("src");
 }
 File pictureFile(JSONObject item,boolean isSeries,String source)throws Exception {
  String id=item.getString("id");if(!id.matches("[a-zA-Z0-9_-]+"))throw new IOException("Неверный ID");
  return new File(root,"art/"+(isSeries?"series-":"game-")+id+"-"+hash(resolve(BASE,source).getBytes(java.nio.charset.StandardCharsets.UTF_8))+".img");
 }
 File cachedPicture(JSONObject item,boolean isSeries,boolean preview)throws Exception {
  String id=item.getString("id");if(!id.matches("[a-zA-Z0-9_-]+"))throw new IOException("Неверный ID");
  File legacy=new File(root,"covers/"+id+".img");if(!isSeries&&legacy.isFile())return legacy;
  // Reading artwork already on disk must never initiate a metadata request.
  File document=documentFile(BASE+"assets/gallery-"+(isSeries?"series":"games")+".json");if(!document.isFile())return new File(root,"missing");
  JSONObject record=pictureRecord(item,isSeries);if(record==null)return new File(root,"missing");
  for(boolean small:new boolean[]{preview,!preview}){String source=pictureSource(record,small);if(!source.isEmpty()){File f=pictureFile(item,isSeries,source);if(f.isFile()&&f.length()>0)return f;}}
  return new File(root,"missing");
 }
 File picture(JSONObject item,boolean isSeries,boolean preview)throws Exception {
  File cached=cachedPicture(item,isSeries,preview);if(cached.isFile()&&(preview||!networkAllowed()))return cached;
  JSONObject record=pictureRecord(item,isSeries);if(record==null)return new File(root,"missing");String source=pictureSource(record,preview);if(source.isEmpty())return new File(root,"missing");
  File f=pictureFile(item,isSeries,source);if(!f.isFile()||f.length()==0){requireNetwork();atomic(f,request(resolve(BASE,source),16*1024*1024));}return f;
 }
 File documentFile(String url)throws Exception{return new File(root,"documents/"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".cache");}
 File cover(JSONObject game)throws Exception{return picture(game,false,false);}
 File measurementFile(JSONObject t)throws Exception {return new File(root,"timings/v1-"+t.getJSONObject("asset").getString("sha256")+"-"+t.optInt("subsong")+".json");}
 JSONObject timing(JSONObject t)throws Exception {File f=measurementFile(t);return f.isFile()?new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8)):null;}
 private File decoderDurationFile(JSONObject track)throws Exception{
  return new File(root,"timings/native-v1-"+decoderDurationKey(track)+".json");
 }
 private String decoderDurationKey(JSONObject track)throws Exception{
  String sha=playbackAsset(track).getString("sha256");
  if(!sha.matches("[0-9a-f]{64}"))throw new IOException("Некорректный отпечаток аудио");
  return sha+"-"+track.optInt("subsong")+"-"+vgmPreroll(track);
 }
 private record StreamDuration(long millis,boolean observedEnd){}
 private static final Map<String,StreamDuration> streamDurations=new LinkedHashMap<>(){
  @Override protected boolean removeEldestEntry(Map.Entry<String,StreamDuration> eldest){return size()>4096;}
 };
 static final java.util.concurrent.atomic.AtomicLong STREAM_TIMING_REVISION=new java.util.concurrent.atomic.AtomicLong();
 private StreamDuration streamDuration(JSONObject track){
  try{synchronized(streamDurations){return streamDurations.get(decoderDurationKey(track));}}
  catch(Exception ignored){return null;}
 }
 void recordDecoderDuration(JSONObject track,long duration){recordDecoderDuration(track,duration,false);}
 void recordDecoderDuration(JSONObject track,long duration,boolean observedEnd){
  if(duration<=0)return;
  try{
   if(streaming){
    String key=decoderDurationKey(track);
    StreamDuration value=new StreamDuration(duration,observedEnd);
    synchronized(streamDurations){
     if(!value.equals(streamDurations.put(key,value)))STREAM_TIMING_REVISION.incrementAndGet();
    }
    return;
   }
   File file=decoderDurationFile(track);
   if(!file.isFile()){
    atomic(file,new JSONObject().put("duration_ms",duration).put("observed_end",observedEnd).toString()
      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    revision.incrementAndGet();
   }
  }catch(Exception ignored){}
 }
 private long decodedDuration(JSONObject track){
  JSONObject result=decoderDuration(track);
  return result==null?-1:result.optLong("duration_ms",-1);
 }
 private JSONObject decoderDuration(JSONObject track){
  if(streaming)return null;
  try{File file=decoderDurationFile(track);return file.isFile()?readObject(file):null;}
  catch(Exception ignored){return null;}
 }
 long knownDuration(JSONObject t){
  if(streaming)return displayDuration(t);
  long decoded=decodedDuration(t);if(decoded>=0)return decoded;
  if(!streaming){
   JSONArray frames=fileTiming(t);
   if(frames!=null)return fileDuration(t,frames);
  }
  try{JSONObject measured=timing(t);if(measured!=null&&measured.optLong("ended_ms",-1)>=0)return measured.optLong("sound_ms",0);return t.optBoolean("duration_known")?t.optLong("play_ms",0):-1;}catch(Exception e){return -1;}
 }
 long displayDuration(JSONObject t){
  if(streaming){
   StreamDuration decoded=streamDuration(t);
   return decoded!=null?decoded.millis():playbackEnd(t,-1);
  }
  long decoded=decodedDuration(t);
  if(decoded>0)return decoded;
  long measured=measuredEnd(t);
  return playbackEnd(t,measured);
 }
 long playbackEnd(JSONObject track,long measured){
  JSONArray frames=fileTiming(track);
  long limit=frames!=null?fileDuration(track,frames):PlaybackRules.playbackMs(track);
  return measured>0?Math.min(limit,measured):limit;
 }
 long measuredEnd(JSONObject t){
  if(streaming){
   StreamDuration value=streamDuration(t);
   return value!=null&&value.observedEnd()?value.millis():-1;
  }
  try{
   JSONObject cached=decoderDuration(t);
   if(cached!=null&&cached.optBoolean("observed_end"))return cached.optLong("duration_ms",-1);
   JSONObject measured=timing(t);return measured==null?-1:measured.optLong("ended_ms",-1);
  }
  catch(Exception ignored){return -1;}
 }
 private long fileDuration(JSONObject track,JSONArray timing){
  long preroll=MusicContract.decoder(track).equals("vgm")?vgmPreroll(track):0;
  long samples=timing.optLong(0)-preroll;
  return timing.optLong(2)==0&&samples>0?PlaybackRules.frameDuration(samples,timing.optInt(1)):PlaybackRules.playbackMs(track);
 }
 boolean looping(JSONObject track){JSONArray frames=fileTiming(track);return frames!=null&&frames.optLong(2)>0;}
 static int vgmPreroll(JSONObject track){
  JSONObject playback=track.optJSONObject("playback");
  return Math.max(0,track.optInt("native_preroll_vgm_samples",playback==null?0:playback.optInt("native_preroll_vgm_samples")));
 }
 private JSONArray fileTiming(JSONObject track){
  try{
   JSONObject asset=playbackAsset(track);
   String format=asset.optString("format");
   boolean vgm=format.equals("vgm")||format.equals("vgz");
   if(!vgm&&!format.equals("wav"))return null;
   JSONArray timing=asset.optJSONArray("file_timing");
   if(timing==null)timing=bundledTimings().optJSONArray(asset.optString("sha256"));
   if(timing==null||timing.length()!=3)return null;
   long samples=timing.optLong(0,-1),loop=timing.optLong(2,-1);
   int rate=timing.optInt(1);
   return samples>0&&samples<0xffffffffL&&rate>=8000&&rate<=192000&&loop>=0&&loop<=samples
    &&(vgm?rate==44100:loop==0)?timing:null;
  }catch(Exception ignored){return null;}
 }
 private static JSONObject bundledTimings;
 private JSONObject bundledTimings(){
  synchronized(Library.class){
   if(bundledTimings!=null)return bundledTimings;
   try(InputStream in=context.getAssets().open("music-timings.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
    byte[] block=new byte[16384];int count;
    while((count=in.read(block))!=-1)bytes.write(block,0,count);
    bundledTimings=new JSONObject(bytes.toString("UTF-8"));
   }catch(Exception ignored){bundledTimings=new JSONObject();}
   return bundledTimings;
  }
 }
 static final Object MEASURE_LOCK=new Object();
 void measureTrack(JSONObject t)throws Exception {synchronized(MEASURE_LOCK){measureLocked(t);}}
 private void measureLocked(JSONObject t)throws Exception {
  if(!t.optString("backend").equals("gme")||!MusicContract.decoder(t).equals("gme")||t.optBoolean("duration_known")||measurementFile(t).isFile()||!available(t.getJSONObject("asset")))return;
  long[] r=Gme.measure(assetFile(t.getJSONObject("asset")).toString(),t.optInt("subsong"),180000);
  JSONObject value=new JSONObject().put("sound_ms",r[0]).put("ended_ms",r[1]).put("scan_limit_ms",r[2]).put("method","gme-silence-rms16-22050-v1");atomic(measurementFile(t),value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 int shortThreshold(){return preferences.getInt("short_seconds",0)*1000;}
 boolean hiddenShort(JSONObject t){long d=knownDuration(t);String mode=preferences.getString("unknown_duration","show");return !PlaybackRules.include(d,shortThreshold(),mode);}
 long remaining(List<JSONObject> games)throws Exception{return remaining(games,false);}
 long remainingCached(List<JSONObject> games){try{return remaining(games,true);}catch(Exception e){return -1;}}
 private long remaining(List<JSONObject> games,boolean cached)throws Exception{
  Set<String> seen=new HashSet<>();long total=0;
  for(JSONObject game:games){
   JSONObject manifest=cached?cachedManifest(game):manifest(game,false);
   if(manifest==null)return -1;
   JSONArray tracks=manifest.getJSONArray("tracks");
   for(int i=0;i<tracks.length();i++)for(JSONObject asset:assets(tracks.getJSONObject(i)))
    if(seen.add(asset.getString("sha256"))&&!available(manifest,asset))total+=asset.getLong("bytes");
  }
  return total;
 }
 void download(List<JSONObject> games,Progress progress)throws Exception {
  checkCancelled();downloadSummary="";int count=0,incomplete=0;
  queueDownloads(games);
  List<String> metadata=prepareOffline(progress);List<JSONObject> enriched=enrichGames(games);
  JSONArray allSeries;try{allSeries=series(false);}catch(Exception e){allSeries=new JSONArray();}
  Map<String,List<String>> seriesProblems=new HashMap<>();Map<String,List<File>> seriesFiles=new HashMap<>();
  // Artwork is prepared before music; failed pictures remain visible and retryable.
  for(JSONObject g:enriched){
   checkCancelled();List<String> missing=new ArrayList<>(metadata);List<File> required=new ArrayList<>();
   saveArtwork(g,false,progress,missing,required);
   for(int s=0;s<allSeries.length();s++){JSONObject sr=allSeries.getJSONObject(s);boolean related=false;for(JSONObject member:seriesGames(sr,Collections.singletonList(g)))related=true;if(!related)continue;String id=sr.getString("id");
    if(!seriesProblems.containsKey(id)){List<String> problems=new ArrayList<>();List<File> files=new ArrayList<>();saveArtwork(sr,true,progress,problems,files);seriesProblems.put(id,problems);seriesFiles.put(id,files);}missing.addAll(seriesProblems.get(id));required.addAll(seriesFiles.get(id));
   }
   JSONObject m=manifest(g,false);JSONArray ts=m.getJSONArray("tracks");
   for(int i=0;i<ts.length();i++){checkCancelled();JSONObject t=ts.getJSONObject(i);progress.update(g.getString("title")+" · "+(i+1)+"/"+ts.length());for(JSONObject asset:assets(t))fetch(m,asset);checkCancelled();progress.update("Проверяю длительность · "+g.getString("title")+" · "+(i+1));try{measureTrack(t);}catch(Exception e){checkCancelled();progress.update("Длительность не определена · "+g.getString("title")+": "+e.getMessage());}}
   checkCancelled();markComplete(g,m);saveOfflineState(g,missing,required);completeRequest(g);if(!missing.isEmpty())incomplete++;count++;
   progress.update("Сохранено коллекций: "+count+"/"+games.size()+(missing.isEmpty()?"":" · оформление: "+String.join("; ",missing)));
  }
  downloadSummary=incomplete==0?"Музыка сохранена для прослушивания без сети":"Музыка сохранена. Недостающее оформление загрузится автоматически";
 }
 void checkCancelled()throws InterruptedIOException{if(cancel||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Загрузка остановлена");}
 List<String> prepareOffline(Progress progress)throws Exception{
  List<String> missing=new ArrayList<>();progress.update("Сохраняю каталоги и оформление для офлайна…");
  try{catalog(false);}catch(Exception e){missing.add("каталог музыки: "+e.getMessage());}
  try{json(BASE+"assets/statistics.json",false);}catch(Exception e){missing.add("метаданные игр: "+e.getMessage());}
  for(String kind:new String[]{"games","series"})try{cachedDocument(BASE+"assets/gallery-"+kind+".json",true);}catch(Exception e){missing.add("каталог картинок "+(kind.equals("games")?"игр":"серий")+": "+e.getMessage());}
  ART_REVISION.incrementAndGet();checkCancelled();return missing;
 }
 void saveArtwork(JSONObject item,boolean isSeries,Progress progress,List<String> missing,List<File> required)throws Exception{
  checkCancelled();String title=item.optString("title",item.optString("name"));progress.update("Сохраняю постер"+(isSeries?" серии":"")+" · "+title);
  try{JSONObject record=pictureRecord(item,isSeries);if(record==null)throw new IOException("картинка не опубликована");Set<String> sources=new LinkedHashSet<>();sources.add(pictureSource(record,false));sources.add(pictureSource(record,true));for(String source:sources){checkCancelled();if(source.isEmpty())throw new IOException("нет адреса картинки");File file=pictureFile(item,isSeries,source);if(!file.isFile()||file.length()==0){requireNetwork();atomic(file,request(resolve(BASE,source),16*1024*1024));}required.add(file);}}
  catch(Exception e){checkCancelled();String problem="постер "+title+": "+e.getMessage();missing.add(problem);progress.update(problem);}
 }
 File snapshotFile(JSONObject game)throws Exception{return stateFile("snapshots",game);}
 File stateFile(String kind,JSONObject game)throws Exception{String id=game.getString("id");if(!id.matches("[a-zA-Z0-9_-]+"))throw new IOException("Неверный ID");return new File(root,kind+"/"+id+".json");}
 JSONObject localManifest(JSONObject game)throws Exception{
  JSONObject packaged=archive.playlist(resolve(BASE,game.getString("manifest")));if(packaged!=null)return packaged;
  File snapshot=snapshotFile(game);
  if(snapshot.isFile()){
   JSONObject value=readObject(snapshot),previous=value.getJSONObject("game");
   if(previous.optInt("_music_contract")==1){
    return value.getJSONObject("manifest").put("_url",resolve(BASE,previous.getString("manifest")));
   }
  }
  return cachedManifest(game);
 }
 void saveOfflineState(JSONObject game,List<String> missing,List<File> files)throws Exception{
  JSONArray problems=new JSONArray(),required=new JSONArray();for(String p:missing)problems.put(p);for(File f:files)required.put(root.toPath().relativize(f.toPath()).toString());
  atomic(stateFile("offline",game),new JSONObject().put("schema",1).put("missing",problems).put("files",required).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));revision.incrementAndGet();
 }
 boolean offlineReady(JSONObject game){try{
  File state=stateFile("offline",game);if(!state.isFile())return false;JSONObject value=new JSONObject(new String(Files.readAllBytes(state.toPath()),java.nio.charset.StandardCharsets.UTF_8));if(value.optInt("schema")!=1||value.getJSONArray("missing").length()!=0)return false;
  if((archive.active()==null&&!jsonFile(MusicContract.INDEX).isFile())||!jsonFile(BASE+"assets/statistics.json").isFile())return false;
  for(String kind:new String[]{"games","series"})if(!documentFile(BASE+"assets/gallery-"+kind+".json").isFile())return false;
  JSONArray files=value.getJSONArray("files");for(int i=0;i<files.length();i++){File file=new File(root,files.getString(i));if(!file.isFile()||file.length()==0)return false;}return true;
 }catch(Exception e){return false;}}
 static final java.util.concurrent.locks.ReentrantLock DOWNLOAD_LOCK=new java.util.concurrent.locks.ReentrantLock();
 static final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
 boolean networkAllowed(){return NetworkPolicy.allowed(context,streaming?"any":preferences.getString("network","wifi"));}
 void requireNetwork()throws IOException {if(!networkAllowed())throw new IOException("Ожидание разрешённой сети");}

 File jsonFile(String url)throws Exception{return new File(root,"json/"+hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");}
 JSONObject cachedManifest(JSONObject game)throws Exception {String url=resolve(BASE,game.getString("manifest"));JSONObject packaged=archive.playlist(url);if(packaged!=null)return packaged;File f=jsonFile(url);if(!f.isFile())return null;JSONObject j=new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8));j.put("_url",url);return j;}
 void markComplete(JSONObject game,JSONObject manifest)throws Exception {
  String id=game.getString("id");if(!id.matches("[a-zA-Z0-9_-]+"))throw new IOException("Неверный ID");
  if(manifest.optJSONArray("tracks")!=null){for(int i=0;i<manifest.getJSONArray("tracks").length();i++)if(!savedTrack(manifest,manifest.getJSONArray("tracks").getJSONObject(i)))throw new IOException("Коллекция скачана не полностью");atomic(snapshotFile(game),new JSONObject().put("game",game).put("manifest",manifest).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  atomic(new File(root,"saved/"+id+".json"),game.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  atomic(new File(root,"complete/"+id),new byte[]{1});revision.incrementAndGet();
 }
 boolean subscribed(JSONObject game){
  try{
   String id=game.optString("id");
   return id.matches("[a-zA-Z0-9_-]+")&&new File(root,"complete/"+id).isFile()
           &&readObject(stateFile("saved",game)).optInt("_music_contract")==1;
  }catch(Exception e){return false;}
 }
 private static final String[] DOWNLOAD_DIRS={"files","v1-files","saved","complete","pending","snapshots","offline","art","covers","timings"};
 boolean hasDownloadedMusic(){
  if(archive.hasDownload())return true;
  for(String name:DOWNLOAD_DIRS){
   String[] entries=new File(root,name).list();
   if(entries!=null&&entries.length>0)return true;
  }
  return false;
 }
 void clearDownloadedMusic()throws Exception{
  DOWNLOAD_LOCK.lockInterruptibly();
  try{
   synchronized(REQUEST_LOCK){
    if(!preferences.edit().putBoolean("follow_all_music",false).putBoolean("archive_update_pending",false)
       .putBoolean("archive_paused",false).putStringSet("saved_series",Collections.emptySet())
       .putStringSet("paused_downloads",Collections.emptySet()).commit())
     throw new IOException("Не удалось сбросить очередь загрузки");
    archive.clear();
    for(String name:DOWNLOAD_DIRS)MusicArchive.removeChecked(new File(root,name));
   }
  }finally{revision.incrementAndGet();DOWNLOAD_LOCK.unlock();}
 }
 boolean archived(JSONObject game){
  try{return archive.hasPlaylist(resolve(BASE,game.getString("manifest")));}catch(Exception e){return false;}
 }
 List<JSONObject> archiveGames(){
  try{
   JSONObject index=archive.index();
   if(index!=null){
    JSONArray games=MusicContract.catalog(index).getJSONArray("games");
    List<JSONObject> result=new ArrayList<>();
    for(int i=0;i<games.length();i++)result.add(games.getJSONObject(i));
    return result;
   }
  }catch(Exception ignored){}
  return Collections.emptyList();
 }
 List<JSONObject> savedGames(){List<JSONObject> result=new ArrayList<>();File[] files=new File(root,"saved").listFiles();if(files!=null)for(File f:files)try{JSONObject game=readObject(f);if(game.optInt("_music_contract")==1)result.add(game);}catch(Exception ignored){}return result;}
 int[] localCounts(JSONObject g){try{JSONObject m=cachedManifest(g);if(m==null)return new int[]{0,0};JSONArray tracks=m.getJSONArray("tracks");int have=0;for(int i=0;i<tracks.length();i++)if(savedTrack(m,tracks.getJSONObject(i)))have++;return new int[]{have,tracks.length()};}catch(Exception e){return new int[]{0,0};}}
 boolean fullyLocal(JSONObject g){try{JSONObject m=localManifest(g);if(m==null||m.getJSONArray("tracks").length()==0)return false;for(int i=0;i<m.getJSONArray("tracks").length();i++)if(!savedTrack(m,m.getJSONArray("tracks").getJSONObject(i)))return false;return true;}catch(Exception e){return false;}}
 boolean hasManifestUpdate(JSONObject game){try{File saved=stateFile("saved",game);if(!saved.isFile())return false;JSONObject previous=new JSONObject(new String(Files.readAllBytes(saved.toPath()),java.nio.charset.StandardCharsets.UTF_8));return !previous.optString("manifest").equals(game.optString("manifest"));}catch(Exception e){return false;}}
 String downloadLabel(JSONObject g){if(pending(g)&&!paused(g))return "Ожидание";if(archived(g))return "Скачано";if(subscribed(g)&&hasManifestUpdate(g))return "Обновить";int[] n=localCounts(g);if(n[1]>0&&n[0]==n[1])return subscribed(g)?offlineReady(g)?"Скачано":paused(g)||artworkFailed(g)?"Повторить":"Ожидание":"Скачать";if(subscribed(g))return "Обновить";if(n[0]>0||pending(g))return "Повторить";return "Скачать";}
 boolean artworkFailed(JSONObject game){try{File f=stateFile("offline",game);return f.isFile()&&new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("missing").length()>0;}catch(Exception e){return true;}}
 String downloadStatus(JSONObject g){String action=downloadLabel(g);if(archived(g))return "Скачано";if(!pending(g)&&fullyLocal(g)&&!offlineReady(g))return paused(g)?"Музыка скачана · оформление приостановлено":artworkFailed(g)?"Музыка скачана · оформление повторим автоматически":networkAllowed()?"Музыка скачана · оформление загружается":"Музыка скачана · ожидает разрешённой сети";if(action.equals("Ожидание"))return networkAllowed()?"В очереди на загрузку":"Ожидает разрешённой сети";if(action.equals("Повторить"))return "Загрузка не завершена";if(action.equals("Обновить"))return "Скачано · есть обновления";return action.equals("Скачать")?"":action;}
 String offlineProblem(JSONObject game){try{File f=stateFile("offline",game);if(!f.isFile())return subscribed(game)?"Недостающее оформление будет загружено автоматически.":"";JSONArray missing=new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("missing");List<String> lines=new ArrayList<>();for(int i=0;i<Math.min(3,missing.length());i++)lines.add(missing.getString(i));return String.join("\n",lines);}catch(Exception e){return "Не удалось проверить сохранённые данные.";}}
 private static final Object REQUEST_LOCK=new Object();
 boolean pending(JSONObject game){try{return stateFile("pending",game).isFile();}catch(Exception e){return false;}}
 boolean paused(JSONObject game){return preferences.getStringSet("paused_downloads",Collections.emptySet()).contains(game.optString("id"));}
 void queueDownloads(List<JSONObject> games)throws Exception{synchronized(REQUEST_LOCK){Set<String> paused=new HashSet<>(preferences.getStringSet("paused_downloads",Collections.emptySet()));for(JSONObject game:games){checkCancelled();File file=stateFile("pending",game);byte[] value=game.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(!file.isFile()||!Arrays.equals(Files.readAllBytes(file.toPath()),value))atomic(file,value);paused.remove(game.getString("id"));}preferences.edit().putStringSet("paused_downloads",paused).apply();revision.incrementAndGet();}}
 void completeRequest(JSONObject game)throws Exception{synchronized(REQUEST_LOCK){Files.deleteIfExists(stateFile("pending",game).toPath());}}
 List<JSONObject> pendingGames(){List<JSONObject> result=new ArrayList<>();File[] files=new File(root,"pending").listFiles();if(files!=null)for(File file:files)try{JSONObject game=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));if(game.optInt("_music_contract")==1&&!paused(game))result.add(game);}catch(Exception ignored){}return result;}
 void cancelWork(){cancel=true;cancelTransfers();}
 void pausePending(){cancelWork();synchronized(REQUEST_LOCK){if(archive.hasDownload())preferences.edit().putBoolean("archive_paused",true).apply();Set<String> paused=new HashSet<>(preferences.getStringSet("paused_downloads",Collections.emptySet()));for(JSONObject game:pendingGames())paused.add(game.optString("id"));for(JSONObject game:savedGames())if(!offlineReady(game))paused.add(game.optString("id"));preferences.edit().putStringSet("paused_downloads",paused).apply();revision.incrementAndGet();}}
 List<JSONObject> repairCandidates(){List<JSONObject> result=new ArrayList<>();for(JSONObject game:savedGames())if(game.optInt("_music_contract")==1&&!paused(game)&&!offlineReady(game))result.add(game);return result;}
 boolean needsMaintenance(){return archive.pendingUnpaused()||!pendingGames().isEmpty()||!repairCandidates().isEmpty();}
 void requestArchive()throws IOException{
  synchronized(REQUEST_LOCK){
   checkCancelled();
   if(!preferences.edit().putBoolean("archive_update_pending",true).putBoolean("archive_paused",false)
      .putBoolean("follow_all_music",true).commit())throw new IOException("Не удалось сохранить очередь загрузки ZIP");
   revision.incrementAndGet();
  }
 }
 void resumeRequests()throws InterruptedIOException{synchronized(REQUEST_LOCK){checkCancelled();preferences.edit().putStringSet("paused_downloads",Collections.emptySet()).putBoolean("archive_paused",false).apply();revision.incrementAndGet();}}
 void repairSaved(Progress progress)throws Exception{
  List<JSONObject> games=repairCandidates();if(games.isEmpty())return;List<String> metadata=prepareOffline(progress);JSONArray allSeries;try{allSeries=series(false);}catch(Exception e){allSeries=new JSONArray();}
  Set<String> checkedSeries=new HashSet<>();Map<String,List<String>> problemsBySeries=new HashMap<>();Map<String,List<File>> filesBySeries=new HashMap<>();
  for(JSONObject game:games){checkCancelled();if(!fullyLocal(game)){queueDownloads(Collections.singletonList(game));continue;}List<String> missing=new ArrayList<>(metadata);List<File> required=new ArrayList<>();saveArtwork(game,false,progress,missing,required);
   for(int i=0;i<allSeries.length();i++){JSONObject sr=allSeries.getJSONObject(i);if(seriesGames(sr,Collections.singletonList(game)).isEmpty())continue;String id=sr.getString("id");if(checkedSeries.add(id)){List<String> issues=new ArrayList<>();List<File> files=new ArrayList<>();saveArtwork(sr,true,progress,issues,files);problemsBySeries.put(id,issues);filesBySeries.put(id,files);}missing.addAll(problemsBySeries.get(id));required.addAll(filesBySeries.get(id));}
   JSONObject manifest=localManifest(game);if(manifest!=null&&!snapshotFile(game).isFile())markComplete(game,manifest);saveOfflineState(game,missing,required);
  }
 }
 void trackSeries(String id){Set<String> ids=new HashSet<>(preferences.getStringSet("saved_series",Collections.emptySet()));ids.add(id);preferences.edit().putStringSet("saved_series",ids).apply();}
 void syncSaved(Progress progress)throws Exception {syncSaved(progress,false);}
 void syncSaved(Progress progress,boolean manual)throws Exception {
  checkCancelled();
  downloadSummary="";
  if(archive.hasDownload()&&(manual||preferences.getBoolean("follow_all_music",false))
          &&!preferences.getBoolean("archive_paused",false))downloadArchive(progress);
  JSONObject cat=MusicContract.catalog(index(true,true));
  List<JSONObject> all=new ArrayList<>();JSONArray rows=cat.getJSONArray("games");
  for(int i=0;i<rows.length();i++)all.add(rows.getJSONObject(i));
  Set<String> ids=new HashSet<>();for(JSONObject g:savedGames())if(g.optInt("_music_contract")==1)ids.add(g.getString("id"));
  Set<String> seriesIds=preferences.getStringSet("saved_series",Collections.emptySet());
  JSONArray ss=cat.getJSONArray("series");
  for(int i=0;i<ss.length();i++){JSONObject sr=ss.getJSONObject(i);if(seriesIds.contains(sr.optString("id")))for(JSONObject g:seriesGames(sr,all))ids.add(g.getString("id"));}
  List<JSONObject> selected=new ArrayList<>();
  for(JSONObject game:all)if(ids.contains(game.getString("id"))&&!paused(game)&&!archived(game)){
   checkCancelled();progress.update("Проверяю · "+game.optString("title"));
   manifest(game,true);selected.add(game);
  }
  if(!selected.isEmpty())download(selected,progress);
  else if(downloadSummary.isEmpty())downloadSummary="Музыкальный каталог обновлён";
  revision.incrementAndGet();
 }

}
