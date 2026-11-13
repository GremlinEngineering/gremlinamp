package ru.oopssre.gremlinamp;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
public class DownloadService extends Service {
 static volatile DownloadService instance;static volatile String progress="";static final java.util.concurrent.atomic.AtomicLong accepted=new java.util.concurrent.atomic.AtomicLong();
 Library library;final ExecutorService io=Executors.newSingleThreadExecutor();boolean running;Future<?> task;
 public IBinder onBind(Intent i){return null;}
 public void onCreate(){super.onCreate();instance=this;library=new Library(this);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("download","Скачивание музыки",NotificationManager.IMPORTANCE_LOW));}
 Notification notification(){return new Notification.Builder(this,"download").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Аркадный зал · скачивание").setContentText(progress).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Отмена",PendingIntent.getService(this,0,new Intent(this,DownloadService.class).putExtra("cancel",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build();}
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent==null){stopSelf();return START_NOT_STICKY;}
  if(intent.getBooleanExtra("cancel",false)){library.pausePending();if(task!=null)task.cancel(true);progress="Загрузка приостановлена";stopSelf();return START_NOT_STICKY;}
  if(running)return START_NOT_STICKY;
  running=true;progress="Подготавливаю загрузку…";accepted.incrementAndGet();startForeground(2,notification());
  String payload=intent.getStringExtra("games");
  boolean sync=intent.getBooleanExtra("sync",false);
  boolean archive=intent.getBooleanExtra("archive",false);
  task=io.submit(()->{boolean locked=false;PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:Download");
   try{wake.acquire(6*60*60*1000L);if(!sync&&!archive){JSONArray requested=new JSONArray(payload);List<JSONObject> pending=new ArrayList<>();for(int k=0;k<requested.length();k++)pending.add(requested.getJSONObject(k));library.queueDownloads(pending);}progress="Ожидание текущей загрузки…";Library.DOWNLOAD_LOCK.lockInterruptibly();locked=true;if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
    Library.Progress report=s->{progress=s;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(2,notification());};
    if(archive){
     library.requestArchive();
     library.downloadArchive(report);
     library.checkCancelled();
     MusicSyncJob.result(library,library.downloadSummary,true);
    }
    else if(sync){library.checkCancelled();library.resumeRequests();library.syncSaved(report,true);SimilarityStore.refresh(library);library.checkCancelled();MusicSyncJob.result(library,library.downloadSummary,true);}
    else {JSONArray a=new JSONArray(payload);List<JSONObject> games=new ArrayList<>();for(int k=0;k<a.length();k++)games.add(a.getJSONObject(k));library.download(games,report);report.update("Проверяю индекс похожей музыки…");SimilarityStore.refresh(library);}
    progress=library.downloadSummary;
   }
   catch(Exception e){if((sync||archive)&&!library.cancel&&!Thread.currentThread().isInterrupted())MusicSyncJob.result(library,"Обновление отложено: "+e.getMessage(),false);progress=library.cancel||Thread.currentThread().isInterrupted()?"Загрузка приостановлена":(archive||sync&&library.archive.hasDownload())?"ZIP не обновлён · "+e.getMessage():library.networkAllowed()?"Загрузка продолжится автоматически: "+e.getMessage():"Ожидание разрешённой сети · загрузка продолжится автоматически";}
   finally{Library.revision.incrementAndGet();if(locked)Library.DOWNLOAD_LOCK.unlock();if(wake.isHeld())wake.release();LibraryMaintenanceJob.schedule(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
  });return START_NOT_STICKY;
 }
 public void onTimeout(int startId,int fgsType){library.cancelWork();stopSelf();}
 public void onDestroy(){library.cancelWork();io.shutdownNow();if(instance==this)instance=null;super.onDestroy();}
}
