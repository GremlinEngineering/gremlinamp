package ru.oopssre.gremlinamp;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
public class DownloadService extends Service {
 static volatile DownloadService instance;static volatile String progress="";
 Library library;final ExecutorService io=Executors.newSingleThreadExecutor();boolean running;
 public IBinder onBind(Intent i){return null;}
 public void onCreate(){super.onCreate();instance=this;library=new Library(this);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("download","Скачивание музыки",NotificationManager.IMPORTANCE_LOW));}
 Notification notification(){return new Notification.Builder(this,"download").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Аркадный зал · скачивание").setContentText(progress).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Отмена",PendingIntent.getService(this,0,new Intent(this,DownloadService.class).putExtra("cancel",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build();}
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent==null){stopSelf();return START_NOT_STICKY;}
  if(intent.getBooleanExtra("cancel",false)){library.cancel=true;return START_NOT_STICKY;}
  startForeground(2,notification());if(running)return START_NOT_STICKY;running=true;
  String payload=intent.getStringExtra("games");
  io.submit(()->{PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:Download");
   try{wake.acquire(6*60*60*1000L);JSONArray a=new JSONArray(payload);List<JSONObject> games=new ArrayList<>();for(int k=0;k<a.length();k++)games.add(a.getJSONObject(k));library.download(games,s->{progress=s;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(2,notification());});progress="Загрузка завершена. Музыка доступна локально.";}
   catch(Exception e){progress="Загрузка остановлена: "+e.getMessage();}
   finally{if(wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
  });return START_NOT_STICKY;
 }
 public void onTimeout(int startId,int fgsType){library.cancel=true;stopSelf();}
 public void onDestroy(){library.cancel=true;io.shutdownNow();instance=null;super.onDestroy();}
}
