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
 Library library;Thread worker;volatile boolean stopped;

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
  library=new Library(this);active=this;stopped=false;
  worker=new Thread(()->{boolean locked=false,retry=false;
   try{
    locked=Library.DOWNLOAD_LOCK.tryLock();if(!locked){retry=true;return;}
    if(!library.networkAllowed()){retry=library.needsMaintenance();return;}
    if(!library.needsMaintenance())return;
    Library.Progress report=message->status=message;
    library.repairSaved(report);
    if(!library.pendingGames().isEmpty())library.download(library.pendingGames(),report);
    retry=library.needsMaintenance();status=retry?"Часть оформления пока недоступна · повторим автоматически":"Скачанная музыка готова к работе без сети";
   }catch(Exception e){retry=true;if(!stopped)status="Загрузка продолжится автоматически: "+e.getMessage();}
   finally{if(locked)Library.DOWNLOAD_LOCK.unlock();if(active==this)active=null;Library.revision.incrementAndGet();if(!stopped)jobFinished(params,retry);}
  },"library-maintenance");worker.start();return true;
 }
 public boolean onStopJob(JobParameters params){stopped=true;if(active==this)active=null;if(library!=null){library.cancel=true;library.cancelTransfers();}if(worker!=null)worker.interrupt();return true;}
 public void onDestroy(){stopped=true;if(active==this)active=null;if(library!=null){library.cancel=true;library.cancelTransfers();}if(worker!=null)worker.interrupt();super.onDestroy();}
}
