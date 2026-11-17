package space.shakalizator.eq;

import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int FOLDER = 40, FILE = 41;
    private static final int BG = 0xff14111a, PANEL = 0xff252032, ACCENT = 0xfffacf58;
    private static final int TEXT = 0xfffaf2f6, MUTED = 0xffc5b8ce;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService scanner = Executors.newSingleThreadExecutor();
    private Future<?> scan;
    private PlaybackService service;
    private boolean bound;
    private Uri folder;
    private List<MusicLibrary.Album> albums = new ArrayList<>();
    private MusicLibrary.Album album;
    private String libraryStatus = "Выбери папку с музыкой";
    private int page;
    private boolean tracking;
    private SeekBar position;
    private TextView title, detail, time, play, shuffle, repeat, section;
    private LinearLayout body;
    private List<MusicLibrary.Song> shownQueue;
    private int shownIndex = -1;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); main.postDelayed(this, 300); }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            renderPage();
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        build();
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
        String stored = getPreferences(MODE_PRIVATE).getString("folder", null);
        if (stored != null) load(Uri.parse(stored));
        else renderPage();
    }

    @Override protected void onStart() { super.onStart(); main.post(ticker); }
    @Override protected void onStop() { main.removeCallbacks(ticker); super.onStop(); }
    @Override protected void onDestroy() {
        if (bound) unbindService(connection);
        scanner.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(10));
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(16) + safe.left, dp(12) + safe.top, dp(16) + safe.right, dp(10) + safe.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);

        TextView wordmark = label("ShakalEQ", 27, TEXT);
        wordmark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(wordmark);
        LinearLayout navigation = row(root);
        action(navigation, "Альбомы", () -> openPage(0));
        action(navigation, "Очередь", () -> openPage(1));
        action(navigation, "Эффекты", () -> openPage(2));

        section = label("", 15, MUTED);
        section.setPadding(0, dp(8), 0, dp(5));
        root.addView(section);
        body = column();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        var transport = column();
        transport.setPadding(dp(8), dp(7), dp(8), dp(3));
        transport.setBackground(panel());
        root.addView(transport);
        title = label("Shakal Loop", 17, TEXT);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        transport.addView(title);
        detail = label("", 12, MUTED);
        detail.setMaxLines(1);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        transport.addView(detail);
        position = new SeekBar(this);
        position.setContentDescription("Позиция трека");
        position.setProgressTintList(ColorStateList.valueOf(ACCENT));
        position.setThumbTintList(ColorStateList.valueOf(ACCENT));
        transport.addView(position);
        time = label("0:00", 12, MUTED);
        transport.addView(time);
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) {
                    if (service != null) updateTime(value);
                    if (!tracking && service != null) service.seek(value);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                tracking = false;
                if (service != null) service.seek(bar.getProgress());
            }
        });

        var controls = row(transport);
        action(controls, "⏮", "Предыдущий трек", () -> withPlayer(PlaybackService::previous));
        play = action(controls, "Играть", () -> withPlayer(PlaybackService::toggle));
        action(controls, "⏭", "Следующий трек", () -> withPlayer(PlaybackService::next));
        var modes = row(transport);
        shuffle = action(modes, "Случайно", () -> withPlayer(PlaybackService::toggleShuffle));
        repeat = action(modes, "Повтор", () -> withPlayer(PlaybackService::cycleRepeat));
        action(modes, "Стоп", () -> withPlayer(PlaybackService::stop));
    }

    private void openPage(int value) {
        page = value;
        if (value == 0) album = null;
        renderPage();
    }

    private void renderPage() {
        body.removeAllViews();
        if (page == 2) { renderEffects(); return; }
        if (page == 1) {
            section.setText("Очередь");
            if (service == null) return;
            shownQueue = new ArrayList<>(service.queue());
            shownIndex = service.index();
            var rows = new ArrayList<String>();
            for (int i = 0; i < shownQueue.size(); i++) {
                rows.add((i == shownIndex ? "▶  " : "") + songLine(shownQueue.get(i)));
            }
            list(rows, i -> withPlayer(player -> player.jump(i)));
            return;
        }
        if (album != null) {
            section.setText(album.title());
            var buttons = row(body);
            action(buttons, "К альбомам", () -> openPage(0));
            action(buttons, "Играть альбом", () -> startQueue(album.songs(), 0));
            var tracks = new ArrayList<String>();
            for (var song : album.songs()) tracks.add(songLine(song));
            list(tracks, i -> startQueue(album.songs(), i));
            return;
        }
        section.setText(libraryStatus);
        var buttons = row(body);
        action(buttons, "Папка", () -> pick(FOLDER));
        action(buttons, "Файл", () -> pick(FILE));
        action(buttons, "Демо", () -> startQueue(java.util.Collections.singletonList(MusicLibrary.DEMO), 0));
        if (folder != null) {
            var more = row(body);
            action(more, "Обновить", () -> load(folder));
            if (!albums.isEmpty()) action(more, "Играть всё", () -> {
                var songs = new ArrayList<MusicLibrary.Song>();
                for (var entry : albums) songs.addAll(entry.songs());
                startQueue(songs, 0);
            });
        }
        var rows = new ArrayList<String>();
        for (var entry : albums) {
            String artist = entry.artist().isEmpty() ? entry.songs().get(0).artist() : entry.artist();
            rows.add(entry.title() + "\n" + (artist.isEmpty() ? "" : artist + " · ")
                    + "Треков: " + entry.songs().size());
        }
        list(rows, i -> { album = albums.get(i); renderPage(); });
    }

    private void renderEffects() {
        section.setText("Эффекты");
        if (service == null) return;
        var controls = row(body);
        TextView amount = label("Шакализация " + service.intensity() + "%", 15, ACCENT);
        controls.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        action(controls, service.bypass() ? "Оригинал" : "Пресет", () -> withPlayer(p -> {
            p.effects(p.preset(), p.intensity(), !p.bypass(), true);
            renderPage();
        }));
        SeekBar wet = new SeekBar(this);
        wet.setMax(100);
        wet.setProgress(service.intensity());
        wet.setContentDescription("Интенсивность пресета");
        body.addView(wet);
        wet.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user) return;
                amount.setText("Шакализация " + value + "%");
                withPlayer(p -> p.effects(p.preset(), value, p.bypass(), true));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        var rows = new ArrayList<String>();
        var presets = ShakalDsp.Preset.values();
        for (var preset : presets) rows.add((preset == service.preset() ? "▶  " : "")
                + preset.title + "\n" + preset.description);
        list(rows, i -> withPlayer(p -> {
            p.effects(presets[i], p.intensity(), false, true);
            renderPage();
        }));
    }

    private void list(List<String> rows, java.util.function.IntConsumer clicked) {
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xff383042));
        list.setDividerHeight(dp(1));
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                TextView item = recycled == null ? label("", 16, TEXT) : (TextView) recycled;
                item.setPadding(dp(8), dp(10), dp(8), dp(10));
                item.setMinHeight(dp(48));
                item.setText(getItem(index));
                return item;
            }
        });
        list.setOnItemClickListener((parent, item, index, id) -> clicked.accept(index));
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private static String songLine(MusicLibrary.Song song) {
        return song.title() + (song.artist().isEmpty() ? "" : " · " + song.artist())
                + (song.duration() == 0 ? "" : "  ·  " + clock(song.duration()));
    }

    private void refresh() {
        if (service == null) return;
        var song = service.song();
        title.setText(song.title());
        detail.setText(service.error().isEmpty()
                ? (song.artist().isEmpty() ? song.album() : song.artist() + " · " + song.album())
                : service.error());
        play.setText(service.playing() ? "Пауза" : "Играть");
        shuffle.setTextColor(service.shuffled() ? ACCENT : TEXT);
        shuffle.setContentDescription(service.shuffled() ? "Случайный порядок включён" : "Случайный порядок выключен");
        repeat.setText(service.repeat() == 2 ? "Повтор 1" : "Повтор");
        repeat.setTextColor(service.repeat() == 0 ? TEXT : ACCENT);
        repeat.setContentDescription(service.repeat() == 0 ? "Без повтора"
                : service.repeat() == 1 ? "Повтор очереди" : "Повтор трека");
        if (!tracking) {
            int duration = (int) Math.min(Integer.MAX_VALUE, song.duration());
            position.setMax(duration);
            position.setEnabled(duration > 0);
            position.setProgress((int) Math.min(duration, service.position()));
            updateTime(service.position());
        }
        if (page == 1 && (shownIndex != service.index() || !service.queue().equals(shownQueue))) renderPage();
    }

    private void updateTime(long current) { time.setText(clock(current) + " / " + clock(service.song().duration())); }
    private static String clock(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void pick(int request) {
        Intent intent = request == FOLDER ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, request); }
        catch (ActivityNotFoundException failure) { Toast.makeText(this, "Выбор файлов недоступен", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == FOLDER) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getPreferences(MODE_PRIVATE).edit().putString("folder", uri.toString()).apply();
            } catch (SecurityException failure) {
                libraryStatus = "Доступ к папке действует до закрытия приложения";
            }
            openPage(0);
            load(uri);
        } else if (request == FILE) {
            scanner.submit(() -> {
                String name = "Аудиофайл";
                try (var resultRow = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (resultRow != null && resultRow.moveToFirst() && resultRow.getString(0) != null) name = resultRow.getString(0);
                } catch (RuntimeException ignored) {}
                var song = MusicLibrary.single(this, uri, name);
                main.post(() -> {
                    if (!isDestroyed()) startQueue(java.util.Collections.singletonList(song), 0);
                });
            });
        }
    }

    private void load(Uri uri) {
        if (scan != null) scan.cancel(true);
        folder = uri;
        albums = new ArrayList<>();
        album = null;
        libraryStatus = "Сканирую папку…";
        if (page == 0) renderPage();
        scan = scanner.submit(() -> {
            List<MusicLibrary.Album> found;
            String message;
            try {
                found = MusicLibrary.scan(getApplicationContext(), uri);
                message = found.isEmpty() ? "В папке нет аудиофайлов" : "Альбомов: " + found.size();
            } catch (InterruptedException cancelled) {
                return;
            } catch (java.io.IOException | RuntimeException failure) {
                found = new ArrayList<>();
                message = "Не удалось прочитать папку. Выбери её заново.";
            }
            if (Thread.currentThread().isInterrupted()) return;
            var result = found;
            var status = message;
            main.post(() -> {
                if (isDestroyed() || folder != uri) return;
                albums = result;
                libraryStatus = status;
                if (page == 0) renderPage();
            });
        });
    }

    @Override public void onBackPressed() {
        if (page != 0 || album != null) openPage(0);
        else super.onBackPressed();
    }

    private void startQueue(List<MusicLibrary.Song> songs, int index) { withPlayer(p -> p.setQueue(songs, index)); }
    private void withPlayer(java.util.function.Consumer<PlaybackService> action) {
        if (service != null) { action.accept(service); refresh(); }
    }

    private LinearLayout column() {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row(LinearLayout parent) {
        var row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private TextView action(LinearLayout row, String title, Runnable callback) {
        return action(row, title, title, callback);
    }

    private TextView action(LinearLayout row, String title, String description, Runnable callback) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        if (!title.equals(description)) button.setContentDescription(description);
        button.setOnClickListener(view -> callback.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private TextView label(String text, int size, int color) {
        var label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private GradientDrawable panel() {
        var drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(12));
        drawable.setColor(PANEL);
        return drawable;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
