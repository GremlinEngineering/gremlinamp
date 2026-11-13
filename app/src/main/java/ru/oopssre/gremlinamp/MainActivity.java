package ru.oopssre.gremlinamp;
import android.app.*;import android.os.*;import android.content.*;import android.content.res.Configuration;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;import org.json.*;import java.util.*;import java.util.concurrent.*;

public class MainActivity extends Activity {
 final ExecutorService io=Executors.newFixedThreadPool(3);final ExecutorService artworkIo=Executors.newFixedThreadPool(3);final Handler handler=new Handler(Looper.getMainLooper());Library library;
 List<JSONObject> games=new ArrayList<>(),scope=new ArrayList<>(),archivedGames=new ArrayList<>();java.io.File seenArchive;JSONArray series,trackSearch;JSONObject raIndex,album,manifest,selectedSeries;List<PlaybackService.Entry> albumQueue=new ArrayList<>();
 LinearLayout body,rows,letters;TextView display,time,status,heading,playbackStatus;Button playerPoster;EditText search;SeekBar seek;Button play,previousButton,nextButton,platformButton,downloadButton,queueButton,gamesTab,seriesTab,savedTab;TextView collectionStatus;boolean preparingAll,downloadStarting,maintenancePending,clearingDownloads;long startingSequence;Map<String,String> downloadStates=new HashMap<>(),downloadDescriptions=new HashMap<>();long seenRevision=-1,seenSyncSuccess;int statusRequest;boolean previousDownloadActive;String platformFilter="",yearFilter="";String screen="games",letter="",artPath="",displayedArt="";boolean local,dragging;PlaybackService seekOwner;int seekGeneration;int requestId;volatile int playRequest;int preparingSeriesRequest=-1;String preparingSeries="";volatile int artVersion;int bg,panel,fg,muted,accent,line;
 final Deque<BrowseSnapshot> backStack=new ArrayDeque<>();
 final class BrowseSnapshot {
  final String page=screen,query=search==null?"":search.getText().toString(),initial=letter;final boolean downloaded=local,tracksOnly=explicitTrackSearch;final int scrollY=catalogScroll.getScrollY();
  final JSONObject seriesItem=selectedSeries,game=album,tracks=manifest;final List<JSONObject> members=new ArrayList<>(scope);final List<PlaybackService.Entry> playlist=new ArrayList<>(albumQueue);
  void restore(){screen=page;local=downloaded;selectedSeries=seriesItem;album=game;manifest=tracks;scope=new ArrayList<>(members);albumQueue=new ArrayList<>(playlist);letter=initial;explicitTrackSearch=tracksOnly;search.setText(query);alphabet();render();catalogScroll.post(()->catalogScroll.scrollTo(0,scrollY));}
 }
 void updateNavigation(){if(gamesTab==null)return;backButton.setVisibility(visibleSurface!=Surface.CATALOG||!backStack.isEmpty()||local||!screen.equals("games")?View.VISIBLE:View.GONE);boolean seriesSelected=screen.equals("series")||selectedSeries!=null;Button[] tabs={gamesTab,seriesTab,savedTab};boolean[] chosen={!local&&!seriesSelected,seriesSelected,local};for(int i=0;i<tabs.length;i++){tabs[i].setSelected(chosen[i]);tabs[i].setTextColor(chosen[i]?accent:muted);tabs[i].setTypeface(Typeface.MONOSPACE,chosen[i]?Typeface.BOLD:Typeface.NORMAL);tabs[i].setBackground(surface(chosen[i]?panel:bg));}}
 SimilarityAvailability acousticAvailability=SimilarityAvailability.unavailable("Проверяю доступность анализа…");String acousticKey="@";long acousticRevision=-1;int acousticRequest;
 String acousticSeed(){PlaybackService p=PlaybackService.instance;if(p==null||p.station!=null)return "";List<PlaybackService.Entry> q=p.queue;int i=p.position;if(i<0||i>=q.size())return "";PlaybackService.Entry e=q.get(i);JSONArray tracks=e.manifest.optJSONArray("tracks");JSONObject t=tracks==null?null:tracks.optJSONObject(e.index);return t==null?"":SimilarityRules.key(t);}
 void ensureAcousticAvailability(){String seed=acousticSeed();long revision=SimilarityStore.revision.get();if(seed.equals(acousticKey)&&revision==acousticRevision)return;acousticKey=seed;acousticRevision=revision;int request=++acousticRequest;acousticAvailability=SimilarityAvailability.unavailable("Проверяю доступность анализа…");io.submit(()->{SimilarityAvailability ready=SimilarityStore.availability(library,seed);runOnUiThread(()->{if(!isFinishing()&&request==acousticRequest&&revision==SimilarityStore.revision.get()&&seed.equals(acousticSeed()))acousticAvailability=ready;});});}
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 GradientDrawable surface(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));d.setStroke(dp(1),line);return d;}
 TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTypeface(Typeface.MONOSPACE);v.setTextColor(color);v.setPadding(dp(8),dp(5),dp(8),dp(5));return v;}
 Button button(String name,Runnable click){Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(13);b.setTypeface(Typeface.MONOSPACE);b.setTextColor(accent);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setPadding(dp(8),0,dp(8),0);b.setBackground(surface(panel));b.setOnClickListener(v->click.run());String icon=name.equals("Пред.")?"prev":name.equals("След.")?"next":name.equals("Играть")?"play":null;if(icon!=null){b.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(icon,accent,dp(18)),null,null,null);b.setCompoundDrawablePadding(dp(4));}b.setContentDescription(name);return b;}
 boolean label(TextView view,CharSequence value){if(android.text.TextUtils.equals(view.getText(),value))return false;view.setText(value);return true;}
 void describe(View view,CharSequence value){if(!android.text.TextUtils.equals(view.getContentDescription(),value))view.setContentDescription(value);}
 void enableAction(Button button,boolean enabled){button.setEnabled(enabled);button.setAlpha(enabled?1f:.4f);}
 void weighted(LinearLayout row,View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(v,p);}
 void message(String s){runOnUiThread(()->{if(!isFinishing())label(status,s);});}
 public void onCreate(Bundle state){super.onCreate(state);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);library=new Library(this);seenSyncSuccess=library.preferences.getLong("sync_success",0);platformFilter=library.preferences.getString("platform_filter","");yearFilter=library.preferences.getString("year_filter","");seriesFilter=library.preferences.getString("search_series","");originFilter=HandheldTrackSearch.Origin.saved(library.preferences.getString("search_origin",""));ui();load();MusicSyncJob.schedule(this);LibraryMaintenanceJob.schedule(this);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);handler.post(tick);AppUpdates.schedule(this,false);if(getIntent().getBooleanExtra("app_update",false))handler.post(this::appUpdateSettings);}
 private enum Surface { CATALOG, PLAYER, SETTINGS }
 private Surface visibleSurface=Surface.CATALOG,settingsReturn=Surface.CATALOG;
 private LinearLayout catalog,miniPlayer,queueRows,albumSeek;
 private ScrollView catalogScroll,playerScroll,settingsScroll;
 private TextView brandTitle,miniTitle,miniDetail,playerDetail,settingsProgress,orbitInfo;
 private Button backButton,settingsButton,menuButton,miniToggle,queueToggle,rewindAlbum,forwardAlbum,rewindPlayer,forwardPlayer;
 private HandheldSpectrum spectrum;
 private Button generatorOptions,voyagerOptions;
 private boolean cityMic;
 private CheckBox cityMicControl;
 private CityInput cityInput;
 private PlaybackService streetPlayer;
 private static final int CITY_MIC_PERMISSION=21;

 private boolean queueExpanded,foreground;
 private String highlightedTrack="";
 private List<PlaybackService.Entry> shownQueue=Collections.emptyList();
 private int shownQueuePosition=-1;
 private static final long SEEK_STEP_MS=10_000;

 void ui(){
  String theme=getPreferences(0).getString("theme","dark");
  boolean dark=theme.equals("dark")||theme.equals("auto")
    &&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
  bg=dark?0xff0d1611:0xfff0f5eb;
  panel=dark?0xff15251c:0xffe1eddf;
  fg=dark?0xffe7f3e9:0xff173125;
  muted=dark?0xff9eb9a7:0xff456451;
  accent=dark?0xff8de9ad:0xff17613b;
  line=dark?0xff2e4a3a:0xffa7bfa9;
  body=column();
  body.setBackgroundColor(bg);
  body.setPadding(dp(12),dp(4),dp(12),dp(5));
  setContentView(body);
  body.setOnApplyWindowInsetsListener((v,i)->{
   v.setPadding(dp(12)+i.getSystemWindowInsetLeft(),dp(4)+i.getSystemWindowInsetTop(),
     dp(12)+i.getSystemWindowInsetRight(),dp(5)+i.getSystemWindowInsetBottom());
   return i;
  });
  buildHeader();
  buildCatalog();
  buildPlayer();
  buildSettings();
  buildMiniPlayer();
  updateSurface();
  updateFilterButton();
  alphabet();
  render();
  showTransport(PlaybackService.instance);
 }

 private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
 private LinearLayout horizontal(){LinearLayout layout=new LinearLayout(this);layout.setGravity(Gravity.CENTER_VERTICAL);return layout;}
 private ScrollView scroll(LinearLayout child){
  ScrollView view=new ScrollView(this);
  view.addView(child,new ScrollView.LayoutParams(-1,-2));
  return view;
 }

 private Button headerButton(LinearLayout row,String symbol,String title,Runnable action){
  Button control=button(symbol,action);
  control.setTextSize(22);
  control.setBackgroundColor(Color.TRANSPARENT);
  describe(control,title);
  row.addView(control,new LinearLayout.LayoutParams(dp(48),dp(48)));
  return control;
 }

 private void buildHeader(){
  LinearLayout header=horizontal();
  backButton=headerButton(header,"←","Назад",this::onBackPressed);
  TextView prompt=text(">_",17,accent);
  prompt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  header.addView(prompt);
  brandTitle=text("GremlinAmp",18,fg);
  brandTitle.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
  if(Build.VERSION.SDK_INT>=28)brandTitle.setAccessibilityHeading(true);
  header.addView(brandTitle,new LinearLayout.LayoutParams(0,-2,1));
  menuButton=headerButton(header,"⋮","Меню библиотеки",this::mainMenu);
  settingsButton=headerButton(header,"⚙","Настройки",()->{
   settingsReturn=visibleSurface;
   showSurface(Surface.SETTINGS);
  });
  body.addView(header);
 }

 private void buildCatalog(){
  catalog=column();
  LinearLayout tabs=horizontal();
  gamesTab=button("Игры",()->browse(false));
  seriesTab=button("Серии",this::showSeries);
  savedTab=button("Скачано",()->browse(true));
  for(Button tab:new Button[]{gamesTab,seriesTab,savedTab})weighted(tabs,tab);
  catalog.addView(tabs);

  LinearLayout find=horizontal();
  search=new EditText(this);
  search.setSingleLine();
  search.setTypeface(Typeface.MONOSPACE);
  search.setTextSize(15);
  search.setTextColor(fg);
  search.setHintTextColor(muted);
  search.setHint("Игра, серия, трек");
  describe(search,"Поиск по игре, серии, треку, исполнителю или RA ID");
  search.setBackground(surface(panel));
  search.setPadding(dp(10),0,dp(8),0);
  search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
  search.setOnEditorActionListener((v,action,event)->{
   if(action!=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)return false;
   hideKeyboard();
   if(canSearchTracks()){explicitTrackSearch=true;refreshFilters();}
   return true;
  });
  find.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
  platformButton=button("Фильтры",this::filters);
  describe(platformButton,"Фильтры поиска");
  LinearLayout.LayoutParams filterSize=new LinearLayout.LayoutParams(dp(106),dp(48));
  filterSize.leftMargin=dp(6);
  find.addView(platformButton,filterSize);
  catalog.addView(find);

  LinearLayout titleRow=horizontal();
  heading=text("Каталог",19,fg);
  heading.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
  if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
  titleRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
  downloadButton=button("Скачать",()->plan(downloadSelection()));
  titleRow.addView(downloadButton);
  catalog.addView(titleRow);
  collectionStatus=text("",12,muted);
  catalog.addView(collectionStatus);

  albumSeek=seekButtons(true);
  catalog.addView(albumSeek);
  HorizontalScrollView alphabet=new HorizontalScrollView(this);
  alphabet.setHorizontalScrollBarEnabled(false);
  letters=horizontal();
  alphabet.addView(letters);
  catalog.addView(alphabet,new LinearLayout.LayoutParams(-1,dp(44)));

  status=text("Загрузка каталога…",12,muted);
  catalog.addView(status);
  rows=column();
  catalogScroll=scroll(rows);
  catalog.addView(catalogScroll,new LinearLayout.LayoutParams(-1,0,1));
  body.addView(catalog,new LinearLayout.LayoutParams(-1,0,1));
  search.addTextChangedListener(new android.text.TextWatcher(){
   public void beforeTextChanged(CharSequence s,int start,int count,int after){}
   public void onTextChanged(CharSequence s,int start,int before,int count){trackResultLimit=60;render();}
   public void afterTextChanged(android.text.Editable e){}
  });
 }

 private void buildMiniPlayer(){
  miniPlayer=horizontal();
  miniPlayer.setBackground(surface(panel));
  LinearLayout miniCopy=column();
  miniTitle=text("Выберите композицию",15,fg);
  miniTitle.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
  miniDetail=text("",12,muted);
  for(TextView label:new TextView[]{miniTitle,miniDetail}){
   label.setSingleLine();
   label.setEllipsize(android.text.TextUtils.TruncateAt.END);
   miniCopy.addView(label);
  }
  miniCopy.setMinimumHeight(dp(54));
  miniCopy.setGravity(Gravity.CENTER_VERTICAL);
  miniCopy.setFocusable(true);
  describe(miniCopy,"Открыть плеер");
  miniCopy.setOnClickListener(v->showSurface(Surface.PLAYER));
  miniPlayer.addView(miniCopy,new LinearLayout.LayoutParams(0,-2,1));
  miniToggle=button("▶",()->transport(PlaybackService::toggle));
  miniToggle.setTextSize(20);
  miniPlayer.addView(miniToggle,new LinearLayout.LayoutParams(dp(50),dp(48)));
  body.addView(miniPlayer);
 }

 private void buildPlayer(){
  LinearLayout player=column();
  playbackStatus=text("",12,accent);
  player.addView(playbackStatus);
  display=text("Выберите композицию",25,fg);
  display.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
  if(Build.VERSION.SDK_INT>=28)display.setAccessibilityHeading(true);
  player.addView(display);
  playerDetail=text("",13,muted);
  player.addView(playerDetail);
  generatorOptions=button("Режимы генератора",this::spaceMenu);
  generatorOptions.setVisibility(View.GONE);
  player.addView(generatorOptions);
  voyagerOptions=button("Слои «Вояджера»",this::voyagerMenu);
  voyagerOptions.setVisibility(View.GONE);
  player.addView(voyagerOptions);
  orbitInfo=text("",12,accent);
  orbitInfo.setTypeface(Typeface.MONOSPACE);
  orbitInfo.setPadding(dp(8),dp(8),dp(8),dp(8));
  orbitInfo.setBackground(surface(panel));
  orbitInfo.setVisibility(View.GONE);
  player.addView(orbitInfo);
  spectrum=new HandheldSpectrum();
  spectrum.setBackground(surface(panel));
  LinearLayout.LayoutParams meterSize=new LinearLayout.LayoutParams(-1,dp(110));
  meterSize.topMargin=dp(10);
  player.addView(spectrum,meterSize);
  time=text("00:00 / --:--",15,fg);
  player.addView(time);

  seek=new SeekBar(this);
  seek.setMax(1000);
  describe(seek,"Позиция воспроизведения");
  android.content.res.ColorStateList green=android.content.res.ColorStateList.valueOf(accent);
  seek.setProgressTintList(green);
  seek.setThumbTintList(green);
  player.addView(seek,new LinearLayout.LayoutParams(-1,dp(48)));
  seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onStartTrackingTouch(SeekBar b){
    dragging=true;
    seekOwner=PlaybackService.instance;
    if(seekOwner!=null)seekGeneration=seekOwner.generation;
   }
   public void onStopTrackingTouch(SeekBar b){
    PlaybackService service=PlaybackService.instance;
    if(service!=null&&service==seekOwner&&service.generation==seekGeneration)
     service.seek(service.duration*b.getProgress()/1000);
    seekOwner=null;
    dragging=false;
    showTransport(service);
   }
   public void onProgressChanged(SeekBar b,int progress,boolean user){
    PlaybackService service=PlaybackService.instance;
    if(!user||service==null||!service.seekAvailable())return;
    long target=service.duration*progress/1000;
    if(dragging&&service==seekOwner&&service.generation==seekGeneration)
     label(time,stamp(target)+" / "+stamp(service.duration));
    else if(!dragging)service.seek(target);
   }
  });

  LinearLayout controls=horizontal();
  previousButton=button("Пред.",()->transport(PlaybackService::previous));
  describe(previousButton,"Предыдущий трек");
  play=button("Играть",()->transport(PlaybackService::toggle));
  play.setTextSize(15);
  play.setBackground(surface(accent));
  play.setTextColor(bg);
  nextButton=button("След.",()->transport(PlaybackService::next));
  describe(nextButton,"Следующий трек");
  weighted(controls,previousButton);
  LinearLayout.LayoutParams mainSize=new LinearLayout.LayoutParams(0,dp(52),1.45f);
  mainSize.setMargins(dp(2),0,dp(2),0);
  controls.addView(play,mainSize);
  weighted(controls,nextButton);
  player.addView(controls);
  player.addView(seekButtons(false));

  LinearLayout actions=horizontal();
  queueToggle=button("Треклист",()->{
   queueExpanded=!queueExpanded;
   shownQueuePosition=-1;
   updatePlayerQueue(PlaybackService.instance);
  });
  queueButton=button("Очередь",this::queueSettings);
  weighted(actions,queueToggle);
  weighted(actions,queueButton);
  player.addView(actions);
  queueRows=column();
  player.addView(queueRows);
  Button poster=button("Постер игры",this::zoom);
  player.addView(poster);
  poster.setVisibility(View.GONE);
  playerPoster=poster;
  playerScroll=scroll(player);
  body.addView(playerScroll,new LinearLayout.LayoutParams(-1,0,1));
  shownQueuePosition=-1;
 }

 private LinearLayout seekButtons(boolean albumControls){
  LinearLayout controls=horizontal();
  Button back=seekButton(false),forward=seekButton(true);
  weighted(controls,back);
  weighted(controls,forward);
  if(albumControls){rewindAlbum=back;forwardAlbum=forward;}
  else{rewindPlayer=back;forwardPlayer=forward;}
  return controls;
 }

 private Button seekButton(boolean forward){
  Button control=button(forward?"+10 с":"−10 с",()->{
   PlaybackService service=PlaybackService.instance;
   if(service==null)return;
   if(service.seekAvailable())service.seekRelative(forward?SEEK_STEP_MS:-SEEK_STEP_MS);
   else Toast.makeText(this,"Перемотка недоступна. Удерживайте для перехода по трекам.",Toast.LENGTH_SHORT).show();
  });
  control.setOnLongClickListener(v->{
   PlaybackService service=PlaybackService.instance;
   if(service!=null&&(forward?service.canNext():service.currentEntry()!=null))
    transport(forward?PlaybackService::next:PlaybackService::previous);
   return true;
  });
  return control;
 }

 private void updateSeekButton(Button control,boolean forward,boolean seek,boolean skip){
  enableAction(control,seek||skip);
  control.setLongClickable(skip);
  String direction=forward?"вперёд":"назад";
  String track=forward?"следующий трек":"предыдущий трек";
  describe(control,(seek?"Перемотать на 10 секунд "+direction:"Перемотка "+direction+" недоступна")
    +(skip?". Удерживайте для перехода: "+track:""));
 }

 private void buildSettings(){
  LinearLayout settings=column();
  addSetting(settings,"Воспроизведение","Очередь, повтор и продолжение",this::queueSettings);
  addSetting(settings,"Офлайн-библиотека","Скачивание ZIP и сохранённая музыка",this::offlineSettings);
  settingsProgress=text("",12,muted);
  settingsProgress.setVisibility(View.GONE);
  settings.addView(settingsProgress);
  addSetting(settings,"Сеть и обновления","Wi-Fi, мобильная сеть и обновления",this::syncSettings);
  addSetting(settings,"Оформление","Тема терминала",this::theme);
  addSetting(settings,"Радио","Список станций",this::radioStations);
  if(RadioStations.available(this,true).stream().anyMatch(RadioStations.Station::personal))
   addSetting(settings,"Личный режим радио","Доступ по личному коду",this::personalRadio);
  addSetting(settings,"Обновление приложения","Проверить новую версию",this::appUpdateSettings);
  addSetting(settings,"О приложении","Версия, сайт и лицензии",this::about);
  settingsScroll=scroll(settings);
  body.addView(settingsScroll,new LinearLayout.LayoutParams(-1,0,1));
 }

 private View addSetting(LinearLayout list,String name,String detail,Runnable action){
  LinearLayout row=horizontal();
  row.setBackground(surface(panel));
  row.setMinimumHeight(dp(58));
  row.setFocusable(true);
  describe(row,name+" · "+detail);
  row.setOnClickListener(v->action.run());
  LinearLayout copy=column();
  TextView title=text(name,15,fg);
  title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
  copy.addView(title);
  copy.addView(text(detail,12,muted));
  row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
  row.addView(text("›",18,accent));
  LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);
  spacing.bottomMargin=dp(5);
  list.addView(row,spacing);
  return row;
 }

 private void showSurface(Surface target){
  hideKeyboard();
  visibleSurface=target;
  updateSurface();
  showTransport(PlaybackService.instance);
 }

 private void updateSurface(){
  boolean browsing=visibleSurface==Surface.CATALOG, playing=visibleSurface==Surface.PLAYER;
  catalog.setVisibility(browsing?View.VISIBLE:View.GONE);
  miniPlayer.setVisibility(browsing?View.VISIBLE:View.GONE);
  playerScroll.setVisibility(playing?View.VISIBLE:View.GONE);
  settingsScroll.setVisibility(visibleSurface==Surface.SETTINGS?View.VISIBLE:View.GONE);
  menuButton.setVisibility(browsing?View.VISIBLE:View.GONE);
  settingsButton.setVisibility(visibleSurface==Surface.SETTINGS?View.GONE:View.VISIBLE);
  label(brandTitle,browsing?"GremlinAmp":playing?"Плеер":"Настройки");
  updateNavigation();
 }

 private final class HandheldSpectrum extends View {
  private static final int LEVELS=8;
  private final Paint paint=new Paint();
  HandheldSpectrum(){super(MainActivity.this);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
  @Override protected void onDraw(Canvas canvas){
   PlaybackService service=PlaybackService.instance;
   boolean running=service!=null&&service.state==TransportState.PLAYING;
   float[] bands=running?service.audioEnergy.spectrum():null;
   int columns=AudioEnergy.SPECTRUM_BANDS;
   float gap=dp(3),size=Math.min((getWidth()-dp(16)-gap*(columns-1))/columns,(getHeight()-dp(16)-gap*(LEVELS-1))/LEVELS);
   if(size<=0)return;
   float start=(getWidth()-columns*size-(columns-1)*gap)/2;
   float bottom=(getHeight()+LEVELS*size+(LEVELS-1)*gap)/2;
   for(int column=0;column<columns;column++){
    int level=bands==null?0:Math.round(bands[column]*LEVELS);
    float x=start+column*(size+gap);
    for(int row=0;row<LEVELS;row++){
     paint.setColor(row>=level?line:accent);
     paint.setAlpha(row>=level?180:row==level-1?255:155+row*10);
     float y=bottom-size-row*(size+gap);
     canvas.drawRect(x,y,x+size,y+size,paint);
    }
   }
   if(running&&isShown())postInvalidateDelayed(40);
  }
 }
 AlertDialog.Builder dialogBuilder(){boolean dark=bg==0xff0d1611;return new AppDialogBuilder(new android.view.ContextThemeWrapper(this,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert),bg,fg,accent,line);}
 void theme(){
  String[] names={"Как в системе","Тёмная · терминал","Светлая · терминал"},values={"auto","dark","light"};
  int selected=Arrays.asList(values).indexOf(getPreferences(0).getString("theme","dark"));
  dialogBuilder().setTitle("Оформление").setSingleChoiceItems(names,selected,(d,i)->{
   d.dismiss();
   String query=search.getText().toString();
   String message=status.getText().toString();
   int scrollY=catalogScroll.getScrollY();
   getPreferences(0).edit().putString("theme",values[i]).apply();
   ui();
   search.setText(query);
   label(status,message);
   catalogScroll.post(()->catalogScroll.scrollTo(0,scrollY));
  }).setNegativeButton("Закрыть",null).show();
 }
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
 void mainMenu(){
  List<String> labels=new ArrayList<>();
  List<Runnable> actions=new ArrayList<>();
  offlineActions(labels,actions);
  if(!library.preferences.getString("last_track_id","").isEmpty()){
   labels.add("Последняя композиция");actions.add(this::lastTrack);
  }
  labels.add("Музыка космоса и города");actions.add(this::spaceMenu);
  labels.add("Радио");actions.add(this::radioStations);
  labels.add("Настройки");actions.add(()->{settingsReturn=Surface.CATALOG;showSurface(Surface.SETTINGS);});
  labels.add("Обновление приложения");actions.add(this::appUpdateSettings);
  dialogBuilder().setTitle("Библиотека").setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).show();
 }
 private void offlineSettings(){
  List<String> labels=new ArrayList<>();
  List<Runnable> actions=new ArrayList<>();
  offlineActions(labels,actions);
  labels.add("Сеть и автообновление");actions.add(this::syncSettings);
  dialogBuilder().setTitle("Офлайн-библиотека")
    .setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run())
    .setNegativeButton("Закрыть",null).show();
 }
 private void offlineActions(List<String> labels,List<Runnable> actions){
  if(!downloadsBusy()&&!preparingAll){
   if(library.archive.pending()){labels.add("Продолжить загрузку ZIP");actions.add(this::startArchiveDownload);}
   else if(!library.archive.hasDownload()){labels.add("Скачать все мелодии");actions.add(this::downloadAll);}
   labels.add("Обновить библиотеку");actions.add(this::refreshLibrary);
  }
  if(downloadsBusy()||MusicSyncJob.active!=null||LibraryMaintenanceJob.active!=null||maintenancePending){
   labels.add("Приостановить загрузку");actions.add(this::pauseDownloads);
  }
  if(!clearingDownloads&&(library.hasDownloadedMusic()||DownloadService.instance!=null)){
   labels.add("Удалить скачанную музыку");actions.add(this::clearDownloadedMusic);
  }
 }
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
      offlineSearchKey=null;
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
    .setMessage("Текущая копия будет доступна, пока новый ZIP скачивается, проверяется и распаковывается.")
    .setNegativeButton("Отмена",null).setPositiveButton("Обновить",(d,w)->startLibrarySync()).show();
  }else startLibrarySync();
 }
 void startLibrarySync(){if(downloadsBusy())return;takeDownloadPriority();startingSequence=DownloadService.accepted.get();downloadStarting=true;try{startForegroundService(new Intent(this,DownloadService.class).putExtra("sync",true));message("Обновляю библиотеку…");}catch(Exception e){downloadStarting=false;message(e.getMessage());}}
 void takeDownloadPriority(){android.app.job.JobScheduler scheduler=(android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);scheduler.cancel(LibraryMaintenanceJob.ID);if(MusicSyncJob.active!=null){scheduler.cancel(MusicSyncJob.ID);MusicSyncJob.schedule(this,true);}}
 void pauseDownloads(){LibraryMaintenanceJob current=LibraryMaintenanceJob.active;if(current!=null&&current.library!=null)current.library.cancelWork();MusicSyncJob sync=MusicSyncJob.active;if(sync!=null&&sync.library!=null)sync.library.cancelWork();new Library(this).pausePending();((android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE)).cancel(LibraryMaintenanceJob.ID);if(sync!=null){((android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE)).cancel(MusicSyncJob.ID);MusicSyncJob.schedule(this,true);}if(DownloadService.instance!=null)startService(new Intent(this,DownloadService.class).putExtra("cancel",true));message("Загрузка приостановлена");refreshDownloadStates();}
 void startDownload(List<JSONObject> games,String seriesId){if(downloadsBusy())return;takeDownloadPriority();if(seriesId!=null)library.trackSeries(seriesId);JSONArray payload=new JSONArray();for(JSONObject game:games)payload.put(game);startingSequence=DownloadService.accepted.get();downloadStarting=true;try{startForegroundService(new Intent(this,DownloadService.class).putExtra("games",payload.toString()));message(library.networkAllowed()?"Начинаю скачивание…":"Загрузка продолжится при появлении разрешённой сети");}catch(Exception e){downloadStarting=false;message(e.getMessage());}}
 private void spaceMenu(){
  LinearLayout options=column();
  PlaybackService player=PlaybackService.instance;
  AlertDialog dialog=dialogBuilder().setTitle("Музыка космоса и города")
   .setView(scroll(options)).setNegativeButton("Закрыть",null).create();
  for(SpaceTracker.Mode mode:SpaceTracker.Mode.values()){
   boolean selected=player!=null&&player.spaceMode==mode;
   addSetting(options,(selected?"•  ":"")+mode.title,mode.source,()->{
    dialog.dismiss();
    playRequest++;
    command(service->{
     if(service.spaceMode!=mode||service.state==TransportState.ERROR)service.playSpace(mode);
     else service.resume();
     showSurface(Surface.PLAYER);
    });
   });
  }
  addSetting(options,"Слои «Вояджера»","Запись плазмы · синтетические помехи",()->{
   dialog.dismiss();
   voyagerMenu();
  });
  CheckBox motion=generatorCheckBox("Ритм шагов и наклон");
  motion.setChecked(library.preferences.getBoolean("space_motion",true));
  motion.setOnCheckedChangeListener((button,on)->{
   library.preferences.edit().putBoolean("space_motion",on).apply();
   PlaybackService current=PlaybackService.instance;
   if(current!=null)current.spaceMotionEnabled(on);
  });
  options.addView(motion);
  CheckBox effects=generatorCheckBox("Шум и искажения · «Звёзды» и «Солнечная система»");
  effects.setChecked(library.preferences.getBoolean("space_effects",false));
  effects.setOnCheckedChangeListener((button,on)->{
   android.content.SharedPreferences.Editor preferences=library.preferences.edit().putBoolean("space_effects",on);
   if(!library.preferences.contains("voyager_effects"))
    preferences.putBoolean("voyager_effects",library.preferences.getBoolean("space_effects",false));
   preferences.apply();
   PlaybackService current=PlaybackService.instance;
   if(current!=null)current.spaceEffects(on);
  });
  options.addView(effects);
  CheckBox mic=generatorCheckBox("Уличный звук через микрофон · «Москва»");
  cityMicControl=mic;
  mic.setChecked(cityMic);
  mic.setOnCheckedChangeListener((button,on)->{
   cityMic=on;
   if(on&&checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=0)
    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},CITY_MIC_PERMISSION);
   updateStreetInput(PlaybackService.instance);
  });
  options.addView(mic);
  dialog.setOnDismissListener(d->{if(cityMicControl==mic)cityMicControl=null;});
  dialog.show();
 }
 private CheckBox generatorCheckBox(String label){
  CheckBox option=new CheckBox(this);
  option.setText(label);
  option.setTextColor(fg);
  option.setMinHeight(dp(48));
  option.setButtonTintList(new android.content.res.ColorStateList(
   new int[][]{new int[]{android.R.attr.state_checked},new int[0]},new int[]{accent,muted}));
  return option;
 }
 private void voyagerMenu(){
  LinearLayout options=column();
  TextView description=text("Трекер Mission Control играет во всех сочетаниях.",13,muted);
  description.setPadding(dp(12),dp(8),dp(12),dp(4));
  options.addView(description);
  CheckBox plasma=generatorCheckBox("Архивный сигнал плазмы");
  plasma.setChecked(library.preferences.getBoolean("space_plasma",true));
  plasma.setOnCheckedChangeListener((button,on)->{
   library.preferences.edit().putBoolean("space_plasma",on).apply();
   PlaybackService current=PlaybackService.instance;
   if(current!=null)current.spacePlasma(on);
  });
  options.addView(plasma);
  CheckBox effects=generatorCheckBox("Синтетический шум и искажения");
  effects.setChecked(library.preferences.getBoolean("voyager_effects",library.preferences.getBoolean("space_effects",false)));
  effects.setOnCheckedChangeListener((button,on)->{
   library.preferences.edit().putBoolean("voyager_effects",on).apply();
   PlaybackService current=PlaybackService.instance;
   if(current!=null)current.voyagerEffects(on);
  });
  options.addView(effects);
  dialogBuilder().setTitle("Слои «Вояджера»").setView(scroll(options)).setPositiveButton("Готово",null).show();
 }
 private void updateStreetInput(PlaybackService service){
  PlaybackService target=foreground&&cityMic&&service!=null&&service.spaceMode==SpaceTracker.Mode.CITY&&
    service.state.wantsPlayback()&&checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==0?service:null;
  if(target==streetPlayer)return;
  if(cityInput!=null)cityInput.stop();
  cityInput=null;streetPlayer=target;
  if(target!=null){cityInput=new CityInput(target::street);cityInput.start();}
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
  super.onRequestPermissionsResult(code,permissions,grants);
  if(code==CITY_MIC_PERMISSION){
   cityMic=cityMic&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;
   if(cityMicControl!=null)cityMicControl.setChecked(cityMic);
   updateStreetInput(PlaybackService.instance);
  }
 }
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
 private String seriesFilter="";
 private HandheldTrackSearch.Origin originFilter=HandheldTrackSearch.Origin.ALL;
 private boolean explicitTrackSearch;
 private int trackResultLimit=60;

 private boolean canSearchTracks(){return screen.equals("games")&&selectedSeries==null;}
 private boolean trackResultsOnly(){
  return canSearchTracks()&&(explicitTrackSearch||!seriesFilter.isEmpty()||originFilter!=HandheldTrackSearch.Origin.ALL);
 }

 private JSONObject filterSeries(){
  if(series==null||seriesFilter.isEmpty())return null;
  for(int i=0;i<series.length();i++){
   JSONObject group=series.optJSONObject(i);
   if(group!=null&&seriesFilter.equals(group.optString("id")))return group;
  }
  return null;
 }

 private String seriesLabel(){
  JSONObject group=filterSeries();
  return group==null?"Все серии":group.optString("title");
 }

 private void chooseSearchSeries(){
  List<String> labels=new ArrayList<>(List.of("Все серии"));
  List<String> ids=new ArrayList<>(List.of(""));
  if(series!=null)for(int i=0;i<series.length();i++){
   JSONObject group=series.optJSONObject(i);
   if(group==null)continue;
   labels.add(group.optString("title"));
   ids.add(group.optString("id"));
  }
  dialogBuilder().setTitle("Серия").setSingleChoiceItems(labels.toArray(new String[0]),ids.indexOf(seriesFilter),(dialog,i)->{
   seriesFilter=ids.get(i);
   library.preferences.edit().putString("search_series",seriesFilter).apply();
   dialog.dismiss();
   refreshFilters();
  }).setNegativeButton("Назад",null).show();
 }

 private void chooseTrackOrigin(){
  HandheldTrackSearch.Origin[] choices=HandheldTrackSearch.Origin.values();
  String[] labels=Arrays.stream(choices).map(choice->choice.choice).toArray(String[]::new);
  dialogBuilder().setTitle("Происхождение").setSingleChoiceItems(labels,originFilter.ordinal(),(dialog,i)->{
   originFilter=choices[i];
   library.preferences.edit().putString("search_origin",originFilter.name()).apply();
   dialog.dismiss();
   refreshFilters();
  }).setNegativeButton("Назад",null).show();
 }

 private void updateFilterButton(){
  int count=(platformFilter.isEmpty()?0:1)+(yearFilter.isEmpty()?0:1)+(seriesFilter.isEmpty()?0:1)
    +(originFilter==HandheldTrackSearch.Origin.ALL?0:1)+(library.preferences.getInt("short_seconds",0)>0?1:0)
    +(library.preferences.getString("unknown_duration","show").equals("show")?0:1);
  label(platformButton,count==0?"Фильтры":"Фильтры · "+count);
 }

 private void refreshFilters(){
  trackResultLimit=60;
  letter="";
  updateFilterButton();
  alphabet();
  render();
  catalogScroll.scrollTo(0,0);
 }

 private void clearFilters(){
  platformFilter="";
  yearFilter="";
  seriesFilter="";
  originFilter=HandheldTrackSearch.Origin.ALL;
  explicitTrackSearch=false;
  library.preferences.edit().putString("platform_filter","").putString("year_filter","")
    .remove("search_series").remove("search_origin").putInt("short_seconds",0).putString("unknown_duration","show").apply();
  search.setText("");
  refreshFilters();
 }

 void filters(){
  hideKeyboard();
  List<String> labels=new ArrayList<>();
  List<Runnable> actions=new ArrayList<>();
  labels.add(platformLabel());actions.add(()->chooseFacet(false));
  if(canSearchTracks()){
   labels.add("Серия · "+seriesLabel());actions.add(this::chooseSearchSeries);
  }
  if(canSearchTracks()||screen.equals("tracks")){
   labels.add("Происхождение · "+originFilter.choice);actions.add(this::chooseTrackOrigin);
  }
  labels.add(yearLabel());actions.add(()->chooseFacet(true));
  labels.add(filterLabel());actions.add(this::shortFilter);
  labels.add("Сбросить фильтры");actions.add(this::clearFilters);
  AlertDialog.Builder dialog=dialogBuilder().setTitle("Фильтры библиотеки")
    .setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).setNegativeButton("Назад",null);
  if(canSearchTracks()){
   dialog.setPositiveButton("Найти треки",(d,w)->{explicitTrackSearch=true;refreshFilters();});
   if(explicitTrackSearch)dialog.setNeutralButton("К играм",(d,w)->{
    explicitTrackSearch=false;
    seriesFilter="";
    originFilter=HandheldTrackSearch.Origin.ALL;
    library.preferences.edit().remove("search_series").remove("search_origin").apply();
    refreshFilters();
   });
  }
  dialog.show();
 }
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
    scope=library.seriesGames(selectedSeries,games);
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
 void reset(){explicitTrackSearch=false;trackResultLimit=60;letter="";search.setText("");alphabet();render();catalogScroll.scrollTo(0,0);}
 String initial(String title){if(title==null||title.isEmpty())return "#";int c=title.toUpperCase(Locale.ROOT).codePointAt(0);return Character.isDigit(c)?"0–9":Character.isLetter(c)?new String(Character.toChars(c)):"#";}
 List<JSONObject> items(){List<JSONObject> list=new ArrayList<>();if(screen.equals("series")){if(series!=null)for(int i=0;i<series.length();i++){JSONObject row=series.optJSONObject(i);if(seriesCount(row)>0)list.add(row);}}else if(screen.equals("tracks")){if(manifest!=null){JSONArray a=manifest.optJSONArray("tracks");for(int i=0;i<a.length();i++)list.add(a.optJSONObject(i));}}else for(JSONObject g:scope)if(matchesGame(g))list.add(g);if(!screen.equals("tracks")&&selectedSeries==null)list.sort((a,b)->a.optString("title").compareToIgnoreCase(b.optString("title")));return list;}
 void alphabet(){if(letters==null)return;letters.removeAllViews();View alphabet=(View)letters.getParent();alphabet.setVisibility(trackResultsOnly()?View.GONE:View.VISIBLE);if(trackResultsOnly())return;Set<String> starts=new TreeSet<>();for(JSONObject r:items())starts.add(initial(r.optString("title")));List<String> labels=new ArrayList<>();labels.add("Все");labels.addAll(starts);for(String l:labels){TextView v=text(l,12,l.equals(letter)||l.equals("Все")&&letter.isEmpty()?accent:muted);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(48));v.setOnClickListener(w->{letter=l.equals("Все")?"":l;render();alphabet();});letters.addView(v,new LinearLayout.LayoutParams(-2,-1));}}
 void render(){if(rows==null)return;updateNavigation();artVersion++;rows.removeAllViews();String q=search==null?"":search.getText().toString().toLowerCase(Locale.ROOT);boolean tracks=screen.equals("tracks"),tracksOnly=trackResultsOnly();
  albumSeek.setVisibility(tracks?View.VISIBLE:View.GONE);
  label(heading,tracksOnly?"Поиск треков":screen.equals("series")?"Серии":tracks?album.optString("title"):selectedSeries!=null?selectedSeries.optString("title"):local?"Скачано":"Каталог");
  if(selectedSeries!=null&&!screen.equals("series")&&!screen.equals("tracks")){LinearLayout seriesHeader=new LinearLayout(this);seriesHeader.setGravity(Gravity.CENTER_VERTICAL);ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.FIT_CENTER);art.setContentDescription("Открыть постер серии");seriesHeader.addView(art,new LinearLayout.LayoutParams(dp(58),dp(76)));thumbnail(art,selectedSeries,true);art.setOnClickListener(v->collectionPoster(selectedSeries,true));Button playSeriesButton=button("Играть серию",()->playSeries(selectedSeries));seriesHeader.addView(playSeriesButton,new LinearLayout.LayoutParams(0,dp(48),1));Button more=button("⋮",()->rowActions(selectedSeries,true));more.setContentDescription("Действия с серией");seriesHeader.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));rows.addView(seriesHeader);}

  boolean gameSearch=screen.equals("games");
  String raQuery=gameSearch?MusicContract.raQuery(q):"";
  Set<String> matchingIds=new HashSet<>();
  JSONArray ids=raIndex==null||raQuery.isEmpty()?null:raIndex.optJSONArray(raQuery);
  if(ids!=null)for(int i=0;i<ids.length();i++)matchingIds.add(ids.optString(i));
  int count=0;int trackPosition=-1;
  for(JSONObject r:tracksOnly?Collections.<JSONObject>emptyList():items()){
   trackPosition++;
   String title=r.optString("title");
   boolean matches=matchingIds.contains(r.optString("id"))
           ||(!q.trim().startsWith("ra")||raQuery.isEmpty())
           &&(title.toLowerCase(Locale.ROOT).contains(q)||(gameSearch&&r.optString("id").equalsIgnoreCase(q)));
   if(!matches||!letter.isEmpty()&&!initial(title).equals(letter))continue;
   int index=screen.equals("tracks")?trackPosition:-1;if(tracks&&(library.hiddenShort(r)||!matchesGame(album)||originFilter!=HandheldTrackSearch.Origin.ALL&&HandheldTrackSearch.Origin.of(r)!=originFilter))continue;long measured=tracks?library.displayDuration(r):-1;String detail=screen.equals("tracks")?r.optString("format").toUpperCase(Locale.ROOT)+" · "+(measured>=0?stamp(measured):"--:--")+" · "+HandheldTrackSearch.Origin.of(r).label:screen.equals("series")?"Игр с музыкой: "+seriesCount(r):r.optString("platform_label")+(r.has("year")?" · "+r.optInt("year"):"")+" · "+r.optInt("track_count")+" треков";
   if(index>=0&&MusicContract.decoder(r).isEmpty())detail+=" · "+MusicContract.unsupported(r);
   if(!screen.equals("tracks"))detail+=" · "+(screen.equals("series")?seriesSavedLabel(r):downloadDescriptions.getOrDefault(r.optString("id"),""));final boolean seriesRow=screen.equals("series"),trackRow=screen.equals("tracks");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(6),dp(6),dp(6));row.setMinimumHeight(dp(54));row.setBackground(surface(bg));
 if(trackRow){TextView marker=text("",12,accent);marker.setTag("playing-track-marker");row.addView(marker,new LinearLayout.LayoutParams(dp(25),-2));}
 if(!trackRow){ImageView miniature=new ImageView(this);miniature.setScaleType(ImageView.ScaleType.FIT_CENTER);miniature.setBackground(surface(bg));miniature.setContentDescription("Увеличить постер: "+title);row.addView(miniature,new LinearLayout.LayoutParams(dp(34),dp(45)));miniature.setOnClickListener(v->collectionPoster(r,seriesRow));thumbnail(miniature,r,seriesRow);}
 LinearLayout copy=new LinearLayout(this);copy.setOrientation(1);TextView a=text((index>=0?String.format(Locale.ROOT,"%02d  ",index+1):"")+title,14,fg);copy.addView(a);copy.addView(text(detail,11,muted));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));if(!trackRow){Button more=button("⋮",()->rowActions(r,seriesRow));more.setContentDescription("Действия: "+title);row.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));}row.setTag(index>=0?trackKey(r):null);row.setContentDescription(title);if(trackRow)row.setOnLongClickListener(v->{trackInfo(r);return true;});row.setOnClickListener(v->{if(trackRow){if(MusicContract.decoder(r).isEmpty()){message(MusicContract.unsupported(r));return;}playAlbumTrack(albumQueue,index,local||!library.networkAllowed());}else if(seriesRow)openSeries(r);else open(r);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(5));rows.addView(row,lp);count++;
  }
  boolean searchingTracks=tracksOnly||canSearchTracks()&&q.trim().length()>=2;
  JSONArray index=searchingTracks?trackSearchIndex():null;
  if(index!=null)count+=renderTrackMatches(index,HandheldTrackSearch.normalize(q),matchingIds);
  updateDownloadButton();
  if(count==0)rows.addView(text(local&&searchingTracks&&index==null?"Читаю скачанные треклисты…"
    :tracksOnly&&!local&&(index==null||index.length()==0)?"Индекс треков недоступен"
    :local?"Нет скачанных коллекций или совпадений.":"Ничего не найдено",14,muted));
  PlaybackService service=PlaybackService.instance;
  highlightTrack(service==null?null:service.currentEntry());
 }
 private record OfflineSearchKey(java.io.File archive,List<String> manifests) {}
 private OfflineSearchKey offlineSearchKey;
 private JSONArray offlineTrackSearch;

 private JSONArray trackSearchIndex(){
  if(!local)return trackSearch;
  if(scope.isEmpty())return new JSONArray();
  List<JSONObject> saved=new ArrayList<>(scope);
  List<String> manifests=new ArrayList<>();
  for(JSONObject game:saved)manifests.add(game.optString("id")+" "+game.optString("manifest"));
  OfflineSearchKey key=new OfflineSearchKey(library.archive.active(),manifests);
  if(key.equals(offlineSearchKey))return offlineTrackSearch;
  offlineSearchKey=key;
  offlineTrackSearch=null;
  io.submit(()->{
   JSONArray found=new JSONArray();
   for(JSONObject game:saved){
    if(Thread.currentThread().isInterrupted())return;
    try{
     JSONObject data=library.localManifest(game);
     JSONArray tracks=data==null?null:data.optJSONArray("tracks");
     if(tracks==null)continue;
     boolean archived=library.archived(game);
     for(int index=0;index<tracks.length();index++){
      JSONObject track=tracks.optJSONObject(index);
      if(track==null||!archived&&!library.playableLocal(data,track))continue;
      long duration=library.knownDuration(track);
      JSONObject hit=new JSONObject()
        .put("game_id",game.optString("id")).put("_manifest",data.optString("_url")).put("index",index)
        .put("id",track.optString("id")).put("title",track.optString("title")).put("artist",track.optString("artist"))
        .put("source_kind",track.optString("source_kind")).put("authored",track.optBoolean("authored"))
        .put("duration_known",duration>=0).put("play_ms",duration);
      found.put(hit);
     }
    }catch(Exception ignored){}
   }
   runOnUiThread(()->{
    if(isDestroyed()||isFinishing()||!key.equals(offlineSearchKey))return;
    offlineTrackSearch=found;
    if(local&&canSearchTracks())render();
   });
  });
  return null;
 }

 int renderTrackMatches(JSONArray index,String query,Set<String> raMatches){
  Set<String> groupMembers=null;
  JSONObject group=filterSeries();
  if(!seriesFilter.isEmpty()){
   groupMembers=new HashSet<>();
   JSONArray members=group==null?null:group.optJSONArray("game_ids");
   if(members!=null)for(int i=0;i<members.length();i++)groupMembers.add(members.optString(i));
  }

  Map<String,JSONObject> candidates=new HashMap<>();
  Map<String,StringBuilder> descriptions=new HashMap<>();
  for(JSONObject game:scope){
   String id=game.optString("id");
   if(!matchesGame(game)||(groupMembers!=null&&!groupMembers.contains(id)))continue;
   candidates.put(id,game);
   descriptions.put(id,new StringBuilder(HandheldTrackSearch.normalize(game.optString("title")+" "
     +game.optString("platform_label")+" "+game.optString("platform"))));
  }
  if(series!=null)for(int i=0;i<series.length();i++){
   JSONObject seriesRow=series.optJSONObject(i);
   if(seriesRow==null)continue;
   JSONArray members=seriesRow.optJSONArray("game_ids");
   if(members==null)continue;
   String name=HandheldTrackSearch.normalize(seriesRow.optString("title"));
   for(int member=0;member<members.length();member++){
    StringBuilder description=descriptions.get(members.optString(member));
    if(description!=null)description.append(' ').append(name);
   }
  }

  boolean explicitRa=query.startsWith("ra")&&!MusicContract.raQuery(query).isEmpty();
  String[] words=query.isEmpty()?new String[0]:query.split("\\s+");
  int count=0;
  int minimum=library.shortThreshold();
  String unknown=library.preferences.getString("unknown_duration","show");
  for(int i=0;i<index.length();i++){
   JSONObject hit=index.optJSONObject(i);
   if(hit==null)continue;
   String id=hit.optString("game_id");
   JSONObject game=candidates.get(id);
   if(game==null)continue;
   HandheldTrackSearch.Origin source=HandheldTrackSearch.Origin.of(hit);
   if(originFilter!=HandheldTrackSearch.Origin.ALL&&source!=originFilter)continue;
   long duration=hit.optBoolean("duration_known")?hit.optLong("play_ms",-1):-1;
   if(!PlaybackRules.include(duration,minimum,unknown))continue;
   String title=hit.optString("title");
   if(!letter.isEmpty()&&!initial(title).equals(letter))continue;
   if(!raMatches.contains(id)){
    if(explicitRa)continue;
    String text=descriptions.get(id)+" "+HandheldTrackSearch.normalize(title+" "+hit.optString("artist"));
    if(!HandheldTrackSearch.matchesWords(text,words))continue;
   }
   if(count==trackResultLimit){
    rows.addView(button("Ещё треки",()->{trackResultLimit+=60;render();}));
    break;
   }
   addSetting(rows,title,game.optString("title")+" · "+game.optString("platform_label")+" · "+source.label,
     ()->open(game,hit.optString("id"),false,hit));
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
   if(index<0&&hit!=null&&!data.optString("_url").isEmpty()&&data.optString("_url").equals(hit.optString("_manifest"))){
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

 void setArt(String path){artPath=path;displayedArt=path;playerPoster.setVisibility(path.isEmpty()?View.GONE:View.VISIBLE);}
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
  try{scope=library.seriesGames(item,games);}catch(Exception ignored){scope=members;}screen="games";local=false;reset();
  if(switchQueue)playCollection(members,false,item,null);
 }
 void transport(Command action){playRequest++;command(service->{action.run(service);showTransport(service);});}
 interface Command{void run(PlaybackService s);}void command(Command c){if(clearingDownloads)return;PlaybackService s=PlaybackService.instance;if(s!=null){c.run(s);return;}try{startForegroundService(new Intent(this,PlaybackService.class));awaitService(c,0,playRequest);}catch(Exception e){message(e.getMessage());}}
 void awaitService(Command c,int tries,int intent){handler.postDelayed(()->{if(isFinishing()||clearingDownloads||intent!=playRequest)return;if(PlaybackService.instance!=null)c.run(PlaybackService.instance);else if(tries<20)awaitService(c,tries+1,intent);else message("Не удалось запустить проигрыватель");},100);}
 static String trackKey(JSONObject track){JSONObject asset=track==null?null:track.optJSONObject("asset");return asset==null?"":track.optString("id",asset.optString("sha256")+":"+track.optInt("subsong"));}
 static String stamp(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
 void showTransport(PlaybackService service){
  if(service!=null)service.handheldSpectrum=visibleSurface==Surface.PLAYER&&foreground;
  PlaybackIdentity.View now=service==null?null:service.identity.snapshot();
  PlaybackService.Entry entry=service==null?null:service.currentEntry();
  boolean radio=service!=null&&service.station!=null,generated=service!=null&&service.spaceMode!=null;
  boolean active=radio||generated||entry!=null;
  generatorOptions.setVisibility(generated?View.VISIBLE:View.GONE);
  voyagerOptions.setVisibility(generated&&service.spaceMode==SpaceTracker.Mode.VOYAGER?View.VISIBLE:View.GONE);
  boolean orbits=generated&&service.spaceMode==SpaceTracker.Mode.ORBITS;
  orbitInfo.setVisibility(orbits?View.VISIBLE:View.GONE);
  if(orbits&&visibleSurface==Surface.PLAYER)label(orbitInfo,service.orbitStatus());
  updateStreetInput(service);
  boolean running=active&&service.state.wantsPlayback();
  boolean seeking=active&&service.seekAvailable();
  seek.setVisibility(generated?View.GONE:View.VISIBLE);
  String title=active?now.title:"Выберите композицию";
  String metadata=active?now.game:"";
  if(entry!=null){
   String platform=entry.game.optString("platform_label",entry.game.optString("platform"));
   if(!platform.isEmpty())metadata+=(metadata.isEmpty()?"":" · ")+platform;
  }
  label(display,title);
  label(playerDetail,metadata);
  label(miniTitle,(running?"▶  ":active?"•  ":"")+title);
  String position=active?stamp(service.currentPosition()):"00:00";
  String length=active&&service.durationKnown?stamp(service.duration):"--:--";
  if(!dragging||service==null||service!=seekOwner||service.generation!=seekGeneration)label(time,radio?"ПРЯМОЙ ЭФИР":generated?position+" · "+service.spaceTempo()+" BPM":position+" / "+length);
  label(miniDetail,radio?"Прямой эфир":generated?"Генератор · "+service.spaceTempo()+" BPM":active?(now.game.isEmpty()?"":now.game+" · ")+position:"Открыть плеер  ⌃");
  describe(miniPlayer.getChildAt(0),"Открыть плеер · "+title);
  label(play,running?(radio?"Стоп":"Пауза"):"Играть");
  label(miniToggle,running?(radio?"■":"Ⅱ"):"▶");
  String toggle=running?(radio?"Остановить радио":"Пауза"):"Играть";
  describe(play,toggle);
  describe(miniToggle,toggle);
  enableAction(play,active);
  enableAction(miniToggle,active);
  enableAction(previousButton,entry!=null);
  boolean canNext=service!=null&&service.canNext();
  enableAction(nextButton,canNext);
  updateSeekButton(rewindAlbum,false,seeking,entry!=null);
  updateSeekButton(forwardAlbum,true,seeking,canNext);
  updateSeekButton(rewindPlayer,false,seeking,entry!=null);
  updateSeekButton(forwardPlayer,true,seeking,canNext);
  seek.setEnabled(seeking);
  if(!dragging)seek.setProgress(seeking?(int)(1000*service.currentPosition()/service.duration):0);
  String art=now==null?"":now.art;
  if(!art.equals(displayedArt))setArt(art);
  else playerPoster.setVisibility(art.isEmpty()?View.GONE:View.VISIBLE);
  String state=service==null?"Выберите трек":switch(service.state){
   case IDLE -> "Выберите трек";
   case LOADING -> radio?"Подключение к станции…":generated?"Запускаю генератор…":"Загрузка композиции…";
   case LOADING_PAUSED -> "Пауза · композиция ещё загружается";
   case PLAYING -> radio?(service.station.credit().isEmpty()?"▶  Радио играет":service.station.credit()):"▶  Играет";
   case PAUSED -> radio?"Радио остановлено":"Ⅱ  Пауза";
   case CONTINUING -> "Выбираю продолжение…";
   case ENDED -> service.error.isEmpty()?"Очередь завершена":service.error;
   case ERROR -> service.error;
  };
  label(playbackStatus,state);
  highlightTrack(entry);
  if(visibleSurface==Surface.PLAYER){
   updatePlayerQueue(service);
   spectrum.invalidate();
  }
 }

 private void updatePlayerQueue(PlaybackService service){
  List<PlaybackService.Entry> queue=service==null?Collections.emptyList():service.queue;
  int current=service==null?-1:service.position;
  if(current<0||current>=queue.size()){
   queueToggle.setVisibility(View.GONE);
   queueRows.setVisibility(View.GONE);
   return;
  }
  queueToggle.setVisibility(View.VISIBLE);
  String gameId=queue.get(current).game.optString("id");
  int first=current,last=current+1;
  while(first>0&&gameId.equals(queue.get(first-1).game.optString("id")))first--;
  while(last<queue.size()&&gameId.equals(queue.get(last).game.optString("id")))last++;
  label(queueToggle,"Треклист "+(current-first+1)+"/"+(last-first)+(queueExpanded?" −":" +"));
  queueRows.setVisibility(queueExpanded?View.VISIBLE:View.GONE);
  if(!queueExpanded||queue==shownQueue&&current==shownQueuePosition)return;
  boolean follow=shownQueuePosition>=0;
  shownQueue=queue;
  shownQueuePosition=current;
  queueRows.removeAllViews();
  View selected=null;
  for(int i=first;i<last;i++){
   PlaybackService.Entry item=queue.get(i);
   JSONObject track=item.manifest.optJSONArray("tracks").optJSONObject(item.index);
   if(track==null)continue;
   final int index=i;
   boolean playing=i==current;
   TextView row=text((playing?"▶  ":"    ")+track.optString("title"),14,playing?accent:fg);
   row.setMinHeight(dp(48));
   row.setGravity(Gravity.CENTER_VERTICAL);
   row.setBackground(surface(playing?panel:bg));
   row.setSelected(playing);
   row.setFocusable(true);
   row.setOnClickListener(v->{
    PlaybackService live=PlaybackService.instance;
    if(live==service&&live.queue==queue&&!clearingDownloads){
     playRequest++;
     live.selectQueued(index);
     showTransport(live);
    }
   });
   row.setOnLongClickListener(v->{trackInfo(track);return true;});
   queueRows.addView(row);
   if(playing)selected=row;
  }
  if(follow&&selected!=null)revealTrack(selected);
 }

 private void revealTrack(View row){
  row.post(()->{
   if(!row.isShown())return;
   row.requestRectangleOnScreen(new Rect(0,0,row.getWidth(),row.getHeight()),true);
  });
 }

 void highlightTrack(PlaybackService.Entry entry){
  if(!screen.equals("tracks")||album==null){highlightedTrack="";return;}
  boolean matches=entry!=null&&album.optString("id").equals(entry.game.optString("id"));
  String key=matches?trackKey(entry.manifest.optJSONArray("tracks").optJSONObject(entry.index)):"";
  boolean changed=!key.equals(highlightedTrack);
  highlightedTrack=key;
  for(int n=0;n<rows.getChildCount();n++){
   View row=rows.getChildAt(n);
   Object tag=row.getTag();
   if(tag==null)continue;
   boolean active=matches&&!key.isEmpty()&&tag.equals(key);
   if(row.isSelected()!=active){
    row.setBackground(surface(active?panel:bg));
    row.setSelected(active);
    TextView marker=row.findViewWithTag("playing-track-marker");
    if(marker!=null)label(marker,active?"▶":"");
   }
   if(active&&changed&&visibleSurface==Surface.CATALOG)revealTrack(row);
  }
 }
 final Runnable tick=new Runnable(){public void run(){
  if(downloadStarting&&DownloadService.accepted.get()>startingSequence)downloadStarting=false;
  boolean downloading=DownloadService.instance!=null;
  settingsProgress.setVisibility(downloading?View.VISIBLE:View.GONE);
  if(downloading){
   label(settingsProgress,DownloadService.progress);
   label(status,DownloadService.progress);
  }else if(LibraryMaintenanceJob.active!=null)label(status,LibraryMaintenanceJob.status);
  else if(MusicSyncJob.active!=null)label(status,MusicSyncJob.status);

  PlaybackService active=PlaybackService.instance;
  showTransport(active);
  ensureAcousticAvailability();
  describe(queueButton,"Режим воспроизведения. "+(active!=null&&!active.nextGame.isEmpty()?"Далее: "+active.nextGame
    :"После очереди: "+Continuation.label(library.preferences.getString("after_album","stop"))));
  long syncSuccess=library.preferences.getLong("sync_success",0);
  if(syncSuccess!=seenSyncSuccess){seenSyncSuccess=syncSuccess;reloadCachedCatalog();}
  if(seenRevision!=Library.revision.get()){
   seenRevision=Library.revision.get();
   refreshArchiveView();
   refreshDownloadStates();
  }
  if(!clearingDownloads&&previousDownloadActive!=downloading){
   previousDownloadActive=downloading;
   if(!downloading){
    offlineSearchKey=null;
    if(local&&screen.equals("games")){scope=savedView();alphabet();render();}
    message(DownloadService.progress);
   }
  }
  updateLibraryActions();
  handler.postDelayed(this,400);
 }};
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
 void zoomPath(String path){if(path.isEmpty()){message("Постер пока не загружен");return;}Dialog d=new Dialog(this,android.R.style.Theme_Material_NoActionBar);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setBackgroundColor(bg);layout.addView(button("Закрыть",d::dismiss));PosterView image=new PosterView(this,Library.artwork(new java.io.File(path)));layout.addView(image,new LinearLayout.LayoutParams(-1,0,1));d.setContentView(layout);d.getWindow().setLayout(-1,-1);d.show();d.getWindow().setLayout(-1,-1);}
 public void onBackPressed(){
  if(visibleSurface==Surface.SETTINGS){showSurface(settingsReturn);return;}
  if(visibleSurface==Surface.PLAYER){showSurface(Surface.CATALOG);return;}
  requestId++;if(!backStack.isEmpty()){backStack.pop().restore();return;}if(local||screen.equals("series")){browse(false);return;}super.onBackPressed();}
 @Override protected void onStart(){super.onStart();foreground=true;showTransport(PlaybackService.instance);}
 @Override protected void onStop(){
  foreground=false;
  updateStreetInput(null);
  PlaybackService service=PlaybackService.instance;
  if(service!=null)service.handheldSpectrum=false;
  super.onStop();
 }
 public void onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();artworkIo.shutdownNow();super.onDestroy();}
}
