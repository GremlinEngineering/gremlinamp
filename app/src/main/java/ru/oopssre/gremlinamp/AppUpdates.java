package ru.oopssre.gremlinamp;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;

/** The update manifest and APK URL are resolved against the Arcade site. */
final class AppUpdates {
 static final String SITE="https://games.oopssre.ru/", RELEASE=SITE+"assets/arcade/music-app/release.json";
 static final int JOB=201, NOTICE=201;
 record Inspection(long id,UpdateRules.Stage stage) {}
 static Inspection inspection(Context c,boolean checking){
  long id=prefs(c).getLong("download",-1);
  int status=id<0?-1:downloadState(c,id);
  return new Inspection(id,UpdateRules.stage(checking,id>=0,downloading(status),
   status==DownloadManager.STATUS_SUCCESSFUL));
 }
 static boolean downloading(int state){
  return state==DownloadManager.STATUS_PENDING||state==DownloadManager.STATUS_RUNNING||state==DownloadManager.STATUS_PAUSED;
 }
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("app_updates",0);}
 static long code(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
 static DownloadManager dm(Context c){return (DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);}
 static String version(Context c){
  try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}
  catch(PackageManager.NameNotFoundException e){return "—";}
 }
 static void schedule(Context c,boolean reset){JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(reset)js.cancel(JOB);if(!prefs(c).getBoolean("auto",false))return;if(js.getPendingJob(JOB)!=null)return;
  JobInfo.Builder b=new JobInfo.Builder(JOB,new ComponentName(c,AppUpdateJob.class)).setPersisted(true).setMinimumLatency(prefs(c).getBoolean("hourly",false)?3600000:SyncRules.untilMorning(System.currentTimeMillis()));
  if(new Library(c).preferences.getString("network","wifi").equals("any"))b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);else if(Build.VERSION.SDK_INT>=28)b.setRequiredNetwork(new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build());else b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);js.schedule(b.build());
 }
 static void network(Context c)throws IOException{if(!NetworkPolicy.allowed(c,new Library(c).preferences.getString("network","wifi").equals("any")?"any":"wifi"))throw new IOException("Ожидание разрешённой сети");}
 static synchronized String check(Context c)throws Exception{
  network(c);HttpURLConnection con=(HttpURLConnection)new URL(RELEASE).openConnection();con.setConnectTimeout(15000);con.setReadTimeout(15000);con.setInstanceFollowRedirects(false);con.setUseCaches(false);JSONObject r;
  try{if(con.getResponseCode()!=200)throw new IOException("Сайт: HTTP "+con.getResponseCode());try(InputStream in=con.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>131072)throw new IOException("Слишком большой файл релиза");}r=new JSONObject(out.toString("UTF-8"));}}finally{con.disconnect();}
  if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Проверка обновления отменена");
  long installed=code(c.getPackageManager().getPackageInfo(c.getPackageName(),0)),build=r.getLong("build");
  if(build<=installed){clear(c);return status(c,"Установлена актуальная версия");}
  String url=UpdateRules.apkUrl(r.getJSONObject("apk").getString("url")),version=r.getString("version");
  long id=prefs(c).getLong("download",-1);
  if(id!=-1&&prefs(c).getLong("build",0)==build&&url.equals(downloadUrl(c,id))){
   int state=downloadState(c,id);
   if(state==DownloadManager.STATUS_SUCCESSFUL&&finishDownload(c,id))return prefs(c).getString("status","");
   if(downloading(state))return status(c,"APK скачивается · ожидание сети возможно");
  }
  clear(c);
  File destination=new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"GremlinAmp-update.apk");if(destination.exists()&&!destination.delete())throw new IOException("Не удалось заменить старый APK");
  DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url)).setTitle("GremlinAmp · обновление "+version).setDescription("После скачивания подтвердите установку в GremlinAmp").setMimeType("application/octet-stream").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setDestinationUri(Uri.fromFile(destination)).setAllowedOverRoaming(false).setAllowedNetworkTypes(new Library(c).preferences.getString("network","wifi").equals("any")?DownloadManager.Request.NETWORK_WIFI|DownloadManager.Request.NETWORK_MOBILE:DownloadManager.Request.NETWORK_WIFI);
  long download=dm(c).enqueue(req);
  prefs(c).edit().putLong("download",download).putLong("build",build).commit();
  status(c,"Скачивается GremlinAmp "+version);
  finishDownload(c,download);
  return prefs(c).getString("status","");
 }
 static int downloadState(Context c,long id){try(Cursor q=dm(c).query(new DownloadManager.Query().setFilterById(id))){return q!=null&&q.moveToFirst()?q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)):-1;}}
 static String downloadUrl(Context c,long id){try(Cursor q=dm(c).query(new DownloadManager.Query().setFilterById(id))){return q!=null&&q.moveToFirst()?q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)):"";}}
 static synchronized void clear(Context c){
  long id=prefs(c).getLong("download",-1);
  if(id!=-1)dm(c).remove(id);
  prefs(c).edit().remove("download").remove("build").remove("ready_notified")
   .remove("verified_download").remove("bytes").remove("sha").remove("version").remove("status").apply();
  ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE);
 }
 static String status(Context c,String s){prefs(c).edit().putString("status",s).putLong("checked",System.currentTimeMillis()).apply();return s;}
 static synchronized void cancelDownload(Context c,long id){
  if(id<0||id!=prefs(c).getLong("download",-1)||!downloading(downloadState(c,id)))return;
  clear(c);status(c,"Скачивание обновления отменено");
 }
 static synchronized boolean finishDownload(Context c,long id){
  if(id<0||id!=prefs(c).getLong("download",-1))return false;
  int state=downloadState(c,id);
  if(downloading(state))return false;
  if(state!=DownloadManager.STATUS_SUCCESSFUL){
   status(c,"Скачивание обновления не завершилось · повторите проверку");return false;
  }
  status(c,"Обновление скачано · можно установить");
  if(prefs(c).getLong("ready_notified",-1)!=id){
   prefs(c).edit().putLong("ready_notified",id).apply();
   notifyReady(c);
  }
  return true;
 }
 static void notifyReady(Context c){NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("app_updates","Обновления GremlinAmp",NotificationManager.IMPORTANCE_DEFAULT));PendingIntent p=PendingIntent.getActivity(c,201,new Intent(c,MainActivity.class).putExtra("app_update",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);try{nm.notify(NOTICE,new Notification.Builder(c,"app_updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("GremlinAmp · обновление скачано").setContentText("Нажмите, чтобы установить обновление").setContentIntent(p).setAutoCancel(true).build());}catch(SecurityException ignored){}}
 static String install(MainActivity a,long id){
  if(id<0||id!=prefs(a).getLong("download",-1)||a.isFinishing()||a.isDestroyed())return "Файл обновления недоступен";
  try{
   Uri uri=dm(a).getUriForDownloadedFile(id);
   if(uri==null){
    a.io.submit(()->{synchronized(AppUpdates.class){
     if(id==prefs(a).getLong("download",-1)){clear(a);status(a,"Файл обновления недоступен · повторите проверку");}
    }});
    return "Файл обновления недоступен · повторите проверку";
   }
   if(!a.getPackageManager().canRequestPackageInstalls()){
    a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
     Uri.fromParts("package",a.getPackageName(),null)));
    return "";
   }
   Intent installer=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   installer.setClipData(ClipData.newRawUri("GremlinAmp",uri));
   a.startActivity(installer);
   return "";
  }catch(RuntimeException error){
   return "Не удалось открыть системный установщик";
  }
 }
}
