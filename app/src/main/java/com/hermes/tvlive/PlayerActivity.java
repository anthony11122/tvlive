package com.hermes.tvlive;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 酷9 风格的直播主界面：
 * 打开就是画面，OK 键呼出半透明频道面板，面板外上下键直接换台，数字键跳台。
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private LinearLayout panel, guide, osd;
    private TextView tvOsdName, tvOsdNum, tvOsdTime, tvError, tvDigit, tvHint;
    private RecyclerView rvGroups, rvChannels;

    private SourceStore store;
    private ExoPlayer player;

    private final List<Channel> all = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final Map<String, List<Channel>> byGroup = new LinkedHashMap<>();

    private int currentIndex = 0;
    private int currentGroup = 0;
    private boolean panelOpen = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder digitBuf = new StringBuilder();
    private final Runnable digitCommit = this::commitDigits;
    private final Runnable fadeOsd = () ->
            osd.animate().alpha(0f).setDuration(500)
                    .withEndAction(() -> osd.setVisibility(View.GONE)).start();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        store = new SourceStore(this);

        playerView = findViewById(R.id.player_view);
        panel = findViewById(R.id.panel);
        guide = findViewById(R.id.guide);
        osd = findViewById(R.id.osd);
        tvOsdName = findViewById(R.id.tv_osd_name);
        tvOsdNum = findViewById(R.id.tv_osd_num);
        tvOsdTime = findViewById(R.id.tv_osd_time);
        tvError = findViewById(R.id.tv_error);
        tvDigit = findViewById(R.id.tv_digit);
        tvHint = findViewById(R.id.tv_hint);
        rvGroups = findViewById(R.id.rv_groups);
        rvChannels = findViewById(R.id.rv_channels);

        rvGroups.setLayoutManager(new LinearLayoutManager(this));
        rvChannels.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        loadPlaylist();
        startClock();
    }

    // ---------- 源加载 ----------

    private void loadPlaylist() {
        int idx = store.getCurrent();
        new Thread(() -> {
            String body = store.fetch(idx);
            final List<Channel> parsed = body == null ? new ArrayList<>() : PlaylistParser.parse(body);
            ui.post(() -> applyPlaylist(parsed));
        }).start();
    }

    private void applyPlaylist(List<Channel> parsed) {
        all.clear();
        groups.clear();
        byGroup.clear();
        all.addAll(parsed);
        for (Channel c : parsed) {
            if (!byGroup.containsKey(c.group)) {
                byGroup.put(c.group, new ArrayList<>());
                groups.add(c.group);
            }
            byGroup.get(c.group).add(c);
        }
        if (all.isEmpty()) {
            guide.setVisibility(View.VISIBLE);
            panel.setVisibility(View.GONE);
            return;
        }
        guide.setVisibility(View.GONE);

        String last = getSharedPreferences("tvlive", MODE_PRIVATE).getString("last_url", null);
        int start = 0;
        if (last != null) {
            for (int i = 0; i < all.size(); i++) {
                if (last.equals(all.get(i).url)) { start = i; break; }
            }
        }
        playIndex(start, true);

        EpgManager.get(this).loadIfNeeded(ok -> {
            if (ok && rvChannels.getAdapter() != null) rvChannels.getAdapter().notifyDataSetChanged();
        });
    }

    // ---------- 播放 ----------

    private void playIndex(int idx, boolean updatePanel) {
        if (all.isEmpty()) return;
        currentIndex = Math.max(0, Math.min(idx, all.size() - 1));
        Channel c = all.get(currentIndex);
        currentGroup = Math.max(0, groups.indexOf(c.group));
        getSharedPreferences("tvlive", MODE_PRIVATE).edit().putString("last_url", c.url).apply();

        tvError.setVisibility(View.GONE);
        releasePlayer();
        RenderersFactory rf = new NextRenderersFactory(getApplicationContext())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        player = new ExoPlayer.Builder(this, rf).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                String msg = error.getErrorCodeName();
                tvError.setText("播放失败：" + msg + "\n\nOK 重试 · ↑↓ 换台 · 菜单 设置");
                tvError.setVisibility(View.VISIBLE);
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) tvError.setVisibility(View.GONE);
            }
        });
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.setPlayWhenReady(true);
        player.prepare();

        updateOsd(c);
        if (updatePanel) refreshChannelList();
    }

    private void updateOsd(Channel c) {
        tvOsdName.setText(c.name);
        tvOsdNum.setText(String.format(Locale.US, "%02d", currentIndex + 1));
        tvOsdTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        osd.setVisibility(View.VISIBLE);
        osd.setAlpha(1f);
        ui.removeCallbacks(fadeOsd);
        ui.postDelayed(fadeOsd, 4000);
    }

    private void startClock() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (osd.getVisibility() == View.VISIBLE && osd.getAlpha() > 0.9f) {
                    tvOsdTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                }
                ui.postDelayed(this, 30000);
            }
        });
    }

    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
    }

    // ---------- 频道面板 ----------

    private void openPanel() {
        if (all.isEmpty()) { startActivity(new Intent(this, SettingsActivity.class)); return; }
        panelOpen = true;
        panel.setVisibility(View.VISIBLE);
        tvHint.setVisibility(View.VISIBLE);
        rvGroups.setAdapter(new GroupAdapter());
        refreshChannelList();
        ui.post(() -> {
            rvChannels.requestFocus();
            scrollToCurrent();
        });
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        tvHint.setVisibility(View.GONE);
    }

    private void refreshChannelList() {
        if (groups.isEmpty()) return;
        List<Channel> list = byGroup.get(groups.get(currentGroup));
        rvChannels.setAdapter(new ChannelAdapter(list == null ? new ArrayList<>() : list));
        if (rvGroups.getAdapter() != null) rvGroups.getAdapter().notifyDataSetChanged();
    }

    private void selectGroup(int g) {
        if (g < 0 || g >= groups.size()) return;
        currentGroup = g;
        refreshChannelList();
    }

    private void scrollToCurrent() {
        if (groups.isEmpty()) return;
        List<Channel> list = byGroup.get(groups.get(currentGroup));
        if (list == null) return;
        int pos = list.indexOf(all.get(currentIndex));
        if (pos < 0) return;
        rvChannels.scrollToPosition(pos);
        ui.postDelayed(() -> {
            View v = rvChannels.getLayoutManager() != null
                    ? rvChannels.getLayoutManager().findViewByPosition(pos) : null;
            if (v != null) v.requestFocus();
        }, 80);
    }

    // ---------- 按键 ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 && !panelOpen) {
            appendDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (panelOpen) return super.onKeyDown(keyCode, event);
                if (tvError.getVisibility() == View.VISIBLE) { playIndex(currentIndex, false); return true; }
                openPanel();
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (panelOpen) return super.onKeyDown(keyCode, event);
                if (currentIndex > 0) { playIndex(currentIndex - 1, false); return true; }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (panelOpen) return super.onKeyDown(keyCode, event);
                if (currentIndex < all.size() - 1) { playIndex(currentIndex + 1, false); return true; }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (panelOpen) { selectGroup(currentGroup - 1); return true; }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (panelOpen) { selectGroup(currentGroup + 1); return true; }
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (panelOpen) { closePanel(); return true; }
                finish();
                return true;

            case KeyEvent.KEYCODE_MENU:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- 数字键跳台 ----------

    private void appendDigit(int d) {
        if (digitBuf.length() >= 3) digitBuf.setLength(0);
        digitBuf.append(d);
        tvDigit.setText(digitBuf.toString());
        tvDigit.setVisibility(View.VISIBLE);
        ui.removeCallbacks(digitCommit);
        ui.postDelayed(digitCommit, 1500);
    }

    private void commitDigits() {
        tvDigit.setVisibility(View.GONE);
        if (digitBuf.length() == 0) return;
        int n;
        try { n = Integer.parseInt(digitBuf.toString()); } catch (Exception e) { digitBuf.setLength(0); return; }
        digitBuf.setLength(0);
        if (n >= 1 && n <= all.size()) playIndex(n - 1, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (all.isEmpty()) loadPlaylist();
        else if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    // ---------- 适配器 ----------

    private class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_group_row, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            String g = groups.get(pos);
            h.tvGroup.setText(g);
            int count = byGroup.get(g) == null ? 0 : byGroup.get(g).size();
            h.tvCount.setText(String.valueOf(count));
            boolean sel = pos == currentGroup;
            h.tvGroup.setTextColor(sel ? 0xFFFFFFFF : 0xFF98A4B8);
            h.itemView.setAlpha(sel ? 1f : 0.85f);
            h.itemView.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    int p = h.getAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) selectGroup(p);
                }
            });
            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION) selectGroup(p);
            });
        }
        @Override public int getItemCount() { return groups.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvGroup, tvCount;
            VH(View v) { super(v); tvGroup = v.findViewById(R.id.tv_group); tvCount = v.findViewById(R.id.tv_count); }
        }
    }

    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {
        private final List<Channel> data;
        ChannelAdapter(List<Channel> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_row, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Channel c = data.get(pos);
            final int globalIdx = all.indexOf(c);
            h.tvIndex.setText(String.format(Locale.US, "%02d", globalIdx + 1));
            h.tvName.setText(c.name);
            h.tvNow.setText(EpgManager.get(PlayerActivity.this).currentTitle(c.name));
            boolean isCur = globalIdx == currentIndex;
            h.tvIndex.setTextColor(isCur ? 0xFF4C8DFF : 0xFF5A6478);
            h.tvName.setTextColor(isCur ? 0xFFFFFFFF : 0xFFB9C4D6);
            h.itemView.setOnClickListener(v -> {
                playIndex(globalIdx, false);
                closePanel();
            });
            h.itemView.setOnFocusChangeListener((v, has) -> {
                if (has) h.tvName.setTextColor(0xFFFFFFFF);
                else h.tvName.setTextColor(isCur ? 0xFFFFFFFF : 0xFFB9C4D6);
            });
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvIndex, tvName, tvNow;
            VH(View v) {
                super(v);
                tvIndex = v.findViewById(R.id.tv_index);
                tvName = v.findViewById(R.id.tv_name);
                tvNow = v.findViewById(R.id.tv_now);
            }
        }
    }
}
