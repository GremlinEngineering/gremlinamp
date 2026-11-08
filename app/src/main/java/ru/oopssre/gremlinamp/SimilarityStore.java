package ru.oopssre.gremlinamp;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Streaming import keeps the full library JSON out of the application's heap. */
final class SimilarityStore {
 static final java.util.concurrent.atomic.AtomicBoolean REFRESHING=new java.util.concurrent.atomic.AtomicBoolean();static final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
 static final Object LOCK=new Object();static final Object REFRESH_LOCK=new Object();
 static final String URL=Library.BASE+"assets/music-similarity.json";
 static File database(Library l){return new File(l.root,"similarity-v1.db");}
 static boolean readable(Library l){
  synchronized(LOCK){
   if(!database(l).isFile())return false;
   try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database(l).toString(),null,SQLiteDatabase.OPEN_READONLY);
       Cursor cursor=db.rawQuery("SELECT key FROM tracks LIMIT 1",null)){
    return cursor.getColumnCount()==1;
   }catch(Exception e){return false;}
  }
 }
 static String status(Library l){return l.preferences.getString("similarity_status","Индекс похожей музыки ещё не загружен");}
 static void refresh(Library l){if(!REFRESHING.compareAndSet(false,true))return;synchronized(REFRESH_LOCK){revision.incrementAndGet();File temp=null;SQLiteDatabase db=null;HttpURLConnection connection=null;
  try{l.requireNetwork();connection=(HttpURLConnection)new java.net.URL(URL).openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
   String etag=l.preferences.getString("similarity_etag","");if(!etag.isEmpty()&&readable(l))connection.setRequestProperty("If-None-Match",etag);
   int code=connection.getResponseCode();if(code==304){l.preferences.edit().putString("similarity_status","Индекс похожей музыки актуален").apply();return;}if(code==404)throw new IOException("Индекс ещё не опубликован на сайте");if(code!=200)throw new IOException("Индекс: HTTP "+code);
   temp=File.createTempFile("similarity-",".db",l.root);db=SQLiteDatabase.openOrCreateDatabase(temp,null);db.execSQL("CREATE TABLE tracks (key TEXT PRIMARY KEY, data TEXT NOT NULL)");db.beginTransaction();int schema=-1,count=0;boolean found=false;
   try(JsonReader reader=new JsonReader(new InputStreamReader(new LimitedInput(connection.getInputStream(),l),java.nio.charset.StandardCharsets.UTF_8))){reader.beginObject();while(reader.hasNext()){String name=reader.nextName();if(name.equals("schema_version"))schema=reader.nextInt();else if(name.equals("tracks")){found=true;reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(!SimilarityRules.validKey(key))throw new IOException("Некорректный ключ композиции");Object value=readValue(reader,0,new int[]{0});if(!(value instanceof JSONObject))throw new IOException("Некорректная запись индекса");JSONObject row=(JSONObject)value;if(row.optJSONArray("refs")==null||row.optJSONObject("neighbors")==null)throw new IOException("Неполная запись индекса");db.execSQL("INSERT INTO tracks(key,data) VALUES (?,?)",new Object[]{key.toLowerCase(Locale.ROOT),row.toString()});if(++count>250000)throw new IOException("Слишком много композиций в индексе");}reader.endObject();}else reader.skipValue();}reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Лишние данные индекса");}
   if(schema!=1||!found)throw new IOException("Неподдерживаемый формат индекса");db.setTransactionSuccessful();db.endTransaction();db.close();db=null;
   synchronized(LOCK){Files.move(temp.toPath(),database(l).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}temp=null;
   l.preferences.edit().putString("similarity_etag",Objects.toString(connection.getHeaderField("ETag"),"")).putString("similarity_status","Индекс похожей музыки: "+count+" композиций").putLong("similarity_updated",System.currentTimeMillis()).apply();
  }catch(Exception e){l.preferences.edit().putString("similarity_status",String.valueOf(e.getMessage())+(readable(l)?" · используется сохранённый индекс":"")).apply();}
  finally{if(db!=null)db.close();if(connection!=null)connection.disconnect();if(temp!=null){temp.delete();new File(temp+"-journal").delete();}REFRESHING.set(false);revision.incrementAndGet();}
 }}
 static Object readValue(JsonReader r,int depth,int[] nodes)throws Exception{if(depth>10||++nodes[0]>20000)throw new IOException("Слишком сложная запись индекса");switch(r.peek()){
  case BEGIN_OBJECT:{JSONObject o=new JSONObject();r.beginObject();while(r.hasNext()){String key=r.nextName();o.put(key,readValue(r,depth+1,nodes));}r.endObject();return o;}
  case BEGIN_ARRAY:{JSONArray a=new JSONArray();r.beginArray();while(r.hasNext())a.put(readValue(r,depth+1,nodes));r.endArray();return a;}
  case NULL:r.nextNull();return JSONObject.NULL;
  case BOOLEAN:return r.nextBoolean();
  case NUMBER:{double n=r.nextDouble();if(!Double.isFinite(n))throw new IOException("Некорректное число индекса");return n;}
  case STRING:{String s=r.nextString();if(s.length()>65536)throw new IOException("Слишком длинная строка индекса");return s;}
  default:throw new IOException("Некорректный JSON индекса");
 }}
 static JSONObject row(SQLiteDatabase db,String key)throws Exception{try(Cursor cursor=db.rawQuery("SELECT data FROM tracks WHERE key=?",new String[]{key})){return cursor.moveToFirst()?new JSONObject(cursor.getString(0)):null;}}
 static List<SimilarityRules.Target> targets(Library l,String seed,String mode,Set<String> played)throws Exception{synchronized(LOCK){if(!database(l).isFile())throw new IOException("Индекс похожей музыки ещё не загружен. Обновите каталог после публикации индекса на сайте");try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database(l).toString(),null,SQLiteDatabase.OPEN_READONLY)){
  JSONObject source=row(db,seed);if(source==null)throw new IOException("Для этой композиции анализ ещё не готов");return SimilarityRules.targets(source,mode,played,key->row(db,key));
 }}}
 static SimilarityAvailability availability(Library l,String key){synchronized(LOCK){
  if(!database(l).isFile())return SimilarityAvailability.evaluate(false,!key.isEmpty(),false,false,false);
  if(key.isEmpty())return SimilarityAvailability.evaluate(true,false,false,false,false);
  try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database(l).toString(),null,SQLiteDatabase.OPEN_READONLY)){
   JSONObject source=row(db,key);if(source==null)return SimilarityAvailability.evaluate(true,true,false,false,false);
   Set<String> excluded=Collections.singleton(key);
   boolean rhythm=!SimilarityRules.targets(source,"rhythm",excluded,k->row(db,k)).isEmpty();
   boolean sound=!SimilarityRules.targets(source,"sound",excluded,k->row(db,k)).isEmpty();
   return SimilarityAvailability.evaluate(true,true,true,rhythm,sound);
  }catch(Exception e){return SimilarityAvailability.unavailable("Сохранённый индекс не читается · обновите индекс");}
 }}
 static final class LimitedInput extends FilterInputStream {long bytes;final Library library;LimitedInput(InputStream input,Library l){super(input);library=l;}void check(int n)throws IOException{if(n>0)bytes+=n;if(bytes>512L*1048576)throw new IOException("Индекс больше 512 МБ");if(library.cancel||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Обновление индекса остановлено");library.requireNetwork();}public int read()throws IOException{int n=in.read();check(n<0?0:1);return n;}public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);check(n);return n;}}
}
