package com.hermes.tvlive;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_OPEN_FILE = 1001;

    private RecyclerView rvGroups, rvChannels;
    private TextView tvStatus, tvSource;
    private SourceStore store;

    private final List<String> groups = new ArrayList<>();
    private final Map<String, List<Channel>> byGroup = new LinkedHashMap<>();
    private List<Channel> allChannels = new ArrayList<>();

    private int currentGroup = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new SourceStore(this);

        rvGroups = findViewById(R.id.rv_groups);
        rvChannels = findViewById(R.id.rv_channels);
        tvStatus = findViewById(R.id.tv_status);
        tvSource = findViewById(R.id.tv_source);

        rvGroups.setLayoutManager(new LinearLayoutManager(this));
        rvChannels.setLayoutManager(new GridLayoutManager(this, 5));
        rvChannels.setItemAnimator(null);

        findViewById(R.id.btn_menu).setOnClickListener(v -> showSourceDialog());

        loadCurrentSource();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allChannels.isEmpty()) loadCurrentSource();
    }

    // ---------- 源加载 ----------

    private void loadCurrentSource() {
        List<SourceStore.Source> sources = store.getSources();
        int idx = Math.min(store.getCurrent(), sources.size() - 1);
        if (idx < 0) idx = 0;
        final int fi = idx;
        String name = sources.get(fi).name;
        tvSource.setText(name);
        tvStatus.setText("正在加载…");

        new Thread(() -> {
            String body = store.fetch(fi);
            final List<Channel> parsed = body == null ? new ArrayList<>() : PlaylistParser.parse(body);
            ui.post(() -> applyChannels(parsed, body == null));
        }).start();
    }

    private void applyChannels(List<Channel> parsed, boolean failed) {
        allChannels = parsed;
        groups.clear();
        byGroup.clear();
        for (Channel c : parsed) {
            if (!byGroup.containsKey(c.group)) {
                byGroup.put(c.group, new ArrayList<>());
                groups.add(c.group);
            }
            byGroup.get(c.group).add(c);
        }
        if (groups.isEmpty()) {
            tvStatus.setText(failed
                    ? "拉取失败，且没有缓存 —— 点右上角「切换直播源」检查地址"
                    : "这个源里没解析到频道 —— 点右上角「切换直播源」换一个");
            rvGroups.setAdapter(new GroupAdapter());
            rvChannels.setAdapter(new ChannelAdapter(new ArrayList<>()));
            return;
        }
        tvStatus.setText(parsed.size() + " 个频道 · " + groups.size() + " 个分组"
                + (failed ? "  ·  网络失败，显示的是缓存" : ""));
        currentGroup = 0;
        rvGroups.setAdapter(new GroupAdapter());
        selectGroup(0);
        ui.postDelayed(() -> {
            View first = rvChannels.getChildAt(0);
            if (first != null) first.requestFocus();
            else rvGroups.requestFocus();
        }, 250);
    }

    private void selectGroup(int i) {
        if (i < 0 || i >= groups.size()) return;
        currentGroup = i;
        List<Channel> list = byGroup.get(groups.get(i));
        rvChannels.setAdapter(new ChannelAdapter(list == null ? new ArrayList<>() : list));
        highlightGroup(i);
    }

    /** 只改可见子项的选中态，不重新绑定，避免焦点跳动。 */
    private void highlightGroup(int pos) {
        for (int i = 0; i < rvGroups.getChildCount(); i++) {
            View v = rvGroups.getChildAt(i);
            if (v == null) continue;
            int p = rvGroups.getChildAdapterPosition(v);
            View ind = v.findViewById(R.id.indicator);
            TextView name = v.findViewById(R.id.tv_group);
            if (ind != null) ind.setVisibility(p == pos ? View.VISIBLE : View.INVISIBLE);
            if (name != null) name.setTextColor(p == pos ? 0xFFFFFFFF : 0xFFC8D0E0);
        }
    }

    private void play(Channel c) {
        List<Channel> list = byGroup.get(groups.get(currentGroup));
        if (list != null) {
            PlayerActivity.playlist = list;
            PlayerActivity.index = Math.max(0, list.indexOf(c));
        }
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("url", c.url);
        it.putExtra("name", c.name);
        startActivity(it);
    }

    // ---------- 源管理 ----------

    private void showSourceDialog() {
        List<SourceStore.Source> sources = store.getSources();
        String[] items = new String[sources.size() + 3];
        for (int i = 0; i < sources.size(); i++) {
            items[i] = (i == store.getCurrent() ? "● " : "   ") + sources.get(i).name;
        }
        items[sources.size()] = "＋  添加订阅地址（m3u / txt）";
        items[sources.size() + 1] = "📁  从本地文件导入（m3u / txt）";
        items[sources.size() + 2] = "✎  编辑当前源";

        new AlertDialog.Builder(this)
                .setTitle("直播源")
                .setItems(items, (d, which) -> {
                    if (which < sources.size()) {
                        store.setCurrent(which);
                        loadCurrentSource();
                    } else if (which == sources.size()) {
                        showAddUrlDialog();
                    } else if (which == sources.size() + 1) {
                        pickFile();
                    } else {
                        showEditDialog(store.getCurrent());
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAddUrlDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_source, null);
        EditText etName = v.findViewById(R.id.et_name);
        EditText etUrl = v.findViewById(R.id.et_url);
        etUrl.setHint("https://example.com/live.m3u  或  .../live.txt");

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("添加直播源")
                .setView(v)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String name = etName.getText().toString().trim();
            String url = etUrl.getText().toString().trim();
            if (TextUtils.isEmpty(url)) { etUrl.setError("填地址"); return; }
            if (TextUtils.isEmpty(name)) name = url.replaceAll(".*/", "");
            store.addSource(name, url);
            store.setCurrent(store.getSources().size() - 1);
            dlg.dismiss();
            loadCurrentSource();
        }));
        dlg.show();
    }

    private void showEditDialog(int idx) {
        List<SourceStore.Source> sources = store.getSources();
        if (idx < 0 || idx >= sources.size()) return;
        SourceStore.Source s = sources.get(idx);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_source, null);
        EditText etName = v.findViewById(R.id.et_name);
        EditText etUrl = v.findViewById(R.id.et_url);
        etName.setText(s.name);
        etUrl.setText(s.url);

        new AlertDialog.Builder(this)
                .setTitle("编辑源")
                .setView(v)
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) return;
                    if (TextUtils.isEmpty(name)) name = url;
                    store.updateSource(idx, name, url);
                    loadCurrentSource();
                })
                .setNeutralButton("删除", (d, w) -> {
                    store.removeSource(idx);
                    loadCurrentSource();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_OPEN_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "这个设备没有文件选择器，请用订阅地址", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_OPEN_FILE || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            List<Channel> parsed = PlaylistParser.parse(sb.toString());
            if (parsed.isEmpty()) {
                Toast.makeText(this, "文件里没解析到频道", Toast.LENGTH_LONG).show();
                return;
            }
            store.addSource("本地文件 · " + parsed.size() + " 台", uri.toString());
            store.setCurrent(store.getSources().size() - 1);
            applyChannels(parsed, false);
            Toast.makeText(this, "已导入 " + parsed.size() + " 个频道", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 适配器 ----------

    private class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_group, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            String g = groups.get(pos);
            int count = byGroup.get(g) == null ? 0 : byGroup.get(g).size();
            h.tvGroup.setText(g);
            h.tvCount.setText(String.valueOf(count));
            h.indicator.setVisibility(pos == currentGroup ? View.VISIBLE : View.INVISIBLE);
            h.tvGroup.setTextColor(pos == currentGroup ? 0xFFFFFFFF : 0xFFC8D0E0);
            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION) selectGroup(p);
            });
            h.itemView.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    int p = h.getAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) selectGroup(p);
                }
            });
        }
        @Override public int getItemCount() { return groups.size(); }
        class VH extends RecyclerView.ViewHolder {
            View indicator; TextView tvGroup, tvCount;
            VH(View v) {
                super(v);
                indicator = v.findViewById(R.id.indicator);
                tvGroup = v.findViewById(R.id.tv_group);
                tvCount = v.findViewById(R.id.tv_count);
            }
        }
    }

    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {
        private final List<Channel> data;
        ChannelAdapter(List<Channel> data) { this.data = data; }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Channel c = data.get(pos);
            h.tvName.setText(c.name);
            if (c.logo != null && !c.logo.isEmpty()) {
                h.ivLogo.setVisibility(View.VISIBLE);
                ImageLoader.get().load(c.logo, h.ivLogo);
            } else {
                h.ivLogo.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> play(c));
            h.itemView.setOnFocusChangeListener((v, has) -> {
                h.card.animate()
                        .scaleX(has ? 1.06f : 1f)
                        .scaleY(has ? 1.06f : 1f)
                        .setDuration(140)
                        .start();
                v.setZ(has ? 20f : 0f);
            });
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            View card; ImageView ivLogo; TextView tvName;
            VH(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                ivLogo = v.findViewById(R.id.iv_logo);
                tvName = v.findViewById(R.id.tv_name);
            }
        }
    }
}
