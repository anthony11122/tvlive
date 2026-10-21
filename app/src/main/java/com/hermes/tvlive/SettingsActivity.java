package com.hermes.tvlive;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 源管理 + EPG 配置。 */
public class SettingsActivity extends AppCompatActivity {

    private SourceStore store;
    private RecyclerView rvSources;
    private TextView tvEpg, tvEpgStatus, tvAbout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new SourceStore(this);

        rvSources = findViewById(R.id.rv_sources);
        tvEpg = findViewById(R.id.tv_epg);
        tvEpgStatus = findViewById(R.id.tv_epg_status);
        tvAbout = findViewById(R.id.tv_about);
        rvSources.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_add_source).setOnClickListener(v -> addSource());
        findViewById(R.id.btn_epg_edit).setOnClickListener(v -> editEpg());
        findViewById(R.id.btn_epg_refresh).setOnClickListener(v -> refreshEpg());

        tvAbout.setText("TvLive " + BuildConfig.VERSION_NAME
                + "\n支持 M3U / M3U8 / TXT 直播源"
                + "\n音频解码：平台硬解 + FFmpeg 软解（MP2 / AC-3）"
                + "\n返回键回到播放");
    }

    @Override
    protected void onResume() {
        super.onResume();
        rvSources.setAdapter(new SourceAdapter(store.getSources()));
        updateEpgText();
    }

    private void updateEpgText() {
        EpgManager e = EpgManager.get(this);
        tvEpg.setText(e.getUrl());
        boolean ready = e.isReady();
        tvEpgStatus.setText(ready ? "节目单已加载，列表里会显示当前节目" : "节目单未加载，点「立即刷新」");
    }

    // ---------- 源管理 ----------

    private void addSource() {
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
            onResume();
            Toast.makeText(this, "已添加，返回即可播放", Toast.LENGTH_SHORT).show();
        }));
        dlg.show();
    }

    private void editSource(int idx) {
        List<SourceStore.Source> list = store.getSources();
        if (idx < 0 || idx >= list.size()) return;
        SourceStore.Source s = list.get(idx);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_source, null);
        EditText etName = v.findViewById(R.id.et_name);
        EditText etUrl = v.findViewById(R.id.et_url);
        etName.setText(s.name);
        etUrl.setText(s.url);

        new AlertDialog.Builder(this)
                .setTitle("编辑直播源")
                .setView(v)
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) return;
                    if (TextUtils.isEmpty(name)) name = url;
                    store.updateSource(idx, name, url);
                    onResume();
                })
                .setNeutralButton("删除", (d, w) -> {
                    store.removeSource(idx);
                    onResume();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- EPG ----------

    private void editEpg() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_source, null);
        EditText etName = v.findViewById(R.id.et_name);
        EditText etUrl = v.findViewById(R.id.et_url);
        etName.setVisibility(View.GONE);
        etUrl.setText(EpgManager.get(this).getUrl());
        etUrl.setHint(EpgManager.DEFAULT_EPG);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("EPG 节目单地址（XMLTV）")
                .setView(v)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (d, w) -> {
                    EpgManager.get(this).setUrl(EpgManager.DEFAULT_EPG);
                    onResume();
                })
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String url = etUrl.getText().toString().trim();
            if (TextUtils.isEmpty(url)) url = EpgManager.DEFAULT_EPG;
            EpgManager.get(this).setUrl(url);
            dlg.dismiss();
            refreshEpg();
        }));
        dlg.show();
    }

    private void refreshEpg() {
        tvEpgStatus.setText("正在下载并解析…");
        EpgManager.get(this).forceRefresh(ok -> {
            tvEpgStatus.setText(ok ? "节目单已更新" : "下载失败，检查地址或网络");
            Toast.makeText(this, ok ? "节目单已更新" : "节目单下载失败", Toast.LENGTH_SHORT).show();
        });
    }

    // ---------- 适配器 ----------

    private class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.VH> {
        private final List<SourceStore.Source> data;
        SourceAdapter(List<SourceStore.Source> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_source, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            SourceStore.Source s = data.get(pos);
            boolean cur = pos == store.getCurrent();
            h.tvName.setText((cur ? "● " : "   ") + s.name);
            h.tvName.setTextColor(cur ? 0xFF4C8DFF : 0xFFE8EDF5);
            h.tvUrl.setText(s.url);
            h.itemView.setOnClickListener(v -> {
                store.setCurrent(pos);
                notifyDataSetChanged();
                Toast.makeText(SettingsActivity.this, "已切换到：" + s.name, Toast.LENGTH_SHORT).show();
            });
            h.itemView.setOnLongClickListener(v -> { editSource(pos); return true; });
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvUrl;
            VH(View v) { super(v); tvName = v.findViewById(R.id.tv_name); tvUrl = v.findViewById(R.id.tv_url); }
        }
    }
}
