package ru.oopssre.gremlinamp;

import android.app.job.*;
import android.os.*;

public class AppUpdateJob extends JobService {
 private Thread worker;
 public boolean onStartJob(JobParameters params){
  worker=new Thread(()->{
   Thread current=Thread.currentThread();
   try{AppUpdates.check(this);}
   catch(Exception e){if(!current.isInterrupted())AppUpdates.status(this,"Проверка APK: "+e.getMessage());}
   finally{new Handler(Looper.getMainLooper()).post(()->{
    if(worker!=current)return;
    worker=null;jobFinished(params,false);AppUpdates.schedule(this,true);
   });}
  },"apk-update");
  worker.start();return true;
 }
 public boolean onStopJob(JobParameters params){cancelCurrent();return true;}
 public void onDestroy(){cancelCurrent();super.onDestroy();}
 private void cancelCurrent(){if(worker!=null){worker.interrupt();worker=null;}}
}
