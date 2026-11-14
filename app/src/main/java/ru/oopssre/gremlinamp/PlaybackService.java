package ru.oopssre.gremlinamp;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
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
 SpaceTracker.Mode spaceMode;
 private SpaceSensors spaceSensors;
 private volatile SpaceTracker.Street street=SpaceTracker.Street.QUIET;
 private volatile boolean spaceMotionEnabled=true;
 void street(SpaceTracker.Street input){street=input;}
 private void updateSpaceSensors(){
  boolean active=state.wantsPlayback()&&(spaceMode!=null&&spaceMode.usesMotion()&&spaceMotionEnabled
    ||activeArrangement!=null&&arrangementWalking);
  if(spaceSensors==null&&active)spaceSensors=new SpaceSensors(this,this::spaceMotion);
  if(spaceSensors!=null)spaceSensors.setActive(active);
 }

 private volatile SpaceTracker.Motion spaceMotion=SpaceTracker.Motion.STILL;
 static final String ARRANGEMENT_MODE="arrangement_enabled", ARRANGEMENT_STYLE="arrangement_style",
   ARRANGEMENT_LENGTH="arrangement_length", ARRANGEMENT_WALK="arrangement_walk";
 private record ArrangementOptions(AmbientArrangement.Style style,AmbientArrangement.Length length,int variant){}
 private record CachedTheme(String key,AmbientAnalysis.Theme theme){}
 private volatile CachedTheme cachedTheme;
 private volatile boolean arrangementWalking;
 private int arrangementVariant;
 AmbientArrangement.Style activeArrangement;
 boolean arrangementFallback;
 String arrangementProblem="";

 static AmbientArrangement.Style arrangementStyle(android.content.SharedPreferences p){
  try{return AmbientArrangement.Style.valueOf(p.getString(ARRANGEMENT_STYLE,"AUTO"));}
  catch(IllegalArgumentException invalid){return AmbientArrangement.Style.AUTO;}
 }
 static AmbientArrangement.Length arrangementLength(android.content.SharedPreferences p){
  try{return AmbientArrangement.Length.valueOf(p.getString(ARRANGEMENT_LENGTH,"NORMAL"));}
  catch(IllegalArgumentException invalid){return AmbientArrangement.Length.NORMAL;}
 }
 boolean arrangementEnabled(){return library.preferences.getBoolean(ARRANGEMENT_MODE,false);}
 void refreshArrangement(boolean variation){
  if(currentEntry()==null||state==CONTINUING||state==ENDED)return;
  if(variation)arrangementVariant++;
  long at=currentPosition();
  long oldDuration=durationKnown?duration:0;
  if(state==LOADING||state==LOADING_PAUSED){
   SeekRequest pending=seekRequest.get();
   if(pending!=null&&pending.generation()==generation){at=pending.target();oldDuration=pending.reference();}
  }
  start(position,at,oldDuration,!state.wantsPlayback());
 }
 void arrangementWalking(boolean enabled){
  arrangementWalking=enabled;
  updateSpaceSensors();
 }
 private volatile boolean spaceEffects, voyagerEffects, spacePlasma;
 private volatile OrbitData orbitData;
 private ExecutorService orbitIo;
 private Future<?> orbitWork;
 private long nextOrbitRefresh;
 private final Runnable refreshOrbits=this::updateOrbits;
 private TeahouseWeather teahouseWeather;
 private ExecutorService weatherIo;
 private Future<?> weatherWork;
 private long nextWeatherRefresh;
 private final Runnable refreshWeather=this::updateWeather;
 static final int SLEEP_STEP_MINUTES=5, SLEEP_MAX_MINUTES=720;
 private volatile long sleepDeadline;
 private PowerManager.WakeLock sleepWake;
 boolean sleepExpired;
 private final Runnable sleepAlarm=this::checkSleepTimer;

 long sleepRemaining(){return Math.max(0,sleepDeadline-SystemClock.elapsedRealtime());}
 void sleepTimer(int minutes){
  if(destroyed)return;
  main.removeCallbacks(sleepAlarm);
  releaseSleepWake();
  sleepExpired=false;
  int duration=Math.min(SLEEP_MAX_MINUTES,Math.max(0,minutes));
  sleepDeadline=duration==0?0:SystemClock.elapsedRealtime()+duration*60_000L;
  checkSleepTimer();
 }
 private void checkSleepTimer(){
  if(sleepDeadline==0||destroyed)return;
  long remaining=sleepRemaining();
  if(remaining>0){
   if(state.wantsPlayback()){
    if(sleepWake==null)
     sleepWake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:SleepTimer");
    if(!sleepWake.isHeld())sleepWake.acquire(remaining+1_000L);
   }
   main.removeCallbacks(sleepAlarm);
   main.postDelayed(sleepAlarm,remaining);
   return;
  }
  sleepDeadline=0;
  releaseSleepWake();
  sleepExpired=state.wantsPlayback();
  if(sleepExpired)pause();
 }
 private void releaseSleepWake(){
  if(sleepWake!=null&&sleepWake.isHeld())sleepWake.release();
 }
 private volatile int spaceTempo;
 int spaceTempo(){return spaceTempo;}
 void spaceMotion(SpaceTracker.Motion motion){spaceMotion=motion;}
 void spaceEffects(boolean enabled){spaceEffects=enabled;}
 void voyagerEffects(boolean enabled){voyagerEffects=enabled;}
 void spacePlasma(boolean enabled){spacePlasma=enabled;}

 private void updateOrbits(){
  main.removeCallbacks(refreshOrbits);
  if(destroyed||spaceMode!=SpaceTracker.Mode.ORBITS||!state.wantsPlayback()
    ||!library.streaming&&!TrackerPreferences.refresh(library.preferences,spaceMode))return;
  long now=SystemClock.elapsedRealtime();
  long delay=nextOrbitRefresh-now;
  if(delay<=0){
   delay=OrbitData.REFRESH_MILLIS;
   if(!library.networkAllowed())delay=15_000;
   else if(orbitWork==null||orbitWork.isDone()){
    if(orbitIo==null)orbitIo=Executors.newSingleThreadExecutor();
    OrbitData data=orbitData;
    orbitWork=orbitIo.submit(()->data.refresh(library.networkAllowed()));
    nextOrbitRefresh=now+delay;
   }else delay=15_000;
  }
  main.postDelayed(refreshOrbits,delay);
 }
 String orbitStatus(){
  boolean online=(library.streaming||TrackerPreferences.refresh(library.preferences,SpaceTracker.Mode.ORBITS))&&library.networkAllowed();
  return orbitData==null?"Загружаю архив":orbitData.describe(java.time.Instant.now().getEpochSecond(),online);
 }
 private void updateWeather(){
  main.removeCallbacks(refreshWeather);
  if(destroyed||spaceMode!=SpaceTracker.Mode.WEATHER||!state.wantsPlayback()
    ||!TrackerPreferences.refresh(library.preferences,spaceMode))return;
  long now=SystemClock.elapsedRealtime(), delay=nextWeatherRefresh-now;
  if(delay<=0){
   delay=15_000;
   if(library.networkAllowed()&&(weatherWork==null||weatherWork.isDone())){
    if(weatherIo==null)weatherIo=Executors.newSingleThreadExecutor();
    TeahouseWeather source=teahouseWeather;
    weatherWork=weatherIo.submit(()->{
     try{if(library.networkAllowed())source.refresh();}
     catch(Exception failure){android.util.Log.d("GremlinAmp", "Teahouse weather refresh", failure);}
    });
    delay=TeahouseWeather.REFRESH_MILLIS;
    nextWeatherRefresh=now+delay;
   }
  }
  main.postDelayed(refreshWeather,delay);
 }
 String weatherStatus(){return teahouseWeather.description();}

 void refreshTrackerOptions(SpaceTracker.Mode mode){
  if(library.streaming||spaceMode!=mode)return;
  loadPhoneTrackerOptions(mode);
  if(!spaceMotionEnabled)spaceMotion=SpaceTracker.Motion.STILL;
  updateSpaceSensors();
 }
 void trackerNetworkChanged(SpaceTracker.Mode mode){
  if(library.streaming||spaceMode!=mode)return;
  if(mode==SpaceTracker.Mode.ORBITS){
   if(orbitWork!=null){orbitWork.cancel(true);orbitWork=null;}
   nextOrbitRefresh=0;
   updateOrbits();
  }else if(mode==SpaceTracker.Mode.WEATHER){
   if(weatherWork!=null){weatherWork.cancel(true);weatherWork=null;}
   nextWeatherRefresh=0;
   updateWeather();
  }
 }
 private void loadPhoneTrackerOptions(SpaceTracker.Mode mode){
  spaceMotionEnabled=TrackerPreferences.motion(library.preferences,mode);
  spaceEffects=TrackerPreferences.noise(library.preferences,mode);
  voyagerEffects=TrackerPreferences.noise(library.preferences,SpaceTracker.Mode.VOYAGER);
  spacePlasma=TrackerPreferences.plasma(library.preferences);
 }

 private Bitmap albumArt;
 private String artist="";
 final AudioEnergy audioEnergy=new AudioEnergy();
 volatile boolean handheldSpectrum;
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
 private Future<?> continuationWork;
 final ExecutorService executor=Executors.newSingleThreadExecutor();final Handler main=new Handler(Looper.getMainLooper());
 volatile List<Entry> queue=Collections.emptyList();volatile int position;volatile int generation;volatile String title="Готов к музыке",error="";volatile long elapsed;
 private record SeekRequest(int generation,long target,long reference) {}
 private final java.util.concurrent.atomic.AtomicReference<SeekRequest> seekRequest=new java.util.concurrent.atomic.AtomicReference<>();
 volatile TransportState state=IDLE;volatile long duration;volatile boolean durationKnown;boolean mediaReady;volatile boolean destroyed;volatile String artworkPath="";volatile String gameTitle="";
 boolean durationLoop;
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
   .setContentTitle(title).setContentText(error.isEmpty()?(spaceMode!=null?spaceMode.source:station==null?"Музыка Аркадного зала":"Интернет-радио"):error)
   .setContentIntent(PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE))
   .setOnlyAlertOnce(true);
  Notification.MediaStyle style=new Notification.MediaStyle().setMediaSession(session.getSessionToken());
  boolean previous=!queue.isEmpty(), toggle=previous||station!=null||spaceMode!=null, next=canNext();
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
 private boolean continuationEnabled(){
  return !repeat&&!repeatCollection&&!library.preferences.getString("after_album","stop").equals("stop");
 }
 boolean canNext(){
  return !queue.isEmpty()&&(state.canAdvance(pendingResume>=0)
    ||library.streaming&&state==ENDED&&continuationEnabled());
 }
 void announce(){main.post(()->{
  if(destroyed)return;
  if(sleepDeadline!=0&&!state.wantsPlayback())releaseSleepWake();
  else if(sleepDeadline!=0)checkSleepTimer();
  updateSpaceSensors();
  updateOrbits();
  updateWeather();
  long actions=PlaybackState.ACTION_STOP;
  if(station!=null||spaceMode!=null||!queue.isEmpty())actions|=state.wantsPlayback()?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY;
  if(!queue.isEmpty())actions|=PlaybackState.ACTION_SKIP_TO_PREVIOUS;
  if(canNext())actions|=PlaybackState.ACTION_SKIP_TO_NEXT;
  if(seekAvailable())actions|=PlaybackState.ACTION_SEEK_TO;
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
  sleepExpired=false;
  activeArrangement=null;arrangementFallback=false;arrangementProblem="";
  cancelContinuation();
  clearStreamCache();
  if(work!=null)work.cancel(true);
  if(artworkWork!=null)artworkWork.cancel(true);
  library.cancelTransfers();releaseMedia();
  queue=Collections.emptyList();history.clear();pendingResume=-1;completionPending=false;nextGame="";artworkPath="";
  spaceMode=null;spaceMotion=SpaceTracker.Motion.STILL;spaceTempo=0;
  audioEnergy.reset();station=chosen;position=0;elapsed=0;duration=0;durationKnown=false;durationLoop=false;seekRequest.set(null);error="";state=state.on(Event.LOAD);
  title=chosen.title();gameTitle="Интернет-радио";artist="";albumArt=null;identity.begin(token,title,gameTitle);
  publishMetadata();
  announce();
  if(radio==null)radio=new RadioPlayback(this,main,library::networkAllowed,audioEnergy);
  radio.play(chosen.url(),(next,problem)->{
   if(destroyed||token!=generation)return;
   TransportState updated=state.on(next);
   if(updated==state&&error.equals(problem))return;
   state=updated;error=problem;announce();
  });
 }
 void playSpace(SpaceTracker.Mode mode){
  if(destroyed||library.streaming&&mode.phoneOnly())return;
  if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
   if(state.wantsPlayback())return;
   error="Не удалось получить аудиофокус";state=ERROR;announce();return;
  }
  int token=++generation;
  sleepExpired=false;
  activeArrangement=null;arrangementFallback=false;arrangementProblem="";
  previewRequest++;cancelContinuation();clearStreamCache();
  if(work!=null)work.cancel(true);
  if(artworkWork!=null)artworkWork.cancel(true);
  library.cancelTransfers();releaseMedia();releaseRadio();
  queue=Collections.emptyList();history.clear();pendingResume=-1;completionPending=false;nextGame="";artworkPath="";
  audioEnergy.reset();spaceMode=mode;spaceMotion=SpaceTracker.Motion.STILL;spaceTempo=mode.restTempo;
  spaceEffects=library.preferences.getBoolean("space_effects",false);
  voyagerEffects=library.preferences.getBoolean("voyager_effects",spaceEffects);
  spacePlasma=library.preferences.getBoolean("space_plasma",true);
  spaceMotionEnabled=false;
  if(!library.streaming)loadPhoneTrackerOptions(mode);
  street=SpaceTracker.Street.QUIET;
  position=0;elapsed=0;duration=0;durationKnown=false;durationLoop=false;seekRequest.set(null);error="";state=state.on(Event.LOAD);
  if(mode==SpaceTracker.Mode.ORBITS&&orbitData==null)
   orbitData=new OrbitData(getFilesDir(),()->spaceAsset("orbits.json",32768));
  OrbitData orbits=mode==SpaceTracker.Mode.ORBITS?orbitData:null;
  if(mode==SpaceTracker.Mode.WEATHER&&teahouseWeather==null)
   teahouseWeather=new TeahouseWeather(library.preferences);
  TeahouseWeather weather=mode==SpaceTracker.Mode.WEATHER?teahouseWeather:null;
  title=mode.title;gameTitle=mode.source;artist=mode.earth!=null?"GremlinAmp · Генератор":library.streaming?"Музыка космоса":"Музыка космоса и города";albumArt=null;
  identity.begin(token,title,gameTitle);publishMetadata();announce();
  work=executor.submit(()->{
   try{
    if(mode.earth!=null){
     if(weather!=null&&weather.sound()==EarthTracker.Weather.UNKNOWN){
      try{weather.accept(new JSONObject(new String(spaceAsset("teahouse-weather.json",4096),java.nio.charset.StandardCharsets.UTF_8)));}
      catch(Exception failure){android.util.Log.w("GremlinAmp", "Bundled teahouse weather", failure);}
     }
     renderPcm(null,token,()->new EarthPcm(mode,weather));
     return;
    }
    byte[] data=mode.asset.isEmpty()?new byte[0]:spaceProfile(mode.asset);
    if(orbits!=null)orbits.load();
    short[] plasma=new short[0];
    if(mode==SpaceTracker.Mode.VOYAGER){
     byte[] pcm=spaceAsset("voyager-plasma.pcm",512*1024);
     if((pcm.length&1)!=0)throw new IOException("Повреждена запись Voyager 1");
     plasma=new short[pcm.length/2];
     java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(plasma);
    }
    SpaceTracker tracker=new SpaceTracker(mode,data,mode==SpaceTracker.Mode.CITY?citySeed():new Random().nextLong(),plasma);
    renderPcm(null,token,()->new SpacePcm(tracker,mode,orbits));
   }catch(Exception failure){
    if(token==generation&&!Thread.currentThread().isInterrupted())main.post(()->{
     if(token!=generation||destroyed)return;
     error="Генератор · "+failure.getMessage();state=ERROR;announce();
    });
   }
  });
 }
 private final class EarthPcm implements PcmSource {
  private final EarthTracker tracker;
  private final TeahouseWeather weather;
  private final String clockKey, shiftingKey;
  private long nextClock;
  private int localMinute;
  EarthPcm(SpaceTracker.Mode mode,TeahouseWeather weather){
   tracker=new EarthTracker(mode.earth,new Random().nextLong());
   this.weather=weather;
   clockKey=TrackerPreferences.key(mode,"clock");
   shiftingKey=TrackerPreferences.key(mode,"shifting_accents");
  }
  public int sampleRate(){return EarthTracker.RATE;}
  public int render(short[] pcm,int frames){
   long now=SystemClock.elapsedRealtime();
   if(now>=nextClock){
    java.time.LocalTime time=java.time.LocalTime.now();
    localMinute=time.getHour()*60+time.getMinute();
    nextClock=now+5_000;
   }
   boolean clock=library.preferences.getBoolean(clockKey,true);
   boolean shifting=library.preferences.getBoolean(shiftingKey,true);
   tracker.render(pcm,frames,clock?localMinute:0,weather==null?EarthTracker.Weather.UNKNOWN:weather.sound(),shifting);
   spaceTempo=tracker.tempo();
   return frames;
  }
 }
 private byte[] spaceAsset(String name,int limit)throws IOException{
  try(InputStream input=getAssets().open("space/"+name)){
   ByteArrayOutputStream buffer=new ByteArrayOutputStream();
   byte[] chunk=new byte[4096];
   for(int count;(count=input.read(chunk))!=-1;){
    if(buffer.size()+count>limit)throw new IOException("Недопустимый размер данных сонификации");
    buffer.write(chunk,0,count);
   }
   return buffer.toByteArray();
  }
 }
 private byte[] spaceProfile(String name)throws IOException,JSONException{
  JSONArray profile=new JSONArray(new String(spaceAsset(name+".json",65536),java.nio.charset.StandardCharsets.UTF_8));
  byte[] result=new byte[profile.length()];
  for(int i=0;i<result.length;i++){
   int value=profile.getInt(i);
   if(value<0||value>255)throw new IOException("Некорректный профиль сонификации");
   result[i]=(byte)value;
  }
  return result;
 }
 private static long citySeed(){
  java.time.ZonedDateTime clock=java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"));
  return clock.toLocalDate().toEpochDay()*24+clock.getHour();
 }
 private final class SpacePcm implements PcmSource {
  final SpaceTracker tracker;
  final SpaceTracker.Mode mode;
  final OrbitData orbits;
  OrbitSnapshot previous;
  long lastSignalTime;
  SpacePcm(SpaceTracker tracker,SpaceTracker.Mode mode,OrbitData orbits){this.tracker=tracker;this.mode=mode;this.orbits=orbits;}
  public int sampleRate(){return SpaceTracker.RATE;}
  public int render(short[] pcm,int frames){
   if(orbits!=null){
    long time=System.currentTimeMillis()/1000;
    OrbitSnapshot current=orbits.current();
    if(previous!=current||lastSignalTime!=time){
     tracker.setOrbit(current.signal(time));previous=current;lastSignalTime=time;
    }
   }
   boolean effects=switch(mode){
    case VOYAGER -> voyagerEffects;
    case STARS,SOLAR -> spaceEffects;
    default -> false;
   };
   tracker.render(pcm,frames,mode==SpaceTracker.Mode.ORBITS||!spaceMotionEnabled?SpaceTracker.Motion.STILL:spaceMotion,street,effects,spacePlasma);
   spaceTempo=tracker.tempo();return frames;
  }
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
  releaseRadio();spaceMode=null;spaceMotion=SpaceTracker.Motion.STILL;spaceTempo=0;
  if(library.streaming){clearStreamCache();preloadEnabled=true;}
  queue=new ArrayList<>(list);history.clear();playedGames.clear();playedAudio.clear();playedTracks.clear();rejectedTracks.clear();
  localOnly=downloadedOnly;start(index);
 }
 void selectQueued(int index){
  rejectedTracks.clear();
  start(index);
 }
 synchronized void start(int index){
  start(index,0,0,false);
 }
 private synchronized void start(int index,long initialMs,long referenceMs,boolean paused){
  if(index<0||index>=queue.size())return;
  sleepExpired=false;
  generation++;int token=generation;
  cancelPreload();
  if(work!=null)work.cancel(true);
  library.cancelTransfers();releaseMedia();
  audioEnergy.reset();position=index;pendingResume=-1;completionPending=false;elapsed=0;error="";state=paused?LOADING_PAUSED:LOADING;artworkPath="";
  seekRequest.set(initialMs>0?new SeekRequest(token,initialMs,referenceMs):null);
  activeArrangement=null;arrangementFallback=false;arrangementProblem="";
  ArrangementOptions arrangement=arrangementEnabled()?new ArrangementOptions(arrangementStyle(library.preferences),
    arrangementLength(library.preferences),arrangementVariant):null;
  arrangementWalking=!library.streaming&&arrangement!=null&&library.preferences.getBoolean(ARRANGEMENT_WALK,false);
  Entry e=queue.get(index);playedTracks.add(index);previewNext(e.game,token);JSONObject t=e.manifest.optJSONArray("tracks").optJSONObject(e.index);title=t.optString("title");gameTitle=e.game.optString("title");artist=t.optString("artist");albumArt=null;identity.begin(token,title,e.seriesTitle.isEmpty()?gameTitle:e.seriesTitle+" · "+gameTitle);duration=library.displayDuration(t);durationKnown=duration>=0;publishMetadata();announce();
  durationLoop=library.looping(t);
  if(library.hiddenShort(t)){main.post(()->skipUnavailable(token,"Композиция скрыта фильтром"));return;}
  if(!paused&&am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
   error="Не удалось получить аудиофокус";state=state.on(Event.FAIL);announce();return;
  }
  if(!library.streaming)loadArtwork(e,token);
  work=executor.submit(()->{
   try{
    String decoder=library.streaming?MusicContract.streamingDecoder(t):MusicContract.decoder(t);
    if(decoder.isEmpty())throw new IOException(MusicContract.unsupported(t));
    if(library.streaming){
     try(StreamCache.Track lease=streamCache.track(true)){
      Library.StreamInputs inputs=library.streamInputs(e.manifest,t,decoder,lease);
      if(token==generation)playPcmTrack(t,token,arrangement,decoder,inputs.descriptor(),
        ()->new ChipDecoder(decoder,inputs,t));
     }
     return;
    }
    if((localOnly||!library.networkAllowed())&&!library.playableLocal(e.manifest,t))throw new IOException("Композиция не скачана");
    List<File> files=new ArrayList<>();
    JSONObject descriptor=MusicContract.decoderNeedsInputs(decoder)?library.decoderInput(e.manifest,t,files):null;
    File file=descriptor==null?library.fetch(e.manifest,library.playbackAsset(t)):files.get(0);
    if(token!=generation)return;
    if(decoder.equals("media")){
     if(!renderArrangement(t,token,arrangement,decoder,descriptor,()->new MediaAnalysisPcm(file)))
      main.post(()->mediaPlay(file,t,token));
    }else if(arrangement==null)nativePlay(file,t,decoder,files,descriptor,token);
    else playPcmTrack(t,token,arrangement,decoder,descriptor,()->openChip(file,t,decoder,files,descriptor));
   }catch(Exception ex){
    if(token==generation&&!Thread.currentThread().isInterrupted()){
     String failure=String.valueOf(ex.getMessage());main.post(()->skipUnavailable(token,failure));
    }
   }
  });
 }
 private void playPcmTrack(JSONObject track,int token,ArrangementOptions options,String engine,
                           JSONObject descriptor,DecoderFactory original)throws Exception{
  if(!renderArrangement(track,token,options,engine,descriptor,original))renderPcm(track,token,original);
 }
 private boolean renderArrangement(JSONObject track,int token,ArrangementOptions options,String engine,
                                   JSONObject descriptor,DecoderFactory original)throws Exception{
  if(options==null)return token!=generation;
  AmbientArrangement result;
  try{result=arrange(track,engine,descriptor,options,original);}
  catch(Exception analysisFailure){
   AmbientAnalysis.checkInterrupted();
   String reason=analysisFailure.getMessage();
   main.post(()->{if(token==generation&&!destroyed){
    arrangementFallback=true;
    arrangementProblem=reason==null?"Аранжировка недоступна":reason;
    announce();
   }});
   return token!=generation;
  }
  if(token==generation)renderPcm(track,token,()->result);
  return true;
 }
 private AmbientArrangement arrange(JSONObject track,String engine,JSONObject descriptor,
                                    ArrangementOptions options,DecoderFactory original)throws Exception{
  JSONObject asset=library.playbackAsset(track);
  String key=asset.optString("sha256")+":"+track.optInt("subsong")+":"+track.optLong("play_ms",-1)
    +":"+engine+":"+Objects.toString(descriptor,"");
  CachedTheme cached=cachedTheme;
  AmbientAnalysis.Theme theme;
  if(cached!=null&&cached.key.equals(key))theme=cached.theme;
  else{
   try(PcmSource source=original.open()){
    theme=AmbientAnalysis.read(source,track.optLong("play_ms",-1),key.hashCode());
   }
   cachedTheme=new CachedTheme(key,theme);
  }
  return new AmbientArrangement(theme,options.style,options.length,options.variant,
    ()->arrangementWalking?spaceMotion:SpaceTracker.Motion.STILL);
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
 void loadArtwork(Entry e,int token){
  if(artworkWork!=null)artworkWork.cancel(true);
  artworkWork=artworkIo.submit(()->{try{File cached=library.cachedPicture(e.game,false,false);if(cached.isFile())publishArtwork(cached,token);if(token!=generation||destroyed||localOnly||!library.networkAllowed())return;File file=library.cover(e.game);if(file.isFile()&&!file.equals(cached))publishArtwork(file,token);}catch(Exception ignored){}});
 }
 void publishArtwork(File file,int token){
  if(token!=generation||destroyed)return;
  Bitmap bitmap=Library.artwork(file);
  main.post(()->{
   if(token!=generation||destroyed)return;
   artworkPath=file.toString();identity.artwork(token,artworkPath);albumArt=bitmap;publishMetadata();
  });
 }
 void began(int token){if(token!=generation||destroyed||token==startedGeneration||queue.isEmpty())return;startedGeneration=token;rejectedTracks.clear();history.record(position);Entry e=queue.get(position);playedGames.add(e.game.optString("id"));JSONObject track=e.manifest.optJSONArray("tracks").optJSONObject(e.index);
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
  generation++;previewRequest++;cancelContinuation();cancelPreload();if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();
  completionPending=false;state=state.on(event);error=reason;nextGame="";announce();
 }
 private void publishMetadata(){
  MediaMetadata.Builder metadata=new MediaMetadata.Builder()
   .putString(MediaMetadata.METADATA_KEY_TITLE,title)
   .putString(MediaMetadata.METADATA_KEY_ALBUM,gameTitle)
   .putString(MediaMetadata.METADATA_KEY_ARTIST,artist);
  if(durationKnown)metadata.putLong(MediaMetadata.METADATA_KEY_DURATION,duration);
  if(albumArt!=null)metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,albumArt);
  session.setMetadata(metadata.build());
 }
 void ready(int token){
  if(token!=generation||destroyed)return;
  TransportState next=state.on(Event.READY);
  if(next==state)return;
  state=next;publishMetadata();if(next==PLAYING)began(token);announce();
 }
 void completed(int token){
  if(token!=generation||destroyed)return;
  if(state==PAUSED){completionPending=true;announce();}
  else if(state==PLAYING)advance(true);
 }
 void releaseMedia(){mediaReady=false;MediaPlayer old=media;media=null;if(old!=null){old.setOnPreparedListener(null);old.setOnCompletionListener(null);old.setOnErrorListener(null);old.release();}}
 void mediaPlay(File file,JSONObject track,int token){
  if(token!=generation||destroyed)return;
  try{
   media=new MediaPlayer();MediaPlayer p=media;
   p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
   p.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);p.setDataSource(file.toString());
   p.setOnPreparedListener(v->{
    if(token!=generation||media!=v)return;
    try{
     mediaReady=true;duration=v.getDuration();durationKnown=duration>=0;
     if(durationKnown){
      long decoded=duration;
      artworkIo.submit(()->library.recordDecoderDuration(track,decoded));
     }
     SeekRequest initial=seekRequest.get();
     if(initial!=null&&initial.generation()==token&&seekRequest.compareAndSet(initial,null))
      v.seekTo((int)seekTarget(initial,duration));
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
  renderPcm(track,token,()->openChip(file,track,engine,files,descriptor));
 }
 private PcmSource openChip(File file,JSONObject track,String engine,List<File> files,JSONObject descriptor)throws Exception{
  return descriptor==null?new ChipDecoder(engine,file,track):new ChipDecoder(engine,readDecoderFiles(files,descriptor),track);
 }
 private Library.StreamInputs readDecoderFiles(List<File> files,JSONObject descriptor)throws IOException {
  List<byte[]> buffers=new ArrayList<>(files.size());
  long bytes=0;
  for(File input:files){
   library.checkCancelled();
   bytes+=input.length();
   if(bytes>StreamCache.LIMIT)throw new IOException("Набор файлов превышает допустимый размер");
   buffers.add(java.nio.file.Files.readAllBytes(input.toPath()));
  }
  return new Library.StreamInputs(buffers,descriptor);
 }
 private interface DecoderFactory {PcmSource open()throws Exception;}
 private void renderPcm(JSONObject track,int token,DecoderFactory factory)throws Exception{
  AudioTrack output=null;
  PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GremlinAmp:PCM");
  boolean continuous=track==null;
  try(PcmSource decoder=factory.open()){
   AmbientArrangement arrangement=decoder instanceof AmbientArrangement prepared?prepared:null;
   if(!continuous)wake.acquire(6*60*60*1000L);
   int rate=decoder.sampleRate();
   int size=Math.max(16384,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT));
   output=new AudioTrack.Builder()
     .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
     .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
     .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
   if(token!=generation)return;
   long actualFrames=decoder.duration();
   long play=continuous?0:PlaybackRules.playMs(track);
   long fade=continuous?0:PlaybackRules.fadeMs(track,play);
   long limit=continuous?Long.MAX_VALUE:actualFrames>=0?actualFrames:
    library.playbackEnd(track,library.measuredEnd(track))*rate/1000;
   long endMs=continuous?0:PlaybackRules.frameDuration(limit,rate);
   main.post(()->{
    if(token!=generation||destroyed)return;
    duration=endMs;
    activeArrangement=arrangement==null?null:arrangement.style;
    durationKnown=!continuous&&(actualFrames>=0||endMs>0);
    ready(token);
   });
   if(actualFrames>=0&&arrangement==null)library.recordDecoderDuration(track,endMs);
   double gain=continuous||arrangement!=null?1:MusicContract.gain(track);
   short[] pcm=new short[4096];
   long frames=0,segmentStart=0,headBase=0,lastHead=0;
   boolean sought=false;
   while(token==generation&&!Thread.currentThread().isInterrupted()){
    SeekRequest request=seekRequest.get();
    if(request!=null&&request.generation()==token&&seekRequest.compareAndSet(request,null)){
     sought=true;
     output.pause();
     output.flush();
     if(library.streaming||handheldSpectrum)audioEnergy.reset();
     frames=decoder.seek(Math.min(limit,seekTarget(request,endMs)*rate/1000));
     segmentStart=frames;
     headBase=0;lastHead=0;
     long target=frames*1000/rate;
     main.post(()->{if(token==generation){elapsed=target;announce();}});
    }
    if(!state.wantsPlayback()){
     if(output.getPlayState()==AudioTrack.PLAYSTATE_PLAYING)output.pause();
     if(continuous&&wake.isHeld())wake.release();
     Thread.sleep(50);continue;
    }
    if(continuous&&!wake.isHeld())wake.acquire(30_000);
    if(output.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)output.play();
    long head=Integer.toUnsignedLong(output.getPlaybackHeadPosition());
    if(continuous&&head<lastHead)headBase+=1L<<32;
    lastHead=head;
    long heard=segmentStart+headBase+head;
    long audible=Math.min(heard,frames)*1000/rate;
    main.post(()->{if(token==generation)elapsed=audible;});
    if(frames>=limit||decoder.ended()){
     if(heard>=frames)break;
     Thread.sleep(15);
     continue;
    }
    int requested=(int)Math.min(pcm.length/2,limit-frames);
    int rendered=decoder.render(pcm,requested);
    if(rendered==0&&!decoder.ended())throw new IOException("Декодер остановился без сигнала конца");
    int samples=2*rendered;
    if(!continuous&&arrangement==null)for(int i=0;i<samples;i+=2){
     long time=(frames+i/2)*1000/rate;
     double scale=gain*(actualFrames>=0||time<play?1:fade==0?0:Math.max(0,1.0-(double)(time-play)/fade));
     pcm[i]=scale(pcm[i],scale);pcm[i+1]=scale(pcm[i+1],scale);
    }
    int offset=0;
    while(offset<samples&&token==generation){
     int n=output.write(pcm,offset,samples-offset,AudioTrack.WRITE_BLOCKING);
     if(n<=0)throw new IOException("AudioTrack "+n);
     offset+=n;
    }
    if((library.streaming||handheldSpectrum)&&token==generation&&state==PLAYING)audioEnergy.pcm(pcm,samples,rate);
    frames+=rendered;
   }
   if(token==generation&&!Thread.currentThread().isInterrupted()){
    long naturalEnd=!continuous&&arrangement==null&&actualFrames<0
      &&!sought&&frames>0&&frames<limit&&decoder.ended()?PlaybackRules.frameDuration(frames,rate):-1;
    if(naturalEnd>0)library.recordDecoderDuration(track,naturalEnd,true);
    main.post(()->{
     if(token!=generation||destroyed)return;
     if(naturalEnd>0){duration=naturalEnd;durationKnown=true;elapsed=naturalEnd;publishMetadata();}
     completed(token);
    });
   }
  }finally{if(output!=null)output.release();if(wake.isHeld())wake.release();}
 }
 static short scale(short sample,double gain){return (short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(sample*gain)));}

 public void toggle(){if(state.wantsPlayback())pause();else resume();}
 public void pause(){
  if(!state.wantsPlayback())return;
  if(library.streaming||handheldSpectrum)audioEnergy.reset();
  if(station!=null){
   generation++;if(radio!=null)radio.stop();state=PAUSED;error="";announce();return;
  }
  if(state==CONTINUING){finishQueue("");return;}
  state=state.on(Event.PAUSE);
  if(media!=null&&mediaReady)try{if(media.isPlaying())media.pause();}catch(IllegalStateException ignored){}
  announce();
 }
 public void resume(){
  if(!state.wantsPlayback()&&sleepDeadline!=0&&sleepRemaining()==0)checkSleepTimer();
  sleepExpired=false;
  if(state.wantsPlayback())return;
  if(station!=null){playRadio(station);return;}
  if(spaceMode!=null){
   if(state==ERROR){playSpace(spaceMode);return;}
   if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return;
   state=state.on(Event.RESUME);announce();return;
  }
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
  if((state==ENDED||state==ERROR)&&pendingResume>=0){start(pendingResume);return;}
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
   if(continuationEnabled()){continueAlbum();return;}
   if(rejectedTracks.isEmpty())finishQueue("");else finishQueue("Следующая композиция недоступна · "+error,Event.FAIL);
   return;
  }
  start(i);
 }
 private void cancelContinuation(){
  if(continuationWork!=null){continuationWork.cancel(true);continuationWork=null;}
 }
 void previewNext(JSONObject game,int token){cancelContinuation();final int preview=++previewRequest;nextGame="";if(shuffle||repeat)return;for(int k=position+1;k<queue.size();k++){Entry e=queue.get(k);if(!e.game.optString("id").equals(game.optString("id"))){nextGame=e.game.optString("title");return;}}
  if(library.preferences.getString("after_album","stop").equals("stop")||repeat||repeatCollection)return;
  if(library.streaming)return; // The TV does not show a speculative next-game preview.
  String acousticMode=library.preferences.getString("after_album","stop");if(SimilarityRules.mode(acousticMode)){JSONObject seed=queue.get(position).manifest.optJSONArray("tracks").optJSONObject(queue.get(position).index);Set<String> listened=new HashSet<>(playedAudio);boolean onlyLocal=localOnly||library.continueLocal();continuationWork=continuationIo.submit(()->{try{MusicSimilarity.Selection selected=MusicSimilarity.next(library,seed,acousticMode,listened,onlyLocal,true,()->token==generation&&preview==previewRequest&&!destroyed);main.post(()->{if(token==generation&&preview==previewRequest&&!destroyed&&selected!=null)nextGame=selected.manifest.optJSONArray("tracks").optJSONObject(selected.index).optString("title")+" · "+selected.game.optString("title");});}catch(Exception ignored){}});return;}
  String seriesId=playingSeries();
  Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationWork=continuationIo.submit(()->{try{List<JSONObject> candidates=Continuation.candidates(library,game,seen,seriesId);String found="";for(JSONObject g:candidates){if(Thread.currentThread().isInterrupted()||token!=generation||preview!=previewRequest)return;JSONObject m=localOnly||library.continueLocal()?library.localManifest(g):library.cachedManifest(g);if(m==null){if(!library.continueLocal()&&!localOnly){found=g.optString("title");break;}continue;}JSONArray ts=m.getJSONArray("tracks");boolean usable=false;for(int k=0;k<ts.length();k++)if(!continuationDecoder(ts.getJSONObject(k)).isEmpty()&&!library.hiddenShort(ts.getJSONObject(k))&&(!localOnly&&!library.continueLocal()||library.playableLocal(m,ts.getJSONObject(k)))){usable=true;break;}if(usable){found=g.optString("title");break;}}final String name=found;main.post(()->{if(!destroyed&&token==generation&&preview==previewRequest)nextGame=name;});}catch(Exception ignored){}});
 }
 private String continuationDecoder(JSONObject track){return library.streaming?MusicContract.streamingDecoder(track):MusicContract.decoder(track);}
 void continueAlbum(){if(SimilarityRules.mode(library.preferences.getString("after_album","stop"))){continueMusic();return;}final int token=beginContinuation();Entry origin=queue.get(position);JSONObject current=origin.game;String mode=library.preferences.getString("after_album","stop");Set<String> seen=new HashSet<>(playedGames);for(Entry e:queue)seen.add(e.game.optString("id"));
  continuationWork=continuationIo.submit(()->{try{List<Continuation.Choice> choices=Continuation.choices(library,current,seen,origin.seriesId,mode);List<Entry> next=new ArrayList<>();String title="";boolean downloaded=localOnly||library.continueLocal();
   Exception loadFailure=null;
   for(Continuation.Choice choice:choices){
    if(Thread.currentThread().isInterrupted()||token!=generation)return;
    JSONObject game=choice.game();
    JSONObject manifest;
    try{manifest=downloaded?library.localManifest(game):library.manifest(game,false);}
    catch(Exception unavailable){loadFailure=unavailable;continue;}
    if(manifest==null)continue;
    JSONArray tracks=manifest.optJSONArray("tracks");
    if(tracks==null)continue;
    for(int k=0;k<tracks.length();k++){
     JSONObject t=tracks.optJSONObject(k);
     if(t==null||continuationDecoder(t).isEmpty()||library.hiddenShort(t))continue;
     if(downloaded&&!library.playableLocal(manifest,t))continue;
     next.add(new Entry(game,manifest,k,choice.seriesId(),choice.seriesTitle()));
    }
    if(!next.isEmpty()){title=game.optString("title");break;}
   }
   if(next.isEmpty()&&loadFailure!=null)throw loadFailure;
   final String chosen=title;main.post(()->{if(destroyed||token!=generation)return;if(next.isEmpty()){finishQueue(mode.equals("series")?"В текущей серии нет следующей доступной игры":"Нет следующей игры для выбранного режима и фильтров");return;}nextGame=chosen;appendContinuation(next);});
  }catch(Exception e){main.post(()->{if(token==generation&&!destroyed)finishQueue("Продолжение недоступно: "+e.getMessage());});}});
 }
 void continueMusic(){final String mode=library.preferences.getString("after_album","stop");final int token=beginContinuation();Entry current=queue.get(position);JSONObject seed=current.manifest.optJSONArray("tracks").optJSONObject(current.index);Set<String> listened=new HashSet<>(playedAudio);boolean onlyLocal=localOnly||library.continueLocal();
  continuationWork=continuationIo.submit(()->{try{MusicSimilarity.Selection selected=MusicSimilarity.next(library,seed,mode,listened,onlyLocal,false,()->token==generation&&!destroyed);main.post(()->{if(token!=generation||destroyed)return;if(selected==null){finishQueue("Нет похожих композиций для выбранного режима, фильтров и истории");return;}appendContinuation(Collections.singletonList(new Entry(selected.game,selected.manifest,selected.index)));});}catch(Exception e){main.post(()->{if(token==generation&&!destroyed)finishQueue("Продолжение: "+e.getMessage());});}});
 }
 void refreshContinuation(){
  if(queue.isEmpty())return;
  if(state==CONTINUING){
   if(continuationEnabled())continueAlbum();else finishQueue("");
   return;
  }
  previewNext(queue.get(position).game,generation);
  announce();
 }
 int beginContinuation(){generation++;cancelContinuation();cancelPreload();if(work!=null)work.cancel(true);library.cancelTransfers();releaseMedia();completionPending=false;state=state.on(Event.CONTINUE);error="";nextGame="";announce();return generation;}
 void appendContinuation(List<Entry> entries){int index=queue.size();List<Entry> combined=new ArrayList<>(queue);combined.addAll(entries);queue=combined;rejectedTracks.clear();start(index);}
 public void previous(){if(queue.isEmpty())return;rejectedTracks.clear();int index=history.current();if(index<0)index=position;else if(index==position){int previous=history.previous();if(previous>=0)index=previous;}start(index);}
 public long currentPosition(){if(media!=null&&mediaReady)try{return PlaybackRules.position(media.getCurrentPosition(),duration,durationKnown);}catch(IllegalStateException ignored){}return PlaybackRules.position(elapsed,duration,durationKnown);}
 public boolean seekAvailable(){return station==null&&spaceMode==null&&state.canSeek()&&duration>0&&!completionPending;}
 private static long seekTarget(SeekRequest request,long newDuration){
  long target=request.target();
  if(request.reference()>0&&newDuration>0)
   target=Math.min(newDuration-1,Math.round(target*(double)newDuration/request.reference()));
  return Math.max(0,Math.min(target,newDuration));
 }
 public void seek(long target){if(!seekAvailable())return;target=Math.max(0,Math.min(target,duration));if(media!=null&&mediaReady){try{media.seekTo((int)target);}catch(IllegalStateException ignored){}}else seekRequest.set(new SeekRequest(generation,target,0));}
 void seekRelative(long delta){
  if(!seekAvailable())return;
  SeekRequest pending=seekRequest.get();
  long current=pending!=null&&pending.generation()==generation?pending.target():currentPosition();
  seek(current+delta);
 }
 public void onDestroy(){destroyed=true;releaseSleepWake();if(spaceSensors!=null)spaceSensors.setActive(false);if(orbitIo!=null)orbitIo.shutdownNow();if(weatherIo!=null)weatherIo.shutdownNow();generation++;clearStreamCache();if(preloadIo!=null)preloadIo.shutdownNow();if(work!=null)work.cancel(true);executor.shutdownNow();continuationIo.shutdownNow();if(artworkWork!=null)artworkWork.cancel(true);artworkIo.shutdownNow();library.cancelTransfers();releaseMedia();releaseRadio();main.removeCallbacksAndMessages(null);am.abandonAudioFocusRequest(focus);unregisterReceiver(noisy);session.release();instance=null;super.onDestroy();}
}
