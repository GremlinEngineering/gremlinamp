package ru.oopssre.gremlinamp;
import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.media.*;
import android.media.session.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class PlaybackService extends Service {
 public static final class Entry {final JSONObject game,manifest;final int index; Entry(JSONObject g,JSONObject m,int i){game=g;manifest=m;index=i;}}
 public static volatile PlaybackService instance;final PlaybackIdentity identity=new PlaybackIdentity();
 final PlaybackHistory history=new PlaybackHistory();
 final ExecutorService artworkIo=Executors.newFixedThreadPool(2);Future<?> artworkWork;int skippedUnavailable,pendingResume=-1;
 final ExecutorService continuationIo=Executors.newSingleThreadExecutor();final Set<String> playedAudio=new HashSet<>();volatile int previewRequest;final Set<String> playedGames=new HashSet<>();final Set<Integer> playedTracks=new HashSet<>();volatile String nextGame="";boolean localOnly;
 final ExecutorService executor=Executors.newSingleThreadExecutor();final Handler main=new Handler(Looper.getMainLooper());
 volatile List<Entry> queue=Collections.emptyList();volatile int position;volatile int generation;volatile boolean paused;volatile String title="Готов к музыке",error="";volatile long elapsed;
 volatile String state="idle";volatile long duration;volatile boolean durationKnown;int skippedShort;volatile long seekTo=-1;boolean mediaReady;volatile boolean destroyed;volatile String artworkPath="";volatile String gameTitle="";
 volatile boolean shuffle,repeat,repeatCollection;MediaPlayer media;MediaSession session;AudioManager am;AudioFocusRequest focus;Library library;long handle;Future<?> work;
 public IBinder onBind(Intent i){return null;}
 public void onCreate(){super.onCreate();instance=this;library=new Library(this);shuffle=library.preferences.getBoolean("shuffle",false);repeat=library.preferences.getBoolean("repeat",false);repeatCollection=library.preferences.getBoolean("loop",false);am=(AudioManager)getSystemService(AUDIO_SERVICE);
  ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("play","Музыка",NotificationManager.IMPORTANCE_LOW));
  session=new MediaSession(this,"GremlinAmp");session.setCallback(new MediaSession.Callback(){public void onPlay(){resume();}public void onPause(){pause();}public void onSkipToNext(){next();}public void onSkipToPrevious(){previous();}public void onStop(){stopSelf();}});
  session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);session.setActive(true);
  focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(v->{if(v<0)pause();}).build();
  registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));startForeground(1,notification());
 }
 final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){pause();}};
 public int onStartCommand(Intent i,int f,int id){if(i!=null)switch(i.getStringExtra("action")==null?"":i.getStringExtra("action")){case "toggle"->toggle();case "next"->next();case "previous"->previous();}return START_NOT_STICKY;}
 PendingIntent action(String a,int code){return PendingIntent.getService(this,code,new Intent(this,PlaybackService.class).putExtra("action",a),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
 Notification notification(){return new Notification.Builder(this,"play").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(error.isEmpty()?"Музыка Аркадного зала":error).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2)).addAction(android.R.drawable.ic_media_previous,"Назад",action("previous",1)).addAction(paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,paused?"Играть":"Пауза",action("toggle",2)).addAction(android.R.drawable.ic_media_next,"Далее",action("next",3)).setOnlyAlertOnce(true).build();}
 void announce(){main.post(()->{if(destroyed)return;session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_STOP).setState(state.equals("loading")?PlaybackState.STATE_BUFFERING:state.equals("error")?PlaybackState.STATE_ERROR:state.equals("ended")?PlaybackState.STATE_STOPPED:paused?PlaybackState.STATE_PAUSED:PlaybackState.STATE_PLAYING,elapsed,paused?0:1).build());((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());});}
 public void play(List<Entry> list,int index){play(list,index,false);}
 public void enqueue(List<Entry> entries,boolean downloadedOnly){if(entries.isEmpty())return;if(queue.isEmpty()){play(entries,0,downloadedOnly);return;}int first=queue.size();List<Entry> combined=new ArrayList<>(queue);combined.addAll(entries);queue=combined;localOnly=localOnly&&downloadedOnly;if(state.equals("ended")||state.equals("error"))pendingResume=first;announce();}
 public void play(List<Entry> list,int index,boolean downloadedOnly){if(list.isEmpty()||index<0||index>=list.size())return;if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){pause();error="Не удалось получить аудиофокус";state="error";announce();return;}queue=new ArrayList<>(list);history.clear();playedGames.clear();playedAudio.clear();playedTracks.clear();localOnly=downloadedOnly;skippedShort=0;skippedUnavailable=0;start(index);}
 synchronized void start(int index){if(index<0||index>=queue.size())return;if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){error="Не удалось получить аудиофокус";announce();return;}
  generation++;int token=generation;if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();position=index;pendingResume=-1;paused=false;elapsed=0;seekTo=-1;error="";state="loading";artworkPath="";
  Entry e=queue.get(index);playedTracks.add(index);previewNext(e.game,token);JSONObject t=e.manifest.optJSONArray("tracks").optJSONObject(e.index);title=t.optString("title");gameTitle=e.game.optString("title");identity.begin(token,title,gameTitle);durationKnown=library.displayDuration(t)>=0;duration=durationKnown?library.displayDuration(t):t.optLong("play_ms",120000);session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ALBUM,gameTitle).build());announce();
  if(library.hiddenShort(t)){main.post(()->{if(token!=generation)return;if(++skippedShort>=queue.size())finishQueue("Все композиции скрыты фильтром");else advance(false);});return;}skippedShort=0;
  loadArtwork(e,t,token);
  work=executor.submit(()->{try{if((localOnly||!library.networkAllowed())&&!library.available(t.getJSONObject("asset")))throw new IOException("Композиция не скачана");File file=library.fetch(e.manifest,t.getJSONObject("asset"));if(token!=generation)return;
   if(t.optString("backend").equals("gme"))nativePlay(file,t,token);else main.post(()->mediaPlay(file,token));
  }catch(Exception ex){if(token==generation&&!Thread.currentThread().isInterrupted()){final String failure=String.valueOf(ex.getMessage());main.post(()->skipUnavailable(token,failure));}}});
 }
 void loadArtwork(Entry e,JSONObject t,int token){
  if(artworkWork!=null)artworkWork.cancel(true);
  artworkWork=artworkIo.submit(()->{try{File cached=library.cachedPicture(e.game,false,false);if(cached.isFile())publishArtwork(cached,t,e.game,token);if(token!=generation||destroyed||localOnly||!library.networkAllowed())return;File file=library.cover(e.game);if(file.isFile()&&!file.equals(cached))publishArtwork(file,t,e.game,token);}catch(Exception ignored){}});
 }
 void publishArtwork(File file,JSONObject t,JSONObject game,int token){
  if(token!=generation||destroyed)return;android.graphics.Bitmap bitmap=Library.artwork(file);
  main.post(()->{if(token!=generation||destroyed)return;artworkPath=file.toString();identity.artwork(token,artworkPath);MediaMetadata.Builder meta=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.optString("title")).putString(MediaMetadata.METADATA_KEY_ALBUM,game.optString("title")).putString(MediaMetadata.METADATA_KEY_ARTIST,t.optString("artist"));if(durationKnown)meta.putLong(MediaMetadata.METADATA_KEY_DURATION,duration);if(bitmap!=null)meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,bitmap);session.setMetadata(meta.build());});
 }
 void began(int token){if(token!=generation||destroyed)return;skippedUnavailable=0;history.record(position);Entry e=queue.get(position);playedGames.add(e.game.optString("id"));playedAudio.add(SimilarityRules.key(e.manifest.optJSONArray("tracks").optJSONObject(e.index)));}
 void skipUnavailable(int token,String reason){if(token!=generation||destroyed)return;releaseMedia();if(++skippedUnavailable>=queue.size()){finishQueue("Нет доступных композиций · "+reason);return;}error="Пропущено: "+title+" · "+reason;advance(false);}
 void finishQueue(String reason){generation++;previewRequest++;if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();paused=true;state="ended";error=reason;nextGame="";announce();}
 void releaseMedia(){mediaReady=false;MediaPlayer old=media;media=null;if(old!=null){old.setOnPreparedListener(null);old.setOnCompletionListener(null);old.setOnErrorListener(null);old.release();}}
 void mediaPlay(File file,int token){if(token!=generation||destroyed)return;try{media=new MediaPlayer();MediaPlayer p=media;p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());p.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);p.setDataSource(file.toString());p.setOnPreparedListener(v->{if(token==generation&&media==v){mediaReady=true;duration=v.getDuration();durationKnown=true;began(token);if(!paused)v.start();state=paused?"paused":"playing";announce();}});p.setOnCompletionListener(v->{if(token==generation)advance(true);});p.setOnErrorListener((v,a,b)->{if(token==generation)skipUnavailable(token,"Ошибка аудио "+a+"/"+b);return true;});p.prepareAsync();}catch(Exception ex){skipUnavailable(token,String.valueOf(ex.getMessage()));}}
 void nativePlay(File file,JSONObject track,int token)throws Exception {
  long h=Gme.open(file.toString(),track.optInt("subsong"));AudioTrack out=null;PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:PCM");
  try{wake.acquire(6*60*60*1000L);int size=Math.max(16384,AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT));out=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();if(token!=generation)return;out.play();main.post(()->{if(token!=generation)return;began(token);state=paused?"paused":"playing";announce();});
   long known=library.knownDuration(track);long frames=0,play=known>=0?known:track.optLong("play_ms",120000),fade=known>=0&&!track.optBoolean("duration_known")?0:Math.max(0,track.optLong("fade_ms",5000));short[] pcm=new short[4096];
   while(token==generation&&!Thread.currentThread().isInterrupted()){
    if(seekTo>=0){long target=seekTo;seekTo=-1;out.pause();out.flush();Gme.seek(h,(int)target);frames=target*44100/1000;final long sought=target;main.post(()->{if(token==generation)elapsed=sought;});if(!paused)out.play();}
    if(paused){out.pause();Thread.sleep(50);continue;}if(out.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)out.play();
    if(frames*1000/44100>=play+fade||Gme.ended(h))break;Gme.render(h,pcm);
    for(int i=0;i<pcm.length;i+=2){long ms=(frames+i/2)*1000/44100;double gain=ms<play?1:fade==0?0:Math.max(0,1.0-(double)(ms-play)/fade);pcm[i]=(short)(pcm[i]*gain);pcm[i+1]=(short)(pcm[i+1]*gain);}
    int off=0;while(off<pcm.length&&token==generation){int n=out.write(pcm,off,pcm.length-off,AudioTrack.WRITE_BLOCKING);if(n<0)throw new IOException("AudioTrack "+n);off+=n;}frames+=pcm.length/2;final long rendered=Math.min(frames*1000/44100,play+fade);main.post(()->{if(token==generation)elapsed=rendered;});
   }
   if(token==generation)main.post(()->{if(token==generation)advance(true);});
  }finally{Gme.close(h);if(out!=null)out.release();if(wake.isHeld())wake.release();}
 }
 public void toggle(){if(queue.isEmpty())return;if(paused)resume();else pause();}
 public void pause(){if(queue.isEmpty())return;if(state.equals("continuing")){generation++;paused=true;state="ended";announce();return;}paused=true;if(media!=null&&mediaReady)try{if(media.isPlaying())media.pause();}catch(IllegalStateException ignored){}if(!state.equals("error")&&!state.equals("ended"))state="paused";announce();}
 public void resume(){if(queue.isEmpty())return;if(state.equals("ended")||state.equals("error")){start(pendingResume>=0?pendingResume:position);return;}if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return;paused=false;if(media!=null&&mediaReady)try{media.start();}catch(IllegalStateException e){start(position);return;}state=mediaReady||state.equals("paused")?"playing":"loading";announce();}
 public void next(){skippedUnavailable=0;if(state.equals("ended")&&pendingResume>=0){start(pendingResume);return;}if(state.equals("continuing")){finishQueue("");return;}advance(false);}
 void advance(boolean natural){
  if(queue.isEmpty()||state.equals("continuing"))return;
  int i=-1;
  if(natural&&repeat)i=position;
  else {if(skippedUnavailable==0&&skippedShort==0&&history.current()==position)i=history.next();if(i<0)i=ContinuationRules.nextIndex(position,queue.size(),playedTracks,shuffle,repeatCollection,new Random());}
  if(i<0){if(!repeat&&!repeatCollection&&!library.preferences.getString("after_album","stop").equals("stop")){continueAlbum();return;}finishQueue("");return;}start(i);
 }
 void previewNext(JSONObject game,int token){final int preview=++previewRequest;nextGame="";for(int k=position+1;k<queue.size();k++){Entry e=queue.get(k);if(!e.game.optString("id").equals(game.optString("id"))){nextGame=e.game.optString("title");return;}}
  if(library.preferences.getString("after_album","stop").equals("stop")||repeat||repeatCollection)return;
  String acousticMode=library.preferences.getString("after_album","stop");if(SimilarityRules.mode(acousticMode)){JSONObject seed=queue.get(position).manifest.optJSONArray("tracks").optJSONObject(queue.get(position).index);Set<String> listened=new HashSet<>(playedAudio);boolean onlyLocal=localOnly||library.preferences.getBoolean("continue_local",true);continuationIo.submit(()->{try{MusicSimilarity.Selection selected=MusicSimilarity.next(library,seed,acousticMode,listened,onlyLocal,true,()->token==generation&&preview==previewRequest&&!destroyed);main.post(()->{if(token==generation&&preview==previewRequest&&!destroyed&&selected!=null)nextGame=selected.manifest.optJSONArray("tracks").optJSONObject(selected.index).optString("title")+" · "+selected.game.optString("title");});}catch(Exception ignored){}});return;}
  Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationIo.submit(()->{try{List<JSONObject> candidates=Continuation.candidates(library,game,seen);String found="";for(JSONObject g:candidates){JSONObject m=localOnly||library.preferences.getBoolean("continue_local",true)?library.localManifest(g):library.cachedManifest(g);if(m==null){if(!library.preferences.getBoolean("continue_local",true)&&!localOnly){found=g.optString("title");break;}continue;}JSONArray ts=m.getJSONArray("tracks");boolean usable=false;for(int k=0;k<ts.length();k++)if(!library.hiddenShort(ts.getJSONObject(k))&&(!localOnly&&!library.preferences.getBoolean("continue_local",true)||library.available(ts.getJSONObject(k).getJSONObject("asset")))){usable=true;break;}if(usable){found=g.optString("title");break;}}final String name=found;main.post(()->{if(!destroyed&&token==generation&&preview==previewRequest)nextGame=name;});}catch(Exception ignored){}});
 }
 void continueAlbum(){if(SimilarityRules.mode(library.preferences.getString("after_album","stop"))){continueMusic();return;}final int token=beginContinuation();JSONObject current=queue.get(position).game;Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationIo.submit(()->{try{List<JSONObject> choices=Continuation.candidates(library,current,seen);List<Entry> next=new ArrayList<>();String title="";boolean downloaded=localOnly||library.preferences.getBoolean("continue_local",true);
   for(JSONObject game:choices){if(Thread.currentThread().isInterrupted()||token!=generation)return;JSONObject manifest=downloaded?library.localManifest(game):library.manifest(game,false);if(manifest==null)continue;JSONArray tracks=manifest.getJSONArray("tracks");for(int k=0;k<tracks.length();k++){JSONObject t=tracks.getJSONObject(k);if(!library.hiddenShort(t)&&(!downloaded||library.available(t.getJSONObject("asset"))))next.add(new Entry(game,manifest,k));}if(!next.isEmpty()){title=game.optString("title");break;}}
   final String chosen=title;main.post(()->{if(destroyed||token!=generation)return;if(paused){state="ended";announce();return;}if(next.isEmpty()){paused=true;state="ended";error="Нет следующей игры для выбранного режима и фильтров";announce();return;}nextGame=chosen;appendContinuation(next);});
  }catch(Exception e){main.post(()->{if(token==generation&&!destroyed){paused=true;state="ended";error="Продолжение недоступно: "+e.getMessage();announce();}});}});
 }
 void continueMusic(){final String mode=library.preferences.getString("after_album","stop");final int token=beginContinuation();Entry current=queue.get(position);JSONObject seed=current.manifest.optJSONArray("tracks").optJSONObject(current.index);Set<String> listened=new HashSet<>(playedAudio);boolean onlyLocal=localOnly||library.preferences.getBoolean("continue_local",true);
  continuationIo.submit(()->{try{MusicSimilarity.Selection selected=MusicSimilarity.next(library,seed,mode,listened,onlyLocal,false,()->token==generation&&!destroyed);main.post(()->{if(token!=generation||destroyed)return;if(selected==null){paused=true;state="ended";error="Нет похожих композиций для выбранного режима, фильтров и истории";announce();return;}appendContinuation(Collections.singletonList(new Entry(selected.game,selected.manifest,selected.index)));});}catch(Exception e){main.post(()->{if(token==generation&&!destroyed){paused=true;state="ended";error="Продолжение: "+e.getMessage();announce();}});}});
 }
 void refreshContinuation(){previewRequest++;if(queue.isEmpty())return;if(state.equals("continuing")){generation++;paused=true;state="ended";announce();}previewNext(queue.get(position).game,generation);}
 int beginContinuation(){generation++;if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();state="continuing";paused=false;error="";nextGame="";announce();return generation;}
 void appendContinuation(List<Entry> entries){int index=queue.size();List<Entry> combined=new ArrayList<>(queue);combined.addAll(entries);queue=combined;skippedShort=0;skippedUnavailable=0;start(index);}
 public void previous(){if(queue.isEmpty())return;skippedUnavailable=0;int index=history.current();if(index<0)index=position;else if(index==position){int previous=history.previous();if(previous>=0)index=previous;}start(index);}
 public long currentPosition(){if(media!=null&&mediaReady)try{return PlaybackRules.position(media.getCurrentPosition(),duration,durationKnown);}catch(IllegalStateException ignored){}return PlaybackRules.position(elapsed,duration,durationKnown);}
 public void seek(long target){target=Math.max(0,Math.min(target,duration));if(state.equals("loading")||state.equals("idle")||state.equals("error")||state.equals("ended"))return;if(media!=null&&mediaReady){try{media.seekTo((int)target);}catch(IllegalStateException ignored){}}else seekTo=target;}
 public void onDestroy(){destroyed=true;generation++;if(work!=null)work.cancel(true);executor.shutdownNow();continuationIo.shutdownNow();if(artworkWork!=null)artworkWork.cancel(true);artworkIo.shutdownNow();library.cancelTransfers();releaseMedia();main.removeCallbacksAndMessages(null);am.abandonAudioFocusRequest(focus);unregisterReceiver(noisy);session.release();instance=null;super.onDestroy();}
}
