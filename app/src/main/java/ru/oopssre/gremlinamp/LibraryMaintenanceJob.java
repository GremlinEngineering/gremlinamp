package ru.oopssre.gremlinamp;

import android.app.job.*;
import android.content.*;
import android.net.*;
import android.os.Build;

/** Finishes requested downloads and fills artwork for previously saved music. */
public class LibraryMaintenanceJob extends JobService {
 static final int ID=202;
 static volatile LibraryMaintenanceJob active;
 static volatile String status="";
 volatile Library library;Thread worker;

 static void schedule(Context context){
  JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
  if(scheduler.getPendingJob(ID)!=null)return;
  Library l=new Library(context);
  JobInfo.Builder job=new JobInfo.Builder(ID,new ComponentName(context,LibraryMaintenanceJob.class))
   .setPersisted(true).setMinimumLatency(1000).setBackoffCriteria(15*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
  if(l.preferences.getString("network","wifi").equals("any"))job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
  else if(Build.VERSION.SDK_INT>=28)job.setRequiredNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build());
  else job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
  scheduler.schedule(job.build());
 }
 static void reschedule(Context context){((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(ID);schedule(context);}
 public boolean onStartJob(JobParameters params){
  Library current=new Library(this);library=current;
  status="Проверяю офлайн-загрузки…";active=this;
  worker=new Thread(()->{
   boolean locked=false,retry=false;
   try{
    locked=Library.DOWNLOAD_LOCK.tryLock();if(!locked){retry=true;return;}
    if(!current.networkAllowed()){retry=current.needsMaintenance();return;}
    if(!current.needsMaintenance())return;
    Library.Progress report=message->{if(current==library&&!current.cancel)status=message;};
    current.repairSaved(report);
    java.util.List<org.json.JSONObject> pending=current.pendingGames();
    if(!pending.isEmpty())current.download(pending,report);
    retry=current.needsMaintenance();report.update(retry?"Часть оформления пока недоступна · повторим автоматически":"Скачанная музыка готова к работе без сети");
   }catch(Exception e){retry=true;if(current==library&&!current.cancel)status="Загрузка продолжится автоматически: "+e.getMessage();}
   finally{
    if(locked)Library.DOWNLOAD_LOCK.unlock();
    Library.revision.incrementAndGet();
    boolean reschedule=retry;
    new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
     if(current!=library||current.cancel)return;
     if(active==this)active=null;
     jobFinished(params,reschedule);
    });
   }
  },"library-maintenance");
  worker.start();return true;
 }
 public boolean onStopJob(JobParameters params){cancelCurrent();return true;}
 private void cancelCurrent(){
  if(active==this)active=null;
  Library current=library;if(current!=null)current.cancelWork();
  if(worker!=null)worker.interrupt();
 }
 public void onDestroy(){cancelCurrent();super.onDestroy();}
}
