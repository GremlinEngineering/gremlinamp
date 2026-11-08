package ru.oopssre.gremlinamp;
import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;
public class MusicSyncJob extends JobService {
 static final int ID=200;static volatile MusicSyncJob active;static volatile String status="";volatile Library library;Future<?> task;final ExecutorService io=Executors.newSingleThreadExecutor();
 static void schedule(Context c){schedule(c,false);}
 static void schedule(Context c,boolean replace){schedule(c,replace,false);}
 static void schedule(Context c,boolean replace,boolean retry){Library l=new Library(c);JobScheduler js=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);if(!l.preferences.getBoolean("auto_music",false)){js.cancel(ID);return;}if(!replace&&js.getPendingJob(ID)!=null)return;
  JobInfo.Builder builder=new JobInfo.Builder(ID,new ComponentName(c,MusicSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
  if(l.preferences.getString("network","wifi").equals("wifi")&&android.os.Build.VERSION.SDK_INT>=28)builder.setRequiredNetwork(new android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build());
  if(l.preferences.getString("sync_interval","daily").equals("hourly"))builder.setPeriodic(60*60*1000L,10*60*1000L);
  else builder.setMinimumLatency(retry?60*60*1000L:SyncRules.untilMorning(System.currentTimeMillis()));js.schedule(builder.build());
 }
 void finish(JobParameters params,Library current,boolean retry){jobFinished(params,false);LibraryMaintenanceJob.schedule(this);if(current.preferences.getString("sync_interval","daily").equals("daily"))new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->schedule(this,true,retry),500);}
 static void result(Library l,String text,boolean success){status=text;l.preferences.edit().putString("sync_status",text).putLong(success?"sync_success":"sync_attempt",System.currentTimeMillis()).apply();Library.revision.incrementAndGet();}
 public boolean onStartJob(JobParameters params){
  Library current=new Library(this);library=current;
  if(!current.preferences.getBoolean("auto_music",false))return false;
  if(!current.networkAllowed()){
   result(current,"Ожидание разрешённой сети",false);
   if(current.preferences.getString("sync_interval","daily").equals("daily"))
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->schedule(this,true,true),500);
   return false;
  }
  status="Проверяю обновления музыки…";active=this;
  task=io.submit(()->{
   boolean acquired=false,retry=false;
   try{
    acquired=Library.DOWNLOAD_LOCK.tryLock();
    if(!acquired){retry=true;if(current==library&&!current.cancel)result(current,"Ожидание окончания текущей загрузки",false);return;}
    current.syncSaved(s->{if(current==library&&!current.cancel)status=s;});
    current.checkCancelled();SimilarityStore.refresh(current);
    if(current==library&&!current.cancel)result(current,current.downloadSummary,true);
   }catch(Exception e){retry=true;if(current==library&&!current.cancel)result(current,"Обновление отложено: "+e.getMessage(),false);}
   finally{
    if(acquired)Library.DOWNLOAD_LOCK.unlock();
    boolean reschedule=retry;
    new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
     if(current!=library||current.cancel)return;
     if(active==this)active=null;
     finish(params,current,reschedule);
    });
   }
  });
  return true;
 }
 public boolean onStopJob(JobParameters p){cancelCurrent();return true;}
 private void cancelCurrent(){
  if(active==this)active=null;
  Library current=library;if(current!=null)current.cancelWork();
  if(task!=null)task.cancel(true);
 }
 public void onDestroy(){cancelCurrent();io.shutdownNow();super.onDestroy();}
}
