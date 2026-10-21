package com.hermes.tvlive;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.ui.PlayerView;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    /** 由 MainActivity 在启动前塞入，支持上下键换台。 */
    public static List<Channel> playlist = new ArrayList<>();
    public static int index = 0;

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView tvName, tvInfo, tvError;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        tvName = findViewById(R.id.tv_channel);
        tvInfo = findViewById(R.id.tv_info);
        tvError = findViewById(R.id.tv_error);

        String url = getIntent().getStringExtra("url");
        String name = getIntent().getStringExtra("name");
        if (url != null) {
            // 直接进来的情况：把当前频道放进 playlist 以便上下键
            if (playlist.isEmpty() || index >= playlist.size()
                    || !url.equals(playlist.get(index).url)) {
                playlist = new ArrayList<>();
                playlist.add(new Channel(name == null ? "" : name, url, "未分组", null));
                index = 0;
            }
        }
        playCurrent();
        startClock();
    }

    private void startClock() {
        ui.post(new Runnable() {
            @Override public void run() {
                tvInfo.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date())
                        + "   " + (index + 1) + "/" + playlist.size());
                ui.postDelayed(this, 20000);
            }
        });
    }

    private void playCurrent() {
        if (playlist.isEmpty()) { finish(); return; }
        index = Math.max(0, Math.min(index, playlist.size() - 1));
        Channel c = playlist.get(index);
        tvName.setText(c.name);
        tvError.setVisibility(View.GONE);

        releasePlayer();
        // 关键：用 NextLib 的 RenderersFactory，让 MP2/AC-3 这类 Android 原生
        // 不支持的音频走 FFmpeg 软解，否则国内 IPTV 源一半频道没声音。
        RenderersFactory renderersFactory = new NextRenderersFactory(getApplicationContext())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        player = new ExoPlayer.Builder(this, renderersFactory).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                showError(describe(error));
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) tvError.setVisibility(View.GONE);
            }
        });
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private String describe(PlaybackException e) {
        String msg = e.getErrorCodeName();
        if (msg == null) msg = e.getMessage();
        return "播放失败：" + msg + "\n\n确定键重试 · 左右键换台 · 返回键退出";
    }

    private void showError(String text) {
        tvError.setText(text);
        tvError.setVisibility(View.VISIBLE);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (index > 0) { index--; playCurrent(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (index < playlist.size() - 1) { index++; playCurrent(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (tvError.getVisibility() == View.VISIBLE) { playCurrent(); return true; }
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
    }
}
