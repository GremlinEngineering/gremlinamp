package ru.oopssre.gremlinamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;

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
    private static final String APPEARANCE = "tv_appearance";
    private record Palette(int background, int panel, int ink, int muted, int accent,
                           int focusInk, int outline, int selected) {}
    private static final Palette DARK = new Palette(0xff0b1411, 0xff14231c, 0xffedf7ef, 0xffa8c0b3,
            0xff8ee8b9, 0xff071910, 0xff426654, 0xff234d39);
    private static final Palette LIGHT = new Palette(0xfffbf8ec, 0xfff2ead9, 0xff272635, 0xff56536b,
            0xff8c2758, 0xffffffff, 0xffaa9b84, 0xffeed9e3);
    private Palette palette;
    private static final long HINT_TIMEOUT_MS = 4_000;
    private static final String[] CONTINUATION = {"stop", "series", "library", "genre", "similar", "rhythm", "sound"};
    private enum Page { RADIO, GAMES, SERIES, SEARCH, ALBUM }
    private record SearchHit(JSONObject game, JSONObject track, TrackOrigin origin) {}
    private record ListRow(String label, String platform, String gameId) {
        ListRow(String label) { this(label, "", ""); }
        @Override public String toString() { return label; }
    }
    private record ListCursor(int request, ListRow row, int position, int top) {}
    private enum TrackOrigin {
        ALL("Все треки", ""), ORIGINAL("Оригинальные", "Оригинал"), AUTHORED("Авторские", "Авторская тема"),
        ARRANGEMENT("Аранжировки", "Аранжировка"), RECONSTRUCTION("Реконструкции", "Реконструкция"),
        UNKNOWN("Без отметки", "Источник не указан");

        final String filter, label;
        TrackOrigin(String filter, String label) { this.filter = filter; this.label = label; }

        static TrackOrigin of(JSONObject track) {
            String kind = track.optString("source_kind");
            if (kind.startsWith("original_")) return ORIGINAL;
            if (kind.equals("chiptune_arrangement")) return ARRANGEMENT;
            if (kind.equals("reconstructed_native")) return RECONSTRUCTION;
            if (kind.equals("authored_inspired") || track.optBoolean("authored")) return AUTHORED;
            return UNKNOWN;
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService indexIo = Executors.newSingleThreadExecutor();
    private List<JSONObject> games, series;
    private Page albumParent=Page.GAMES;
    private String query = "", seriesFilter = "", platformFilter = "";
    private TrackOrigin originFilter = TrackOrigin.ALL;
    private JSONArray searchTracks;
    private final Map<String, List<JSONObject>> seriesByGame = new HashMap<>();
    private Page page = Page.RADIO;
    private Library library;
    private TvUpdates updates;
    private FrameLayout root, stage;
    private LinearLayout menu;
    private ListView list;
    private List<PlaybackService.Entry> displayedTracks = Collections.emptyList();
    private List<ListRow> displayedRows = Collections.emptyList();
    private List<RadioStations.Station> displayedStations = Collections.emptyList();
    private List<PlaybackService.Entry> displayedQueue;
    private PlaybackService.Entry displayedAlbum;
    private PlaybackService.Entry markedEntry;
    private RadioStations.Station markedStation;
    private int markedTrack = -1;
    private TransportState markedState = TransportState.IDLE;
    private TextView heading, status, transport, hint;
    private TextView playingState, playingContext, playingPlatform, listColumns, listNumbers, listPlatform;
    private TextView trackTime, trackDuration, listKeys, listPosition;
    private SpectrumView spectrumView;
    private SeekBar seek;
    private Button searchButton, playButton, nextButton, previousButton, continuationButton;
    private Button radioButton, gamesButton, seriesButton, sceneButton;
    private LinearLayout trackControls;
    private int selectedScene;
    private AlertDialog updateDialog;
    private TextView updateSummary, updateProgressText, updatePercent;
    private LinearLayout updateTransfer;
    private SegmentScale updateScale;
    private boolean installAfterDownload;
    private String installNote = "";
    private FractalView fractal;
    private View lastFocus;
    private ListCursor sceneCursor;
    private boolean running, visual, stageActive;
    private int request, command;
    private String message = "";
    private Runnable reopenAlbum;

    @Override public void onCreate(Bundle saved) {
        applyPalette("light".equals(getSharedPreferences("player", MODE_PRIVATE).getString(APPEARANCE, "dark")));
        super.onCreate(saved);
        library = new Library(this, true);
        updates = TvUpdates.get(this);
        buildScreen();
        showRadio();
    }

    private void applyPalette(boolean light) {
        palette = light ? LIGHT : DARK;
        setTheme(light ? R.style.GremlinAmpTvLightTheme : R.style.GremlinAmpTvTheme);
    }

    private void buildScreen() {
        sceneCursor = null;
        root = new FrameLayout(this);
        root.setBackgroundColor(palette.background);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(32), dp(6), dp(32), dp(6));
        TextView title = terminalText("GREMLINAMP TV", 16, palette.accent);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        nav.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        radioButton = navigationTab(nav, "Радио", this::showRadio);
        gamesButton = navigationTab(nav, "Игры", () -> showCatalog(Page.GAMES));
        seriesButton = navigationTab(nav, "Серии", () -> showCatalog(Page.SERIES));
        searchButton = navigationTab(nav, "Поиск", this::search);
        terminalButton(nav, "Визуализация", () -> showFractal(false), 15);
        terminalButton(nav, "Настройки", this::settingsDialog, 15);
        menu.addView(nav);
        addRule(menu);

        LinearLayout content = new LinearLayout(this);
        menu.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout catalogue = new LinearLayout(this);
        catalogue.setOrientation(LinearLayout.VERTICAL);
        catalogue.setPadding(dp(32), dp(18), dp(22), dp(4));
        content.addView(catalogue, new LinearLayout.LayoutParams(0, -1, 5));
        heading = terminalText("", 28, palette.ink);
        heading.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        catalogue.addView(heading);
        status = terminalText("", 14, palette.muted);
        catalogue.addView(status);

        LinearLayout columns = new LinearLayout(this);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        columns.setPadding(dp(8), 0, dp(8), 0);
        columns.addView(new View(this), new LinearLayout.LayoutParams(dp(32), 1));
        listNumbers = terminalText("№", 13, palette.muted);
        columns.addView(listNumbers, new LinearLayout.LayoutParams(dp(36), -2));
        listColumns = terminalText("", 13, palette.muted);
        columns.addView(listColumns, new LinearLayout.LayoutParams(0, -2, 1));
        listPlatform = terminalText("ПЛАТФОРМА", 13, palette.muted);
        columns.addView(listPlatform, new LinearLayout.LayoutParams(dp(146), -2));
        catalogue.addView(columns);
        addRule(catalogue);

        list = new ListView(this);
        list.setId(View.generateViewId());
        list.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(0);
        list.setFocusable(true);
        catalogue.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        View divider = new View(this);
        divider.setBackgroundColor(palette.outline);
        content.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
        buildPlayer(content);

        addRule(menu);
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(32), dp(4), dp(32), dp(4));
        listKeys = terminalText("", 13, palette.muted);
        footer.addView(listKeys, new LinearLayout.LayoutParams(0, -2, 1));
        listPosition = terminalText("", 13, palette.muted);
        footer.addView(listPosition);
        menu.addView(footer);

        list.setNextFocusRightId(sceneButton.getId());
        list.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_RIGHT || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            sceneCursor = rememberCursor();
            sceneButton.requestFocus();
            return true;
        });
        list.setOnFocusChangeListener((v, focused) -> {
            if (focused) sceneCursor = null;
            renderVisibleRows();
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                renderVisibleRows();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                renderVisibleRows();
            }
        });
    }

    private void buildPlayer(LinearLayout content) {
        ScrollView playerScroll = new ScrollView(this);
        playerScroll.setFillViewport(true);
        playerScroll.setFocusable(false);
        playerScroll.setBackgroundColor(palette.panel);
        content.addView(playerScroll, new LinearLayout.LayoutParams(0, -1, 3));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(12), dp(26), dp(8));
        playerScroll.addView(controls);

        LinearLayout playerHeader = new LinearLayout(this);
        playerHeader.setGravity(Gravity.CENTER_VERTICAL);
        playingState = terminalText("ПЛЕЕР", 14, palette.accent);
        playingState.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        playingState.setMaxLines(2);
        playingState.setEllipsize(TextUtils.TruncateAt.END);
        playerHeader.addView(playingState, new LinearLayout.LayoutParams(0, -2, 1));
        sceneButton = terminalButton(playerHeader, "[Визуализация]", () -> showFractal(true), 14);
        sceneButton.setContentDescription("Визуализация");
        sceneButton.setOnFocusChangeListener((view, focused) -> {
            if (!focused && !visual) sceneCursor = null;
        });
        controls.addView(playerHeader);
        addRule(controls);

        LinearLayout current = new LinearLayout(this);
        current.setOrientation(LinearLayout.VERTICAL);
        current.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(current, new LinearLayout.LayoutParams(-1, 0, 1));
        transport = terminalText("Выберите станцию", 32, palette.ink);
        transport.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        transport.setMaxLines(2);
        transport.setEllipsize(TextUtils.TruncateAt.END);
        current.addView(transport);
        playingContext = terminalText("", 17, palette.ink);
        playingContext.setMaxLines(2);
        playingContext.setEllipsize(TextUtils.TruncateAt.END);
        current.addView(playingContext);
        playingPlatform = terminalText("", 14, palette.muted);
        current.addView(playingPlatform);

        spectrumView = new SpectrumView();
        spectrumView.setVisibility(View.GONE);
        LinearLayout.LayoutParams spectrumSize = new LinearLayout.LayoutParams(-1, dp(110));
        spectrumSize.setMargins(0, dp(3), 0, dp(8));
        controls.addView(spectrumView, spectrumSize);

        LinearLayout times = new LinearLayout(this);
        trackTime = terminalText("", 17, palette.accent);
        times.addView(trackTime, new LinearLayout.LayoutParams(0, -2, 1));
        trackDuration = terminalText("", 15, palette.muted);
        times.addView(trackDuration);
        controls.addView(times);
        seek = new SeekBar(this);
        seek.setId(View.generateViewId());
        seek.setFocusable(true);
        seek.setContentDescription("Перемотка");
        seek.setThumb(null);
        seek.setPadding(0, dp(4), 0, dp(4));
        seek.setProgressDrawable(new SegmentScale());
        seek.setBackground(terminalFocus(false));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int seconds, boolean fromUser) {
                if (fromUser) withService(service -> service.seek(seconds * 1000L), false);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        controls.addView(seek, new LinearLayout.LayoutParams(-1, dp(28)));
        updateTimeline(null);

        trackControls = new LinearLayout(this);
        controls.addView(trackControls);
        previousButton = terminalButton(trackControls, "Предыдущий", () -> withService(PlaybackService::previous, false), 13);
        playButton = terminalButton(trackControls, "Играть", () -> withService(PlaybackService::toggle, false), 13);
        nextButton = terminalButton(trackControls, "Следующий", () -> withService(PlaybackService::next, false), 13);
        for (Button button : new Button[]{previousButton, playButton, nextButton}) {
            button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        continuationButton = terminalButton(controls, "После очереди", this::chooseContinuation, 13);
        updateContinuationLabel();
        for (View control : new View[]{sceneButton, seek, playButton, previousButton, nextButton, continuationButton}) {
            control.setNextFocusLeftId(list.getId());
        }
        playButton.setNextFocusLeftId(previousButton.getId());
        nextButton.setNextFocusLeftId(playButton.getId());
    }

    private TextView terminalText(String label, int size, int color) {
        TextView view = text(label, size, color);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private void addRule(LinearLayout layout) {
        View rule = new View(this);
        rule.setBackgroundColor(palette.outline);
        layout.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private StateListDrawable terminalFocus(boolean fill) {
        GradientDrawable focus = new GradientDrawable();
        if (fill) focus.setColor(palette.accent);
        else focus.setStroke(dp(2), palette.accent);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focus);
        states.addState(new int[]{}, new ColorDrawable(android.graphics.Color.TRANSPARENT));
        return states;
    }

    private Button terminalButton(LinearLayout row, String label, Runnable action, int size) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText(label);
        styleTerminalButton(button, size);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(-2, dp(44)));
        return button;
    }

    private void styleTerminalButton(Button button, int size) {
        button.setAllCaps(false);
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextSize(size);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        button.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                        {android.R.attr.state_activated}, {}},
                new int[]{palette.muted, palette.focusInk, palette.accent, palette.ink}));
        button.setBackground(terminalFocus(true));
    }

    private Button navigationTab(LinearLayout row, String label, Runnable action) {
        Button tab = terminalButton(row, label, action, 15);
        tab.setTag(label);
        tab.setContentDescription(label);
        tab.setMinWidth((int) Math.ceil(tab.getPaint().measureText("[" + label + "]"))
                + tab.getPaddingLeft() + tab.getPaddingRight());
        return tab;
    }

    private final class SpectrumView extends View {
        private static final int ROWS = 6;
        private final Paint paint = new Paint();
        private final int peak = palette == DARK ? palette.accent : 0xff23674b;
        private final int dim = ColorUtils.blendARGB(palette.panel, peak, .18f);
        private final int low = ColorUtils.blendARGB(palette.panel, peak, .42f);
        private final int middle = ColorUtils.blendARGB(palette.panel, peak, .69f);

        SpectrumView() {
            super(TvActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            PlaybackService service = PlaybackService.instance;
            boolean playing = service != null && service.state == TransportState.PLAYING;
            float[] bands = playing ? service.audioEnergy.spectrum() : null;
            int columns = AudioEnergy.SPECTRUM_BANDS;
            float gap = dp(4);
            float size = Math.min((getWidth() - gap * (columns - 1)) / columns,
                    (getHeight() - gap * (ROWS - 1)) / ROWS);
            if (size <= 0) return;
            float originX = (getWidth() - (columns * size + (columns - 1) * gap)) / 2;
            float bottom = (getHeight() + ROWS * size + (ROWS - 1) * gap) / 2;
            for (int column = 0; column < columns; column++) {
                int height = bands == null ? 0 : Math.round(bands[column] * ROWS);
                float x = originX + column * (size + gap);
                for (int row = 0; row < ROWS; row++) {
                    int color = row >= height ? dim : row == height - 1 ? peak : row == 0 ? low : middle;
                    paint.setColor(color);
                    float y = bottom - size - row * (size + gap);
                    canvas.drawRect(x, y, x + size, y + size, paint);
                }
            }
            if (playing && running && isShown()) postInvalidateDelayed(40);
        }
    }

    private final class SegmentScale extends Drawable {
        private final Paint paint = new Paint();
        private final boolean square;

        SegmentScale() { this(false); }

        SegmentScale(boolean square) { this.square = square; }

        @Override public void draw(Canvas canvas) {
            var bounds = getBounds();
            float width = bounds.width() / 24f;
            float center = bounds.exactCenterY();
            float segmentWidth = Math.max(0, width - dp(2));
            float halfHeight = Math.min(bounds.height(), square ? segmentWidth : dp(9)) / 2f;
            int filled = Math.round(24 * getLevel() / 10000f);
            for (int i = 0; i < 24; i++) {
                paint.setColor(i < filled ? palette.accent : palette.outline);
                float left = bounds.left + i * width;
                canvas.drawRect(left, center - halfHeight, left + segmentWidth, center + halfHeight, paint);
            }
        }

        @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
                new int[]{palette.muted, palette.focusInk, palette.ink}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(palette.accent));
        GradientDrawable active = surface(palette.selected);
        active.setStroke(dp(1), palette.accent);
        background.addState(new int[]{android.R.attr.state_activated}, active);
        GradientDrawable outline = surface(0);
        outline.setStroke(dp(1), palette.outline);
        background.addState(new int[]{}, outline);
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
        rows(labels, action, Collections.emptyList());
    }

    private void rows(List<String> labels, Consumer<Integer> action, List<PlaybackService.Entry> tracks) {
        displayedQueue = null;
        displayedAlbum = null;
        bindRows(trackRows(labels, tracks), action, tracks, true);
        PlaybackService service = PlaybackService.instance;
        if (service == null || markedEntry == null || markedTrack < 0 || tracks.isEmpty()) return;
        String gameId = albumId(markedEntry);
        if (tracks.stream().allMatch(entry -> gameId.equals(albumId(entry))
                && (entry.seriesId.isEmpty() || entry.seriesId.equals(markedEntry.seriesId)))) {
            displayedQueue = service.queue;
            syncDisplayedQueue(service);
        }
    }

    private static List<ListRow> trackRows(List<String> labels, List<PlaybackService.Entry> tracks) {
        List<ListRow> result = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            if (tracks.isEmpty()) result.add(new ListRow(labels.get(i)));
            else {
                PlaybackService.Entry entry = tracks.get(i);
                result.add(new ListRow(labels.get(i), "", albumId(entry) + "#" + entry.index));
            }
        }
        return result;
    }

    private void bindRows(List<ListRow> rows, Consumer<Integer> action,
                          List<PlaybackService.Entry> tracks, boolean focus) {
        View focusedControl = focus ? null : getCurrentFocus();
        ListCursor cursor = !focus && list.hasFocus() ? rememberCursor() : null;
        displayedRows = rows;
        displayedTracks = tracks;
        PlaybackService service = PlaybackService.instance;
        markedEntry = activeTrack(service);
        markedStation = activeStation(service);
        markedState = service == null ? TransportState.IDLE : service.state;
        markedTrack = markedRow();
        listNumbers.setVisibility(page == Page.ALBUM ? View.VISIBLE : View.GONE);
        listPlatform.setVisibility(page == Page.GAMES ? View.VISIBLE : View.GONE);
        listColumns.setText(switch (page) {
            case GAMES -> "ИГРА";
            case SERIES -> "СЕРИЯ";
            case RADIO -> "СТАНЦИЯ";
            case SEARCH, ALBUM -> "ТРЕК";
        });
        list.setAdapter(new ArrayAdapter<ListRow>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                TerminalRow row = convert instanceof TerminalRow existing ? existing : new TerminalRow();
                row.bind(getItem(position), position);
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, index, id) -> action.accept(index));
        if (!rows.isEmpty()) {
            if (focus && !visual) list.requestFocus();
            if (cursor == null || !restoreCursor(cursor)) {
                list.setSelectionFromTop(Math.max(0, markedTrack), 0);
            }
        }
        if (focusedControl != null && focusedControl != list && focusedControl.isShown()
                && focusedControl.isEnabled() && !focusedControl.hasFocus()) focusedControl.requestFocus();
        renderVisibleRows();
    }

    private final class TerminalRow extends LinearLayout {
        private final TextView marker, number, label, platform;

        TerminalRow() {
            super(TvActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), 0, dp(8), 0);
            setMinimumHeight(dp(44));
            marker = terminalText("", 17, palette.accent);
            addView(marker, new LinearLayout.LayoutParams(dp(32), -2));
            number = terminalText("", 14, palette.muted);
            addView(number, new LinearLayout.LayoutParams(dp(36), -2));
            label = terminalText("", 17, palette.ink);
            label.setSingleLine();
            label.setEllipsize(TextUtils.TruncateAt.END);
            addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            platform = terminalText("", 13, palette.muted);
            platform.setSingleLine();
            platform.setEllipsize(TextUtils.TruncateAt.END);
            addView(platform, new LinearLayout.LayoutParams(dp(146), -2));
        }

        void bind(ListRow row, int position) {
            boolean focused = list.hasFocus() && position == list.getSelectedItemPosition();
            boolean active = position == markedTrack;
            setActivated(active);
            setBackgroundColor(focused ? palette.accent : android.graphics.Color.TRANSPARENT);
            String mark = active ? playbackMark() : "";
            marker.setText((focused ? ">" : "") + mark);
            marker.setTextColor(focused ? palette.focusInk : palette.accent);
            number.setVisibility(page == Page.ALBUM ? View.VISIBLE : View.GONE);
            number.setText(String.format(Locale.ROOT, "%02d", position + 1));
            number.setTextColor(focused ? palette.focusInk : palette.muted);
            label.setText(row.label());
            label.setTextColor(focused ? palette.focusInk : active ? palette.accent : palette.ink);
            label.setTypeface(Typeface.MONOSPACE, focused ? Typeface.BOLD : Typeface.NORMAL);
            platform.setVisibility(page == Page.GAMES ? View.VISIBLE : View.GONE);
            platform.setText(row.platform());
            platform.setTextColor(focused ? palette.focusInk : palette.muted);
            setContentDescription(row.label() + (row.platform().isEmpty() ? "" : ", " + row.platform())
                    + (active ? ", " + playbackStateLabel(markedState, page == Page.RADIO) : ""));
        }
    }

    private void renderVisibleRows() {
        int first = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount() && first + i < displayedRows.size(); i++) {
            if (list.getChildAt(i) instanceof TerminalRow row) row.bind(displayedRows.get(first + i), first + i);
        }
        int position = list.getSelectedItemPosition();
        setText(listPosition, position < 0 || displayedRows.isEmpty() ? ""
                : (position + 1) + " / " + displayedRows.size());
        String action = switch (page) {
            case GAMES, SERIES -> "ОК Открыть треклист";
            case RADIO, SEARCH, ALBUM -> "ОК Воспроизвести";
        };
        setText(listKeys, displayedRows.isEmpty() ? "→ К визуализации"
                : "↑ ↓ Строка    " + action + "    → К визуализации");
    }

    private ListCursor rememberCursor() {
        int position = list.getSelectedItemPosition();
        if (position < 0 || position >= displayedRows.size()) return null;
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        return new ListCursor(request, displayedRows.get(position), position, row == null ? 0 : row.getTop());
    }

    private boolean restoreCursor(ListCursor cursor) {
        if (cursor.request() != request) return false;
        int position = cursor.position();
        if (position >= displayedRows.size() || !cursor.row().equals(displayedRows.get(position))) {
            position = displayedRows.indexOf(cursor.row());
        }
        if (position < 0) return false;
        list.setSelectionFromTop(position, cursor.top());
        return true;
    }

    private String playbackMark() {
        return switch (markedState) {
            case LOADING, CONTINUING -> "…";
            case PAUSED, LOADING_PAUSED -> "Ⅱ";
            default -> "▶";
        };
    }

    private static PlaybackService.Entry activeTrack(PlaybackService service) {
        if (service == null || service.station != null) return null;
        return switch (service.state) {
            case LOADING, LOADING_PAUSED, PLAYING, PAUSED, CONTINUING -> service.currentEntry();
            default -> null;
        };
    }

    private RadioStations.Station activeStation(PlaybackService service) {
        return page == Page.RADIO && service != null && (service.state.wantsPlayback()
                || service.state == TransportState.PAUSED || service.state == TransportState.LOADING_PAUSED)
                ? service.station : null;
    }

    private int findTrack(PlaybackService.Entry entry) {
        if (entry == null || displayedTracks.isEmpty()) return -1;
        int position = displayedTracks.indexOf(entry);
        if (position >= 0) return position;
        String game = albumId(entry);
        String key = trackKey(entry);
        if (game.isEmpty() || key.isEmpty()) return -1;
        for (int i = 0; i < displayedTracks.size(); i++) {
            PlaybackService.Entry row = displayedTracks.get(i);
            if (game.equals(albumId(row)) && (entry.manifest == row.manifest
                    ? entry.index == row.index : key.equals(trackKey(row)))) {
                return i;
            }
        }
        return -1;
    }

    private static String trackKey(PlaybackService.Entry entry) {
        return MainActivity.trackKey(track(entry));
    }

    private static String albumId(PlaybackService.Entry entry) {
        return MusicContract.packageId(entry.manifest, entry.game);
    }

    private static JSONObject track(PlaybackService.Entry entry) {
        return entry.manifest.optJSONArray("tracks").optJSONObject(entry.index);
    }

    private int markedRow() {
        if (markedStation != null) return displayedStations.indexOf(markedStation);
        if (page == Page.GAMES && markedEntry != null) {
            String id = markedEntry.game.optString("id");
            if (!id.isEmpty()) for (int i = 0; i < displayedRows.size(); i++) {
                if (id.equals(displayedRows.get(i).gameId())) return i;
            }
        }
        return findTrack(markedEntry);
    }

    private void updateTrackMarker(PlaybackService service) {
        if (visual) return;
        if (sceneCursor == null || !sceneButton.hasFocus()) syncDisplayedQueue(service);
        PlaybackService.Entry entry = activeTrack(service);
        RadioStations.Station station = activeStation(service);
        TransportState state = service == null ? TransportState.IDLE : service.state;
        if (entry == markedEntry && java.util.Objects.equals(station, markedStation) && state == markedState) return;
        markedEntry = entry;
        markedStation = station;
        markedState = state;
        markedTrack = markedRow();
        renderVisibleRows();
        if (page == Page.ALBUM && markedTrack >= 0 && !list.hasFocus() && !sceneButton.hasFocus()) {
            View row = list.getChildAt(markedTrack - list.getFirstVisiblePosition());
            if (row == null || row.getTop() < list.getPaddingTop()
                    || row.getBottom() > list.getHeight() - list.getPaddingBottom()) {
                list.setSelectionFromTop(markedTrack, 0);
            }
        }
    }

    private void syncDisplayedQueue(PlaybackService service) {
        if (displayedQueue == null) return;
        List<PlaybackService.Entry> queue = service == null ? Collections.emptyList() : service.queue;
        if (queue != displayedQueue && (queue.size() <= displayedQueue.size()
                || queue.get(0) != displayedQueue.get(0))) {
            displayedQueue = null;
            displayedAlbum = null;
            return;
        }
        boolean extended = queue != displayedQueue;
        displayedQueue = queue;
        PlaybackService.Entry current = activeTrack(service);
        if (current == null || !extended && sameAlbum(current, displayedAlbum)) return;
        displayedAlbum = current;
        renderPlayingAlbum(service, current, queue);
    }

    private static boolean sameAlbum(PlaybackService.Entry current, PlaybackService.Entry other) {
        return other != null && current.manifest == other.manifest && albumId(current).equals(albumId(other));
    }

    private void renderPlayingAlbum(PlaybackService service, PlaybackService.Entry current,
                                    List<PlaybackService.Entry> queue) {
        int first = service.position;
        int end = first + 1;
        while (first > 0 && sameAlbum(current, queue.get(first - 1))) first--;
        while (end < queue.size() && sameAlbum(current, queue.get(end))) end++;
        Map<Integer, Integer> queued = new HashMap<>();
        for (int i = first; i < end; i++) queued.put(queue.get(i).index, i);
        queued.put(current.index, service.position);
        List<PlaybackService.Entry> album = albumEntries(current.game, current.manifest, current.seriesId, current.seriesTitle);
        List<String> labels = new ArrayList<>(album.size());
        for (PlaybackService.Entry entry : album) labels.add(track(entry).optString("title"));
        heading.setText(current.game.optString("title"));
        setMessage(albumSummary(current.game, album.size()));
        int token = request;
        bindRows(trackRows(labels, album), i -> withService(active -> {
            if (token != request) return;
            Integer position = active.queue == queue && sameAlbum(current, active.currentEntry())
                    ? queued.get(album.get(i).index) : null;
            if (position == null) startDisplayedAlbum(active, album, i);
            else active.selectQueued(position);
        }, true), album, false);
    }

    private void showRadio() {
        request++;
        page = Page.RADIO;
        updateNavigation();
        searchButton.setEnabled(true);
        heading.setText("Интернет-радио");
        setMessage("Выбери станцию и нажми ОК");
        List<RadioStations.Station> stations = RadioStations.available(this,
                library.preferences.getBoolean(RadioStations.PERSONAL_MODE, false));
        displayedStations = stations;
        List<String> names = new ArrayList<>();
        for (RadioStations.Station station : stations) names.add(station.title());
        rows(names, i -> withService(service -> service.playRadio(stations.get(i)), true));
    }

    private void showCatalog(Page target) { showCatalog(target, null); }

    private void showCatalog(Page target, Runnable loaded) {
        if (target == Page.SEARCH) {
            showSearchResults();
            return;
        }
        int token = ++request;
        page = target;
        updateNavigation();
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
        List<ListRow> rows = new ArrayList<>(games.size());
        for (JSONObject game : games) {
            rows.add(new ListRow(game.optString("title"), terminalPlatform(game), game.optString("id")));
        }
        setMessage(rows.isEmpty() ? "Нет игр с музыкой" : rows.size() + " игр");
        displayedQueue = null;
        displayedAlbum = null;
        bindRows(rows, i -> {
            JSONObject game = games.get(i);
            showCollection(List.of(game), game.optString("title"), "");
        }, Collections.emptyList(), true);
    }

    private static String terminalPlatform(JSONObject game) {
        return game.optString("platform").equals("arcade") ? "Аркада" : platformLabel(game);
    }

    private static String albumSummary(JSONObject game, int count) {
        String platform = terminalPlatform(game);
        return (platform.isEmpty() ? "" : platform + " · ") + count + " треков";
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

    private static String searchText(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
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
        TrackOrigin[] origin = {originFilter};
        LinearLayout body = panelBody();
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setTextSize(20);
        input.setHint("Трек, игра, серия, исполнитель или RA ID");
        input.setText(query);
        body.addView(input);
        Button[] facets = new Button[3];
        Runnable labels = () -> {
            String group = selectedLabel(draft[0], series, "id", "title");
            facets[0].setText("Серия · " + (group.isEmpty() ? "Все серии" : group));
            facets[1].setText("Платформа · " + platforms.getOrDefault(draft[1], "Все платформы"));
            facets[2].setText("Происхождение · " + origin[0].filter);
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
        List<String> origins = new ArrayList<>();
        for (TrackOrigin choice : TrackOrigin.values()) origins.add(choice.filter);
        facets[2] = add(body, "", () -> choices("Происхождение", origins, i -> {
            origin[0] = TrackOrigin.values()[i];
            labels.run();
        }));
        labels.run();

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Поиск музыки").setView(body)
                .setNegativeButton("Назад", null)
                .setNeutralButton("Сбросить", (d, w) -> applySearch("", "", "", TrackOrigin.ALL))
                .setPositiveButton("Найти треки", (d, w) ->
                        applySearch(input.getText().toString(), draft[0], draft[1], origin[0])).create();
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        facets[0].requestFocus();
    }

    private void applySearch(String words, String group, String platform, TrackOrigin origin) {
        query = words.trim();
        seriesFilter = group;
        platformFilter = platform;
        originFilter = origin;
        showSearchResults();
    }

    private void showSearchResults() {
        int token = ++request;
        page = Page.SEARCH;
        updateNavigation();
        heading.setText("Поиск музыки");
        setMessage("Ищу треки…");
        rows(Collections.emptyList(), i -> {});
        String words = searchText(query);
        String groupId = seriesFilter;
        String platform = platformFilter;
        TrackOrigin origin = originFilter;
        Map<String, JSONObject> candidates = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        for (JSONObject game : games) {
            String id = game.optString("id");
            if (!platform.isEmpty() && !platform.equals(game.optString("platform"))) continue;
            List<JSONObject> groups = seriesByGame.getOrDefault(id, Collections.emptyList());
            if (!groupId.isEmpty() && groups.stream().noneMatch(g -> groupId.equals(g.optString("id")))) continue;
            candidates.put(id, game);
            StringBuilder description = new StringBuilder(game.optString("title"))
                    .append(' ').append(platformLabel(game)).append(' ').append(game.optString("platform"));
            for (JSONObject g : groups) description.append(' ').append(g.optString("title"));
            descriptions.put(id, searchText(description.toString()));
        }
        String groupTitle = selectedLabel(groupId, series, "id", "title");
        io.execute(() -> {
            try {
                if (searchTracks == null) {
                    JSONObject index = library.onlineJson(Library.BASE + "assets/gallery-music.json");
                    if (index.optInt("schema_version") != 1) throw new IOException("Формат поиска не поддерживается");
                    searchTracks = index.getJSONArray("tracks");
                }
                List<SearchHit> hits = findSearchHits(candidates, descriptions, words, origin);
                main.post(() -> {
                    if (token != request || isDestroyed()) return;
                    List<String> labels = new ArrayList<>(hits.size());
                    for (SearchHit hit : hits) labels.add(hit.origin().label + "  ·  " + hit.track().optString("title")
                            + "  ·  " + hit.game().optString("title") + "  ·  " + platformLabel(hit.game()));
                    setMessage((hits.isEmpty() ? "Ничего не найдено" : hits.size() + " треков")
                            + (platform.isEmpty() ? "" : " · " + platformNames().getOrDefault(platform, platform))
                            + (groupTitle.isEmpty() ? "" : " · " + groupTitle)
                            + (origin == TrackOrigin.ALL ? "" : " · " + origin.filter));
                    rows(labels, i -> {
                        SearchHit hit = hits.get(i);
                        showCollection(List.of(hit.game()), hit.game().optString("title"), groupId, groupTitle, hit);
                    });
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (token != request || isDestroyed()) return;
                    setMessage("Поиск недоступен · " + e.getMessage());
                    rows(List.of("Повторить"), i -> showSearchResults());
                });
            }
        });
    }

    private List<SearchHit> findSearchHits(Map<String, JSONObject> games, Map<String, String> descriptions,
                                           String query, TrackOrigin origin) {
        String ra = MusicContract.raQuery(query);
        boolean explicitRa = !ra.isEmpty() && query.startsWith("ra");
        String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < searchTracks.length(); i++) {
            JSONObject track = searchTracks.optJSONObject(i);
            if (track == null) continue;
            String id = track.optString("game_id");
            JSONObject game = games.get(id);
            if (game == null) continue;
            TrackOrigin source = TrackOrigin.of(track);
            if (origin != TrackOrigin.ALL && source != origin) continue;
            boolean matches = false;
            JSONArray ids = game.optJSONArray("ra_ids");
            if (!ra.isEmpty() && ids != null) {
                for (int j = 0; j < ids.length(); j++) if (ra.equals(ids.optString(j))) matches = true;
            }
            if (!matches && !explicitRa) {
                String text = descriptions.get(id) + ' ' + searchText(track.optString("title"))
                        + ' ' + searchText(track.optString("artist"));
                matches = true;
                for (String word : words) if (!text.contains(word)) { matches = false; break; }
            }
            if (matches) hits.add(new SearchHit(game, track, source));
        }
        return hits;
    }

    private void choices(String title, List<String> labels, Consumer<Integer> select) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View view, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, view, parent);
                row.setMinHeight(dp(56));
                row.setTextSize(20);
                row.setTextColor(palette.ink);
                return row;
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
                .setAdapter(adapter, (d, index) -> select.accept(index)).setNegativeButton("Назад", null).show();
        dialog.getListView().setSelector(surface(palette.selected));
        dialog.getListView().requestFocus();
    }

    private void updateContinuationLabel() {
        continuationButton.setText("После очереди\n"
                + Continuation.label(library.preferences.getString("after_album", "stop")));
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
                case "rhythm" -> "По ритму";
                case "sound" -> "По звучанию";
                default -> "Остановиться";
            });
        }
        labels.add("Индекс анализа…");
        choices("После последнего трека", labels, i -> {
            if (i == CONTINUATION.length) {
                showAnalysisIndex();
                return;
            }
            String mode = CONTINUATION[i];
            if (mode.equals(current)) return;
            library.preferences.edit().putString("after_album", mode).apply();
            updateContinuationLabel();
            withService(PlaybackService::refreshContinuation, false);
        });
    }

    private void showAnalysisIndex() {
        LinearLayout body = panelBody();
        TextView indexStatus = text("", 19, palette.ink);
        body.addView(indexStatus);
        Button download = add(body, "Обновить индекс", () -> indexIo.execute(() -> SimilarityStore.refresh(library)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Индекс анализа")
                .setView(body).setNegativeButton("Назад", null).show();
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || isDestroyed()) return;
                boolean busy = SimilarityStore.REFRESHING.get();
                String status = busy ? "Загружаю индекс анализа…" : SimilarityStore.status(library);
                if (!TextUtils.equals(indexStatus.getText(), status)) indexStatus.setText(status);
                download.setEnabled(!busy);
                main.postDelayed(this, 350);
            }
        };
        download.requestFocus();
        update.run();
    }

    private void showCollection(List<JSONObject> collection, String name, String group) {
        showCollection(collection, name, group, group.isEmpty() ? "" : name, null);
    }

    private static List<PlaybackService.Entry> albumEntries(JSONObject game, JSONObject manifest,
                                                            String group, String groupTitle) {
        List<PlaybackService.Entry> entries = new ArrayList<>();
        JSONArray tracks = manifest.optJSONArray("tracks");
        if (tracks == null) return entries;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track != null && !MusicContract.streamingDecoder(track).isEmpty()) {
                entries.add(new PlaybackService.Entry(game, manifest, i, group, groupTitle));
            }
        }
        return entries;
    }

    private void startDisplayedAlbum(PlaybackService service, List<PlaybackService.Entry> entries, int index) {
        service.play(entries, index);
        displayedQueue = service.queue;
        displayedAlbum = null;
    }

    private static int findSearchTrack(List<PlaybackService.Entry> entries, SearchHit hit) {
        String id = hit.track().optString("id");
        String audioKey = hit.track().optString("audio_key");
        String title = hit.track().optString("title");
        int index = hit.track().optInt("index", -1);
        int sameAudio = -1;
        for (int i = 0; i < entries.size(); i++) {
            PlaybackService.Entry entry = entries.get(i);
            JSONObject candidate = track(entry);
            if (TrackOrigin.of(candidate) != hit.origin()) continue;
            if (!id.isEmpty() && id.equals(candidate.optString("id"))) return i;
            if (!title.equals(candidate.optString("title"))) continue;
            if (!audioKey.isEmpty() && audioKey.equals(SimilarityRules.key(candidate))) {
                if (entry.index == index) return i;
                if (sameAudio == -1) sameAudio = i;
            } else if (id.isEmpty() && audioKey.isEmpty() && entry.index == index) return i;
        }
        return sameAudio;
    }

    private void showCollection(List<JSONObject> collection, String name, String group, String groupTitle,
                                SearchHit selected) {
        int token = ++request;
        if (page != Page.ALBUM) albumParent = page;
        page = Page.ALBUM;
        updateNavigation();
        reopenAlbum = () -> showCollection(collection, name, group, groupTitle, null);
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
                    for (PlaybackService.Entry entry : albumEntries(game, manifest, group, groupTitle)) {
                        entries.add(entry);
                        String title = track(entry).optString("title");
                        names.add(collection.size() == 1 ? title : game.optString("title") + "   ·   " + title);
                    }
                } catch (Exception e) { error = e.getMessage(); }
            }
            String problem = error;
            main.post(() -> {
                if (token != request || isDestroyed()) return;
                if (entries.isEmpty() && !problem.isEmpty()) {
                    setMessage("Музыка недоступна · " + problem);
                    rows(Collections.singletonList("Повторить"), i -> showCollection(collection, name, group, groupTitle, selected));
                } else {
                    setMessage(entries.isEmpty() ? "Нет поддерживаемых треков"
                            : collection.size() == 1 ? albumSummary(collection.get(0), names.size())
                            : names.size() + " треков");
                    rows(names, i -> withService(service -> {
                        if (token != request) return;
                        startDisplayedAlbum(service, entries, i);
                    }, true), entries);
                    if (selected != null) {
                        int index = findSearchTrack(entries, selected);
                        if (index < 0) setMessage("Трек из поиска отсутствует в актуальном списке игры");
                        else withService(service -> {
                            if (token == request) startDisplayedAlbum(service, entries, index);
                        }, true);
                    }
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
        List<String> labels = List.of("После очереди", "Кэш", "О приложении", "Личный режим", "Оформление");
        choices("Настройки", labels, index -> {
            switch (index) {
                case 0 -> chooseContinuation();
                case 1 -> cacheDialog();
                case 2 -> showAbout();
                case 3 -> personalMode();
                case 4 -> chooseAppearance();
            }
        });
    }

    private void chooseAppearance() {
        boolean light = palette == LIGHT;
        choices("Оформление", List.of((light ? "" : "✓  ") + "Тёмная · Терминал",
                (light ? "✓  " : "") + "Светлая · Бумага"), i -> changeAppearance(i == 1));
    }

    private void changeAppearance(boolean light) {
        if (light == (palette == LIGHT)) return;
        library.preferences.edit().putString(APPEARANCE, light ? "light" : "dark").apply();
        applyPalette(light);
        buildScreen();
        switch (page) {
            case RADIO -> showRadio();
            case GAMES, SERIES, SEARCH -> showCatalog(page);
            case ALBUM -> {
                PlaybackService service = PlaybackService.instance;
                if (displayedQueue != null && activeTrack(service) != null) {
                    displayedAlbum = null;
                    syncDisplayedQueue(service);
                    list.requestFocus();
                } else if (reopenAlbum != null) reopenAlbum.run();
                updateNavigation();
            }
        }
    }

    private void showAbout() {
        LinearLayout body = panelBody();
        TextView name = terminalText("GremlinAmp TV", 25, palette.ink);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        body.addView(name);
        body.addView(terminalText("Версия " + updates.installedVersion() + " · сборка "
                + updates.installedBuild() + "\nДмитрий Малыхин aka Timon_Crazy", 15, palette.muted));
        addRule(body);
        Button site = terminalPanelButton(body, "Сайт проекта", () -> projectLink(false));
        terminalPanelButton(body, "Исходный код", () -> projectLink(true));
        terminalPanelButton(body, "Обновление приложения", this::showUpdates);
        terminalPanelButton(body, "Музыка и авторы", () -> new AlertDialog.Builder(this)
                .setTitle("Музыка и авторы")
                .setMessage("Музыка и авторство отдельных композиций указаны в медиатеке Аркадного зала. "
                        + "Tennis for Two — векторная сцена по мотивам осциллографической игры.")
                .setPositiveButton("Закрыть", null).show());
        showSidePanel("О приложении", body);
        site.requestFocus();
    }

    private void projectLink(boolean source) {
        String title = source ? "Исходный код" : "Сайт проекта";
        String url = source ? "https://github.com/GremlinEngineering/gremlinamp"
                : "https://games.oopssre.ru/gremlin.html";
        LinearLayout body = panelBody();
        body.addView(terminalText("Наведите камеру телефона на QR-код", 16, palette.ink));
        ImageView qr = new ImageView(this);
        qr.setImageResource(source ? R.drawable.tv_source_qr : R.drawable.tv_site_qr);
        if (qr.getDrawable() instanceof BitmapDrawable bitmap) bitmap.setFilterBitmap(false);
        qr.setColorFilter(terminalQrColors());
        qr.setContentDescription("QR-код · " + title);
        LinearLayout.LayoutParams qrSize = new LinearLayout.LayoutParams(dp(180), dp(180));
        qrSize.gravity = Gravity.CENTER_HORIZONTAL;
        qrSize.setMargins(0, dp(8), 0, dp(8));
        body.addView(qr, qrSize);
        body.addView(terminalText(url, 15, palette.muted));
        addRule(body);
        Button open = terminalPanelButton(body, "Открыть на телевизоре", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (RuntimeException e) {
                android.widget.Toast.makeText(this, "Откройте QR-код на телефоне", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        showSidePanel(title, body);
        open.requestFocus();
    }

    private static ColorMatrixColorFilter terminalQrColors() {
        int ink = 0xff103625;
        int paper = 0xffd9f9e7;
        return new ColorMatrixColorFilter(new float[]{
                (Color.red(paper) - Color.red(ink)) / 255f, 0, 0, 0, Color.red(ink),
                (Color.green(paper) - Color.green(ink)) / 255f, 0, 0, 0, Color.green(ink),
                (Color.blue(paper) - Color.blue(ink)) / 255f, 0, 0, 0, Color.blue(ink),
                0, 0, 0, 1, 0
        });
    }

    private Button terminalPanelButton(LinearLayout body, String label, Runnable action) {
        Button button = terminalButton(body, "   " + label, action, 17);
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setContentDescription(label);
        button.setOnFocusChangeListener((view, focused) -> setText(button, (focused ? ">  " : "   ") + label));
        return button;
    }

    private void showSidePanel(String title, LinearLayout body) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        TextView header = terminalText(title.toUpperCase(Locale.ROOT), 19, palette.accent);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        body.addView(header, 0);
        View rule = new View(this);
        rule.setBackgroundColor(palette.outline);
        LinearLayout.LayoutParams headerRule = new LinearLayout.LayoutParams(-1, dp(1));
        headerRule.setMargins(0, dp(8), 0, dp(14));
        body.addView(rule, 1, headerRule);
        body.setPadding(dp(24), dp(27), dp(26), dp(24));
        terminalPanelButton(body, "Назад", dialog::dismiss);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.background);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        LinearLayout panel = new LinearLayout(this);
        View edge = new View(this);
        edge.setBackgroundColor(palette.outline);
        panel.addView(edge, new LinearLayout.LayoutParams(dp(1), -1));
        panel.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(palette.background));
        window.setLayout(dp(410), ViewGroup.LayoutParams.MATCH_PARENT);
        window.setGravity(Gravity.END | Gravity.TOP);
        window.setDimAmount(.32f);
    }

    private void showUpdates() {
        LinearLayout body = panelBody();
        updateSummary = terminalText("", 18, palette.ink);
        body.addView(updateSummary);
        updateTransfer = new LinearLayout(this);
        updateTransfer.setOrientation(LinearLayout.VERTICAL);
        updateTransfer.setPadding(0, dp(12), 0, 0);
        LinearLayout progressHeader = new LinearLayout(this);
        progressHeader.setGravity(Gravity.CENTER_VERTICAL);
        updateProgressText = terminalText("", 16, palette.muted);
        progressHeader.addView(updateProgressText, new LinearLayout.LayoutParams(0, -2, 1));
        updatePercent = terminalText("", 20, palette.accent);
        updatePercent.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        progressHeader.addView(updatePercent);
        updateTransfer.addView(progressHeader);
        View track = new View(this);
        updateScale = new SegmentScale(true);
        track.setBackground(updateScale);
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateTransfer.addView(track, new LinearLayout.LayoutParams(-1, dp(24)));
        body.addView(updateTransfer);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Обновление приложения")
                .setView(body).setNegativeButton("Закрыть", null)
                .setNeutralButton("Проверить обновление", null).setPositiveButton("Обновить", null).create();
        updateDialog = dialog;
        installNote = "";
        dialog.setOnDismissListener(d -> {
            updateDialog = null;
            updateSummary = null;
            updateProgressText = null;
            updatePercent = null;
            updateTransfer = null;
            updateScale = null;
            installAfterDownload = false;
        });
        dialog.show();
        for (int action : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL}) styleTerminalButton(dialog.getButton(action), 14);
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
        if (!downloading) {
            if (!state.detail().isEmpty()) summary += "\n" + state.detail();
            else if (state.ready()) summary += "\nAPK скачан";
        }
        if (!installNote.isEmpty()) summary += "\n" + installNote;
        updateSummary.setText(summary);
        updateTransfer.setVisibility(downloading ? View.VISIBLE : View.GONE);
        setText(updateProgressText, downloading ? state.detail() : "");
        setText(updatePercent, downloading && state.percent() >= 0 ? state.percent() + "%" : "");
        updateScale.setLevel(downloading ? Math.max(0, state.percent()) * 100 : 0);
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
        TextView summary = text("Проверяю кэш…", 17, palette.ink);
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
        if (RadioStations.available(this, true).stream().noneMatch(RadioStations.Station::personal)) {
            new AlertDialog.Builder(this).setTitle("Личный режим")
                    .setMessage("Личные станции не включены в эту сборку.")
                    .setPositiveButton("Закрыть", null).show();
            return;
        }
        LinearLayout body = panelBody();
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Личный код");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        body.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Личный режим").setView(body)
                .setNegativeButton("Отмена", null).setPositiveButton("Включить", null).create();
        dialog.show();
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
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

    private void updateNavigation() {
        Page section = page == Page.ALBUM ? albumParent : page;
        markNavigationTab(radioButton, section == Page.RADIO);
        markNavigationTab(gamesButton, section == Page.GAMES);
        markNavigationTab(seriesButton, section == Page.SERIES);
        markNavigationTab(searchButton, section == Page.SEARCH);
    }

    private static void markNavigationTab(Button tab, boolean active) {
        String label = (String) tab.getTag();
        tab.setActivated(active);
        setText(tab, active ? "[" + label + "]" : label);
    }

    private void withService(Consumer<PlaybackService> action, boolean start) {
        if (!running) return;
        int token = ++command;
        PlaybackService service = PlaybackService.instance;
        if (service != null) {
            if(start)shownError="";
            action.accept(service);
            updateTrackMarker(service);
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
            if (service != null) {
                action.accept(service);
                updateTrackMarker(service);
            }
            else if (retries > 0) awaitService(token, action, retries - 1);
            else setMessage("Плеер не запустился");
        }, 100);
    }

    private void setMessage(String value) {
        message = value;
        status.setText(value);
    }

    private void showFractal(boolean fromShortcut) {
        if (visual) return;
        lastFocus = getCurrentFocus();
        if (!fromShortcut) sceneCursor = null;
        else if (sceneCursor == null || sceneCursor.request() != request) sceneCursor = rememberCursor();
        stage = new FrameLayout(this);
        fractal = new FractalView(this, () -> {
            if (visual) {
                hideFractal();
                setMessage("Визуализация недоступна на этом устройстве");
            }
        });
        stage.addView(fractal, new FrameLayout.LayoutParams(-1, -1));
        fractal.selectScene(selectedScene);
        hint = text("", 19, DARK.ink);
        hint.setPadding(dp(16), dp(8), dp(16), dp(8));
        hint.setBackgroundColor(0xe608120e);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        overlay.setMargins(dp(48), 0, dp(48), dp(27));
        stage.addView(hint, overlay);
        visual = true;
        menu.setVisibility(View.INVISIBLE);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        stage.setFocusableInTouchMode(true);
        stage.requestFocus();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        resumeStage();
        showHint();
    }

    private final Runnable hideHint = () -> {
        if (hint != null) hint.setVisibility(View.GONE);
    };

    private void showHint() {
        if (!visual) return;
        hint.setVisibility(View.VISIBLE);
        updateHint();
        main.removeCallbacks(hideHint);
        main.postDelayed(hideHint, HINT_TIMEOUT_MS);
    }

    private void updateHint() {
        if (!visual || hint.getVisibility() != View.VISIBLE) return;
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
        main.removeCallbacks(hideHint);
        root.removeView(stage);
        hint = null;
        stage = null;
        fractal = null;
        visual = false;
        ListCursor cursor = sceneCursor;
        sceneCursor = null;
        PlaybackService service = PlaybackService.instance;
        PlaybackService.Entry current = activeTrack(service);
        if (cursor != null && cursor.request() == request && displayedAlbum != null
                && current != null && !sameAlbum(current, displayedAlbum)) {
            // Keep the album the viewer was browsing even if playback moved to another game.
            displayedQueue = null;
            displayedAlbum = null;
        }
        menu.setVisibility(View.VISIBLE);
        if (cursor != null && restoreCursor(cursor)) list.requestFocus();
        else if (lastFocus != null) lastFocus.requestFocus();
        lastFocus = null;
        updateTrackMarker(service);
        renderPlayer(service);
        renderVisibleRows();
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

    private void updateTimeline(PlaybackService service) {
        boolean track = service != null && service.station == null && service.currentEntry() != null;
        trackTime.setVisibility(track ? View.VISIBLE : View.GONE);
        trackDuration.setVisibility(track && service.durationKnown ? View.VISIBLE : View.GONE);
        seek.setVisibility(track && service.duration > 0 ? View.VISIBLE : View.GONE);
        seek.setEnabled(track && service.seekAvailable());
        if (!track) return;
        long position = service.currentPosition();
        long duration = service.duration;
        setText(trackTime, timeLabel(position));
        setText(trackDuration, service.durationKnown ? timeLabel(duration) : "");
        int seconds = duration > 0 ? (int) Math.min(Integer.MAX_VALUE, 1 + (duration - 1) / 1000) : 0;
        if (seek.getMax() != seconds) {
            seek.setMax(seconds);
            seek.setKeyProgressIncrement(10);
        }
        seek.setProgress((int) Math.min(seconds, position / 1000));
    }

    private static String timeLabel(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String playbackStateLabel(TransportState state, boolean radio) {
        return switch (state) {
            case IDLE, ERROR -> "";
            case LOADING -> "Подключаюсь";
            case LOADING_PAUSED, PAUSED -> "Ⅱ ПАУЗА";
            case PLAYING -> radio ? "▶ В ЭФИРЕ" : "▶ ИГРАЕТ";
            case CONTINUING -> "Следующий трек";
            case ENDED -> "Очередь завершена";
        };
    }

    private void renderPlayer(PlaybackService service) {
        updateTimeline(service);
        boolean radio = service != null && service.station != null;
        PlaybackService.Entry entry = service == null ? null : service.currentEntry();
        boolean gameTrack = entry != null;
        spectrumView.setVisibility(gameTrack || radio ? View.VISIBLE : View.GONE);
        spectrumView.invalidate();
        trackControls.setVisibility(gameTrack || radio ? View.VISIBLE : View.GONE);
        previousButton.setVisibility(gameTrack ? View.VISIBLE : View.GONE);
        nextButton.setVisibility(gameTrack ? View.VISIBLE : View.GONE);
        continuationButton.setVisibility(gameTrack ? View.VISIBLE : View.GONE);
        playButton.setNextFocusLeftId(gameTrack ? previousButton.getId() : list.getId());
        previousButton.setEnabled(gameTrack);
        boolean continuing = service != null && service.state == TransportState.CONTINUING;
        nextButton.setEnabled(continuing || service != null && service.canNext());
        setText(nextButton, continuing ? "Ищу следующий…" : "Следующий");
        playButton.setVisibility(radio || gameTrack ? View.VISIBLE : View.GONE);
        playButton.setEnabled(radio || gameTrack);
        String playLabel = service != null && service.state.wantsPlayback()
                ? (radio ? "Остановить" : "Пауза")
                : radio && service.state == TransportState.ERROR ? "Повторить" : "Играть";
        setText(playButton, playLabel);

        playingContext.setVisibility(gameTrack ? View.VISIBLE : View.GONE);
        String platform = gameTrack ? terminalPlatform(entry.game) : "";
        playingPlatform.setVisibility(platform.isEmpty() ? View.GONE : View.VISIBLE);
        if (service == null) {
            setText(transport, page == Page.RADIO ? "Выберите станцию" : "Выберите композицию");
            setText(playingState, "ПЛЕЕР");
            return;
        }
        String state = service.error.isEmpty() ? playbackStateLabel(service.state, radio) : service.error;
        setText(transport, service.identity.snapshot().title);
        setText(playingState, state.isEmpty() ? "ПЛЕЕР" : state);
        if (gameTrack) {
            setText(playingContext, service.gameTitle);
            setText(playingPlatform, platform);
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            PlaybackService service = PlaybackService.instance;
            updateTrackMarker(service);
            renderPlayer(service);
            if (service == null) shownError = "";
            else if (service.state == TransportState.ERROR) {
                if (!service.error.equals(shownError)) {
                    shownError = service.error;
                    setMessage(service.error);
                    if (visual) hideFractal();
                }
            } else if (!shownError.isEmpty()) {
                if (message.equals(shownError)) setMessage("");
                shownError = "";
            }
            updateHint();
            main.postDelayed(this, 350);
        }
    };

    private static void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean media = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS || key == KeyEvent.KEYCODE_MEDIA_REWIND
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        boolean sceneKey = visual && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER);
        if (!media && !sceneKey) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (visual) showHint();
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
                    updateHint();
                }
                case KeyEvent.KEYCODE_MEDIA_PLAY -> withService(PlaybackService::resume, false);
                case KeyEvent.KEYCODE_MEDIA_PAUSE -> withService(PlaybackService::pause, false);
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS -> withService(PlaybackService::previous, false);
                case KeyEvent.KEYCODE_MEDIA_NEXT -> withService(PlaybackService::next, false);
                case KeyEvent.KEYCODE_MEDIA_REWIND -> withService(service -> service.seek(service.currentPosition() - 10_000), false);
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> withService(service -> service.seek(service.currentPosition() + 10_000), false);
                default -> withService(PlaybackService::toggle, false);
            }
        }
        return true;
    }

    @Override public void onBackPressed() {
        if (visual) hideFractal();
        else if (page == Page.ALBUM) showCatalog(albumParent);
        else if (page == Page.SEARCH) showCatalog(Page.GAMES);
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
        indexIo.shutdownNow();
        super.onDestroy();
    }
}
