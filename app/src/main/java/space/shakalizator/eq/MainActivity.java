package space.shakalizator.eq;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.EnumMap;

public final class MainActivity extends Activity {
    private static final int PICK_TRACK = 40;
    private static final int BACKGROUND = 0xff14111a;
    private static final int PANEL = 0xff221c2c;
    private static final int LINE = 0xff443850;
    private static final int ACCENT = 0xfffacf58;
    private static final int TEXT = 0xfffaf2f6;
    private static final int MUTED = 0xffc5b8ce;

    private final EnumMap<ShakalDsp.Preset, LinearLayout> cards = new EnumMap<>(ShakalDsp.Preset.class);
    private ShakalDsp.Preset preset = ShakalDsp.Preset.AIRPLANE;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean hasFocus;
    private Uri uri;
    private String title = "Демо · Shakal Loop";
    private boolean bypass;
    private boolean paused;
    private int intensity = 100;
    private ShakalPlayer player;
    private TextView play, dry, trackName, status, amount;
    private final BroadcastReceiver unplug = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        audio = getSystemService(AudioManager.class);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(ShakalPlayer.attributes())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) pause();
                }).build();
        if (state != null) {
            String savedUri = state.getString("uri");
            if (savedUri != null) uri = Uri.parse(savedUri);
            title = state.getString("title", title);
            preset = ShakalDsp.Preset.valueOf(state.getString("preset", preset.name()));
            bypass = state.getBoolean("dry");
            intensity = state.getInt("intensity", 100);
        }
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unplug, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unplug, filter);
        build();
        refresh();
    }

    private void build() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BACKGROUND);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        if (Build.VERSION.SDK_INT >= 30) {
            scroller.setOnApplyWindowInsetsListener((view, insets) -> {
                var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(dp(20) + safe.left, dp(20) + safe.top,
                        dp(20) + safe.right, dp(28) + safe.bottom);
                return insets;
            });
        }
        scroller.addView(root);
        setContentView(scroller);

        TextView wordmark = label("ShakalEQ", 42, TEXT);
        wordmark.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        root.addView(wordmark);
        add(root, label("Эквалайзер для плохих наушников", 15, MUTED), 0);

        LinearLayout transport = column();
        transport.setPadding(dp(14), dp(12), dp(14), dp(12));
        transport.setBackground(shape(PANEL, LINE));
        add(root, transport, 20);
        trackName = label(title, 17, TEXT);
        trackName.setMaxLines(2);
        trackName.setEllipsize(TextUtils.TruncateAt.END);
        transport.addView(trackName);
        status = label("Готово к прослушиванию", 13, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(transport, status, 3);

        LinearLayout actions = new LinearLayout(this);
        add(transport, actions, 10);
        play = button("Играть", true, () -> {
            if (player == null) start();
            else if (paused) resume();
            else pause();
        });
        action(actions, play, 0);
        action(actions, button("Стоп", false, this::stop), 8);
        if (uri != null) addDemoToggle(transport);

        TextView file = button("Открыть аудиофайл", false, this::pick);
        add(transport, file, 8);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        add(root, controls, 17);
        amount = label("", 15, ACCENT);
        amount.setTypeface(Typeface.MONOSPACE);
        controls.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        dry = button("", false, () -> {
            bypass = !bypass;
            applyEffect();
        });
        controls.addView(dry, new LinearLayout.LayoutParams(-2, -2));

        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(intensity);
        slider.setContentDescription("Степень шакализации");
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        add(root, slider, 4);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                intensity = progress;
                applyEffect();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        add(root, label("Пресеты", 22, TEXT), 12);
        cards.clear();
        for (ShakalDsp.Preset item : ShakalDsp.Preset.values()) {
            LinearLayout row = column();
            row.setPadding(dp(14), dp(9), dp(14), dp(9));
            row.addView(label(item.title, 17, TEXT));
            add(row, label(item.description, 13, MUTED), 2);
            row.setFocusable(true);
            row.setOnClickListener(view -> {
                preset = item;
                bypass = false;
                applyEffect();
            });
            cards.put(item, row);
            add(root, row, 7);
        }
    }

    private void addDemoToggle(LinearLayout transport) {
        add(transport, button("Вернуться к демо", false, () -> {
            stop();
            uri = null;
            title = "Демо · Shakal Loop";
            build();
            refresh();
        }), 8);
    }

    private void applyEffect() {
        if (player != null) {
            player.preset = preset;
            player.wet = wet();
        }
        refresh();
    }

    private float wet() {
        return bypass ? 0 : intensity / 100f;
    }

    private void refresh() {
        trackName.setText(title);
        amount.setText("Шакализация " + intensity + "%");
        dry.setText(bypass ? "Звук · оригинал" : "Звук · пресет");
        dry.setContentDescription(bypass ? "Оригинал. Включить пресет" : "Пресет. Включить оригинал");
        play.setText(player == null ? "Играть" : paused ? "Продолжить" : "Пауза");
        for (var entry : cards.entrySet()) {
            boolean selected = entry.getKey() == preset;
            entry.getValue().setSelected(selected);
            entry.getValue().setBackground(ripple(selected ? 0xff342840 : PANEL, selected ? ACCENT : LINE));
        }
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*");
        try {
            startActivityForResult(intent, PICK_TRACK);
        } catch (android.content.ActivityNotFoundException error) {
            status.setText("Системный выбор файлов недоступен");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_TRACK || result != RESULT_OK || data == null || data.getData() == null) return;
        stop();
        uri = data.getData();
        title = "Аудиофайл";
        try (Cursor details = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (details != null && details.moveToFirst() && details.getString(0) != null) {
                title = details.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Playback can still work when the provider has no metadata.
        }
        build();
        refresh();
    }

    private boolean requestFocus() {
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) status.setText("Android не предоставил доступ к выводу звука");
        return hasFocus;
    }

    private void start() {
        if (!requestFocus()) return;
        paused = false;
        player = new ShakalPlayer(this, uri, preset, wet(), (finished, error) -> runOnUiThread(() -> {
            if (player != finished) return;
            player = null;
            releaseFocus();
            keepScreenOn(false);
            if (error == null) {
                status.setText("Трек закончился");
            } else {
                android.util.Log.w("ShakalEQ", "Playback failed", error);
                status.setText(error instanceof java.io.IOException && error.getMessage() != null
                        ? error.getMessage() : "Не удалось воспроизвести файл");
            }
            refresh();
        }));
        status.setText("Воспроизведение");
        keepScreenOn(true);
        new Thread(player, "ShakalEQ playback").start();
        refresh();
    }

    private void pause() {
        if (player == null || paused) return;
        paused = true;
        player.setPaused(true);
        releaseFocus();
        keepScreenOn(false);
        status.setText("Пауза");
        refresh();
    }

    private void resume() {
        if (!requestFocus()) return;
        paused = false;
        player.setPaused(false);
        keepScreenOn(true);
        status.setText("Воспроизведение");
        refresh();
    }

    private void stop() {
        if (player != null) {
            player.stop();
            player = null;
        }
        paused = false;
        releaseFocus();
        keepScreenOn(false);
        status.setText("Готово к прослушиванию");
        refresh();
    }

    private void releaseFocus() {
        if (hasFocus) {
            hasFocus = false;
            audio.abandonAudioFocusRequest(focus);
        }
    }

    private void keepScreenOn(boolean enabled) {
        if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onStop() {
        pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        stop();
        unregisterReceiver(unplug);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (uri != null) state.putString("uri", uri.toString());
        state.putString("title", title);
        state.putString("preset", preset.name());
        state.putBoolean("dry", bypass);
        state.putInt("intensity", intensity);
    }

    private LinearLayout column() {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value, float size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        return text;
    }

    private TextView button(String value, boolean primary, Runnable callback) {
        TextView button = label(value, 15, primary ? BACKGROUND : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(ripple(primary ? ACCENT : PANEL, primary ? ACCENT : LINE));
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private GradientDrawable shape(int fill, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private RippleDrawable ripple(int fill, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x44ffffff), shape(fill, border), null);
    }

    private void add(LinearLayout layout, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        layout.addView(view, params);
    }

    private void action(LinearLayout layout, TextView view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.leftMargin = dp(margin);
        layout.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
