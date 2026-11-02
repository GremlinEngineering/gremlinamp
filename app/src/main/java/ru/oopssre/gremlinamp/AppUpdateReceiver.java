package ru.oopssre.gremlinamp;
import android.content.*;
import android.app.DownloadManager;
public class AppUpdateReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){String action=i.getAction();if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)){AppUpdates.clear(c);AppUpdates.schedule(c,true);MusicSyncJob.schedule(c,true);LibraryMaintenanceJob.schedule(c);return;}if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)||i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-2)!=AppUpdates.prefs(c).getLong("download",-1))return;
  PendingResult result=goAsync();new Thread(()->{try{AppUpdates.verify(c);AppUpdates.status(c,"Обновление скачано · можно установить");AppUpdates.notifyReady(c);}catch(Exception e){AppUpdates.status(c,"Обновление: "+e.getMessage());}finally{result.finish();}},"verify-apk").start();
 }
}
