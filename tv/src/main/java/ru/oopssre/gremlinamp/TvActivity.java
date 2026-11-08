package ru.oopssre.gremlinamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class TvActivity extends Activity {
    private static final int NIGHT = 0xff141126, PANEL = 0xff261f3c, AMBER = 0xfffbbf55;
    private static final int INK = 0xfff3ecdb, MUTED = 0xffb9b1ce, ACCENT_INK = 0xff27122b;
    private static final int ROW_FOCUS = 0xff65442f;
    private static final String[] CONTINUATION = {"stop", "series", "library", "genre", "similar"};
    private enum Page { RADIO, GAMES, SERIES, ALBUM }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<JSONObject> games, series;
    private Page albumParent=Page.GAMES;
    private String query = "", seriesFilter = "", platformFilter = "";
    private final Map<String, List<JSONObject>> seriesByGame = new HashMap<>();
    private Page page = Page.RADIO;
    private Library library;
    private TvUpdates updates;
    private FrameLayout root, stage;
    private LinearLayout menu;
    private ListView list;
    private TextView heading, status, transport, hint;
    private Button searchButton, playButton, nextButton, previousButton, continuationButton;
    private int selectedScene;
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
        menu.setPadding(dp(48), dp(27), dp(48), dp(27));
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        TextView title = text("GremlinAmp TV", 30, AMBER);
        menu.addView(title);
        LinearLayout nav = new LinearLayout(this);
        add(nav, "Радио", this::showRadio);
        add(nav, "Игры", () -> showCatalog(Page.GAMES));
        add(nav, "Серии", () -> showCatalog(Page.SERIES));
        searchButton = add(nav, "Поиск", this::search);
        add(nav, "Визуализация", this::showFractal);
        add(nav, "Настройки", this::settingsDialog);
        menu.addView(nav);
        heading = text("", 24, INK);
        status = text("", 18, MUTED);
        LinearLayout content = new LinearLayout(this);
        menu.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout catalogue = new LinearLayout(this);
        catalogue.setOrientation(LinearLayout.VERTICAL);
        content.addView(catalogue, new LinearLayout.LayoutParams(0, -1, 1));
        catalogue.addView(heading);
        catalogue.addView(status);
        list = new ListView(this);
        list.setId(View.generateViewId());
        list.setSelector(surface(ROW_FOCUS));
        list.setDividerHeight(1);
        list.setFocusable(true);
        catalogue.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView playerScroll = new ScrollView(this);
        playerScroll.setFillViewport(true);
        playerScroll.setFocusable(false);
        LinearLayout.LayoutParams playerSize = new LinearLayout.LayoutParams(dp(250), -1);
        playerSize.setMarginStart(dp(20));
        content.addView(playerScroll, playerSize);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        playerScroll.addView(controls);
        controls.addView(text("Плеер", 22, AMBER));
        transport = text("Выберите станцию или композицию", 18, INK);
        transport.setMaxLines(3);
        transport.setEllipsize(TextUtils.TruncateAt.END);
        transport.setMinHeight(dp(58));
        controls.addView(transport);
        playButton = add(controls, "Играть", () -> withService(PlaybackService::toggle, false));
        nextButton = add(controls, "Следующий", () -> withService(PlaybackService::next, false));
        previousButton = add(controls, "Предыдущий", () -> withService(PlaybackService::previous, false));
        continuationButton = add(controls, "После очереди", this::chooseContinuation);
        updateContinuationLabel();
        for (Button button : new Button[]{playButton, nextButton, previousButton, continuationButton}) {
            button.setNextFocusLeftId(list.getId());
        }
        list.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_RIGHT || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            return (nextButton.isEnabled() ? nextButton : continuationButton).requestFocus();
        });
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

    private GradientDrawable surface(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(8));
        return shape;
    }

    private Button add(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(20);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setFocusable(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}},
                new int[]{0xff9289a8, ACCENT_INK, INK}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(AMBER));
        background.addState(new int[]{}, surface(PANEL));
        button.setBackground(background);
        button.setOnClickListener(v -> action.run());
        boolean column = row.getOrientation() == LinearLayout.VERTICAL;
        if (column) button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(column ? -1 : -2, dp(56));
        if (column) params.bottomMargin = dp(8);
        else params.setMarginEnd(dp(8));
        row.addView(button, params);
        return button;
    }

    private void rows(List<String> labels, Consumer<Integer> action) {
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convert, parent);
                row.setTextSize(20);
                row.setTextColor(INK);
                row.setSingleLine();
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setMinHeight(dp(56));
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
        rows(names, i -> withService(service -> service.playRadio(stations.get(i)), true));
    }

    private void showCatalog(Page target) { showCatalog(target, null); }

    private void showCatalog(Page target, Runnable loaded) {
        int token = ++request;
        page = target;
        searchButton.setEnabled(true);
        heading.setText(target == Page.SERIES ? "Серии игр" : "Музыка игр");
        if (games != null) {
            if (target == Page.SERIES) renderSeries(); else renderGames();
            if (loaded != null) loaded.run();
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
                    indexSeries();
                    if (token == request) {
                        if (target == Page.SERIES) renderSeries(); else renderGames();
                        if (loaded != null && running) loaded.run();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (token != request || isDestroyed()) return;
                    setMessage("Каталог сейчас недоступен · " + e.getMessage());
                    rows(Collections.singletonList("Повторить"), i -> showCatalog(target, loaded));
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
            visible.add(item);
            names.add(title + "   ·   " + item.optJSONArray("game_ids").length() + " игр");
        }
        setMessage(names.isEmpty() ? "В каталоге пока нет серий" : names.size() + " серий");
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
            if (!matchesGame(item)) continue;
            visible.add(item);
            String details = platformLabel(item);
            if (item.optInt("year") > 0) details += " · " + item.optInt("year");
            if (item.optInt("track_count") > 0) details += " · " + item.optInt("track_count") + " тр.";
            names.add(details.trim().isEmpty() ? title : title + "   ·   " + details);
        }
        String filters = selectedLabel(seriesFilter, series, "id", "title");
        if (!platformFilter.isEmpty()) {
            String platform = platformNames().getOrDefault(platformFilter, platformFilter);
            filters = filters.isEmpty() ? platform : filters + " · " + platform;
        }
        setMessage((names.isEmpty() ? "Ничего не найдено" : names.size() + " игр")
                + (filters.isEmpty() ? "" : " · " + filters));
        rows(names, i -> showCollection(Collections.singletonList(visible.get(i)),visible.get(i).optString("title"),""));
    }

    private void indexSeries() {
        seriesByGame.clear();
        for (JSONObject group : series) {
            JSONArray ids = group.optJSONArray("game_ids");
            if (ids == null) continue;
            for (int i = 0; i < ids.length(); i++) {
                seriesByGame.computeIfAbsent(ids.optString(i), key -> new ArrayList<>()).add(group);
            }
        }
    }

    private static String platformLabel(JSONObject game) {
        String code = game.optString("platform");
        String label = game.optString("platform_label");
        if (!label.isEmpty() && !label.equals(code)) return label;
        return switch (code) {
            case "arcade" -> "Аркадные автоматы";
            case "3do" -> "3DO";
            case "nes" -> "NES / Famicom";
            case "snes" -> "Super Nintendo";
            case "famicom_disk_system" -> "Famicom Disk System";
            case "game_boy" -> "Game Boy";
            case "game_boy_color" -> "Game Boy Color";
            case "game_boy_advance" -> "Game Boy Advance";
            case "mega_drive" -> "Mega Drive / Genesis";
            case "master_system" -> "Master System";
            case "game_gear" -> "Game Gear";
            case "sg_1000" -> "SG-1000";
            case "sega_cd" -> "Sega CD";
            case "sega_32x" -> "Sega 32X";
            case "playstation" -> "PlayStation";
            case "playstation2", "playstation_2" -> "PlayStation 2";
            case "pc_8000_8800" -> "PC-8000 / PC-8800";
            case "pc_engine" -> "PC Engine";
            case "pc_engine_cd" -> "PC Engine CD";
            case "neo_geo_cd" -> "Neo Geo CD";
            case "neo_geo_pocket" -> "Neo Geo Pocket";
            case "colecovision" -> "ColecoVision";
            case "intellivision" -> "Intellivision";
            case "supervision" -> "Supervision";
            case "vectrex" -> "Vectrex";
            case "wonderswan" -> "WonderSwan";
            default -> code.startsWith("atari_") ? "Atari " + code.substring(6).replace('_', ' ')
                    : code.replace('_', ' ');
        };
    }

    private Map<String, String> platformNames() {
        Map<String, String> platforms = new TreeMap<>();
        for (JSONObject game : games) {
            String platform = game.optString("platform");
            if (!platform.trim().isEmpty()) platforms.putIfAbsent(platform, platformLabel(game));
        }
        return platforms;
    }

    private boolean matchesGame(JSONObject game) {
        if (!platformFilter.isEmpty() && !platformFilter.equals(game.optString("platform"))) return false;
        List<JSONObject> groups = seriesByGame.getOrDefault(game.optString("id"), Collections.emptyList());
        if (!seriesFilter.isEmpty() && groups.stream().noneMatch(g -> seriesFilter.equals(g.optString("id")))) return false;
        if (query.isEmpty()) return true;
        JSONArray ids = game.optJSONArray("ra_ids");
        if (ids != null) for (int i = 0; i < ids.length(); i++) {
            if (query.equals(ids.optString(i))) return true;
        }
        return contains(game.optString("title"), query)
                || contains(platformLabel(game), query) || contains(game.optString("platform"), query)
                || groups.stream().anyMatch(g -> contains(g.optString("title"), query));
    }

    private static boolean contains(String value, String query) {
        return value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String selectedLabel(String id, List<JSONObject> items, String key, String label) {
        for (JSONObject item : items) if (id.equals(item.optString(key))) return item.optString(label);
        return "";
    }

    private void search() {
        if (games == null) {
            showCatalog(Page.GAMES, this::search);
            return;
        }
        Map<String, String> platforms = platformNames();
        List<String> platformIds = new ArrayList<>(platforms.keySet());
        platformIds.sort(Comparator.comparing(platforms::get, String.CASE_INSENSITIVE_ORDER));
        platformIds.add(0, "");
        List<String> seriesNames = new ArrayList<>();
        seriesNames.add("Все серии");
        for (JSONObject group : series) seriesNames.add(group.optString("title"));

        String[] draft = {seriesFilter, platformFilter};
        LinearLayout body = panelBody();
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setTextSize(20);
        input.setHint("Игра, серия, платформа или RA ID");
        input.setText(query);
        body.addView(input);
        Button[] facets = new Button[2];
        Runnable labels = () -> {
            String group = selectedLabel(draft[0], series, "id", "title");
            facets[0].setText("Серия · " + (group.isEmpty() ? "Все серии" : group));
            facets[1].setText("Платформа · " + platforms.getOrDefault(draft[1], "Все платформы"));
        };
        facets[0] = add(body, "", () -> choices("Серия", seriesNames, i -> {
            draft[0] = i == 0 ? "" : series.get(i - 1).optString("id");
            labels.run();
        }));
        List<String> platformLabels = new ArrayList<>();
        for (String id : platformIds) platformLabels.add(platforms.getOrDefault(id, "Все платформы"));
        facets[1] = add(body, "", () -> choices("Платформа", platformLabels, i -> {
            draft[1] = platformIds.get(i);
            labels.run();
        }));
        labels.run();

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Поиск музыки").setView(body)
                .setNegativeButton("Назад", null)
                .setNeutralButton("Сбросить", (d, w) -> applySearch("", "", ""))
                .setPositiveButton("Показать игры", (d, w) ->
                        applySearch(input.getText().toString(), draft[0], draft[1])).create();
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        facets[0].requestFocus();
    }

    private void applySearch(String words, String group, String platform) {
        query = words.trim().toLowerCase(Locale.ROOT);
        seriesFilter = group;
        platformFilter = platform;
        showCatalog(Page.GAMES);
    }

    private void choices(String title, List<String> labels, Consumer<Integer> select) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View view, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, view, parent);
                row.setMinHeight(dp(56));
                row.setTextSize(20);
                row.setTextColor(INK);
                return row;
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
                .setAdapter(adapter, (d, index) -> select.accept(index)).setNegativeButton("Назад", null).show();
        dialog.getListView().setSelector(surface(ROW_FOCUS));
        dialog.getListView().requestFocus();
    }

    private void updateContinuationLabel() {
        continuationButton.setText("После очереди");
        continuationButton.setContentDescription("После очереди · "
                + Continuation.label(library.preferences.getString("after_album", "stop")));
    }

    private void chooseContinuation() {
        String current = library.preferences.getString("after_album", "stop");
        List<String> labels = new ArrayList<>();
        for (String mode : CONTINUATION) {
            labels.add((mode.equals(current) ? "✓  " : "") + switch (mode) {
                case "series" -> "Следующая игра из той же серии";
                case "library" -> "Продолжить по всей библиотеке";
                case "genre" -> "Продолжить по жанру";
                case "similar" -> "Поставить похожую игру";
                default -> "Остановиться";
            });
        }
        choices("После последнего трека", labels, i -> {
            library.preferences.edit().putString("after_album", CONTINUATION[i]).apply();
            updateContinuationLabel();
            withService(PlaybackService::refreshContinuation, false);
        });
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
                    rows(names, i -> withService(service -> service.play(entries, i), true));
                }
            });
        });
    }

    private LinearLayout panelBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(12), dp(24), dp(8));
        return body;
    }

    private void settingsDialog() {
        List<String> labels = new ArrayList<>(java.util.Arrays.asList("После очереди", "Кэш", "О приложении"));
        if (RadioStations.available(this, true).stream().anyMatch(RadioStations.Station::personal)) {
            labels.add("Личный режим");
        }
        choices("Настройки", labels, index -> {
            switch (index) {
                case 0 -> chooseContinuation();
                case 1 -> cacheDialog();
                case 2 -> showAbout();
                case 3 -> personalMode();
            }
        });
    }

    private void showAbout() {
        LinearLayout body = panelBody();
        body.addView(text("GremlinAmp TV", 26, INK));
        body.addView(text("Версия " + updates.installedVersion() + " · сборка "
                + updates.installedBuild() + "\nДмитрий Малыхин aka Timon_Crazy", 18, MUTED));
        Button site = add(body, "Сайт проекта", () -> projectLink(false));
        add(body, "Исходный код", () -> projectLink(true));
        add(body, "Обновление приложения", this::showUpdates);
        add(body, "Музыка и авторы", () -> new AlertDialog.Builder(this)
                .setTitle("Музыка и авторы")
                .setMessage("Музыка и авторство отдельных композиций указаны в медиатеке Аркадного зала. "
                        + "Tennis for Two — запись демо из проекта «Лаборатория Школы Империи».")
                .setPositiveButton("Закрыть", null).show());
        showSidePanel("О приложении", body);
        site.requestFocus();
    }

    private void projectLink(boolean source) {
        String title = source ? "Исходный код" : "Сайт проекта";
        String url = source ? "https://github.com/GremlinEngineering/gremlinamp"
                : "https://games.oopssre.ru/gremlin.html";
        LinearLayout body = panelBody();
        body.addView(text("Откройте ссылку на телефоне или компьютере", 18, INK));
        ImageView qr = new ImageView(this);
        qr.setImageResource(source ? R.drawable.tv_source_qr : R.drawable.tv_site_qr);
        qr.setContentDescription("QR-код · " + title);
        LinearLayout.LayoutParams qrSize = new LinearLayout.LayoutParams(dp(180), dp(180));
        qrSize.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(qr, qrSize);
        body.addView(text(url, 17, MUTED));
        add(body, "Открыть на телевизоре", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (RuntimeException e) {
                android.widget.Toast.makeText(this, "Откройте QR-код на телефоне", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        showSidePanel(title, body);
    }

    private void showSidePanel(String title, LinearLayout body) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        body.addView(text(title, 28, AMBER), 0);
        body.setPadding(dp(24), dp(27), dp(48), dp(27));
        add(body, "Назад", dialog::dismiss);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(NIGHT);
        scroll.addView(body);
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        window.setLayout(dp(410), ViewGroup.LayoutParams.MATCH_PARENT);
        window.setGravity(Gravity.END | Gravity.TOP);
        window.setDimAmount(.32f);
    }

    private void showUpdates() {
        updateSummary = text("", 20, INK);
        updateSummary.setPadding(dp(24), dp(12), dp(24), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Обновление приложения")
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
        if (!running) return;
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
                    setMessage("Визуализация недоступна на этом устройстве");
                }
            });
        } catch (IOException e) {
            setMessage("Не удалось открыть визуализацию");
            return;
        }
        stage.addView(fractal, new FrameLayout.LayoutParams(-1, -1));
        fractal.selectScene(selectedScene);
        hint = text("", 19, INK);
        hint.setPadding(dp(16), dp(8), dp(16), dp(8));
        hint.setBackgroundColor(0xe6141126);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        overlay.setMargins(dp(48), 0, dp(48), dp(27));
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
        boolean hasMusic = service != null && (service.station != null || service.currentEntry() != null);
        boolean next = hasMusic && service.canNext();
        String guide = "← → Смена сцены   ·   ↑ Список сцен   ·   Назад к списку";
        if (hasMusic) guide += service.state.wantsPlayback() ? "   ·   ОК Пауза" : "   ·   ОК Играть";
        else guide = "← → Смена сцены   ·   ОК Список сцен   ·   Назад к списку";
        if (next) guide += "   ·   ↓ Следующий трек";
        String text = fractal.sceneName() + "   ·   " + fractal.sceneDescription() + "\n" + guide;
        if (!TextUtils.equals(hint.getText(), text)) hint.setText(text);
    }

    private void selectScene(int scene) {
        selectedScene = Math.floorMod(scene, FractalView.SCENES.length);
        fractal.selectScene(selectedScene);
        showHint();
    }

    private void chooseScene() {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < FractalView.SCENES.length; i++) {
            labels.add((i == selectedScene ? "✓  " : "") + FractalView.SCENES[i]);
        }
        choices("Сцены визуализации", labels, this::selectScene);
    }

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
            boolean demo = service == null || service.station == null && service.currentEntry() == null
                    || service.state == TransportState.ENDED;
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
            String playLabel = service != null && service.state.wantsPlayback() ? "Пауза" : "Играть";
            if (!TextUtils.equals(playButton.getText(), playLabel)) playButton.setText(playLabel);
            if (service == null) {
                transport.setText("Выберите станцию или композицию");
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
            showHint();
            main.postDelayed(this, 350);
        }
    };

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean media = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        boolean sceneKey = visual && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER);
        if (!media && !sceneKey) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT -> selectScene(selectedScene - 1);
                case KeyEvent.KEYCODE_DPAD_RIGHT -> selectScene(selectedScene + 1);
                case KeyEvent.KEYCODE_DPAD_UP -> chooseScene();
                case KeyEvent.KEYCODE_DPAD_DOWN -> withService(service -> {
                    if (service.canNext()) service.next();
                }, false);
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    PlaybackService service = PlaybackService.instance;
                    if (service != null && (service.station != null || service.currentEntry() != null)) service.toggle();
                    else chooseScene();
                    showHint();
                }
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

    @Override protected void onStop() {
        command++;
        stopService(new Intent(this, PlaybackService.class));
        super.onStop();
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
