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
 public static final class Entry {
  final JSONObject game,manifest;
  final int index;
  final String seriesId,seriesTitle;
  Entry(JSONObject game,JSONObject manifest,int index){this(game,manifest,index,"","");}
  Entry(JSONObject game,JSONObject manifest,int index,String seriesId,String seriesTitle){
   this.game=game;this.manifest=manifest;this.index=index;this.seriesId=seriesId;this.seriesTitle=seriesTitle;
  }
 }
 public static volatile PlaybackService instance;final PlaybackIdentity identity=new PlaybackIdentity();
 final PlaybackHistory history=new PlaybackHistory();
 private RadioPlayback radio;
 RadioStations.Station station;
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
 Notification notification(){
  Notification.Builder builder=new Notification.Builder(this,"play").setSmallIcon(android.R.drawable.ic_media_play)
   .setContentTitle(title).setContentText(error.isEmpty()?(station==null?"Музыка Аркадного зала":"Интернет-радио"):error)
   .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE))
   .setOnlyAlertOnce(true);
  Notification.MediaStyle style=new Notification.MediaStyle().setMediaSession(session.getSessionToken());
  if(station==null)builder.addAction(android.R.drawable.ic_media_previous,"Назад",action("previous",1));
  builder.addAction(paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,
   paused?"Играть":station==null?"Пауза":"Остановить радио",action("toggle",2));
  if(station==null)builder.addAction(android.R.drawable.ic_media_next,"Далее",action("next",3));
  return builder.setStyle(station==null?style.setShowActionsInCompactView(0,1,2):style.setShowActionsInCompactView(0)).build();
 }
 void announce(){main.post(()->{
  if(destroyed)return;
  long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP;
  if(station==null)actions|=PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS;
  int current=state.equals("loading")?PlaybackState.STATE_BUFFERING:state.equals("error")?PlaybackState.STATE_ERROR:
   state.equals("ended")?PlaybackState.STATE_STOPPED:paused?PlaybackState.STATE_PAUSED:PlaybackState.STATE_PLAYING;
  session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(current,elapsed,paused?0:1).build());
  ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());
 });}
 Entry currentEntry(){return position>=0&&position<queue.size()?queue.get(position):null;}
 String playingSeries(){Entry entry=currentEntry();return entry==null?"":entry.seriesId;}
 void playRadio(RadioStations.Station chosen){
  if(destroyed||chosen.personal()&&!library.preferences.getBoolean(RadioStations.PERSONAL_MODE,false))return;
  if(chosen.equals(station)&&!paused)return;
  if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
   if(station==null)return;
   state="error";paused=true;error="Не удалось получить аудиофокус";announce();return;
  }
  generation++;previewRequest++;int token=generation;
  if(work!=null)work.cancel(true);
  if(artworkWork!=null)artworkWork.cancel(true);
  library.cancelTransfers();releaseMedia();
  queue=Collections.emptyList();history.clear();pendingResume=-1;nextGame="";artworkPath="";
  station=chosen;position=0;elapsed=0;duration=0;durationKnown=false;seekTo=-1;paused=false;error="";state="loading";
  title=chosen.title();gameTitle="Интернет-радио";identity.begin(token,title,gameTitle);
  session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
   .putString(MediaMetadata.METADATA_KEY_ALBUM,gameTitle).build());
  announce();
  if(radio==null)radio=new RadioPlayback(this,main,library::networkAllowed);
  radio.play(chosen.url(),(next,problem)->{
   if(destroyed||token!=generation)return;
   state=next;error=problem;paused=next.equals("error");announce();
  });
 }
 private void releaseRadio(){
  station=null;
  if(radio!=null){radio.release();radio=null;}
 }
 void lockPersonalRadio(){if(station!=null&&station.personal()){pause();stopSelf();}}
 public void play(List<Entry> list,int index){play(list,index,false);}
 public void enqueue(List<Entry> entries,boolean downloadedOnly){if(entries.isEmpty())return;if(queue.isEmpty()){play(entries,0,downloadedOnly);return;}int first=queue.size();List<Entry> combined=new ArrayList<>(queue);combined.addAll(entries);queue=combined;localOnly=localOnly&&downloadedOnly;if(state.equals("ended")||state.equals("error"))pendingResume=first;announce();}
 public void play(List<Entry> list,int index,boolean downloadedOnly){if(list.isEmpty()||index<0||index>=list.size())return;if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){pause();error="Не удалось получить аудиофокус";state="error";announce();return;}releaseRadio();queue=new ArrayList<>(list);history.clear();playedGames.clear();playedAudio.clear();playedTracks.clear();localOnly=downloadedOnly;skippedShort=0;skippedUnavailable=0;start(index);}
 synchronized void start(int index){if(index<0||index>=queue.size())return;if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){error="Не удалось получить аудиофокус";announce();return;}
  generation++;int token=generation;if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();position=index;pendingResume=-1;paused=false;elapsed=0;seekTo=-1;error="";state="loading";artworkPath="";
  Entry e=queue.get(index);playedTracks.add(index);previewNext(e.game,token);JSONObject t=e.manifest.optJSONArray("tracks").optJSONObject(e.index);title=t.optString("title");gameTitle=e.game.optString("title");identity.begin(token,title,e.seriesTitle.isEmpty()?gameTitle:e.seriesTitle+" · "+gameTitle);durationKnown=library.displayDuration(t)>=0;duration=durationKnown?library.displayDuration(t):t.optLong("play_ms",120000);session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ALBUM,gameTitle).build());announce();
  if(library.hiddenShort(t)){main.post(()->{if(token!=generation)return;if(++skippedShort>=queue.size())finishQueue("Все композиции скрыты фильтром");else advance(false);});return;}skippedShort=0;
  loadArtwork(e,t,token);
  work=executor.submit(()->{
   try{
    String decoder=MusicContract.decoder(t);
    if(decoder.isEmpty())throw new IOException(MusicContract.unsupported(t));
    if((localOnly||!library.networkAllowed())&&!library.playableLocal(e.manifest,t))throw new IOException("Композиция не скачана");
    List<File> files=new ArrayList<>();
    JSONObject descriptor=decoder.equals("site")?library.decoderInput(e.manifest,t,files):null;
    File file=descriptor==null?library.fetch(e.manifest,library.playbackAsset(t)):files.get(0);
    if(token!=generation)return;
    if(decoder.equals("media"))main.post(()->mediaPlay(file,token));
    else nativePlay(file,t,decoder.equals("fallback")?"wav":decoder,files,descriptor,token);
   }catch(Exception ex){
    if(token==generation&&!Thread.currentThread().isInterrupted()){
     String failure=String.valueOf(ex.getMessage());main.post(()->skipUnavailable(token,failure));
    }
   }
  });
 }
 void loadArtwork(Entry e,JSONObject t,int token){
  if(artworkWork!=null)artworkWork.cancel(true);
  artworkWork=artworkIo.submit(()->{try{File cached=library.cachedPicture(e.game,false,false);if(cached.isFile())publishArtwork(cached,t,e.game,token);if(token!=generation||destroyed||localOnly||!library.networkAllowed())return;File file=library.cover(e.game);if(file.isFile()&&!file.equals(cached))publishArtwork(file,t,e.game,token);}catch(Exception ignored){}});
 }
 void publishArtwork(File file,JSONObject t,JSONObject game,int token){
  if(token!=generation||destroyed)return;android.graphics.Bitmap bitmap=Library.artwork(file);
  main.post(()->{if(token!=generation||destroyed)return;artworkPath=file.toString();identity.artwork(token,artworkPath);MediaMetadata.Builder meta=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.optString("title")).putString(MediaMetadata.METADATA_KEY_ALBUM,game.optString("title")).putString(MediaMetadata.METADATA_KEY_ARTIST,t.optString("artist"));if(durationKnown)meta.putLong(MediaMetadata.METADATA_KEY_DURATION,duration);if(bitmap!=null)meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,bitmap);session.setMetadata(meta.build());});
 }
 void began(int token){if(token!=generation||destroyed)return;skippedUnavailable=0;history.record(position);Entry e=queue.get(position);playedGames.add(e.game.optString("id"));JSONObject track=e.manifest.optJSONArray("tracks").optJSONObject(e.index);
  playedAudio.add(SimilarityRules.key(track));
  if(!track.optString("id").isEmpty())library.preferences.edit()
   .putString("last_game_id",MusicContract.packageId(e.manifest,e.game))
   .putString("last_track_id",track.optString("id")).apply();}
 void skipUnavailable(int token,String reason){if(token!=generation||destroyed)return;releaseMedia();if(++skippedUnavailable>=queue.size()){finishQueue("Нет доступных композиций · "+reason);return;}error="Пропущено: "+title+" · "+reason;advance(false);}
 void finishQueue(String reason){generation++;previewRequest++;if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();paused=true;state="ended";error=reason;nextGame="";announce();}
 void releaseMedia(){mediaReady=false;MediaPlayer old=media;media=null;if(old!=null){old.setOnPreparedListener(null);old.setOnCompletionListener(null);old.setOnErrorListener(null);old.release();}}
 void mediaPlay(File file,int token){if(token!=generation||destroyed)return;try{media=new MediaPlayer();MediaPlayer p=media;p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());p.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);p.setDataSource(file.toString());p.setOnPreparedListener(v->{if(token==generation&&media==v){mediaReady=true;duration=v.getDuration();durationKnown=true;began(token);if(!paused)v.start();state=paused?"paused":"playing";announce();}});p.setOnCompletionListener(v->{if(token==generation)advance(true);});p.setOnErrorListener((v,a,b)->{if(token==generation)skipUnavailable(token,"Ошибка аудио "+a+"/"+b);return true;});p.prepareAsync();}catch(Exception ex){skipUnavailable(token,String.valueOf(ex.getMessage()));}}
 void nativePlay(File file,JSONObject track,String engine,List<File> files,JSONObject descriptor,int token)throws Exception {
  int subsong=engine.equals("wav")?0:track.optInt("subsong");
  AudioTrack output=null;
  PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:PCM");
  try(ChipDecoder decoder=new ChipDecoder(this,engine,file,subsong,files,descriptor)){
   wake.acquire(6*60*60*1000L);
   int rate=decoder.sampleRate();
   int size=Math.max(16384,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT));
   output=new AudioTrack.Builder()
     .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
     .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
     .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
   if(token!=generation)return;
   output.play();
   main.post(()->{if(token==generation){began(token);state=paused?"paused":"playing";announce();}});

   long play=Math.max(0,track.optLong("play_ms",120000));
   long fade=Math.max(0,track.optLong("fade_ms",0));
   long frames=0,limit=(play+fade)*rate/1000;
   double gain=engine.equals("site")?1:MusicContract.gain(track);
   short[] pcm=new short[4096];
   while(token==generation&&!Thread.currentThread().isInterrupted()){
    if(seekTo>=0){
     long target=seekTo;seekTo=-1;output.pause();output.flush();decoder.seek((int)target);
     frames=target*rate/1000;
     main.post(()->{if(token==generation)elapsed=target;});
     if(!paused)output.play();
    }
    if(paused){output.pause();Thread.sleep(50);continue;}
    if(output.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)output.play();
    if(frames>=limit||decoder.ended())break;
    decoder.render(pcm);
    int samples=2*(int)Math.min(pcm.length/2,limit-frames);
    for(int i=0;i<samples;i+=2){
     long time=(frames+i/2)*1000/rate;
     double scale=gain*(time<play?1:fade==0?0:Math.max(0,1.0-(double)(time-play)/fade));
     pcm[i]=scale(pcm[i],scale);pcm[i+1]=scale(pcm[i+1],scale);
    }
    int offset=0;
    while(offset<samples&&token==generation){
     int n=output.write(pcm,offset,samples-offset,AudioTrack.WRITE_BLOCKING);
     if(n<=0)throw new IOException("AudioTrack "+n);
     offset+=n;
    }
    frames+=samples/2;
    long rendered=frames*1000/rate;main.post(()->{if(token==generation)elapsed=rendered;});
   }
   if(token==generation)main.post(()->{if(token==generation)advance(true);});
  }finally{if(output!=null)output.release();if(wake.isHeld())wake.release();}
 }
 static short scale(short sample,double gain){return (short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(sample*gain)));}

 public void toggle(){if(station==null&&queue.isEmpty())return;if(paused)resume();else pause();}
 public void pause(){if(station!=null){
  generation++;if(radio!=null)radio.stop();paused=true;state="paused";error="";announce();return;
 }if(queue.isEmpty())return;if(state.equals("continuing")){generation++;paused=true;state="ended";announce();return;}paused=true;if(media!=null&&mediaReady)try{if(media.isPlaying())media.pause();}catch(IllegalStateException ignored){}if(!state.equals("error")&&!state.equals("ended"))state="paused";announce();}
 public void resume(){if(station!=null){playRadio(station);return;}if(queue.isEmpty())return;if(state.equals("ended")||state.equals("error")){start(pendingResume>=0?pendingResume:position);return;}if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return;paused=false;if(media!=null&&mediaReady)try{media.start();}catch(IllegalStateException e){start(position);return;}state=mediaReady||state.equals("paused")?"playing":"loading";announce();}
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
  String seriesId=playingSeries();
  Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationIo.submit(()->{try{List<JSONObject> candidates=Continuation.candidates(library,game,seen,seriesId);String found="";for(JSONObject g:candidates){JSONObject m=localOnly||library.preferences.getBoolean("continue_local",true)?library.localManifest(g):library.cachedManifest(g);if(m==null){if(!library.preferences.getBoolean("continue_local",true)&&!localOnly){found=g.optString("title");break;}continue;}JSONArray ts=m.getJSONArray("tracks");boolean usable=false;for(int k=0;k<ts.length();k++)if(!library.hiddenShort(ts.getJSONObject(k))&&(!localOnly&&!library.preferences.getBoolean("continue_local",true)||library.playableLocal(m,ts.getJSONObject(k)))){usable=true;break;}if(usable){found=g.optString("title");break;}}final String name=found;main.post(()->{if(!destroyed&&token==generation&&preview==previewRequest)nextGame=name;});}catch(Exception ignored){}});
 }
 void continueAlbum(){if(SimilarityRules.mode(library.preferences.getString("after_album","stop"))){continueMusic();return;}final int token=beginContinuation();Entry origin=queue.get(position);JSONObject current=origin.game;String mode=library.preferences.getString("after_album","stop");Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationIo.submit(()->{try{List<JSONObject> choices=Continuation.candidates(library,current,seen,origin.seriesId);List<Entry> next=new ArrayList<>();String title="";boolean downloaded=localOnly||library.preferences.getBoolean("continue_local",true);
   for(JSONObject game:choices){if(Thread.currentThread().isInterrupted()||token!=generation)return;JSONObject manifest=downloaded?library.localManifest(game):library.manifest(game,false);if(manifest==null)continue;JSONArray tracks=manifest.getJSONArray("tracks");for(int k=0;k<tracks.length();k++){JSONObject t=tracks.getJSONObject(k);if(!library.hiddenShort(t)&&(!downloaded||library.playableLocal(manifest,t)))next.add(mode.equals("series")?new Entry(game,manifest,k,origin.seriesId,origin.seriesTitle):new Entry(game,manifest,k));}if(!next.isEmpty()){title=game.optString("title");break;}}
   final String chosen=title;main.post(()->{if(destroyed||token!=generation)return;if(paused){state="ended";announce();return;}if(next.isEmpty()){paused=true;state="ended";error=mode.equals("series")?"В текущей серии нет следующей доступной игры":"Нет следующей игры для выбранного режима и фильтров";announce();return;}nextGame=chosen;appendContinuation(next);});
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
 public void seek(long target){if(station!=null)return;target=Math.max(0,Math.min(target,duration));if(state.equals("loading")||state.equals("idle")||state.equals("error")||state.equals("ended"))return;if(media!=null&&mediaReady){try{media.seekTo((int)target);}catch(IllegalStateException ignored){}}else seekTo=target;}
 public void onDestroy(){destroyed=true;generation++;if(work!=null)work.cancel(true);executor.shutdownNow();continuationIo.shutdownNow();if(artworkWork!=null)artworkWork.cancel(true);artworkIo.shutdownNow();library.cancelTransfers();releaseMedia();releaseRadio();main.removeCallbacksAndMessages(null);am.abandonAudioFocusRequest(focus);unregisterReceiver(noisy);session.release();instance=null;super.onDestroy();}
}
