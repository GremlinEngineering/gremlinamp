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
import static ru.oopssre.gremlinamp.TransportState.*;

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
 final AudioEnergy audioEnergy=new AudioEnergy();
 final StreamCache streamCache=new StreamCache(StreamCache.LIMIT);
 private ExecutorService preloadIo;
 private Future<?> preloadWork;
 private boolean preloadEnabled;
 RadioStations.Station station;
 final ExecutorService artworkIo=Executors.newFixedThreadPool(2);Future<?> artworkWork;int pendingResume=-1;
 final Set<Integer> rejectedTracks=new HashSet<>();
 boolean completionPending;
 int startedGeneration=-1;
 final ExecutorService continuationIo=Executors.newSingleThreadExecutor();final Set<String> playedAudio=new HashSet<>();volatile int previewRequest;final Set<String> playedGames=new HashSet<>();final Set<Integer> playedTracks=new HashSet<>();volatile String nextGame="";boolean localOnly;
 final ExecutorService executor=Executors.newSingleThreadExecutor();final Handler main=new Handler(Looper.getMainLooper());
 volatile List<Entry> queue=Collections.emptyList();volatile int position;volatile int generation;volatile String title="Готов к музыке",error="";volatile long elapsed;
 volatile TransportState state=IDLE;volatile long duration;volatile boolean durationKnown;volatile long seekTo=-1;boolean mediaReady;volatile boolean destroyed;volatile String artworkPath="";volatile String gameTitle="";
 volatile boolean shuffle,repeat,repeatCollection;MediaPlayer media;MediaSession session;AudioManager am;AudioFocusRequest focus;Library library;long handle;Future<?> work;
 public IBinder onBind(Intent i){return null;}
 public void onCreate(){super.onCreate();instance=this;library=new Library(this,getPackageName().endsWith(".tv"));if(library.streaming)preloadIo=Executors.newSingleThreadExecutor();shuffle=library.preferences.getBoolean("shuffle",false);repeat=library.preferences.getBoolean("repeat",false);repeatCollection=library.preferences.getBoolean("loop",false);am=(AudioManager)getSystemService(AUDIO_SERVICE);
  ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("play","Музыка",NotificationManager.IMPORTANCE_LOW));
  session=new MediaSession(this,"GremlinAmp");session.setCallback(new MediaSession.Callback(){public void onPlay(){resume();}public void onPause(){pause();}public void onSkipToNext(){next();}public void onSkipToPrevious(){previous();}public void onStop(){stopSelf();}public void onSeekTo(long value){seek(value);}});
  session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);session.setActive(true);
  focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(v->{if(v<0)pause();}).build();
  registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));startForeground(1,notification());
 }
 final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){pause();}};
 public int onStartCommand(Intent i,int f,int id){if(i!=null)switch(i.getStringExtra("action")==null?"":i.getStringExtra("action")){case "toggle"->toggle();case "next"->next();case "previous"->previous();}return START_NOT_STICKY;}
 PendingIntent action(String a,int code){return PendingIntent.getService(this,code,new Intent(this,PlaybackService.class).putExtra("action",a),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
 Notification notification(){
  Intent open=new Intent(this,MainActivity.class);
  if(library.streaming)open.setClassName(this,"ru.oopssre.gremlinamp.TvActivity");
  Notification.Builder builder=new Notification.Builder(this,"play").setSmallIcon(android.R.drawable.ic_media_play)
   .setContentTitle(title).setContentText(error.isEmpty()?(station==null?"Музыка Аркадного зала":"Интернет-радио"):error)
   .setContentIntent(PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE))
   .setOnlyAlertOnce(true);
  Notification.MediaStyle style=new Notification.MediaStyle().setMediaSession(session.getSessionToken());
  boolean previous=!queue.isEmpty(), toggle=previous||station!=null, next=canNext();
  int actions=0;
  if(previous){builder.addAction(android.R.drawable.ic_media_previous,"Назад",action("previous",1));actions++;}
  if(toggle){
   boolean running=state.wantsPlayback();
   builder.addAction(running?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,
    running?(station==null?"Пауза":"Остановить радио"):"Играть",action("toggle",2));
   actions++;
  }
  if(next){builder.addAction(android.R.drawable.ic_media_next,"Далее",action("next",3));actions++;}
  if(actions==3)style.setShowActionsInCompactView(0,1,2);
  if(actions==2)style.setShowActionsInCompactView(0,1);
  if(actions==1)style.setShowActionsInCompactView(0);
  return builder.setStyle(style).build();
 }
 boolean canNext(){return !queue.isEmpty()&&state.canAdvance(pendingResume>=0);}
 void announce(){main.post(()->{
  if(destroyed)return;
  long actions=PlaybackState.ACTION_STOP;
  if(station!=null||!queue.isEmpty())actions|=state.wantsPlayback()?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY;
  if(!queue.isEmpty())actions|=PlaybackState.ACTION_SKIP_TO_PREVIOUS;
  if(canNext())actions|=PlaybackState.ACTION_SKIP_TO_NEXT;
  if(station==null&&state.canSeek()&&durationKnown&&duration>0&&!completionPending)actions|=PlaybackState.ACTION_SEEK_TO;
  int current=switch(state){
   case IDLE -> PlaybackState.STATE_NONE;
   case LOADING, CONTINUING -> PlaybackState.STATE_BUFFERING;
   case PAUSED, LOADING_PAUSED -> PlaybackState.STATE_PAUSED;
   case PLAYING -> PlaybackState.STATE_PLAYING;
   case ENDED -> PlaybackState.STATE_STOPPED;
   case ERROR -> PlaybackState.STATE_ERROR;
  };
  session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(current,currentPosition(),state==PLAYING?1:0).build());
  ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());
 });}
 Entry currentEntry(){return position>=0&&position<queue.size()?queue.get(position):null;}
 String playingSeries(){Entry entry=currentEntry();return entry==null?"":entry.seriesId;}
 void playRadio(RadioStations.Station chosen){
  if(destroyed||chosen.personal()&&!library.preferences.getBoolean(RadioStations.PERSONAL_MODE,false))return;
  if(chosen.equals(station)&&state.wantsPlayback())return;
  if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
   if(state.wantsPlayback())return;
   error="Не удалось получить аудиофокус";state=state.on(Event.FAIL);announce();return;
  }
  generation++;previewRequest++;int token=generation;
  clearStreamCache();
  if(work!=null)work.cancel(true);
  if(artworkWork!=null)artworkWork.cancel(true);
  library.cancelTransfers();releaseMedia();
  queue=Collections.emptyList();history.clear();pendingResume=-1;completionPending=false;nextGame="";artworkPath="";
  audioEnergy.reset();station=chosen;position=0;elapsed=0;duration=0;durationKnown=false;seekTo=-1;error="";state=state.on(Event.LOAD);
  title=chosen.title();gameTitle="Интернет-радио";identity.begin(token,title,gameTitle);
  session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
   .putString(MediaMetadata.METADATA_KEY_ALBUM,gameTitle).build());
  announce();
  if(radio==null)radio=new RadioPlayback(this,main,library::networkAllowed,library.streaming?audioEnergy:null);
  radio.play(chosen.url(),(next,problem)->{
   if(destroyed||token!=generation)return;
   TransportState updated=state.on(next);
   if(updated==state&&error.equals(problem))return;
   state=updated;error=problem;announce();
  });
 }
 private void releaseRadio(){
  station=null;
  if(radio!=null){radio.release();radio=null;}
 }
 void lockPersonalRadio(){if(station!=null&&station.personal()){pause();stopSelf();}}
 public void play(List<Entry> list,int index){play(list,index,false);}
 public void enqueue(List<Entry> entries,boolean downloadedOnly){
  if(entries.isEmpty())return;
  if(queue.isEmpty()){play(entries,0,downloadedOnly);return;}
  int first=queue.size();
  List<Entry> combined=new ArrayList<>(queue);combined.addAll(entries);queue=combined;
  localOnly=localOnly&&downloadedOnly;
  if(state==CONTINUING){rejectedTracks.clear();start(first);return;}
  if((state==ENDED||state==ERROR)&&pendingResume<0)pendingResume=first;
  announce();
 }
 public void play(List<Entry> list,int index,boolean downloadedOnly){
  if(list.isEmpty()||index<0||index>=list.size())return;
  releaseRadio();
  if(library.streaming){clearStreamCache();preloadEnabled=true;}
  queue=new ArrayList<>(list);history.clear();playedGames.clear();playedAudio.clear();playedTracks.clear();rejectedTracks.clear();
  localOnly=downloadedOnly;start(index);
 }
 synchronized void start(int index){
  if(index<0||index>=queue.size())return;
  generation++;int token=generation;
  cancelPreload();
  if(work!=null)work.cancel(true);
  library.cancelTransfers();releaseMedia();
  audioEnergy.reset();position=index;pendingResume=-1;completionPending=false;elapsed=0;seekTo=-1;error="";state=state.on(Event.LOAD);artworkPath="";
  Entry e=queue.get(index);playedTracks.add(index);previewNext(e.game,token);JSONObject t=e.manifest.optJSONArray("tracks").optJSONObject(e.index);title=t.optString("title");gameTitle=e.game.optString("title");identity.begin(token,title,e.seriesTitle.isEmpty()?gameTitle:e.seriesTitle+" · "+gameTitle);durationKnown=library.displayDuration(t)>=0;duration=durationKnown?library.displayDuration(t):t.optLong("play_ms",120000);session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ALBUM,gameTitle).build());announce();
  if(library.hiddenShort(t)){main.post(()->skipUnavailable(token,"Композиция скрыта фильтром"));return;}
  if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
   error="Не удалось получить аудиофокус";state=state.on(Event.FAIL);announce();return;
  }
  if(!library.streaming)loadArtwork(e,t,token);
  work=executor.submit(()->{
   try{
    String decoder=library.streaming?MusicContract.streamingDecoder(t):MusicContract.decoder(t);
    if(decoder.isEmpty())throw new IOException(MusicContract.unsupported(t));
    if(library.streaming){
     try(StreamCache.Track lease=streamCache.track(true)){
      Library.StreamInputs inputs=library.streamInputs(e.manifest,t,decoder,lease);
      if(token==generation)renderPcm(t,decoder,token,()->new ChipDecoder(this,decoder,inputs));
     }
     return;
    }
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
 void clearStreamCache(){
  preloadEnabled=false;
  cancelPreload();
  streamCache.clear();
 }
 private void cancelPreload(){
  if(preloadWork!=null){preloadWork.cancel(true);preloadWork=null;}
 }
 private void preload(int token,Entry entry){
  if(preloadIo==null||!preloadEnabled||entry.seriesId.isEmpty())return;
  List<Entry> queued=queue;
  int next=position+1;
  preloadWork=preloadIo.submit(()->{
   for(int i=next;i<queued.size();i++){
    if(token!=generation||Thread.currentThread().isInterrupted())return;
    Entry item=queued.get(i);
    JSONObject track=item.manifest.optJSONArray("tracks").optJSONObject(item.index);
    String decoder=MusicContract.streamingDecoder(track);
    if(decoder.isEmpty())continue;
    try(StreamCache.Track lease=streamCache.track(false)){
     library.streamInputs(item.manifest,track,decoder,lease);
    }catch(Exception e){return;}
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
 void began(int token){if(token!=generation||destroyed||token==startedGeneration)return;startedGeneration=token;rejectedTracks.clear();history.record(position);Entry e=queue.get(position);playedGames.add(e.game.optString("id"));JSONObject track=e.manifest.optJSONArray("tracks").optJSONObject(e.index);
  playedAudio.add(SimilarityRules.key(track));
  if(library.streaming)preload(token,e);
  if(!track.optString("id").isEmpty())library.preferences.edit()
   .putString("last_game_id",MusicContract.packageId(e.manifest,e.game))
   .putString("last_track_id",track.optString("id")).apply();}
 void skipUnavailable(int token,String reason){
  if(token!=generation||destroyed)return;
  boolean userPaused=state==LOADING_PAUSED||state==PAUSED;
  releaseMedia();
  rejectedTracks.add(position);
  error=reason;
  if(userPaused||rejectedTracks.size()>=queue.size()){
   finishQueue("Нет доступной композиции · "+reason,Event.FAIL);return;
  }
  advance(false);
 }
 void finishQueue(String reason){finishQueue(reason,Event.FINISH);}
 void finishQueue(String reason,Event event){
  generation++;previewRequest++;cancelPreload();if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();
  completionPending=false;state=state.on(event);error=reason;nextGame="";announce();
 }
 void ready(int token){
  if(token!=generation||destroyed)return;
  TransportState next=state.on(Event.READY);
  if(next==state)return;
  state=next;if(next==PLAYING)began(token);announce();
 }
 void completed(int token){
  if(token!=generation||destroyed)return;
  if(state==PAUSED){completionPending=true;announce();}
  else if(state==PLAYING)advance(true);
 }
 void releaseMedia(){mediaReady=false;MediaPlayer old=media;media=null;if(old!=null){old.setOnPreparedListener(null);old.setOnCompletionListener(null);old.setOnErrorListener(null);old.release();}}
 void mediaPlay(File file,int token){
  if(token!=generation||destroyed)return;
  try{
   media=new MediaPlayer();MediaPlayer p=media;
   p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
   p.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);p.setDataSource(file.toString());
   p.setOnPreparedListener(v->{
    if(token!=generation||media!=v)return;
    try{
     mediaReady=true;duration=v.getDuration();durationKnown=duration>=0;
     if(state==LOADING)v.start();
     ready(token);
    }catch(RuntimeException e){skipUnavailable(token,String.valueOf(e.getMessage()));}
   });
   p.setOnCompletionListener(v->completed(token));
   p.setOnErrorListener((v,a,b)->{if(token==generation)skipUnavailable(token,"Ошибка аудио "+a+"/"+b);return true;});
   p.prepareAsync();
  }catch(Exception ex){skipUnavailable(token,String.valueOf(ex.getMessage()));}
 }
 void nativePlay(File file,JSONObject track,String engine,List<File> files,JSONObject descriptor,int token)throws Exception {
  int subsong=engine.equals("wav")?0:track.optInt("subsong");
  renderPcm(track,engine,token,()->new ChipDecoder(this,engine,file,subsong,files,descriptor));
 }
 private interface DecoderFactory {ChipDecoder open()throws IOException;}
 private void renderPcm(JSONObject track,String engine,int token,DecoderFactory factory)throws Exception{
  AudioTrack output=null;
  PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:PCM");
  try(ChipDecoder decoder=factory.open()){
   wake.acquire(6*60*60*1000L);
   int rate=decoder.sampleRate();
   int size=Math.max(16384,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT));
   output=new AudioTrack.Builder()
     .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
     .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
     .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
   if(token!=generation)return;
   if(state.wantsPlayback())output.play();
   main.post(()->ready(token));

   long play=Math.max(0,track.optLong("play_ms",120000));
   long fade=Math.max(0,track.optLong("fade_ms",0));
   long frames=0,limit=(play+fade)*rate/1000;
   double gain=engine.equals("site")?1:MusicContract.gain(track);
   short[] pcm=new short[4096];
   while(token==generation&&!Thread.currentThread().isInterrupted()){
    if(seekTo>=0){
     long target=seekTo;seekTo=-1;output.pause();output.flush();decoder.seek((int)target);
     frames=target*rate/1000;
     main.post(()->{if(token==generation){elapsed=target;announce();}});
     if(state.wantsPlayback())output.play();
    }
    if(!state.wantsPlayback()){output.pause();Thread.sleep(50);continue;}
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
    if(library.streaming&&token==generation&&state==PLAYING)audioEnergy.pcm(pcm,samples,rate);
    frames+=samples/2;
    long rendered=frames*1000/rate;main.post(()->{if(token==generation)elapsed=rendered;});
   }
   if(token==generation)main.post(()->completed(token));
  }finally{if(output!=null)output.release();if(wake.isHeld())wake.release();}
 }
 static short scale(short sample,double gain){return (short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(sample*gain)));}

 public void toggle(){if(state.wantsPlayback())pause();else resume();}
 public void pause(){
  if(!state.wantsPlayback())return;
  if(library.streaming)audioEnergy.reset();
  if(station!=null){
   generation++;if(radio!=null)radio.stop();state=PAUSED;error="";announce();return;
  }
  if(state==CONTINUING){finishQueue("");return;}
  state=state.on(Event.PAUSE);
  if(media!=null&&mediaReady)try{if(media.isPlaying())media.pause();}catch(IllegalStateException ignored){}
  announce();
 }
 public void resume(){
  if(state.wantsPlayback())return;
  if(station!=null){playRadio(station);return;}
  if(queue.isEmpty())return;
  if(state==ENDED||state==ERROR){
   rejectedTracks.clear();start(pendingResume>=0?pendingResume:position);return;
  }
  if(completionPending){
   completionPending=false;state=state.on(Event.RESUME);advance(true);return;
  }
  if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return;
  if(media!=null&&mediaReady)try{media.start();}catch(IllegalStateException e){start(position);return;}
  state=state.on(Event.RESUME);if(state==PLAYING)began(generation);announce();
 }
 public void next(){
  if(!canNext())return;
  rejectedTracks.clear();
  if(state==ENDED||state==ERROR){start(pendingResume);return;}
  advance(false);
 }
 void advance(boolean natural){
  if(queue.isEmpty()||state==CONTINUING)return;
  int i=-1;
  if(natural&&repeat)i=position;
  else {
   if(rejectedTracks.isEmpty()&&history.current()==position)i=history.next();
   if(i<0)i=ContinuationRules.nextIndex(position,queue.size(),playedTracks,rejectedTracks,shuffle,repeatCollection,new Random());
  }
  if(i<0){
   if(!repeat&&!repeatCollection&&!library.preferences.getString("after_album","stop").equals("stop")){continueAlbum();return;}
   if(rejectedTracks.isEmpty())finishQueue("");else finishQueue("Следующая композиция недоступна · "+error,Event.FAIL);
   return;
  }
  start(i);
 }
 void previewNext(JSONObject game,int token){final int preview=++previewRequest;nextGame="";if(shuffle||repeat)return;for(int k=position+1;k<queue.size();k++){Entry e=queue.get(k);if(!e.game.optString("id").equals(game.optString("id"))){nextGame=e.game.optString("title");return;}}
  if(library.preferences.getString("after_album","stop").equals("stop")||repeat||repeatCollection)return;
  String acousticMode=library.preferences.getString("after_album","stop");if(SimilarityRules.mode(acousticMode)){JSONObject seed=queue.get(position).manifest.optJSONArray("tracks").optJSONObject(queue.get(position).index);Set<String> listened=new HashSet<>(playedAudio);boolean onlyLocal=localOnly||library.continueLocal();continuationIo.submit(()->{try{MusicSimilarity.Selection selected=MusicSimilarity.next(library,seed,acousticMode,listened,onlyLocal,true,()->token==generation&&preview==previewRequest&&!destroyed);main.post(()->{if(token==generation&&preview==previewRequest&&!destroyed&&selected!=null)nextGame=selected.manifest.optJSONArray("tracks").optJSONObject(selected.index).optString("title")+" · "+selected.game.optString("title");});}catch(Exception ignored){}});return;}
  String seriesId=playingSeries();
  Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationIo.submit(()->{try{List<JSONObject> candidates=Continuation.candidates(library,game,seen,seriesId);String found="";for(JSONObject g:candidates){JSONObject m=localOnly||library.continueLocal()?library.localManifest(g):library.cachedManifest(g);if(m==null){if(!library.continueLocal()&&!localOnly){found=g.optString("title");break;}continue;}JSONArray ts=m.getJSONArray("tracks");boolean usable=false;for(int k=0;k<ts.length();k++)if(!continuationDecoder(ts.getJSONObject(k)).isEmpty()&&!library.hiddenShort(ts.getJSONObject(k))&&(!localOnly&&!library.continueLocal()||library.playableLocal(m,ts.getJSONObject(k)))){usable=true;break;}if(usable){found=g.optString("title");break;}}final String name=found;main.post(()->{if(!destroyed&&token==generation&&preview==previewRequest)nextGame=name;});}catch(Exception ignored){}});
 }
 private String continuationDecoder(JSONObject track){return library.streaming?MusicContract.streamingDecoder(track):MusicContract.decoder(track);}
 void continueAlbum(){if(SimilarityRules.mode(library.preferences.getString("after_album","stop"))){continueMusic();return;}final int token=beginContinuation();Entry origin=queue.get(position);JSONObject current=origin.game;String mode=library.preferences.getString("after_album","stop");Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationIo.submit(()->{try{List<JSONObject> choices=Continuation.candidates(library,current,seen,origin.seriesId);List<Entry> next=new ArrayList<>();String title="";boolean downloaded=localOnly||library.continueLocal();
   for(JSONObject game:choices){
    if(Thread.currentThread().isInterrupted()||token!=generation)return;
    JSONObject manifest;
    try{manifest=downloaded?library.localManifest(game):library.manifest(game,false);}
    catch(Exception unavailable){continue;}
    if(manifest==null)continue;
    JSONArray tracks=manifest.optJSONArray("tracks");
    if(tracks==null)continue;
    for(int k=0;k<tracks.length();k++){
     JSONObject t=tracks.optJSONObject(k);
     if(t==null||continuationDecoder(t).isEmpty()||library.hiddenShort(t))continue;
     if(downloaded&&!library.playableLocal(manifest,t))continue;
     next.add(mode.equals("series")?new Entry(game,manifest,k,origin.seriesId,origin.seriesTitle):new Entry(game,manifest,k));
    }
    if(!next.isEmpty()){title=game.optString("title");break;}
   }
   final String chosen=title;main.post(()->{if(destroyed||token!=generation)return;if(next.isEmpty()){state=state.on(Event.FINISH);error=mode.equals("series")?"В текущей серии нет следующей доступной игры":"Нет следующей игры для выбранного режима и фильтров";announce();return;}nextGame=chosen;appendContinuation(next);});
  }catch(Exception e){main.post(()->{if(token==generation&&!destroyed){state=state.on(Event.FINISH);error="Продолжение недоступно: "+e.getMessage();announce();}});}});
 }
 void continueMusic(){final String mode=library.preferences.getString("after_album","stop");final int token=beginContinuation();Entry current=queue.get(position);JSONObject seed=current.manifest.optJSONArray("tracks").optJSONObject(current.index);Set<String> listened=new HashSet<>(playedAudio);boolean onlyLocal=localOnly||library.continueLocal();
  continuationIo.submit(()->{try{MusicSimilarity.Selection selected=MusicSimilarity.next(library,seed,mode,listened,onlyLocal,false,()->token==generation&&!destroyed);main.post(()->{if(token!=generation||destroyed)return;if(selected==null){state=state.on(Event.FINISH);error="Нет похожих композиций для выбранного режима, фильтров и истории";announce();return;}appendContinuation(Collections.singletonList(new Entry(selected.game,selected.manifest,selected.index)));});}catch(Exception e){main.post(()->{if(token==generation&&!destroyed){state=state.on(Event.FINISH);error="Продолжение: "+e.getMessage();announce();}});}});
 }
 void refreshContinuation(){previewRequest++;if(queue.isEmpty())return;if(state==CONTINUING)finishQueue("");previewNext(queue.get(position).game,generation);}
 int beginContinuation(){generation++;if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();completionPending=false;state=state.on(Event.CONTINUE);error="";nextGame="";announce();return generation;}
 void appendContinuation(List<Entry> entries){int index=queue.size();List<Entry> combined=new ArrayList<>(queue);combined.addAll(entries);queue=combined;rejectedTracks.clear();start(index);}
 public void previous(){if(queue.isEmpty())return;rejectedTracks.clear();int index=history.current();if(index<0)index=position;else if(index==position){int previous=history.previous();if(previous>=0)index=previous;}start(index);}
 public long currentPosition(){if(media!=null&&mediaReady)try{return PlaybackRules.position(media.getCurrentPosition(),duration,durationKnown);}catch(IllegalStateException ignored){}return PlaybackRules.position(elapsed,duration,durationKnown);}
 public void seek(long target){if(station!=null||!state.canSeek()||!durationKnown||duration<=0||completionPending)return;target=Math.max(0,Math.min(target,duration));if(media!=null&&mediaReady){try{media.seekTo((int)target);}catch(IllegalStateException ignored){}}else seekTo=target;}
 public void onDestroy(){destroyed=true;generation++;clearStreamCache();if(preloadIo!=null)preloadIo.shutdownNow();if(work!=null)work.cancel(true);executor.shutdownNow();continuationIo.shutdownNow();if(artworkWork!=null)artworkWork.cancel(true);artworkIo.shutdownNow();library.cancelTransfers();releaseMedia();releaseRadio();main.removeCallbacksAndMessages(null);am.abandonAudioFocusRequest(focus);unregisterReceiver(noisy);session.release();instance=null;super.onDestroy();}
}
