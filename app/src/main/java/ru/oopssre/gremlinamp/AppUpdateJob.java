package ru.oopssre.gremlinamp;
import android.app.job.*;
public class AppUpdateJob extends JobService {
 volatile Thread worker;
 public boolean onStartJob(JobParameters p){worker=new Thread(()->{try{AppUpdates.check(this);}catch(Exception e){AppUpdates.status(this,"Проверка APK: "+e.getMessage());}finally{if(!Thread.currentThread().isInterrupted()){jobFinished(p,false);AppUpdates.schedule(this,true);}}},"apk-update");worker.start();return true;}
 public boolean onStopJob(JobParameters p){if(worker!=null)worker.interrupt();return true;}
}
