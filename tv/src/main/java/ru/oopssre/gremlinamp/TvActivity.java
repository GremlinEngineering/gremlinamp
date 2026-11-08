package ru.oopssre.gremlinamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class TvActivity extends Activity {
    private static final int NIGHT = 0xff0b101d, PANEL = 0xff172439, FOCUS = 0xff805522, AMBER = 0xffffbf69;
    private enum Page { RADIO, GAMES, SERIES, ALBUM }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<JSONObject> games, series;
    private Page albumParent=Page.GAMES;
    private String query = "";
    private Page page = Page.RADIO;
    private Library library;
    private TvUpdates updates;
    private FrameLayout root, stage;
    private LinearLayout menu;
    private ListView list;
    private TextView heading, status, transport, hint;
    private Button searchButton, playButton, nextButton, previousButton;
    private AlertDialog updateDialog;
    private TextView updateSummary;
    private boolean installAfterDownload;
    private String installNote = "";
    private FractalView fractal;
    private View lastFocus;
    private boolean running, visual, stageActive;
    private int request, command;
    private String message = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        library = new Library(this, true);
        updates = TvUpdates.get(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(NIGHT);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(48), dp(28), dp(48), dp(22));
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        TextView title = text("GREMLINAMP  /  TV", 28, AMBER);
        menu.addView(title);
        LinearLayout nav = new LinearLayout(this);
        add(nav, "Радио", this::showRadio);
        add(nav, "Игры", () -> showCatalog(Page.GAMES));
        add(nav, "Серии", () -> showCatalog(Page.SERIES));
        searchButton = add(nav, "Поиск", this::search);
        add(nav, "Демосцена", this::showFractal);
        add(nav, "Настройки", this::settingsDialog);
        menu.addView(nav);
        heading = text("", 19, Color.WHITE);
        status = text("", 15, 0xffb8c8df);
        menu.addView(heading);
        menu.addView(status);
        list = new ListView(this);
        list.setSelector(new ColorDrawable(FOCUS));
        list.setDividerHeight(1);
        list.setFocusable(true);
        menu.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        previousButton = add(controls, "◀◀", () -> withService(PlaybackService::previous, false));
        playButton = add(controls, "Играть / пауза", () -> withService(PlaybackService::toggle, false));
        nextButton = add(controls, "▶▶", () -> withService(PlaybackService::next, false));
        transport = text("", 17, Color.WHITE);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(transport, new LinearLayout.LayoutParams(0, -2, 1));
        menu.addView(controls);
        showRadio();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(8), dp(4), dp(8), dp(4));
        return view;
    }

    private Button add(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setFocusable(true);
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{FOCUS, PANEL}));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(50));
        params.setMarginEnd(dp(8));
        row.addView(button, params);
        return button;
    }

    private void rows(List<String> labels, Consumer<Integer> action) {
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convert, parent);
                row.setTextSize(20);
                row.setTextColor(Color.WHITE);
                row.setSingleLine();
                row.setMinHeight(dp(48));
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, index, id) -> action.accept(index));
        if (!labels.isEmpty()) {
            list.setSelection(0);
            list.requestFocus();
        }
    }

    private void showRadio() {
        request++;
        page = Page.RADIO;
        searchButton.setEnabled(true);
        heading.setText("Интернет-радио");
        setMessage("Выбери станцию и нажми ОК");
        List<RadioStations.Station> stations = RadioStations.available(this,
                library.preferences.getBoolean(RadioStations.PERSONAL_MODE, false));
        List<String> names = new ArrayList<>();
        for (RadioStations.Station station : stations) names.add(station.title());
        rows(names, i -> withService(service -> {
            service.playRadio(stations.get(i));
            showFractal();
        }, true));
    }

    private void showCatalog(Page target) {
        int token = ++request;
        page = target;
        searchButton.setEnabled(true);
        heading.setText(target == Page.SERIES ? "Серии игр" : "Музыка игр");
        if (games != null) {
            if (target == Page.SERIES) renderSeries(); else renderGames();
            return;
        }
        setMessage("Загружаю каталог…");
        rows(Collections.emptyList(), i -> {});
        io.execute(() -> {
            try {
                JSONObject catalog = library.catalog(false);
                List<JSONObject> found = sortedRows(catalog.getJSONArray("games"));
                List<JSONObject> groups = sortedRows(catalog.getJSONArray("series"));
                main.post(() -> {
                    if (isDestroyed()) return;
                    games = found;
                    series = groups;
                    if (token == request) {
                        if (target == Page.SERIES) renderSeries(); else renderGames();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (token != request || isDestroyed()) return;
                    setMessage("Каталог сейчас недоступен · " + e.getMessage());
                    rows(Collections.singletonList("Повторить"), i -> showCatalog(target));
                });
            }
        });
    }

    private List<JSONObject> sortedRows(JSONArray rows) throws Exception {
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) items.add(rows.getJSONObject(i));
        items.sort(Comparator.comparing(item -> item.optString("title"), String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private void renderSeries() {
        List<JSONObject> visible = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JSONObject item : series) {
            String title = item.optString("title");
            if (!title.toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(item);
            names.add(title + "   ·   " + item.optJSONArray("game_ids").length() + " игр");
        }
        setMessage(names.isEmpty() ? "Нет серий по этому запросу" : names.size() + " серий");
        rows(names, i -> {
            JSONObject group = visible.get(i);
            try {
                showCollection(library.seriesGames(group, games), group.optString("title"), group.optString("id"));
            } catch (Exception e) {
                setMessage("Серия недоступна");
            }
        });
    }

    private void renderGames() {
        List<JSONObject> visible = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JSONObject item : games) {
            String title = item.optString("title");
            if (!title.toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(item);
            names.add(title + "   ·   " + item.optString("platform_label", item.optString("platform")));
        }
        setMessage(names.isEmpty() ? "Ничего не найдено" : names.size() + " игр");
        rows(names, i -> showCollection(Collections.singletonList(visible.get(i)),visible.get(i).optString("title"),""));
    }

    private void search() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint("Название игры или серии");
        input.setText(query);
        new AlertDialog.Builder(this).setTitle("Поиск").setView(input)
                .setNegativeButton("Отмена", null).setPositiveButton("Найти", (d, w) -> {
                    query = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                    showCatalog(page == Page.SERIES ? Page.SERIES : Page.GAMES);
                }).show();
        input.requestFocus();
    }

    private void showCollection(List<JSONObject> collection, String name, String group) {
        int token = ++request;
        if (page != Page.ALBUM) albumParent = page;
        page = Page.ALBUM;
        heading.setText(name);
        setMessage("Загружаю список композиций…");
        rows(Collections.emptyList(), i -> {});
        io.execute(() -> {
            List<PlaybackService.Entry> entries = new ArrayList<>();
            List<String> names = new ArrayList<>();
            String error = "";
            for (JSONObject game : collection) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    JSONObject manifest = library.manifest(game, false);
                    JSONArray tracks = manifest.getJSONArray("tracks");
                    for (int i = 0; i < tracks.length(); i++) {
                        JSONObject track = tracks.getJSONObject(i);
                        if (MusicContract.streamingDecoder(track).isEmpty()) continue;
                        entries.add(new PlaybackService.Entry(game, manifest, i, group, group.isEmpty() ? "" : name));
                        names.add(group.isEmpty() ? track.optString("title")
                                : game.optString("title") + "   ·   " + track.optString("title"));
                    }
                } catch (Exception e) { error = e.getMessage(); }
            }
            String problem = error;
            main.post(() -> {
                if (token != request || isDestroyed()) return;
                if (entries.isEmpty() && !problem.isEmpty()) {
                    setMessage("Музыка недоступна · " + problem);
                    rows(Collections.singletonList("Повторить"), i -> showCollection(collection, name, group));
                } else {
                    setMessage(entries.isEmpty() ? "Нет поддерживаемых треков" : names.size() + " треков");
                    rows(names, i -> withService(service -> { service.play(entries, i); showFractal(); }, true));
                }
            });
        });
    }

    private void settingsDialog() {
        List<String> labels = new ArrayList<>();
        labels.add("Кэш");
        labels.add("О приложении");
        if (RadioStations.available(this, true).stream().anyMatch(RadioStations.Station::personal)) {
            labels.add("Личный режим");
        }
        new AlertDialog.Builder(this).setTitle("Настройки")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    switch (index) {
                        case 0 -> cacheDialog();
                        case 1 -> showUpdates();
                        case 2 -> personalMode();
                    }
                }).setNegativeButton("Закрыть", null).show();
    }

    private void showUpdates() {
        updateSummary = text("", 17, Color.WHITE);
        updateSummary.setPadding(dp(24), dp(12), dp(24), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("О приложении · GremlinAmp TV")
                .setView(updateSummary).setNegativeButton("Закрыть", null)
                .setNeutralButton("Проверить обновление", null).setPositiveButton("Обновить", null).create();
        updateDialog = dialog;
        installNote = "";
        dialog.setOnDismissListener(d -> {
            updateDialog = null;
            updateSummary = null;
            installAfterDownload = false;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            installNote = "";
            updates.check();
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            installNote = "";
            TvUpdates.State state = updates.state();
            if (state.stage() == TvUpdates.Stage.DOWNLOADING) {
                installAfterDownload = false;
                updates.cancel();
            } else if (state.ready()) {
                openInstaller();
            } else if (state.stage() == TvUpdates.Stage.IDLE && state.offer() != null) {
                installAfterDownload = true;
                updates.download();
            }
        });
        renderUpdates(updates.state());
    }

    private void renderUpdates(TvUpdates.State state) {
        if (updateDialog == null || !updateDialog.isShowing()) return;
        boolean idle = state.stage() == TvUpdates.Stage.IDLE;
        boolean downloading = state.stage() == TvUpdates.Stage.DOWNLOADING;
        boolean offer = state.offer() != null;
        String summary = "Установлена версия " + updates.installedVersion();
        if (offer) summary += "\nДоступна версия " + state.offer().version();
        if (!state.detail().isEmpty()) summary += "\n" + state.detail();
        else if (state.ready()) summary += "\nAPK скачан";
        if (!installNote.isEmpty()) summary += "\n" + installNote;
        updateSummary.setText(summary);
        Button action = updateDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button check = updateDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        boolean focus = action.hasFocus() || check.hasFocus();
        check.setEnabled(idle);
        action.setVisibility(offer ? View.VISIBLE : View.GONE);
        action.setEnabled(downloading || idle && offer);
        action.setText(downloading ? "Отменить" : state.ready() ? "Установить обновление" : "Обновить");
        if (focus && !action.isEnabled() && !check.isEnabled()) {
            updateDialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        }
        if (idle && installAfterDownload) {
            installAfterDownload = false;
            if (state.ready() && running) openInstaller();
        }
    }

    private void openInstaller() {
        if (!running || updateDialog == null || !updateDialog.isShowing()) return;
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                installNote = "Разреши установку для GremlinAmp TV в настройках Android.";
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", getPackageName(), null)));
            } else {
                Uri apk = updates.installerUri();
                if (apk == null) return;
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("GremlinAmp TV", apk));
                updates.installerActive(true);
                startActivity(intent);
            }
        } catch (RuntimeException error) {
            updates.installerActive(false);
            installNote = "Не удалось открыть системный установщик.";
            renderUpdates(updates.state());
        }
    }

    private void cacheDialog() {
        TextView summary = text("Проверяю кэш…", 17, Color.WHITE);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Кэш").setView(summary)
                .setNegativeButton("Закрыть", null).setPositiveButton("Очистить память", null)
                .setNeutralButton("Очистить диск", null).create();
        dialog.show();
        long[] disk = {-1};
        boolean[] diskBusy = {true};
        String[] note = {""};
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || isDestroyed()) return;
                PlaybackService service = PlaybackService.instance;
                StreamCache.Usage usage = service == null ? new StreamCache.Usage(0, 0, 0) : service.streamCache.usage();
                summary.setText(String.format(Locale.ROOT,
                        "Память · %.2f / 30 МБ · файлов %d\nИспользуется плеером · %.2f МБ\nДиск · %s%s",
                        usage.bytes()/1_000_000d, usage.entries(), usage.pinned()/1_000_000d,
                        diskBusy[0] ? "проверяю…" : disk[0]<0 ? "недоступен" : String.format(Locale.ROOT,"%.2f МБ",disk[0]/1_000_000d),
                        note[0].isEmpty() ? "" : "\n" + note[0]));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(usage.bytes() > 0);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!diskBusy[0] && disk[0] > 0);
                main.postDelayed(this, 500);
            }
        };
        Consumer<Boolean> inspectDisk = clear -> {
            diskBusy[0] = true;
            io.execute(() -> {
                long size;
                try {
                    if (clear) diskCache(true);
                    size = diskCache(false);
                } catch (IOException e) { size = -1; }
                long result = size;
                main.post(() -> {
                    disk[0] = result;
                    diskBusy[0] = false;
                    if (clear) note[0] = result<0 ? "Не удалось очистить диск" : "Дисковый кэш очищен";
                });
            });
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.instance;
            if (service != null) {
                boolean active = service.streamCache.usage().pinned() > 0;
                service.clearStreamCache();
                note[0] = active ? "Предзагрузка сброшена. Текущий трек останется до конца воспроизведения." : "Память очищена";
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> inspectDisk.accept(true));
        dialog.setOnDismissListener(d -> main.removeCallbacks(refresh));
        inspectDisk.accept(false);
        refresh.run();
    }

    private long diskCache(boolean clear) throws IOException {
        Path cache = getCacheDir().toPath();
        long[] bytes = {0};
        Files.walkFileTree(cache, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (clear) Files.deleteIfExists(path);
                else if (attrs.isRegularFile()) bytes[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                if (clear && !dir.equals(cache)) Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    private void personalMode() {
        if (library.preferences.getBoolean(RadioStations.PERSONAL_MODE, false)) {
            new AlertDialog.Builder(this).setTitle("Личный режим включён")
                    .setNegativeButton("Закрыть", null).setPositiveButton("Выключить", (d, w) -> {
                        library.preferences.edit().putBoolean(RadioStations.PERSONAL_MODE, false).apply();
                        withService(PlaybackService::lockPersonalRadio, false);
                        showRadio();
                    }).show();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Личный режим").setView(input)
                .setNegativeButton("Отмена", null).setPositiveButton("Включить", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!RadioStations.acceptsCode(input.getText().toString())) {
                input.setError("Неверный код");
                return;
            }
            library.preferences.edit().putBoolean(RadioStations.PERSONAL_MODE, true).apply();
            dialog.dismiss();
            showRadio();
        });
    }

    private void withService(Consumer<PlaybackService> action, boolean start) {
        int token = ++command;
        PlaybackService service = PlaybackService.instance;
        if (service != null) {
            if(start)shownError="";
            action.accept(service);
        } else if (start) {
            try {
                startForegroundService(new Intent(this, PlaybackService.class));
                awaitService(token, action, 20);
            } catch (RuntimeException e) {
                setMessage("Не удалось запустить плеер");
            }
        }
    }

    private void awaitService(int token, Consumer<PlaybackService> action, int retries) {
        main.postDelayed(() -> {
            if (!running || token != command || isFinishing()) return;
            PlaybackService service = PlaybackService.instance;
            if (service != null) action.accept(service);
            else if (retries > 0) awaitService(token, action, retries - 1);
            else setMessage("Плеер не запустился");
        }, 100);
    }

    private void setMessage(String value) {
        message = value;
        status.setText(value);
    }

    private void showFractal() {
        if (visual) return;
        lastFocus = getCurrentFocus();
        stage = new FrameLayout(this);
        try {
            fractal = new FractalView(this, () -> {
                if (visual) {
                    hideFractal();
                    setMessage("Фрактальный эффект недоступен на этом устройстве");
                }
            });
        } catch (IOException e) {
            setMessage("Не удалось открыть фрактальный эффект");
            return;
        }
        stage.addView(fractal, new FrameLayout.LayoutParams(-1, -1));
        hint = text("", 18, Color.WHITE);
        hint.setBackgroundColor(0xb0202030);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        overlay.bottomMargin = dp(42);
        stage.addView(hint, overlay);
        visual = true;
        menu.setVisibility(View.GONE);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        stage.setFocusableInTouchMode(true);
        stage.requestFocus();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        resumeStage();
        showHint();
    }

    private void showHint() {
        if (!visual) return;
        PlaybackService service = PlaybackService.instance;
        String title = service == null ? "Деморежим" : service.identity.snapshot().title;
        hint.setText(title + "  ·  " + fractal.sceneName() + "\nОК — пауза / игра   ← → — сцена   Назад — к списку");
        hint.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHint);
        main.postDelayed(hideHint, 4800);
    }

    private final Runnable hideHint = () -> { if (hint != null) hint.setVisibility(View.GONE); };

    private void resumeStage() {
        if (running && visual && !stageActive) {
            stageActive = true;
            fractal.onResume();
            main.post(frame);
        }
    }

    private void pauseStage() {
        if (stageActive) {
            stageActive = false;
            main.removeCallbacks(frame);
            fractal.onPause();
        }
    }

    private void hideFractal() {
        if (!visual) return;
        pauseStage();
        main.removeCallbacks(hideHint);
        root.removeView(stage);
        stage = null;
        fractal = null;
        visual = false;
        menu.setVisibility(View.VISIBLE);
        if (lastFocus != null) lastFocus.requestFocus();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!stageActive) return;
            PlaybackService service = PlaybackService.instance;
            boolean playing = service != null && service.state == TransportState.PLAYING;
            boolean demo = service == null || service.state == TransportState.IDLE;
            fractal.frame(playing || demo, playing ? service.audioEnergy.levels() : AudioEnergy.SILENCE);
            main.postDelayed(this, 33);
        }
    };

    private String shownError = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            PlaybackService service = PlaybackService.instance;
            previousButton.setEnabled(service != null && service.currentEntry() != null);
            nextButton.setEnabled(service != null && service.canNext());
            playButton.setEnabled(service != null && (service.station != null || service.currentEntry() != null));
            if (service == null) {
                transport.setText("");
                shownError = "";
            } else {
                String state = switch (service.state) {
                    case IDLE -> "";
                    case LOADING -> "Подключаюсь";
                    case LOADING_PAUSED, PAUSED -> "Пауза";
                    case PLAYING -> service.station == null ? "Играет" : "В эфире";
                    case CONTINUING -> "Следующий трек";
                    case ENDED -> "Очередь завершена";
                    case ERROR -> service.error;
                };
                transport.setText(service.identity.snapshot().title + "  ·  " + state);
                if (service.state == TransportState.ERROR && !service.error.equals(shownError)) {
                    shownError = service.error;
                    setMessage(service.error);
                    if (visual) hideFractal();
                } else if (service.state != TransportState.ERROR && !shownError.isEmpty()) {
                    if (message.equals(shownError)) setMessage("");
                    shownError = "";
                }
            }
            main.postDelayed(this, 350);
        }
    };

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean media = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        boolean sceneKey = visual && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER);
        if (!media && !sceneKey) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT -> { fractal.nextScene(-1); showHint(); }
                case KeyEvent.KEYCODE_DPAD_RIGHT -> { fractal.nextScene(1); showHint(); }
                case KeyEvent.KEYCODE_MEDIA_PLAY -> withService(PlaybackService::resume, false);
                case KeyEvent.KEYCODE_MEDIA_PAUSE -> withService(PlaybackService::pause, false);
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS -> withService(PlaybackService::previous, false);
                case KeyEvent.KEYCODE_MEDIA_NEXT -> withService(PlaybackService::next, false);
                default -> { withService(PlaybackService::toggle, false); if (visual) showHint(); }
            }
        }
        return true;
    }

    @Override public void onBackPressed() {
        if (visual) hideFractal();
        else if (page == Page.ALBUM) showCatalog(albumParent);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        updates.installerActive(false);
        installNote = "";
        updates.observe(this::renderUpdates);
        main.post(refresh);
        resumeStage();
    }

    @Override protected void onPause() {
        running = false;
        installAfterDownload = false;
        updates.observe(null);
        main.removeCallbacks(refresh);
        pauseStage();
        super.onPause();
    }

    @Override protected void onDestroy() {
        request++;
        command++;
        if (updateDialog != null) updateDialog.dismiss();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
