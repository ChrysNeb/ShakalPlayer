package space.shakalizator.eq;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaybackService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Binder binder = new LocalBinder();
    private List<MusicLibrary.Song> original = Collections.singletonList(MusicLibrary.DEMO);
    private List<MusicLibrary.Song> queue = new ArrayList<>(original);
    private int index;
    private ShakalPlayer player;
    private boolean focused;
    private boolean foreground;
    private boolean paused;
    private boolean shuffled;
    private int repeat;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private MediaSession media;
    private PendingIntent openPlayer;
    private ShakalDsp.Preset preset = ShakalDsp.Preset.AIRPLANE;
    private int intensity = 100;
    private boolean bypass;
    private String error = "";
    private final BroadcastReceiver unplug = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };

    final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        wake = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ShakalEQ:Playback");
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(ShakalPlayer.attributes())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> { if (change < 0) pause(); }, main)
                .build();
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unplug, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unplug, filter);
        openPlayer = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("playback", "Воспроизведение", NotificationManager.IMPORTANCE_LOW));
        media = new MediaSession(this, "ShakalEQ");
        media.setSessionActivity(openPlayer);
        media.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (!playing()) toggle(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seek(pos); }
            @Override public void onStop() { stop(); }
        }, main);
        var prefs = getSharedPreferences("player", MODE_PRIVATE);
        preset = ShakalDsp.Preset.valueOf(prefs.getString("preset", preset.name()));
        intensity = prefs.getInt("intensity", 100);
        bypass = prefs.getBoolean("bypass", false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "toggle": toggle(); break;
                case "previous": previous(); break;
                case "next": next(); break;
                case "stop": stop(); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    MusicLibrary.Song song() { return queue.get(index); }
    long position() { return player == null ? 0 : player.positionMs(); }
    boolean playing() { return player != null && !paused; }
    boolean shuffled() { return shuffled; }
    int repeat() { return repeat; }
    String error() { return error; }
    ShakalDsp.Preset preset() { return preset; }
    int intensity() { return intensity; }
    boolean bypass() { return bypass; }
    List<MusicLibrary.Song> queue() { return Collections.unmodifiableList(queue); }
    int index() { return index; }

    void setQueue(List<MusicLibrary.Song> tracks, int selected) {
        MusicLibrary.Song first = tracks.get(selected);
        original = new ArrayList<>(tracks);
        arrange(first);
        launch(0, false);
    }

    private void arrange(MusicLibrary.Song selected) {
        queue = new ArrayList<>(original);
        if (shuffled) {
            queue.remove(selected);
            Collections.shuffle(queue);
            queue.add(0, selected);
        }
        index = queue.indexOf(selected);
    }

    void toggleShuffle() { shuffled = !shuffled; arrange(song()); }
    void cycleRepeat() { repeat = (repeat + 1) % 3; }

    void jump(int selected) {
        if (selected < 0 || selected >= queue.size()) return;
        index = selected;
        launch(0, false);
    }

    void next() {
        if (index + 1 < queue.size()) jump(index + 1);
        else if (repeat != 0) jump(0);
    }

    void previous() {
        if (position() > 3000 || index == 0) seek(0);
        else jump(index - 1);
    }

    void seek(long ms) {
        long limit = Math.max(0, song().duration() - 100);
        launch(Math.max(0, Math.min(ms, limit)), player != null && paused);
    }

    void effects(ShakalDsp.Preset value, int amount, boolean dry, boolean persist) {
        preset = value;
        intensity = amount;
        bypass = dry;
        if (player != null) { player.preset = value; player.wet = wet(); }
        if (persist) getSharedPreferences("player", MODE_PRIVATE).edit()
                .putString("preset", value.name()).putInt("intensity", amount).putBoolean("bypass", dry).apply();
    }

    private float wet() { return bypass ? 0 : intensity / 100f; }

    void toggle() {
        if (player == null) launch(0, false);
        else if (paused) {
            paused = false;
            if (activate()) player.setPaused(false);
            else paused = true;
            publish();
        } else pause();
    }

    private void launch(long position, boolean startPaused) {
        error = "";
        disposePlayer();
        paused = startPaused;
        if (!paused && !activate()) { publish(); return; }
        player = new ShakalPlayer(this, song().uri(), position, paused, preset, wet(),
                (finished, failure) -> main.post(() -> finished(finished, failure)));
        new Thread(player, "ShakalEQ playback").start();
        publish();
    }

    private boolean activate() {
        try {
            if (!foreground) {
                startService(new Intent(this, PlaybackService.class));
                startForeground(1, notification());
                foreground = true;
            }
        } catch (IllegalStateException | SecurityException failure) {
            error = "Открой ShakalEQ, чтобы продолжить воспроизведение";
            return false;
        }
        if (!focused) focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!focused) {
            error = "Android не предоставил доступ к выводу звука";
            if (player == null) detach();
        }
        return focused;
    }

    private void finished(ShakalPlayer finished, Exception failure) {
        if (finished != player) return;
        player = null;
        if (failure != null) {
            android.util.Log.w("ShakalEQ", "Playback failed", failure);
            error = failure instanceof java.io.IOException && failure.getMessage() != null
                    ? failure.getMessage() : "Не удалось воспроизвести файл";
        } else if (repeat == 2) {
            launch(0, false);
            return;
        } else if (index + 1 < queue.size() || repeat == 1) {
            jump((index + 1) % queue.size());
            return;
        }
        idle();
    }

    private void pause() {
        if (!playing()) return;
        paused = true;
        player.setPaused(true);
        releaseFocus();
        publish();
    }

    void stop() { disposePlayer(); error = ""; idle(); }
    private void idle() { paused = false; releaseFocus(); detach(); publish(); }

    private void detach() {
        if (foreground) {
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void disposePlayer() {
        if (player != null) { player.stop(); player = null; }
    }

    private void releaseFocus() {
        if (focused) { focused = false; audio.abandonAudioFocusRequest(focus); }
    }

    private void publish() {
        if (playing() && !wake.isHeld()) wake.acquire();
        if (!playing() && wake.isHeld()) wake.release();
        var song = song();
        media.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, song.duration()).build());
        long actions = PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        int state = player == null ? PlaybackState.STATE_STOPPED
                : paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
        media.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position(), playing() ? 1f : 0f).build());
        media.setActive(player != null);
        if (foreground) getSystemService(NotificationManager.class).notify(1, notification());
    }

    private Notification notification() {
        MusicLibrary.Song song = song();
        return new Notification.Builder(this, "playback").setSmallIcon(R.drawable.ic_shakal)
                .setContentTitle(song.title()).setContentText(song.artist().isEmpty() ? song.album() : song.artist())
                .setContentIntent(openPlayer).setOnlyAlertOnce(true).setOngoing(!paused)
                .addAction(action(android.R.drawable.ic_media_previous, "Предыдущий", "previous"))
                .addAction(action(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                        paused ? "Играть" : "Пауза", "toggle"))
                .addAction(action(android.R.drawable.ic_media_next, "Следующий", "next"))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", "stop"))
                .setDeleteIntent(command("stop"))
                .setStyle(new Notification.MediaStyle().setMediaSession(media.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)).build();
    }

    private Notification.Action action(int icon, String title, String name) {
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                title, command(name)).build();
    }

    private PendingIntent command(String name) {
        return PendingIntent.getService(this, 0, new Intent(this, PlaybackService.class).setAction(name),
                PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() {
        disposePlayer();
        if (wake.isHeld()) wake.release();
        releaseFocus();
        unregisterReceiver(unplug);
        media.release();
        super.onDestroy();
    }
}
