package com.dafeiyu.locationshadow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends Activity {
    private final List<LocationStore.Entry> shown = new ArrayList<>();
    private HistoryAdapter adapter;
    private TextView emptyView;
    private Button allButton;
    private Button favoritesButton;
    private boolean favoritesOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocationStore.cleanupExpired(this);
        favoritesOnly = savedInstanceState != null
                && savedInstanceState.getBoolean("favoritesOnly", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.setBackgroundColor(Color.WHITE);

        TextView title = text("位置历史", 24, Color.rgb(21, 101, 192));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text("点击使用位置；长按可收藏、重命名或删除。全部坐标均为 WGS84。", 14, Color.DKGRAY);
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        allButton = new Button(this);
        allButton.setText("全部");
        allButton.setOnClickListener(v -> {
            favoritesOnly = false;
            reload();
        });
        favoritesButton = new Button(this);
        favoritesButton.setText("仅收藏");
        favoritesButton.setOnClickListener(v -> {
            favoritesOnly = true;
            reload();
        });
        filters.addView(allButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        filters.addView(favoritesButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(filters);

        Button clearButton = new Button(this);
        clearButton.setText("清空全部历史");
        clearButton.setOnClickListener(v -> confirmClearAll());
        root.addView(clearButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        emptyView = text("暂无历史位置", 16, Color.GRAY);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(12), dp(32), dp(12), dp(32));

        adapter = new HistoryAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> useEntry(shown.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showEntryActions(shown.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        list.setEmptyView(emptyView);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) reload();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("favoritesOnly", favoritesOnly);
        super.onSaveInstanceState(outState);
    }

    private void reload() {
        shown.clear();
        for (LocationStore.Entry entry : LocationStore.getAll(this)) {
            if (!favoritesOnly || entry.favorite) shown.add(entry);
        }
        allButton.setEnabled(favoritesOnly);
        favoritesButton.setEnabled(!favoritesOnly);
        emptyView.setText(favoritesOnly ? "暂无收藏位置，长按历史条目即可收藏" : "暂无历史位置");
        adapter.notifyDataSetChanged();
    }

    private void useEntry(LocationStore.Entry entry) {
        Intent result = new Intent();
        result.putExtra("name", entry.name);
        result.putExtra("lat", entry.lat);
        result.putExtra("lng", entry.lng);
        result.putExtra("altitude", entry.altitude);
        setResult(RESULT_OK, result);
        finish();
    }

    private void showEntryActions(LocationStore.Entry entry) {
        String favoriteAction = entry.favorite ? "取消收藏" : "收藏";
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(new String[]{favoriteAction, "重命名", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        boolean success = LocationStore.setFavorite(this, entry, !entry.favorite);
                        Toast.makeText(this, success ? "已更新收藏" : "条目已变化，请重试",
                                Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        showRenameDialog(entry);
                        return;
                    } else {
                        confirmDelete(entry);
                        return;
                    }
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameDialog(LocationStore.Entry entry) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(entry.name);
        input.setSelectAllOnFocus(true);
        int padding = dp(20);
        input.setPadding(padding, dp(8), padding, dp(8));
        new AlertDialog.Builder(this)
                .setTitle("重命名位置")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.length() == 0) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean success = LocationStore.rename(this, entry, name);
                    Toast.makeText(this, success ? "已重命名" : "条目已变化，请重试",
                            Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部历史")
                .setMessage("将删除全部位置历史和收藏，且无法恢复。搜索关键词历史不受影响。确定继续吗？")
                .setPositiveButton("全部清空", (dialog, which) -> {
                    LocationStore.clearAll(this);
                    reload();
                    Toast.makeText(this, "已清空全部历史", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(LocationStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除位置")
                .setMessage("确定删除“" + entry.name + "”吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    LocationStore.delete(this, entry);
                    reload();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public LocationStore.Entry getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(HistoryActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
            }
            LocationStore.Entry entry = getItem(position);
            TextView name = text((entry.favorite ? "★ " : "") + entry.name, 18, Color.BLACK);
            TextView coordinate = text(entry.coordinateText(), 14, Color.DKGRAY);
            String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(entry.lastUsed);
            TextView meta = text(String.format(Locale.US,
                    "海拔 %.1f m · 使用 %d 次\n%s", entry.altitude, entry.useCount, time),
                    13, Color.GRAY);
            coordinate.setPadding(0, dp(5), 0, dp(3));
            row.addView(name);
            row.addView(coordinate);
            row.addView(meta);
            return row;
        }
    }
}
