package ru.oopssre.gremlinamp;

import android.app.DownloadManager;
import android.content.*;

public class AppUpdateReceiver extends BroadcastReceiver {
 public void onReceive(Context context,Intent intent){
  String action=intent.getAction();
  boolean replaced=Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
  long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
  if(!replaced&&(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)||id<0
       ||id!=AppUpdates.prefs(context).getLong("download",-1)))return;
  PendingResult result=goAsync();
  new Thread(()->{
   try{
    if(replaced){
     AppUpdates.clear(context);AppUpdates.schedule(context,true);
     MusicSyncJob.schedule(context,true);LibraryMaintenanceJob.schedule(context);
    }else AppUpdates.finishDownload(context,id);
   }finally{result.finish();}
  },"apk-update-result").start();
 }
}
