package ru.oopssre.gremlinamp;
import android.app.*;import android.os.*;import android.content.*;import android.content.res.Configuration;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;import org.json.*;import java.util.*;import java.util.concurrent.*;

public class MainActivity extends Activity {
 final ExecutorService io=Executors.newFixedThreadPool(3);final ExecutorService artworkIo=Executors.newFixedThreadPool(3);final Handler handler=new Handler(Looper.getMainLooper());Library library;
 List<JSONObject> games=new ArrayList<>(),scope=new ArrayList<>(),archivedGames=new ArrayList<>();java.io.File seenArchive;JSONArray series,trackSearch;JSONObject raIndex,album,manifest,selectedSeries;List<PlaybackService.Entry> albumQueue=new ArrayList<>();
 LinearLayout body,rows,letters;TextView display,time,status,heading,playbackStatus;ImageView cover;EditText search;SeekBar seek;Button play,previousButton,nextButton,platformButton,downloadButton,queueButton,gamesTab,seriesTab,savedTab;TextView collectionStatus;boolean preparingAll,downloadStarting,maintenancePending,clearingDownloads;long startingSequence;Map<String,String> downloadStates=new HashMap<>(),downloadDescriptions=new HashMap<>();long seenRevision=-1,seenSyncSuccess;int statusRequest;boolean previousDownloadActive;CassetteView cassette;String platformFilter="",yearFilter="";String screen="games",letter="",artPath="",displayedArt="";boolean local,dragging;PlaybackService seekOwner;int seekGeneration;int requestId;volatile int playRequest;int preparingSeriesRequest=-1;String preparingSeries="";volatile int artVersion;int bg,panel,fg,muted,accent,line;
 final Deque<BrowseSnapshot> backStack=new ArrayDeque<>();
 final class BrowseSnapshot {
  final String page=screen,query=search==null?"":search.getText().toString(),initial=letter;final boolean downloaded=local;
  final JSONObject seriesItem=selectedSeries,game=album,tracks=manifest;final List<JSONObject> members=new ArrayList<>(scope);final List<PlaybackService.Entry> playlist=new ArrayList<>(albumQueue);
  void restore(){screen=page;local=downloaded;selectedSeries=seriesItem;album=game;manifest=tracks;scope=new ArrayList<>(members);albumQueue=new ArrayList<>(playlist);letter=initial;search.setText(query);alphabet();render();}
 }
 void updateNavigation(){if(gamesTab==null)return;boolean seriesSelected=screen.equals("series")||selectedSeries!=null;Button[] tabs={gamesTab,seriesTab,savedTab};boolean[] chosen={!local&&!seriesSelected,seriesSelected,local};for(int i=0;i<tabs.length;i++){tabs[i].setSelected(chosen[i]);tabs[i].setTextColor(chosen[i]?accent:muted);tabs[i].setTypeface(null,chosen[i]?Typeface.BOLD:Typeface.NORMAL);}}
 SimilarityAvailability acousticAvailability=SimilarityAvailability.unavailable("Проверяю доступность анализа…");String acousticKey="@";long acousticRevision=-1;int acousticRequest;
 String acousticSeed(){PlaybackService p=PlaybackService.instance;if(p==null||p.station!=null)return "";List<PlaybackService.Entry> q=p.queue;int i=p.position;if(i<0||i>=q.size())return "";PlaybackService.Entry e=q.get(i);JSONArray tracks=e.manifest.optJSONArray("tracks");JSONObject t=tracks==null?null:tracks.optJSONObject(e.index);return t==null?"":SimilarityRules.key(t);}
 void ensureAcousticAvailability(){String seed=acousticSeed();long revision=SimilarityStore.revision.get();if(seed.equals(acousticKey)&&revision==acousticRevision)return;acousticKey=seed;acousticRevision=revision;int request=++acousticRequest;acousticAvailability=SimilarityAvailability.unavailable("Проверяю доступность анализа…");io.submit(()->{SimilarityAvailability ready=SimilarityStore.availability(library,seed);runOnUiThread(()->{if(!isFinishing()&&request==acousticRequest&&revision==SimilarityStore.revision.get()&&seed.equals(acousticSeed()))acousticAvailability=ready;});});}
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 GradientDrawable surface(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));d.setStroke(dp(1),line);return d;}
 TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(dp(8),dp(5),dp(8),dp(5));return v;}
 Button button(String name,Runnable click){Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(12);b.setTextColor(accent);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setPadding(dp(8),0,dp(8),0);b.setBackground(surface(panel));b.setOnClickListener(v->click.run());String icon=name.equals("Пред.")?"prev":name.equals("След.")?"next":name.equals("Играть")?"play":null;if(icon!=null){b.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(icon,accent,dp(18)),null,null,null);b.setCompoundDrawablePadding(dp(4));}b.setContentDescription(name);return b;}
 boolean label(TextView view,CharSequence value){if(android.text.TextUtils.equals(view.getText(),value))return false;view.setText(value);return true;}
 void describe(View view,CharSequence value){if(!android.text.TextUtils.equals(view.getContentDescription(),value))view.setContentDescription(value);}
 void enableAction(Button button,boolean enabled){button.setEnabled(enabled);button.setAlpha(enabled?1f:.4f);}
 void weighted(LinearLayout row,View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(v,p);}
 void message(String s){runOnUiThread(()->{if(!isFinishing())label(status,s);});}
 public void onCreate(Bundle state){super.onCreate(state);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);library=new Library(this);seenSyncSuccess=library.preferences.getLong("sync_success",0);platformFilter=library.preferences.getString("platform_filter","");yearFilter=library.preferences.getString("year_filter","");ui();load();MusicSyncJob.schedule(this);LibraryMaintenanceJob.schedule(this);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);handler.post(tick);AppUpdates.schedule(this,false);if(getIntent().getBooleanExtra("app_update",false))handler.post(this::appUpdateSettings);}
 void ui(){String theme=getPreferences(0).getString("theme","auto");boolean dark=theme.equals("dark")||theme.equals("auto")&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
  bg=dark?0xff10151b:0xffe9edf1;panel=dark?0xff1d2630:0xfff9fafb;fg=dark?0xffe8edf4:0xff19232d;muted=dark?0xff98a8b9:0xff566573;accent=dark?0xffffbf69:0xff855000;line=dark?0xff354352:0xffc5cdd5;
  body=new LinearLayout(this);body.setOrientation(1);body.setBackgroundColor(bg);body.setPadding(dp(10),dp(6),dp(10),dp(6));boolean scrollPage=getResources().getConfiguration().screenHeightDp<760||getResources().getConfiguration().fontScale>1.25f;if(scrollPage){ScrollView page=new ScrollView(this);page.setFillViewport(true);page.addView(body);setContentView(page);}else setContentView(body);body.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(10)+i.getSystemWindowInsetLeft(),dp(6)+i.getSystemWindowInsetTop(),dp(10)+i.getSystemWindowInsetRight(),dp(6)+i.getSystemWindowInsetBottom());return i;});
  LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);ImageView mark=new ImageView(this);mark.setImageResource(getResources().getIdentifier("site_icon","drawable",getPackageName()));mark.setContentDescription("Кассетный плеер Аркадного зала");brand.addView(mark,new LinearLayout.LayoutParams(dp(42),dp(42)));TextView name=text("АРКАДНЫЙ ЗАЛ\nШколы Империи · Музыка",15,fg);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));Button mainMenu=button("⋮",this::mainMenu);mainMenu.setContentDescription("Меню библиотеки");brand.addView(mainMenu,new LinearLayout.LayoutParams(dp(48),dp(48)));body.addView(brand);
  LinearLayout player=new LinearLayout(this);player.setPadding(dp(7),dp(7),dp(7),dp(7));player.setBackground(surface(panel));cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);cover.setContentDescription("Увеличить постер игры");cover.setBackgroundColor(dark?0xff0d1217:0xffdce2e7);player.addView(cover,new LinearLayout.LayoutParams(dp(82),dp(116)));cover.setOnClickListener(v->zoom());
  LinearLayout info=new LinearLayout(this);info.setOrientation(1);display=text("Выберите композицию",15,fg);display.setMaxLines(2);LinearLayout playingTitle=new LinearLayout(this);playingTitle.addView(display,new LinearLayout.LayoutParams(0,-2,1));queueButton=button("⋮",this::queueSettings);describe(queueButton,"Режим воспроизведения");playingTitle.addView(queueButton,new LinearLayout.LayoutParams(dp(48),dp(48)));info.addView(playingTitle);time=text("00:00 / --:--",16,dark?0xff9ee89f:0xff246538);time.setTypeface(Typeface.MONOSPACE);LinearLayout tape=new LinearLayout(this);tape.setGravity(Gravity.CENTER_VERTICAL);tape.addView(time,new LinearLayout.LayoutParams(0,dp(32),1));cassette=new CassetteView(this,dark);tape.addView(cassette,new LinearLayout.LayoutParams(dp(58),dp(28)));info.addView(tape);playbackStatus=text("Готов к воспроизведению",11,muted);info.addView(playbackStatus);seek=new SeekBar(this);seek.setMax(1000);seek.setEnabled(false);info.addView(seek,new LinearLayout.LayoutParams(-1,dp(32)));seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){dragging=true;seekOwner=PlaybackService.instance;if(seekOwner!=null)seekGeneration=seekOwner.generation;}public void onStopTrackingTouch(SeekBar b){PlaybackService s=PlaybackService.instance;if(s!=null&&s==seekOwner&&s.generation==seekGeneration)s.seek(s.duration*b.getProgress()/1000);seekOwner=null;dragging=false;}public void onProgressChanged(SeekBar b,int p,boolean u){}});player.addView(info,new LinearLayout.LayoutParams(0,-2,1));body.addView(player);
  LinearLayout controls=new LinearLayout(this);previousButton=button("Пред.",()->transport(s->s.previous()));enableAction(previousButton,false);weighted(controls,previousButton);play=button("Играть",()->transport(s->s.toggle()));weighted(controls,play);nextButton=button("След.",()->transport(s->s.next()));enableAction(nextButton,false);weighted(controls,nextButton);enableAction(play,false);body.addView(controls);
  LinearLayout tabs=new LinearLayout(this);gamesTab=button("Игры",()->browse(false));seriesTab=button("Серии",this::showSeries);savedTab=button("Скачано",()->browse(true));weighted(tabs,gamesTab);weighted(tabs,seriesTab);weighted(tabs,savedTab);body.addView(tabs);
  LinearLayout titleRow=new LinearLayout(this);heading=text("Музыкальная библиотека",15,fg);titleRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));downloadButton=button("Скачать",()->plan(downloadSelection()));titleRow.addView(downloadButton);body.addView(titleRow);collectionStatus=text("",11,muted);body.addView(collectionStatus);
  search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(fg);search.setHintTextColor(muted);search.setHint("Найти игру, серию, трек или RA ID");LinearLayout find=new LinearLayout(this);find.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));platformButton=button("Фильтры",this::filters);platformButton.setContentDescription("Платформа, год и длительность");find.addView(platformButton,new LinearLayout.LayoutParams(dp(94),dp(48)));body.addView(find);search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(android.text.Editable e){}});
  HorizontalScrollView alphabet=new HorizontalScrollView(this);alphabet.setHorizontalScrollBarEnabled(false);letters=new LinearLayout(this);alphabet.addView(letters);body.addView(alphabet,new LinearLayout.LayoutParams(-1,dp(48)));
  status=text("Загрузка каталога…",11,muted);status.setMaxLines(2);body.addView(status);rows=new LinearLayout(this);rows.setOrientation(1);if(scrollPage)body.addView(rows,new LinearLayout.LayoutParams(-1,-2));else{ScrollView scroll=new ScrollView(this);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}if(!artPath.isEmpty())cover.setImageBitmap(Library.artwork(new java.io.File(artPath)));alphabet();render();}
 AlertDialog.Builder dialogBuilder(){boolean dark=bg==0xff10151b;return new AppDialogBuilder(new android.view.ContextThemeWrapper(this,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert),bg,fg,accent,line);}
 void theme(){String[] names={"Как в системе","Тёмная · графит","Светлая · серебро"},values={"auto","dark","light"};dialogBuilder().setTitle("Оформление").setItems(names,(d,i)->{String query=search.getText().toString();getPreferences(0).edit().putString("theme",values[i]).apply();ui();search.setText(query);}).show();}
 String filterLabel(){int seconds=library.preferences.getInt("short_seconds",0);String mode=library.preferences.getString("unknown_duration","show");return mode.equals("only")?"Без времени":mode.equals("hide")?(seconds>0?"От "+seconds+"с · известные":"Известные"):seconds==0?"Длительность":"От "+seconds+" секунд";}
 void appUpdateSettings(){
  LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(12),0,dp(12),0);
  String installed="Установлена версия "+AppUpdates.version(this);
  TextView result=text(installed,13,fg);box.addView(result);
  box.addView(text("Если есть новая версия, плеер скачает её по выбранной сети и откроет установщик Android.",12,muted));
  final boolean[] checking={false},installWhenReady={false};
  final String[] installError={""};
  final AppUpdates.Inspection[] shown={null};
  Button action=button("Проверить обновление",()->{});box.addView(action);
  AlertDialog dialog=dialogBuilder().setTitle("Обновление приложения").setView(box).setNegativeButton("Закрыть",null).create();
  java.util.function.LongConsumer install=id->{
   installError[0]=AppUpdates.install(this,id);
   if(!installError[0].isEmpty())label(result,installError[0]);
  };
  Runnable update=()->{
   AppUpdates.Inspection info=AppUpdates.inspection(this,checking[0]);
   shown[0]=info;
   UpdateRules.Stage stage=info.stage();
   label(action,stage.action);describe(action,stage.action);action.setEnabled(stage.actionable());
   label(result,switch(stage){
    case CHECKING -> "Проверяю обновление…";
    case READY -> installError[0].isEmpty()?"Обновление скачано · можно установить":installError[0];
    default -> AppUpdates.prefs(this).getString("status",installed);
   });
   if(installWhenReady[0]&&stage!=UpdateRules.Stage.CHECKING&&stage!=UpdateRules.Stage.DOWNLOADING){
    installWhenReady[0]=false;
    if(stage==UpdateRules.Stage.READY&&dialog.getWindow()!=null&&dialog.getWindow().getDecorView().hasWindowFocus())
     install.accept(info.id());
   }
  };
  action.setOnClickListener(v->{
   AppUpdates.Inspection info=AppUpdates.inspection(this,checking[0]);
   if(!info.equals(shown[0])){installWhenReady[0]=false;update.run();return;}
   installError[0]="";
   switch(info.stage()){
    case READY -> {installWhenReady[0]=false;install.accept(info.id());}
    case DOWNLOADING -> {
     installWhenReady[0]=false;
     checking[0]=true;update.run();
     io.submit(()->{try{AppUpdates.cancelDownload(this,info.id());}
      finally{runOnUiThread(()->{checking[0]=false;if(dialog.isShowing())update.run();});}});
    }
    case CHECK, RETRY -> {
     installWhenReady[0]=true;
     checking[0]=true;update.run();
     io.submit(()->{
      try{AppUpdates.check(this);}catch(Exception e){AppUpdates.status(this,"Обновление: "+e.getMessage());}
      finally{runOnUiThread(()->{checking[0]=false;if(dialog.isShowing())update.run();});}
     });
    }
    case CHECKING -> { }
   }
  });
  Runnable poll=new Runnable(){public void run(){if(!dialog.isShowing()||isFinishing())return;update.run();handler.postDelayed(this,1000);}};dialog.setOnDismissListener(d->{installWhenReady[0]=false;handler.removeCallbacks(poll);});dialog.show();poll.run();
 }
 boolean downloadsBusy(){return clearingDownloads||downloadStarting||DownloadService.instance!=null;}
 void mainMenu(){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();if(!downloadsBusy()&&!preparingAll){if(!library.archive.hasDownload()){labels.add("Скачать все мелодии");actions.add(this::downloadAll);}labels.add("Обновить библиотеку");actions.add(this::refreshLibrary);}if(!library.preferences.getString("last_track_id","").isEmpty()){labels.add("Последняя композиция");actions.add(this::lastTrack);}if(downloadsBusy()||MusicSyncJob.active!=null||LibraryMaintenanceJob.active!=null||maintenancePending){labels.add("Приостановить загрузку");actions.add(this::pauseDownloads);}if(!clearingDownloads&&(library.hasDownloadedMusic()||DownloadService.instance!=null)){labels.add("Удалить скачанную музыку");actions.add(this::clearDownloadedMusic);}labels.add("Радио");actions.add(this::radioStations);labels.add("Настройки");actions.add(this::settings);labels.add("Обновление приложения");actions.add(this::appUpdateSettings);dialogBuilder().setTitle("Библиотека").setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).show();}
 void clearDownloadedMusic(){
  dialogBuilder().setTitle("Удалить скачанную музыку?")
   .setMessage("Удалятся полная офлайн-библиотека, скачанные игры и их постеры. Очередь загрузки очистится.")
   .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{
    if(clearingDownloads)return;
    clearingDownloads=true;
    preparingAll=false;
    playRequest++;
    takeDownloadPriority();
    stopService(new Intent(this,DownloadService.class));
    stopService(new Intent(this,PlaybackService.class));
    downloadStarting=false;
    message("Удаляю скачанную музыку…");
    io.submit(()->{
     String result;
     try{library.clearDownloadedMusic();result="Скачанная музыка удалена";}
     catch(Exception e){result=e.getMessage();}
     final String status=result;
     runOnUiThread(()->{
      clearingDownloads=false;
      previousDownloadActive=false;
      refreshArchiveView();
      if(local&&screen.equals("games"))scope=savedView();
      alphabet();render();refreshDownloadStates();message(status);
     });
    });
   }).show();
 }
 void refreshLibrary(){
  if(downloadsBusy())return;
  if(library.archive.active()!=null){
   dialogBuilder().setTitle("Обновить офлайн-библиотеку?")
    .setMessage("Если доступен новый ZIP, текущая копия будет удалена до скачивания. При сбое нужно будет повторить загрузку.")
    .setNegativeButton("Отмена",null).setPositiveButton("Обновить",(d,w)->startLibrarySync()).show();
  }else startLibrarySync();
 }
 void startLibrarySync(){if(downloadsBusy())return;takeDownloadPriority();startingSequence=DownloadService.accepted.get();downloadStarting=true;try{startForegroundService(new Intent(this,DownloadService.class).putExtra("sync",true));message("Обновляю библиотеку…");}catch(Exception e){downloadStarting=false;message(e.getMessage());}}
 void takeDownloadPriority(){android.app.job.JobScheduler scheduler=(android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);scheduler.cancel(LibraryMaintenanceJob.ID);if(MusicSyncJob.active!=null){scheduler.cancel(MusicSyncJob.ID);MusicSyncJob.schedule(this,true);}}
 void pauseDownloads(){LibraryMaintenanceJob current=LibraryMaintenanceJob.active;if(current!=null&&current.library!=null)current.library.cancelWork();MusicSyncJob sync=MusicSyncJob.active;if(sync!=null&&sync.library!=null)sync.library.cancelWork();new Library(this).pausePending();((android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE)).cancel(LibraryMaintenanceJob.ID);if(sync!=null){((android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE)).cancel(MusicSyncJob.ID);MusicSyncJob.schedule(this,true);}if(DownloadService.instance!=null)startService(new Intent(this,DownloadService.class).putExtra("cancel",true));message("Загрузка приостановлена");refreshDownloadStates();}
 void startDownload(List<JSONObject> games,String seriesId){if(downloadsBusy())return;takeDownloadPriority();if(seriesId!=null)library.trackSeries(seriesId);JSONArray payload=new JSONArray();for(JSONObject game:games)payload.put(game);startingSequence=DownloadService.accepted.get();downloadStarting=true;try{startForegroundService(new Intent(this,DownloadService.class).putExtra("games",payload.toString()));message(library.networkAllowed()?"Начинаю скачивание…":"Загрузка продолжится при появлении разрешённой сети");}catch(Exception e){downloadStarting=false;message(e.getMessage());}}
 void radioStations(){
  boolean personal=library.preferences.getBoolean(RadioStations.PERSONAL_MODE,false);
  List<RadioStations.Station> stations=RadioStations.available(this,personal);
  List<String> names=new ArrayList<>();
  PlaybackService player=PlaybackService.instance;
  for(RadioStations.Station station:stations)
   names.add(player!=null&&station.equals(player.station)&&player.state.wantsPlayback()?"▶  "+station.title():station.title());
  dialogBuilder().setTitle("Радио").setItems(names.toArray(new String[0]),(d,i)->{
   if(!library.networkAllowed()){message("Для радио нужна разрешённая сеть");return;}
   playRequest++;
   command(service->service.playRadio(stations.get(i)));
  }).setNegativeButton("Закрыть",null).show();
 }
 void personalRadio(){
  if(library.preferences.getBoolean(RadioStations.PERSONAL_MODE,false)){
   dialogBuilder().setTitle("Личный режим включён").setMessage("Личные станции доступны в меню «Радио».")
    .setNegativeButton("Закрыть",null).setPositiveButton("Выключить",(d,w)->{
     playRequest++;
     library.preferences.edit().putBoolean(RadioStations.PERSONAL_MODE,false).apply();
     PlaybackService player=PlaybackService.instance;if(player!=null)player.lockPersonalRadio();
    }).show();
   return;
  }
  EditText code=new EditText(this);code.setSingleLine(true);code.setHint("Код");
  code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
  code.setPadding(dp(20),0,dp(20),0);
  AlertDialog dialog=dialogBuilder().setTitle("Личный режим").setView(code)
   .setNegativeButton("Отмена",null).setPositiveButton("Включить",null).create();
  dialog.show();
  Button confirm=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
  Runnable update=()->confirm.setEnabled(code.length()>0);
  code.addTextChangedListener(new android.text.TextWatcher(){
   public void beforeTextChanged(CharSequence text,int start,int count,int after){}
   public void onTextChanged(CharSequence text,int start,int before,int count){update.run();}
   public void afterTextChanged(android.text.Editable text){}
  });
  update.run();
  confirm.setOnClickListener(v->{
   if(!RadioStations.acceptsCode(code.getText().toString())){code.setError("Неверный код");return;}
   library.preferences.edit().putBoolean(RadioStations.PERSONAL_MODE,true).apply();
   dialog.dismiss();radioStations();
  });
 }
 void settings(){
  List<String> labels=new ArrayList<>(Arrays.asList("Сеть и автоматические обновления","Оформление","О приложении и лицензии"));
  if(RadioStations.available(this,true).stream().anyMatch(RadioStations.Station::personal))labels.add("Личный режим");
  dialogBuilder().setTitle("Настройки").setItems(labels.toArray(new String[0]),(d,i)->{
   if(i==0)syncSettings();if(i==1)theme();if(i==2)about();if(i==3)personalRadio();
  }).show();
 }
 void link(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception e){message("Не удалось открыть ссылку");}}
 void about(){ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(16),dp(12),dp(16),dp(12));box.setBackgroundColor(bg);scroll.addView(box);
 box.addView(text("Музыка Аркадного зала · "+AppUpdates.version(this),19,fg));box.addView(text("Приложение сайта «Аркадный зал Школы Империи». Автор проекта — Дмитрий Малыхин. Игры и серии читаются из музыкального API v1. Постеры берутся из медиатеки сайта.",14,fg));
 String[][] links={{"Аркадный зал",Library.BASE},{"Профиль и медиатека Гремлина",Library.BASE+"gremlin.html"},{"Лаборатория",Library.BASE+"lab/"},{"Инженер школы Империи","https://oopssre.ru/"},{"Telegram · Инженер школы Империи","https://t.me/oopsSRE"},{"VK","https://vk.ru/club238642156"},{"Права и лицензии сайта",Library.BASE+"license.html"},{"Источники сайта",Library.BASE+"sources.html"},{"Game Music Emu · исходники","https://github.com/libgme/game-music-emu"}};
 for(String[] item:links)box.addView(button(item[0],()->link(item[1])));
 box.addView(button("Исходники на GitHub",this::openSources));box.addView(button("MIT · лицензия кода приложения",()->licenseText("MIT.txt")));box.addView(button("Лицензии компонентов и права материалов",()->licenseText("THIRD_PARTY_NOTICES.txt")));
 box.addView(text("Музыка принадлежит её авторам и соответствующим правообладателям. Доступность файлов на сайте не означает передачу исключительных прав или разрешение на их распространение. Источники конкретной коллекции указаны в её манифесте. Условия материалов сайта приведены на странице прав; они не заменяют условия сторонней музыки.",13,fg));
 if(album!=null&&manifest!=null){box.addView(button("Карточка текущей игры",()->{try{link(Library.resolve(Library.BASE,album.getString("href")));}catch(Exception ignored){}}));JSONObject source=manifest.optJSONObject("source");if(source!=null&&source.optString("page_url").startsWith("https://"))box.addView(button("Источник музыкальной коллекции",()->link(source.optString("page_url"))));}
 box.addView(text("Game Music Emu — Shay Green и участники проекта. LGPL 2.1. Полный текст лицензии и сведения о сборке включены в приложение; соответствующие исходники и код для перелинковки — в комплекте исходников приложения. Лицензия GME не распространяется автоматически на музыку и постеры.",13,fg));box.addView(button("Прочитать LGPL 2.1",()->licenseText("LGPL-2.1.txt")));box.addView(button("Происхождение музыкального движка",()->licenseText("PROVENANCE.json")));
 dialogBuilder().setView(scroll).setPositiveButton("Закрыть",null).show();}
 void openSources(){link("https://github.com/GremlinEngineering/gremlinamp");}
 void licenseText(String asset){try(java.io.InputStream in=getAssets().open(asset);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);TextView t=text(out.toString("UTF-8"),12,fg);t.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(bg);scroll.addView(t);dialogBuilder().setTitle(asset).setView(scroll).setPositiveButton("Закрыть",null).show();}catch(Exception e){message(e.getMessage());}}
 void shortFilter(){String current=library.preferences.getString("unknown_duration","show");String[] labels={"Без порога длительности","Не короче 5 секунд","Не короче 10 секунд","Не короче 15 секунд","Неизвестные: показывать","Неизвестные: скрывать","Только неизвестные"};dialogBuilder().setTitle("Длительность · неизвестные: "+(current.equals("hide")?"скрыты":current.equals("only")?"только они":"видны")).setItems(labels,(d,i)->{if(i<4)library.preferences.edit().putInt("short_seconds",new int[]{0,5,10,15}[i]).apply();else library.preferences.edit().putString("unknown_duration",new String[]{"show","hide","only"}[i-4]).apply();refreshFilters();}).show();}
 List<JSONObject> downloadSelection(){if(screen.equals("tracks")&&album!=null)return Collections.singletonList(album);if(selectedSeries!=null)try{return filteredMembers(selectedSeries);}catch(Exception ignored){}return Collections.emptyList();}
 String platformLabel(){return platformFilter.isEmpty()?"Платформа: все":platformFilter;}
 String yearLabel(){return yearFilter.isEmpty()?"Год: все":yearFilter;}
 boolean matchesGame(JSONObject g){return (platformFilter.isEmpty()||platformFilter.equals(g.optString("platform_label")))&&(yearFilter.isEmpty()||yearFilter.equals(g.has("year")?String.valueOf(g.optInt("year")):"Не указан"));}
 List<JSONObject> filteredMembers(JSONObject row)throws Exception{List<JSONObject> result=new ArrayList<>();for(JSONObject g:library.seriesGames(row,games))if(matchesGame(g))result.add(g);return result;}
 void chooseFacet(boolean years){Set<String> values=new TreeSet<>();for(JSONObject g:games)values.add(years?(g.has("year")?String.valueOf(g.optInt("year")):"Не указан"):g.optString("platform_label"));List<String> choices=new ArrayList<>();choices.add(years?"Все годы":"Все платформы");choices.addAll(values);dialogBuilder().setTitle(years?"Год выхода игры":"Платформа").setItems(choices.toArray(new String[0]),(d,i)->{if(years)yearFilter=i==0?"":choices.get(i);else platformFilter=i==0?"":choices.get(i);library.preferences.edit().putString("platform_filter",platformFilter).putString("year_filter",yearFilter).apply();refreshFilters();}).show();}
 void refreshFilters(){int count=(platformFilter.isEmpty()?0:1)+(yearFilter.isEmpty()?0:1)+(library.preferences.getInt("short_seconds",0)>0?1:0)+(library.preferences.getString("unknown_duration","show").equals("show")?0:1);platformButton.setText(count==0?"Фильтры":"Фильтры · "+count);alphabet();render();message("Фильтры применены к библиотеке");}
 void clearFilters(){platformFilter="";yearFilter="";letter="";library.preferences.edit().putString("platform_filter","").putString("year_filter","").putInt("short_seconds",0).putString("unknown_duration","show").apply();search.setText("");refreshFilters();}
 void trackInfo(JSONObject track){
  long known=library.knownDuration(track);
  String details="Формат: "+track.optString("format")+"\nВнутренний номер: "+track.optInt("subsong")
          +"\nДлительность: "+(known>=0?stamp(known):"неизвестна");
  for(String field:new String[]{"label","source_kind","credit"})if(!track.optString(field).isEmpty())details+="\n"+track.optString(field);
  if(MusicContract.decoder(track).isEmpty())details+="\n"+MusicContract.unsupported(track);
  try{JSONObject timing=library.timing(track);if(timing!=null)details+="\nПроверка звука: "+(timing.optLong("ended_ms",-1)>=0?"конец обнаружен":"не закончился за 180 секунд");}catch(Exception ignored){}
  AlertDialog.Builder dialog=dialogBuilder().setTitle(track.optString("title")).setMessage(details).setPositiveButton("Закрыть",null);
  String source=track.optString("source_url");
  if(source.startsWith("https://"))dialog.setNeutralButton("Источник",(d,w)->link(source));
  dialog.show();
 }
 void rowActions(JSONObject item,boolean isSeries){
  List<JSONObject> selected;try{selected=isSeries?filteredMembers(item):Collections.singletonList(item);}catch(Exception e){message(e.getMessage());return;}if(selected.isEmpty())return;
  List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();labels.add("Играть");actions.add(()->playCollection(selected,false,isSeries?item:selectedSeries,null));PlaybackService player=PlaybackService.instance;
  if(player!=null&&!player.queue.isEmpty()){labels.add("Добавить в очередь");actions.add(()->playCollection(selected,true,isSeries?item:selectedSeries,null));}
  String download=selectionLabel(selected);boolean headerDownload=isSeries&&selectedSeries!=null&&selectedSeries.optString("id").equals(item.optString("id"));if(!download.isEmpty()&&!headerDownload){labels.add(download);actions.add(()->plan(selected,isSeries?item.optString("id"):null));}
  dialogBuilder().setTitle(item.optString("title")).setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).show();
 }

 void playCollection(List<JSONObject> list,boolean append,JSONObject seriesItem,PlaybackService.Entry chosen){
  final boolean localPlayback=local||!library.networkAllowed();
  final int intent=++playRequest;
  final String seriesId=seriesItem==null?"":seriesItem.optString("id");
  final String seriesTitle=seriesItem==null?"":seriesItem.optString("title");
  preparingSeries=seriesId;preparingSeriesRequest=intent;
  message("Подготавливаю очередь…");
  io.submit(()->{
   try{
    List<PlaybackService.Entry> queue=new ArrayList<>();
    int selected=0;
    boolean found=chosen==null;
    for(JSONObject game:list){
     if(intent!=playRequest||Thread.currentThread().isInterrupted())return;
     boolean chosenGame=chosen!=null&&chosen.game.optString("id").equals(game.optString("id"));
     JSONObject data;
     try{data=chosenGame?chosen.manifest:localPlayback?library.localManifest(game):library.manifest(game,false);}
     catch(Exception e){continue;}
     if(data==null)continue;
     JSONArray tracks=data.getJSONArray("tracks");
     for(int i=0;i<tracks.length();i++){
      JSONObject track=tracks.getJSONObject(i);
      if(library.hiddenShort(track)||MusicContract.decoder(track).isEmpty()||localPlayback&&!library.playableLocal(data,track))continue;
      if(chosenGame&&i==chosen.index){selected=queue.size();found=true;}
      queue.add(new PlaybackService.Entry(game,data,i,seriesId,seriesTitle));
     }
    }
    final int start=selected;
    final boolean playable=found&&!queue.isEmpty();
    runOnUiThread(()->{
     if(intent!=playRequest||isFinishing())return;
     preparingSeriesRequest=-1;
     if(!playable){message("Нет подходящих композиций");return;}
     command(player->{
      if(append&&!player.queue.isEmpty()){
       player.enqueue(queue,localPlayback);message("Добавлено в очередь: "+queue.size());
      }else player.play(queue,start,localPlayback);
     });
    });
   }catch(Exception e){runOnUiThread(()->{if(intent==playRequest){preparingSeriesRequest=-1;message(e.getMessage());}});}
  });
 }
 void playSeries(JSONObject item){
  try{playCollection(filteredMembers(item),false,item,null);}catch(Exception e){message(e.getMessage());}
 }
 void playAlbumTrack(List<PlaybackService.Entry> queue,int index,boolean offline){
  PlaybackService.Entry chosen=queue.get(index);
  if(selectedSeries!=null){
   try{
    List<JSONObject> members=filteredMembers(selectedSeries);
    if(members.stream().anyMatch(game->game.optString("id").equals(chosen.game.optString("id")))){
     playCollection(members,false,selectedSeries,chosen);return;
    }
   }catch(Exception e){message(e.getMessage());return;}
  }
  playRequest++;
  command(player->player.play(queue,index,offline));
 }
 void collectionPoster(JSONObject item,boolean isSeries){message("Открываю постер…");artworkIo.submit(()->{try{java.io.File file=library.picture(item,isSeries,false);runOnUiThread(()->{if(file.isFile())zoomPath(file.toString());else message("Постер отсутствует в каталоге сайта");});}catch(Exception e){message("Постер: "+e.getMessage());}});}
 void thumbnail(ImageView target,JSONObject item,boolean isSeries){final int revision=artVersion;String identity=(isSeries?"s:":"g:")+item.optString("id");target.setTag(identity);artworkIo.submit(()->{if(revision!=artVersion)return;try{java.io.File f=library.picture(item,isSeries,true);android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.toString(),o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>240)o.inSampleSize*=2;o.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeFile(f.toString(),o);runOnUiThread(()->{if(!isFinishing()&&revision==artVersion&&identity.equals(target.getTag()))target.setImageBitmap(b);});}catch(Exception ignored){}});}
 void load(){load(true);}
 void load(boolean refresh){
  message("Читаю музыкальный каталог…");
  if(refresh&&library.networkAllowed())io.submit(()->SimilarityStore.refresh(library));
  io.submit(()->{
   try{publishCatalog(library.catalog(false));}
   catch(Exception e){message("Каталог: "+e.getMessage());}
   if(!refresh||!library.networkAllowed())return;
   try{
    JSONObject updated=library.catalog(true);
    try{library.json(Library.BASE+"assets/gallery-music.json",true);}catch(Exception ignored){}
    publishCatalog(updated);
   }catch(Exception e){message("Каталог: "+e.getMessage());}
  });
 }
 void publishCatalog(JSONObject catalog)throws Exception{
  List<JSONObject> loaded=new ArrayList<>();JSONArray rows=catalog.getJSONArray("games");
  for(int i=0;i<rows.length();i++)loaded.add(rows.getJSONObject(i));
  List<JSONObject> ready=library.enrichGames(loaded);
  java.io.File archiveAtLoad=library.archive.active();
  List<JSONObject> offline=library.archiveGames();
  JSONArray seriesReady=catalog.getJSONArray("series"),hits=library.trackSearch();
  JSONObject raReady=catalog.getJSONObject("by_ra_id");
  runOnUiThread(()->{if(isFinishing())return;games=ready;series=seriesReady;raIndex=raReady;trackSearch=hits;seenArchive=library.archive.active();archivedGames=Objects.equals(seenArchive,archiveAtLoad)?offline:new ArrayList<>();if(!screen.equals("tracks")){if(selectedSeries!=null)try{
    for(int i=0;i<series.length();i++)if(series.getJSONObject(i).optString("id").equals(selectedSeries.optString("id"))){selectedSeries=series.getJSONObject(i);break;}
    scope=filteredMembers(selectedSeries);
   }catch(Exception ignored){}else scope=local?savedView():new ArrayList<>(games);}alphabet();render();refreshDownloadStates();if(status.getText().toString().startsWith("Читаю музыкальный каталог"))message("Коллекций: "+games.size());});
 }
 void hideKeyboard(){android.view.inputmethod.InputMethodManager input=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(input!=null&&search!=null)input.hideSoftInputFromWindow(search.getWindowToken(),0);}
 List<JSONObject> savedView(){
  Map<String,JSONObject> saved=new LinkedHashMap<>(),current=new HashMap<>();
  for(JSONObject game:games)current.put(game.optString("id"),game);
  for(JSONObject game:archivedGames){
   String id=game.optString("id");JSONObject latest=current.get(id);
   saved.put(id,latest!=null&&game.optString("manifest").equals(latest.optString("manifest"))?latest:game);
  }
  for(JSONObject game:library.savedGames()){
   String id=game.optString("id");JSONObject latest=current.get(id);
   if(latest!=null&&game.optInt("_music_contract")==1)saved.putIfAbsent(id,latest);
  }
  return new ArrayList<>(saved.values());
 }

 void browse(boolean downloaded){hideKeyboard();requestId++;backStack.clear();local=downloaded;screen="games";scope=downloaded?savedView():new ArrayList<>(games);album=null;selectedSeries=null;reset();}
 void reset(){letter="";search.setText("");alphabet();render();}
 String initial(String title){if(title==null||title.isEmpty())return "#";int c=title.toUpperCase(Locale.ROOT).codePointAt(0);return Character.isDigit(c)?"0–9":Character.isLetter(c)?new String(Character.toChars(c)):"#";}
 List<JSONObject> items(){List<JSONObject> list=new ArrayList<>();if(screen.equals("series")){if(series!=null)for(int i=0;i<series.length();i++){JSONObject row=series.optJSONObject(i);if(seriesCount(row)>0)list.add(row);}}else if(screen.equals("tracks")){if(manifest!=null){JSONArray a=manifest.optJSONArray("tracks");for(int i=0;i<a.length();i++)list.add(a.optJSONObject(i));}}else for(JSONObject g:scope)if(matchesGame(g))list.add(g);if(!screen.equals("tracks")&&selectedSeries==null)list.sort((a,b)->a.optString("title").compareToIgnoreCase(b.optString("title")));return list;}
 void alphabet(){if(letters==null)return;letters.removeAllViews();Set<String> starts=new TreeSet<>();for(JSONObject r:items())starts.add(initial(r.optString("title")));List<String> labels=new ArrayList<>();labels.add("Все");labels.addAll(starts);for(String l:labels){TextView v=text(l,12,l.equals(letter)||l.equals("Все")&&letter.isEmpty()?accent:muted);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(48));v.setOnClickListener(w->{letter=l.equals("Все")?"":l;render();alphabet();});letters.addView(v,new LinearLayout.LayoutParams(-2,-1));}}
 void render(){if(rows==null)return;updateNavigation();artVersion++;rows.removeAllViews();String q=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);label(heading,screen.equals("series")?"Серии":screen.equals("tracks")?album.optString("title"):selectedSeries!=null?selectedSeries.optString("title"):local?"Скачанная музыка":"Музыкальная библиотека");
  if(selectedSeries!=null&&!screen.equals("series")&&!screen.equals("tracks")){LinearLayout seriesHeader=new LinearLayout(this);seriesHeader.setGravity(Gravity.CENTER_VERTICAL);ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.FIT_CENTER);art.setContentDescription("Открыть постер серии");seriesHeader.addView(art,new LinearLayout.LayoutParams(dp(58),dp(76)));thumbnail(art,selectedSeries,true);art.setOnClickListener(v->collectionPoster(selectedSeries,true));Button playSeriesButton=button("Играть серию",()->playSeries(selectedSeries));seriesHeader.addView(playSeriesButton,new LinearLayout.LayoutParams(0,dp(48),1));Button more=button("⋮",()->rowActions(selectedSeries,true));more.setContentDescription("Действия с серией");seriesHeader.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));rows.addView(seriesHeader);}

  boolean gameSearch=screen.equals("games");
  String raQuery=gameSearch?MusicContract.raQuery(q):"";
  Set<String> matchingIds=new HashSet<>();
  JSONArray ids=raIndex==null||raQuery.isEmpty()?null:raIndex.optJSONArray(raQuery);
  if(ids!=null)for(int i=0;i<ids.length();i++)matchingIds.add(ids.optString(i));
  int count=0;int trackPosition=-1;
  for(JSONObject r:items()){
   trackPosition++;
   String title=r.optString("title");
   boolean matches=matchingIds.contains(r.optString("id"))
           ||(!q.trim().startsWith("ra")||raQuery.isEmpty())
           &&(title.toLowerCase(Locale.ROOT).contains(q)||(gameSearch&&r.optString("id").equalsIgnoreCase(q)));
   if(!matches||!letter.isEmpty()&&!initial(title).equals(letter))continue;
   int index=screen.equals("tracks")?trackPosition:-1;if(screen.equals("tracks")&&(library.hiddenShort(r)||!matchesGame(album)))continue;long measured=library.displayDuration(r);String detail=screen.equals("tracks")?r.optString("format").toUpperCase(Locale.ROOT)+" · "+(measured>=0?stamp(measured):"длительность неизвестна"):screen.equals("series")?"Игр с музыкой: "+seriesCount(r):r.optString("platform_label")+(r.has("year")?" · "+r.optInt("year"):"")+" · "+r.optInt("track_count")+" треков";
   if(index>=0&&MusicContract.decoder(r).isEmpty())detail+=" · "+MusicContract.unsupported(r);
   if(!screen.equals("tracks"))detail+=" · "+(screen.equals("series")?seriesSavedLabel(r):downloadDescriptions.getOrDefault(r.optString("id"),""));final boolean seriesRow=screen.equals("series"),trackRow=screen.equals("tracks");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(6),dp(6),dp(6));row.setBackground(surface(panel));
 if(!trackRow){ImageView miniature=new ImageView(this);miniature.setScaleType(ImageView.ScaleType.FIT_CENTER);miniature.setBackground(surface(bg));miniature.setContentDescription("Увеличить постер: "+title);row.addView(miniature,new LinearLayout.LayoutParams(dp(58),dp(76)));miniature.setOnClickListener(v->collectionPoster(r,seriesRow));thumbnail(miniature,r,seriesRow);}
 LinearLayout copy=new LinearLayout(this);copy.setOrientation(1);TextView a=text((index>=0?String.format(Locale.ROOT,"%02d  ",index+1):"")+title,14,fg);copy.addView(a);copy.addView(text(detail,11,muted));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));if(!trackRow){Button more=button("⋮",()->rowActions(r,seriesRow));more.setContentDescription("Действия: "+title);row.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));}row.setTag(index>=0?trackKey(r):null);row.setContentDescription(title);if(trackRow)row.setOnLongClickListener(v->{trackInfo(r);return true;});row.setOnClickListener(v->{if(trackRow){if(MusicContract.decoder(r).isEmpty()){message(MusicContract.unsupported(r));return;}playAlbumTrack(albumQueue,index,local||!library.networkAllowed());}else if(seriesRow)openSeries(r);else open(r);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(5));rows.addView(row,lp);count++;
  }if(gameSearch&&!local&&selectedSeries==null&&raQuery.isEmpty()&&q.trim().length()>=2)count+=renderTrackMatches(q.trim());updateDownloadButton();if(count==0)rows.addView(text(local?"Нет скачанных коллекций или совпадений.":"Нет совпадений. Выберите «Все» или измените поиск.",14,muted));}
 int renderTrackMatches(String query){
  if(trackSearch==null)return 0;
  Map<String,JSONObject> index=new HashMap<>();
  for(JSONObject game:games)index.put(game.optString("id"),game);
  int count=0;
  for(int i=0;i<trackSearch.length()&&count<60;i++){
   JSONObject hit=trackSearch.optJSONObject(i);if(hit==null)continue;
   JSONObject game=index.get(hit.optString("game_id"));if(game==null||!matchesGame(game))continue;
   String title=hit.optString("title");
   if(!title.toLowerCase(Locale.ROOT).contains(query)||!letter.isEmpty()&&!initial(title).equals(letter))continue;
   rows.addView(button(title+" · "+game.optString("title"),()->open(game,hit.optString("id"),false,hit)));
   count++;
  }
  return count;
 }
 void lastTrack(){
  String gameId=library.preferences.getString("last_game_id",""),trackId=library.preferences.getString("last_track_id","");
  if(local||!library.networkAllowed())for(JSONObject game:savedView())if(gameId.equals(game.optString("id"))){open(game,trackId,true,null);return;}
  for(JSONObject game:games)if(gameId.equals(game.optString("id"))){open(game,trackId,true,null);return;}
  for(JSONObject game:archivedGames)if(gameId.equals(game.optString("id"))){open(game,trackId,true,null);return;}
  message("Последняя композиция отсутствует в музыкальном каталоге");
 }
 void open(JSONObject game){open(game,"",false,null);}
 void open(JSONObject game,String trackId,boolean playNow,JSONObject hit){
  int ticket=++requestId;int playbackTicket=playNow?++playRequest:playRequest;boolean downloaded=local||!library.networkAllowed();BrowseSnapshot previous=new BrowseSnapshot();
  message("Читаю треклист…");
  io.submit(()->{try{
   JSONObject data=downloaded?library.localManifest(game):library.manifest(game,false);
   if(data==null)throw new java.io.IOException("Треклист не скачан");
   List<PlaybackService.Entry> queue=new ArrayList<>();JSONArray tracks=data.getJSONArray("tracks");
   for(int i=0;i<tracks.length();i++)queue.add(new PlaybackService.Entry(game,data,i));
   int index=trackId.isEmpty()?-1:MusicContract.trackIndex(tracks,trackId);
   if(index<0&&hit!=null&&data.optString("_url").equals(hit.optString("_manifest"))){
    int candidate=hit.optInt("index",-1);if(candidate>=0&&candidate<tracks.length())index=candidate;
   }
   final int selected=index;
   runOnUiThread(()->{
    if(ticket!=requestId||isFinishing())return;
    hideKeyboard();backStack.push(previous);album=game;manifest=data;albumQueue=queue;screen="tracks";
    reset();refreshDownloadStates();
    if(selected<0){message(trackId.isEmpty()?queue.size()+" композиций":"Композиция отсутствует в этом плейлисте");return;}
    if(!playNow){
     for(int i=0;i<rows.getChildCount();i++){
      View row=rows.getChildAt(i);
      if(trackKey(tracks.optJSONObject(selected)).equals(row.getTag())){row.setFocusable(true);row.requestFocus();break;}
     }
     message(tracks.optJSONObject(selected).optString("title"));
    }else if(MusicContract.decoder(tracks.optJSONObject(selected)).isEmpty())message(MusicContract.unsupported(tracks.optJSONObject(selected)));
    else if(playbackTicket==playRequest)playAlbumTrack(queue,selected,downloaded);
   });
  }catch(Exception e){if(ticket==requestId)message("Треклист: "+e.getMessage());}});
 }

 void setArt(String path){artPath=path;displayedArt=path;cover.setImageBitmap(path.isEmpty()?null:Library.artwork(new java.io.File(path)));}
 void showSeries(){hideKeyboard();requestId++;backStack.clear();selectedSeries=null;album=null;screen="series";local=false;reset();message(series==null?"Каталог серий загружается…":"Серии из каталога сайта");}
 int seriesCount(JSONObject row){try{return filteredMembers(row).size();}catch(Exception e){return 0;}}
 void openSeries(JSONObject item){
  List<JSONObject> members;
  try{members=filteredMembers(item);}catch(Exception e){message(e.getMessage());return;}
  if(members.isEmpty()){message("Для этой серии пока нет музыки");return;}
  PlaybackService player=PlaybackService.instance;
  String selected=item.optString("id");
  boolean pending=preparingSeriesRequest==playRequest&&!preparingSeries.isEmpty();
  boolean switchQueue=pending?UiRules.switchSeries(preparingSeries,selected,TransportState.LOADING)
    :player!=null&&UiRules.switchSeries(player.playingSeries(),selected,player.state);
  hideKeyboard();requestId++;backStack.push(new BrowseSnapshot());selectedSeries=item;album=null;
  scope=members;screen="games";local=false;reset();
  if(switchQueue)playCollection(members,false,item,null);
 }
 void transport(Command action){playRequest++;command(action);}
 interface Command{void run(PlaybackService s);}void command(Command c){if(clearingDownloads)return;PlaybackService s=PlaybackService.instance;if(s!=null){c.run(s);return;}try{startForegroundService(new Intent(this,PlaybackService.class));awaitService(c,0,playRequest);}catch(Exception e){message(e.getMessage());}}
 void awaitService(Command c,int tries,int intent){handler.postDelayed(()->{if(isFinishing()||clearingDownloads||intent!=playRequest)return;if(PlaybackService.instance!=null)c.run(PlaybackService.instance);else if(tries<20)awaitService(c,tries+1,intent);else message("Не удалось запустить проигрыватель");},100);}
 static String trackKey(JSONObject track){JSONObject asset=track==null?null:track.optJSONObject("asset");return asset==null?"":track.optString("id",asset.optString("sha256")+":"+track.optInt("subsong"));}
 static String stamp(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
 void showTransport(PlaybackService service){
  if(service==null){
   label(display,"Выберите композицию");label(time,"00:00 / --:--");
   if(!displayedArt.isEmpty())setArt("");
   if(label(play,"Играть"))play.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon("play",accent,dp(18)),null,null,null);
   enableAction(play,false);enableAction(previousButton,false);enableAction(nextButton,false);
   seek.setEnabled(false);if(!dragging)seek.setProgress(0);label(playbackStatus,"Выберите трек");
   highlightTrack(null);return;
  }
  PlaybackIdentity.View now=service.identity.snapshot();
  label(display,now.title+(now.game.isEmpty()?"":"\n"+now.game));
  boolean radio=service.station!=null,running=service.state.wantsPlayback();
  label(time,radio?"ПРЯМОЙ ЭФИР":stamp(service.currentPosition())+" / "+(service.durationKnown?stamp(service.duration):"--:--"));
  if(label(play,running?(radio?"Стоп":"Пауза"):"Играть"))
   play.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(running?"pause":"play",accent,dp(18)),null,null,null);
  describe(play,radio?(running?"Остановить радио":"Включить радио"):(running?"Приостановить воспроизведение":"Начать воспроизведение"));
  PlaybackService.Entry entry=service.currentEntry();
  enableAction(play,radio||entry!=null);enableAction(previousButton,entry!=null);enableAction(nextButton,service.canNext());
  seek.setEnabled(!radio&&!service.completionPending&&UiRules.canSeek(service.state,service.durationKnown,service.duration));
  if(!dragging)seek.setProgress(service.durationKnown&&service.duration>0?(int)(1000*service.currentPosition()/service.duration):0);
  if(!now.art.equals(displayedArt))setArt(now.art);
  String status=switch(service.state){
   case IDLE -> "Выберите трек";
   case LOADING -> radio?"Подключение к станции…":"Загрузка композиции…";
   case LOADING_PAUSED -> "Пауза · композиция ещё загружается";
   case PLAYING -> radio?(service.station.credit().isEmpty()?"Радио играет":service.station.credit()):"Воспроизведение";
   case PAUSED -> radio?"Радио остановлено":"Пауза";
   case CONTINUING -> "Выбираю продолжение…";
   case ENDED -> service.error.isEmpty()?"Очередь завершена":service.error;
   case ERROR -> service.error;
  };
  label(playbackStatus,status);
  highlightTrack(entry);
 }
 void highlightTrack(PlaybackService.Entry entry){
  if(!screen.equals("tracks")||album==null)return;
  boolean matches=entry!=null&&album.optString("id").equals(entry.game.optString("id"));
  String key=matches?trackKey(entry.manifest.optJSONArray("tracks").optJSONObject(entry.index)):"";
  for(int n=0;n<rows.getChildCount();n++){
   View row=rows.getChildAt(n);Object tag=row.getTag();
   if(tag==null)continue;
   boolean active=matches&&tag.equals(key);
   if(row.isSelected()!=active){row.setBackground(surface(active?line:panel));row.setSelected(active);}
  }
 }
 final Runnable tick=new Runnable(){public void run(){
  if(downloadStarting&&DownloadService.accepted.get()>startingSequence)downloadStarting=false;
  if(cassette!=null)cassette.invalidate();
  showTransport(PlaybackService.instance);
  ensureAcousticAvailability();if(queueButton!=null){PlaybackService active=PlaybackService.instance;describe(queueButton,"Режим воспроизведения. "+(active!=null&&!active.nextGame.isEmpty()?"Далее: "+active.nextGame:"После очереди: "+Continuation.label(library.preferences.getString("after_album","stop"))));}if(DownloadService.instance!=null)label(status,DownloadService.progress);else if(LibraryMaintenanceJob.active!=null)label(status,LibraryMaintenanceJob.status);else if(MusicSyncJob.active!=null)label(status,MusicSyncJob.status);long syncSuccess=library.preferences.getLong("sync_success",0);if(syncSuccess!=seenSyncSuccess){seenSyncSuccess=syncSuccess;reloadCachedCatalog();}if(seenRevision!=Library.revision.get()){seenRevision=Library.revision.get();refreshArchiveView();refreshDownloadStates();}if(!clearingDownloads&&previousDownloadActive!=(DownloadService.instance!=null)){previousDownloadActive=DownloadService.instance!=null;updateDownloadButton();if(!previousDownloadActive)message(DownloadService.progress);}updateLibraryActions();handler.postDelayed(this,400);}};
 void refreshArchiveView(){
  java.io.File current=library.archive.active();
  if(Objects.equals(seenArchive,current))return;
  seenArchive=current;
  if(current!=null){reloadCachedCatalog();return;}
  archivedGames=new ArrayList<>();
  if(local&&screen.equals("games")){scope=savedView();alphabet();render();}
 }
 void plan(List<JSONObject> list){plan(list,selectedSeries!=null&&!screen.equals("tracks")?selectedSeries.optString("id"):null);}
 void plan(List<JSONObject> list,String seriesId){
  if(list.isEmpty()||downloadsBusy())return;
  List<JSONObject> copy=new ArrayList<>(list);
  if(selectionLabel(copy).isEmpty())return;
  if(copy.size()==1){startDownload(copy,seriesId);return;}
  io.submit(()->{long amount=library.remainingCached(copy);runOnUiThread(()->{
   if(isFinishing())return;
   String size=amount<0?"Размер уточнится при загрузке":String.format(Locale.ROOT,"Музыка · %.1f МБ",amount/1048576.0);
   String description="Коллекций: "+copy.size()+"\n"+size+"\nПостеры и каталоги сохранятся вместе с музыкой.\nСеть: "+networkLabel();
   dialogBuilder().setTitle("Скачать серию?").setMessage(description).setNegativeButton("Отмена",null)
           .setPositiveButton("Скачать",(d,w)->startDownload(copy,seriesId)).show();
  });});
 }

 void reloadCachedCatalog(){io.submit(()->{try{publishCatalog(library.catalog(false));}catch(Exception e){message("Каталог: "+e.getMessage());}});}
 void followedSeries(){if(series==null){message("Каталог серий ещё не загружен");return;}List<JSONObject> rows=new ArrayList<>();for(int i=0;i<series.length();i++){JSONObject row=series.optJSONObject(i);if(seriesCount(row)>0)rows.add(row);}rows.sort((a,b)->a.optString("title").compareToIgnoreCase(b.optString("title")));String[] labels=new String[rows.size()];boolean[] checked=new boolean[rows.size()];Set<String> saved=new HashSet<>(library.preferences.getStringSet("saved_series",Collections.emptySet()));for(int i=0;i<rows.size();i++){labels[i]=rows.get(i).optString("title");checked[i]=saved.contains(rows.get(i).optString("id"));}dialogBuilder().setTitle("Следить за сериями").setMultiChoiceItems(labels,checked,(d,i,on)->{String id=rows.get(i).optString("id");if(on)saved.add(id);else saved.remove(id);}).setPositiveButton("Сохранить",(d,w)->library.preferences.edit().putStringSet("saved_series",saved).apply()).setNegativeButton("Отмена",null).show();}
 void downloadAll(){
  if(preparingAll||downloadsBusy())return;
  preparingAll=true;
  message("Читаю квитанцию библиотеки…");
  io.submit(()->{
   try{
    MusicArchive.Receipt receipt=library.archiveReceipt();
    runOnUiThread(()->{
     preparingAll=false;if(isFinishing()||downloadsBusy())return;
     String size=String.format(Locale.ROOT,"ZIP · %.1f МБ",receipt.bytes/1048576.0);
     dialogBuilder().setTitle("Скачать всю библиотеку?").setMessage(size+"\nСеть: "+networkLabel())
      .setNegativeButton("Отмена",null).setPositiveButton("Скачать",(d,w)->startArchiveDownload()).show();
    });
   }catch(Exception e){message("ZIP библиотеки недоступен · "+e.getMessage());runOnUiThread(()->preparingAll=false);}
  });
 }
 void startArchiveDownload(){
  if(downloadsBusy())return;
  takeDownloadPriority();
  startingSequence=DownloadService.accepted.get();downloadStarting=true;
  try{startForegroundService(new Intent(this,DownloadService.class).putExtra("archive",true));message("Скачиваю ZIP библиотеки…");}
  catch(Exception e){downloadStarting=false;message(e.getMessage());}
 }
 String networkLabel(){return library.preferences.getString("network","wifi").equals("wifi")?"только Wi-Fi":"Wi-Fi и мобильная";}
 CheckBox checkBox(String name,boolean value){CheckBox c=new CheckBox(this);c.setText(name);c.setTextColor(fg);c.setMinHeight(dp(48));c.setChecked(value);return c;}
 void filters(){dialogBuilder().setTitle("Фильтры библиотеки").setItems(new String[]{platformLabel(),yearLabel(),filterLabel(),"Сбросить фильтры"},(d,i)->{if(i==0)chooseFacet(false);if(i==1)chooseFacet(true);if(i==2)shortFilter();if(i==3)clearFilters();}).show();}
 void queueSettings(){
 if(!SimilarityStore.REFRESHING.get()&&library.networkAllowed())io.submit(()->{
  if(!SimilarityStore.readable(library))SimilarityStore.refresh(library);
 });
 LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(12),0,dp(12),0);
 CheckBox shuffle=checkBox("Перемешивать треки очереди",library.preferences.getBoolean("shuffle",false));shuffle.setOnCheckedChangeListener((v,on)->{library.preferences.edit().putBoolean("shuffle",on).apply();PlaybackService p=PlaybackService.instance;if(p!=null){p.shuffle=on;p.refreshContinuation();}});box.addView(shuffle);
 box.addView(text("Повтор",14,fg));RadioGroup repeatOptions=new RadioGroup(this);String[] repeatNames={"Без повтора","Один трек","Вся очередь"};for(int i=0;i<3;i++){RadioButton r=new RadioButton(this);r.setId(500+i);r.setText(repeatNames[i]);r.setTextColor(fg);r.setMinHeight(dp(48));repeatOptions.addView(r);}repeatOptions.check(library.preferences.getBoolean("repeat",false)?501:library.preferences.getBoolean("loop",false)?502:500);
 repeatOptions.setOnCheckedChangeListener((g,id)->{boolean one=id==501,loop=id==502;library.preferences.edit().putBoolean("repeat",one).putBoolean("loop",loop).apply();PlaybackService p=PlaybackService.instance;if(p!=null){p.repeat=one;p.repeatCollection=loop;p.refreshContinuation();}});box.addView(repeatOptions);
 box.addView(text("После завершения очереди",14,fg));String[] modes={"stop","series","library","genre","similar","rhythm","sound"};RadioGroup options=new RadioGroup(this);List<RadioButton> choices=new ArrayList<>();for(int i=0;i<modes.length;i++){RadioButton r=new RadioButton(this);r.setId(100+i);r.setText(Continuation.label(modes[i]));r.setTextColor(fg);r.setMinHeight(dp(48));options.addView(r);choices.add(r);if(modes[i].equals(library.preferences.getString("after_album","stop")))options.check(r.getId());}box.addView(options);
 TextView explanation=text("",12,muted);box.addView(explanation);options.setOnCheckedChangeListener((g,id)->{if(id<100||id>=100+modes.length)return;String mode=modes[id-100];if(SimilarityRules.mode(mode)&&!acousticAvailability.enabled(mode))return;library.preferences.edit().putString("after_album",mode).apply();PlaybackService p=PlaybackService.instance;if(p!=null)p.refreshContinuation();});
 CheckBox local=checkBox("Продолжать только скачанное",library.preferences.getBoolean("continue_local",true));local.setOnCheckedChangeListener((v,on)->{library.preferences.edit().putBoolean("continue_local",on).apply();PlaybackService p=PlaybackService.instance;if(p!=null)p.refreshContinuation();});box.addView(local);box.addView(text("«Похожие игры» — по данным игр. «По ритму» и «По звучанию» — отдельные треки по анализу музыки. Фильтры действуют; уже звучавшие треки в музыкальном подборе исключаются.",12,muted));
 TextView indexStatus=text("",12,muted);box.addView(indexStatus);
 Button indexRefresh=button("Обновить индекс анализа",()->io.submit(()->SimilarityStore.refresh(library)));
 box.addView(indexRefresh);
 ScrollView scroll=new ScrollView(this);scroll.addView(box);AlertDialog dialog=dialogBuilder().setTitle("Очередь и продолжение").setView(scroll).setPositiveButton("Готово",null).create();
 Runnable poll=new Runnable(){public void run(){if(!dialog.isShowing()||isFinishing())return;ensureAcousticAvailability();boolean repeating=library.preferences.getBoolean("repeat",false)||library.preferences.getBoolean("loop",false);StringBuilder details=new StringBuilder();for(int i=0;i<modes.length;i++){String mode=modes[i];RadioButton r=choices.get(i);boolean available=!SimilarityRules.mode(mode)||acousticAvailability.enabled(mode);r.setEnabled(!repeating&&available);r.setAlpha(!repeating&&available?1f:.45f);r.setContentDescription(Continuation.label(mode)+(repeating?" · недоступно при повторе":available?"":" · "+acousticAvailability.reason(mode)));if(SimilarityRules.mode(mode)&&!available){if(details.length()>0)details.append("\n");details.append(Continuation.label(mode)).append(": ").append(acousticAvailability.reason(mode));}}
 label(explanation,repeating?"Продолжение приостановлено, пока включён повтор. Выбранный режим сохранён.":details.toString());boolean busy=SimilarityStore.REFRESHING.get();label(indexStatus,busy?"Обновляю индекс анализа…":SimilarityStore.status(library));enableAction(indexRefresh,!busy&&library.networkAllowed());updateLibraryActions();handler.postDelayed(this,400);}};
 dialog.setOnDismissListener(d->handler.removeCallbacks(poll));dialog.show();poll.run();}
 void syncSettings(){
  LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(12),0,dp(12),0);
  box.addView(text("Сеть для музыки, постеров и обновлений приложения",14,fg));RadioGroup networks=new RadioGroup(this);String[] networkNames={"Только Wi-Fi","Wi-Fi и мобильная сеть"};for(int i=0;i<2;i++){RadioButton b=new RadioButton(this);b.setId(300+i);b.setText(networkNames[i]);b.setTextColor(fg);b.setMinHeight(dp(48));networks.addView(b);}networks.check(library.preferences.getString("network","wifi").equals("wifi")?300:301);box.addView(networks);
  CheckBox auto=checkBox("Автоматически обновлять скачанную музыку",library.preferences.getBoolean("auto_music",false));box.addView(auto);
  RadioGroup intervals=new RadioGroup(this);String[] intervalNames={"Раз в час","Раз в сутки · после 07:00 МСК"};for(int i=0;i<2;i++){RadioButton b=new RadioButton(this);b.setId(400+i);b.setText(intervalNames[i]);b.setTextColor(fg);b.setMinHeight(dp(48));intervals.addView(b);}intervals.check(library.preferences.getString("sync_interval","daily").equals("hourly")?400:401);box.addView(intervals);
  CheckBox followAll=checkBox("Добавлять музыку новых игр всей библиотеки",library.preferences.getBoolean("follow_all_music",false));box.addView(followAll);box.addView(button("Серии для автообновления",this::followedSeries));
  android.content.SharedPreferences app=AppUpdates.prefs(this);CheckBox autoApp=checkBox("Автоматически скачивать обновления GremlinAmp",app.getBoolean("auto",false));box.addView(autoApp);CheckBox hourlyApp=checkBox("Проверять приложение раз в час",app.getBoolean("hourly",false));box.addView(hourlyApp);Runnable availability=()->{for(int n=0;n<intervals.getChildCount();n++)intervals.getChildAt(n).setEnabled(auto.isChecked());followAll.setEnabled(auto.isChecked());hourlyApp.setEnabled(autoApp.isChecked());};auto.setOnCheckedChangeListener((v,on)->availability.run());autoApp.setOnCheckedChangeListener((v,on)->availability.run());availability.run();
  box.addView(text("Иначе обновления приложения проверяются раз в сутки после 07:00 МСК. Установку подтверждает Android. Прерванные загрузки и оформление сохранённой музыки продолжаются автоматически по выбранной сети.",12,muted));
  long when=library.preferences.getLong("sync_success",0);box.addView(text("Последнее обновление музыки: "+(when==0?"ещё не выполнялось":android.text.format.DateFormat.format("dd.MM HH:mm",when))+"\n"+library.preferences.getString("sync_status",""),12,muted));
  ScrollView scroll=new ScrollView(this);scroll.addView(box);dialogBuilder().setTitle("Сеть и обновления").setView(scroll).setPositiveButton("Сохранить",(d,w)->{String oldNetwork=library.preferences.getString("network","wifi");library.preferences.edit().putBoolean("auto_music",auto.isChecked()).putBoolean("follow_all_music",followAll.isChecked()).putString("sync_interval",intervals.getCheckedRadioButtonId()==400?"hourly":"daily").putString("network",networks.getCheckedRadioButtonId()==300?"wifi":"any").apply();if(!oldNetwork.equals(networks.getCheckedRadioButtonId()==300?"wifi":"any")){long id=app.getLong("download",-1);if(id>=0)io.submit(()->AppUpdates.cancelDownload(this,id));}app.edit().putBoolean("auto",autoApp.isChecked()).putBoolean("hourly",hourlyApp.isChecked()).apply();MusicSyncJob.schedule(this,true);LibraryMaintenanceJob.reschedule(this);AppUpdates.schedule(this,true);message("Настройки сохранены");refreshDownloadStates();}).setNegativeButton("Отмена",null).show();
 }
 String selectionLabel(List<JSONObject> selected){List<String> states=new ArrayList<>();for(JSONObject game:selected)states.add(downloadStates.get(game.optString("id")));return UiRules.downloadAction(states,downloadsBusy());}
 String seriesSavedLabel(JSONObject r){try{List<JSONObject> list=filteredMembers(r);int saved=0;for(JSONObject g:list)if(musicSaved(g))saved++;return "Скачано игр: "+saved+"/"+list.size();}catch(Exception e){return "";}}
 boolean musicSaved(JSONObject game){String id=game.optString("id"),action=downloadStates.get(id),description=downloadDescriptions.getOrDefault(id,"");return "Скачано".equals(action)||"Обновить".equals(action)||description.startsWith("Музыка скачана");}
 void updateLibraryActions(){updateDownloadButton();}
 void updateDownloadButton(){
  if(downloadButton==null)return;List<JSONObject> selection=downloadSelection();String action=selectionLabel(selection);label(downloadButton,action);describe(downloadButton,action);downloadButton.setVisibility(action.isEmpty()?View.GONE:View.VISIBLE);downloadButton.setEnabled(!action.isEmpty());
  if(collectionStatus!=null){String state="";if(selection.size()==1)state=downloadDescriptions.getOrDefault(selection.get(0).optString("id"),"Проверяю загрузку…");else if(!selection.isEmpty()){int saved=0;for(JSONObject g:selection)if(musicSaved(g))saved++;state="Скачано игр: "+saved+"/"+selection.size();}label(collectionStatus,state);collectionStatus.setVisibility(state.isEmpty()?View.GONE:View.VISIBLE);}
 }
 void refreshDownloadStates(){final int token=++statusRequest;List<JSONObject> list=new ArrayList<>(games);if(album!=null&&!list.contains(album))list.add(album);io.submit(()->{Map<String,String> states=new HashMap<>(),descriptions=new HashMap<>();for(JSONObject g:list){states.put(g.optString("id"),library.downloadLabel(g));descriptions.put(g.optString("id"),library.downloadStatus(g));}boolean pendingWork=library.needsMaintenance();runOnUiThread(()->{if(!isFinishing()&&token==statusRequest){maintenancePending=pendingWork;downloadStates=states;downloadDescriptions=descriptions;render();}});});}
 void playMany(List<JSONObject> list){playCollection(list,false,selectedSeries,null);}
 void zoom(){zoomPath(artPath);}
 void zoomPath(String path){if(path.isEmpty()){message("Постер пока не загружен");return;}Dialog d=new Dialog(this,android.R.style.Theme_Material_NoActionBar);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setBackgroundColor(0xff10151b);layout.addView(button("Закрыть",d::dismiss));PosterView image=new PosterView(this,Library.artwork(new java.io.File(path)));layout.addView(image,new LinearLayout.LayoutParams(-1,0,1));d.setContentView(layout);d.getWindow().setLayout(-1,-1);d.show();d.getWindow().setLayout(-1,-1);}
 public void onBackPressed(){requestId++;if(!backStack.isEmpty()){backStack.pop().restore();return;}if(local||screen.equals("series")){browse(false);return;}super.onBackPressed();}
 public void onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();artworkIo.shutdownNow();super.onDestroy();}
}
