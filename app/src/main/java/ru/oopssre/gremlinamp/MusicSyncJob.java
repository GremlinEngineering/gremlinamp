package ru.oopssre.gremlinamp;
import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;
public class MusicSyncJob extends JobService {
 static final int ID=200;static volatile MusicSyncJob active;static volatile String status="";Library library;Future<?> task;final ExecutorService io=Executors.newSingleThreadExecutor();volatile boolean stopped;
 static void schedule(Context c){schedule(c,false);}
 static void schedule(Context c,boolean replace){schedule(c,replace,false);}
 static void schedule(Context c,boolean replace,boolean retry){Library l=new Library(c);JobScheduler js=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);if(!l.preferences.getBoolean("auto_music",false)){js.cancel(ID);return;}if(!replace&&js.getPendingJob(ID)!=null)return;
  JobInfo.Builder builder=new JobInfo.Builder(ID,new ComponentName(c,MusicSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
  if(l.preferences.getString("network","wifi").equals("wifi")&&android.os.Build.VERSION.SDK_INT>=28)builder.setRequiredNetwork(new android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build());
  if(l.preferences.getString("sync_interval","daily").equals("hourly"))builder.setPeriodic(60*60*1000L,10*60*1000L);
  else builder.setMinimumLatency(retry?60*60*1000L:SyncRules.untilMorning(System.currentTimeMillis()));js.schedule(builder.build());
 }
 void finish(JobParameters params,boolean retry){jobFinished(params,false);if(library.preferences.getString("sync_interval","daily").equals("daily"))new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->schedule(this,true,retry),500);}
 static void result(Library l,String text,boolean success){status=text;l.preferences.edit().putString("sync_status",text).putLong(success?"sync_success":"sync_attempt",System.currentTimeMillis()).apply();Library.revision.incrementAndGet();}
 public boolean onStartJob(JobParameters params){library=new Library(this);stopped=false;active=this;
  if(!library.preferences.getBoolean("auto_music",false)){active=null;return false;}
  if(!NetworkPolicy.allowed(this,library.preferences.getString("network","wifi"))){active=null;result(library,"Ожидание разрешённой сети",false);if(library.preferences.getString("sync_interval","daily").equals("daily"))new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->schedule(this,true,true),500);return false;}
  task=io.submit(()->{boolean acquired=false,retry=false;try{acquired=Library.DOWNLOAD_LOCK.tryLock();if(!acquired){retry=true;result(library,"Ожидание окончания текущей загрузки",false);return;}result(library,"Проверяю обновления музыки…",false);library.syncSaved(s->status=s);if(!stopped)SimilarityStore.refresh(library);if(!stopped)result(library,"Музыка обновлена",true);}catch(Exception e){retry=true;if(!stopped)result(library,"Обновление отложено: "+e.getMessage(),false);}finally{if(active==this)active=null;if(acquired)Library.DOWNLOAD_LOCK.unlock();if(!stopped)finish(params,retry);}});return true;
 }
 public boolean onStopJob(JobParameters p){if(active==this)active=null;stopped=true;if(library!=null)library.cancel=true;if(task!=null)task.cancel(true);return true;}
 public void onDestroy(){if(active==this)active=null;stopped=true;if(library!=null)library.cancel=true;io.shutdownNow();super.onDestroy();}
}
