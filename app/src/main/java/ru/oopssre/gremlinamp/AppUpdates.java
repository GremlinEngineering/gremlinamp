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
import java.security.MessageDigest;
import java.util.*;

/** Site release contract; no APK or release data is accepted from another origin. */
final class AppUpdates {
 static final String SITE="https://games.oopssre.ru/", RELEASE=SITE+"assets/arcade/music-app/release.json";
 static final int JOB=201, NOTICE=201;
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("app_updates",0);}
 static long code(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
 static DownloadManager dm(Context c){return (DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);}
 static String hash(byte[] data)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 static void schedule(Context c,boolean reset){JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(reset)js.cancel(JOB);if(!prefs(c).getBoolean("auto",false))return;if(js.getPendingJob(JOB)!=null)return;
  JobInfo.Builder b=new JobInfo.Builder(JOB,new ComponentName(c,AppUpdateJob.class)).setPersisted(true).setMinimumLatency(prefs(c).getBoolean("hourly",false)?3600000:SyncRules.untilMorning(System.currentTimeMillis()));
  if(prefs(c).getBoolean("mobile",false))b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);else if(Build.VERSION.SDK_INT>=28)b.setRequiredNetwork(new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build());else b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);js.schedule(b.build());
 }
 static void network(Context c)throws IOException{if(!NetworkPolicy.allowed(c,prefs(c).getBoolean("mobile",false)?"any":"wifi"))throw new IOException("Ожидание разрешённой сети");}
 static synchronized String check(Context c)throws Exception{
  network(c);HttpURLConnection con=(HttpURLConnection)new URL(RELEASE).openConnection();con.setConnectTimeout(15000);con.setReadTimeout(15000);con.setInstanceFollowRedirects(false);con.setUseCaches(false);JSONObject r;
  try{if(con.getResponseCode()!=200)throw new IOException("Сайт: HTTP "+con.getResponseCode());try(InputStream in=con.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>131072)throw new IOException("Слишком большой файл релиза");}r=new JSONObject(out.toString("UTF-8"));}}finally{con.disconnect();}
  long installed=code(c.getPackageManager().getPackageInfo(c.getPackageName(),0)),build=r.getLong("build");
  if(build<=installed){clear(c);return status(c,"Установлена актуальная версия");}
  JSONObject apk=r.getJSONObject("apk");String url=UpdateRules.apkUrl(apk.getString("url")),sha=apk.getString("sha256");long bytes=apk.getLong("bytes");if(!sha.matches("[a-fA-F0-9]{64}")||bytes<=0||bytes>200*1048576L)throw new IOException("Некорректные данные релиза");
  long id=prefs(c).getLong("download",-1);if(id!=-1&&prefs(c).getLong("build",0)==build&&sha.equalsIgnoreCase(prefs(c).getString("sha",""))){int state=downloadState(c,id);if(state==DownloadManager.STATUS_SUCCESSFUL){verify(c);notifyReady(c);return status(c,"Обновление скачано · можно установить");}if(state==DownloadManager.STATUS_PENDING||state==DownloadManager.STATUS_RUNNING||state==DownloadManager.STATUS_PAUSED)return status(c,"APK скачивается · ожидание сети возможно");}
  clear(c);prefs(c).edit().putLong("build",build).putLong("bytes",bytes).putString("sha",sha).putString("version",r.getString("version")).commit();
  File destination=new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"GremlinAmp-update.apk");if(destination.exists()&&!destination.delete())throw new IOException("Не удалось заменить старый APK");
  DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url)).setTitle("GremlinAmp · обновление "+r.getString("version")).setDescription("После скачивания подтвердите установку в GremlinAmp").setMimeType("application/octet-stream").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setDestinationUri(Uri.fromFile(destination)).setAllowedOverRoaming(false).setAllowedNetworkTypes(prefs(c).getBoolean("mobile",false)?DownloadManager.Request.NETWORK_WIFI|DownloadManager.Request.NETWORK_MOBILE:DownloadManager.Request.NETWORK_WIFI);
  long download=dm(c).enqueue(req);prefs(c).edit().putLong("download",download).commit();return status(c,"Скачивается GremlinAmp "+r.getString("version"));
 }
 static int downloadState(Context c,long id){try(Cursor q=dm(c).query(new DownloadManager.Query().setFilterById(id))){return q!=null&&q.moveToFirst()?q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)):-1;}}
 static void clear(Context c){long id=prefs(c).getLong("download",-1);if(id!=-1)dm(c).remove(id);prefs(c).edit().remove("download").apply();((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE);}
 static String status(Context c,String s){prefs(c).edit().putString("status",s).putLong("checked",System.currentTimeMillis()).apply();return s;}
 static synchronized Uri verify(Context c)throws Exception{
  long id=prefs(c).getLong("download",-1);if(id<0||downloadState(c,id)!=DownloadManager.STATUS_SUCCESSFUL)throw new IOException("APK ещё не скачан");
  File f=new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"GremlinAmp-update.apk");if(f.length()!=prefs(c).getLong("bytes",0))throw new IOException("Размер APK не совпадает");
  MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));if(!hex.toString().equalsIgnoreCase(prefs(c).getString("sha","")))throw new IOException("SHA-256 APK не совпадает");
  PackageManager pm=c.getPackageManager();int flags=PackageManager.GET_SIGNATURES;PackageInfo remote=pm.getPackageArchiveInfo(f.getAbsolutePath(),flags),self=pm.getPackageInfo(c.getPackageName(),flags);
  if(remote==null||!c.getPackageName().equals(remote.packageName)||code(remote)!=prefs(c).getLong("build",0)||code(remote)<=code(self))throw new IOException("APK не является новой версией GremlinAmp");
  Set<String> a=new HashSet<>(),b=new HashSet<>();if(remote.signatures!=null)for(Signature s:remote.signatures)a.add(hash(s.toByteArray()));if(self.signatures!=null)for(Signature s:self.signatures)b.add(hash(s.toByteArray()));if(a.isEmpty()||!a.equals(b))throw new IOException("Подпись APK отличается от установленного приложения");
  Uri uri=dm(c).getUriForDownloadedFile(id);if(uri==null)throw new IOException("Файл обновления недоступен");return uri;
 }
 static void notifyReady(Context c){NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("app_updates","Обновления GremlinAmp",NotificationManager.IMPORTANCE_DEFAULT));PendingIntent p=PendingIntent.getActivity(c,201,new Intent(c,MainActivity.class).putExtra("app_update",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);try{nm.notify(NOTICE,new Notification.Builder(c,"app_updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("GremlinAmp · обновление скачано").setContentText("Нажмите, чтобы проверить и установить APK").setContentIntent(p).setAutoCancel(true).build());}catch(SecurityException ignored){}}
 static void install(MainActivity a){a.message("Проверяю APK и подпись…");a.io.submit(()->{try{Uri uri=verify(a);a.runOnUiThread(()->{try{if(!a.getPackageManager().canRequestPackageInstalls()){a.dialogBuilder().setTitle("Разрешение на обновление").setMessage("Разрешите GremlinAmp установку приложений в настройках Android. Затем снова нажмите «Установить скачанное обновление».").setPositiveButton("Открыть настройки",(d,w)->a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())))).setNegativeButton("Позже",null).show();return;}a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){a.message("Установка: "+e.getMessage());}});}catch(Exception e){a.message("Обновление: "+e.getMessage());}});}
}
