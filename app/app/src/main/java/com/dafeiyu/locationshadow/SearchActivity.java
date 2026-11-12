package com.dafeiyu.locationshadow;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchActivity extends Activity {
    private static final String ENDPOINT = "https://nominatim.openstreetmap.org/search";
    private static final String PHOTON_ENDPOINT = "https://photon.komoot.io/api/";
    private final List<SearchResult> results = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText queryInput;
    private Button searchButton;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout recentContainer;
    private ResultAdapter adapter;
    private int requestGeneration;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(10));
        root.setBackgroundColor(Color.WHITE);

        TextView title = text("搜索地点", 24, Color.rgb(21, 101, 192));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        queryInput = new EditText(this);
        queryInput.setHint("输入城市、道路或地点名称");
        queryInput.setSingleLine(true);
        queryInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        queryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP)) {
                startSearch();
                return true;
            }
            return false;
        });
        root.addView(queryInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchButton = new Button(this);
        searchButton.setText("搜索 OpenStreetMap");
        searchButton.setOnClickListener(v -> startSearch());
        root.addView(searchButton);

        recentContainer = new LinearLayout(this);
        recentContainer.setOrientation(LinearLayout.VERTICAL);
        recentContainer.setVisibility(View.GONE);
        root.addView(recentContainer);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        status = text("最多显示 8 条结果，点击条目返回主地图。", 14, Color.DKGRAY);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        adapter = new ResultAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> select(results.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView note = text("数据：OpenStreetMap / Nominatim；网络失败时自动使用 Photon (Komoot) 开放服务。结果为 WGS84，未作 GCJ-02 等不可验证的强制转换。中国大陆底图或地址可能偏移，请返回主图核对并点击地图手动微调。",
                12, Color.GRAY);
        note.setPadding(0, dp(8), 0, 0);
        root.addView(note);
        setContentView(root);
        refreshRecentSearches();

        if (savedInstanceState != null) {
            queryInput.setText(savedInstanceState.getString("query", ""));
            restoreResults(savedInstanceState.getString("results", "[]"));
        }
    }

    private void startSearch() {
        final String query = queryInput.getText().toString().trim();
        if (query.length() == 0) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        LocationStore.recordSearch(this, query);
        refreshRecentSearches();
        final int generation = ++requestGeneration;
        searchButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("正在搜索…");
        executor.execute(() -> {
            SearchOutcome outcome = performSearch(query);
            runOnUiThread(() -> {
                if (destroyed || generation != requestGeneration) return;
                progress.setVisibility(View.GONE);
                searchButton.setEnabled(true);
                if (outcome.error != null) {
                    status.setText(outcome.error);
                    return;
                }
                results.clear();
                results.addAll(outcome.results);
                adapter.notifyDataSetChanged();
                status.setText(results.isEmpty()
                        ? "未找到相关地址（来源：" + outcome.source + "），请更换关键词"
                        : "找到 " + results.size() + " 条结果（来源：" + outcome.source + "），点击使用");
            });
        });
    }

    private void refreshRecentSearches() {
        recentContainer.removeAllViews();
        List<String> recent = LocationStore.getRecentSearches(this);
        recentContainer.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        if (recent.isEmpty()) return;
        TextView label = text("最近搜索（点击重用）", 13, Color.GRAY);
        recentContainer.addView(label);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String keyword : recent) {
            Button button = new Button(this);
            button.setText(keyword);
            button.setSingleLine(true);
            button.setEllipsize(android.text.TextUtils.TruncateAt.END);
            button.setMaxWidth(dp(240));
            button.setOnClickListener(v -> {
                queryInput.setText(keyword);
                queryInput.setSelection(keyword.length());
                startSearch();
            });
            row.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("清空搜索历史")
                .setMessage("确定清空全部最近搜索关键词吗？位置历史不受影响。")
                .setPositiveButton("清空", (dialog, which) -> {
                    LocationStore.clearSearchHistory(this);
                    refreshRecentSearches();
                })
                .setNegativeButton("取消", null)
                .show());
        row.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        recentContainer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private SearchOutcome performSearch(String query) {
        SearchOutcome primary = searchNominatim(query);
        if (primary.error == null) return primary;
        SearchOutcome fallback = searchPhoton(query);
        if (fallback.error == null) return fallback;
        return SearchOutcome.error("两路搜索均失败。Nominatim：" + primary.error
                + "；Photon：" + fallback.error);
    }

    private SearchOutcome searchNominatim(String query) {
        HttpURLConnection connection = null;
        try {
            String url = ENDPOINT + "?format=json&limit=8&accept-language=zh-CN&q="
                    + URLEncoder.encode(query, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent",
                    "LocationShadow/1.2 (" + getPackageName() + ")");
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return SearchOutcome.error("搜索服务返回 HTTP " + code + "，请稍后重试");
            }
            String body = readBody(connection.getInputStream());
            JSONArray array = new JSONArray(body);
            List<SearchResult> found = new ArrayList<>();
            for (int i = 0; i < array.length() && found.size() < 8; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                try {
                    double lat = Double.parseDouble(object.getString("lat"));
                    double lng = Double.parseDouble(object.getString("lon"));
                    String name = object.optString("display_name", "未命名地点").trim();
                    if (!Double.isFinite(lat) || !Double.isFinite(lng)
                            || lat < -90 || lat > 90 || lng < -180 || lng > 180) continue;
                    if (name.length() == 0) name = "未命名地点";
                    found.add(new SearchResult(name, lat, lng));
                } catch (Exception ignored) {
                    // Skip malformed individual results without discarding valid ones.
                }
            }
            return SearchOutcome.success(found, "OpenStreetMap / Nominatim");
        } catch (SocketTimeoutException e) {
            return SearchOutcome.error("搜索超时，请检查网络后重试");
        } catch (Exception e) {
            return SearchOutcome.error("网络异常或服务暂不可用：" + safeMessage(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private SearchOutcome searchPhoton(String query) {
        HttpURLConnection connection = null;
        try {
            String url = PHOTON_ENDPOINT + "?limit=8&q=" + URLEncoder.encode(query, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent",
                    "LocationShadow/1.2 (" + getPackageName() + ")");
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return SearchOutcome.error("服务返回 HTTP " + code);
            }
            JSONObject root = new JSONObject(readBody(connection.getInputStream()));
            JSONArray features = root.optJSONArray("features");
            List<SearchResult> found = new ArrayList<>();
            if (features == null) return SearchOutcome.success(found, "Photon (Komoot)");
            for (int i = 0; i < features.length() && found.size() < 8; i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) continue;
                JSONObject geometry = feature.optJSONObject("geometry");
                JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
                if (coordinates == null || coordinates.length() < 2) continue;
                double lng = coordinates.optDouble(0, Double.NaN);
                double lat = coordinates.optDouble(1, Double.NaN);
                if (!Double.isFinite(lat) || !Double.isFinite(lng)
                        || lat < -90 || lat > 90 || lng < -180 || lng > 180) continue;
                JSONObject properties = feature.optJSONObject("properties");
                String name = buildPhotonName(properties);
                found.add(new SearchResult(name, lat, lng));
            }
            return SearchOutcome.success(found, "Photon (Komoot)");
        } catch (SocketTimeoutException e) {
            return SearchOutcome.error("搜索超时");
        } catch (Exception e) {
            return SearchOutcome.error("网络异常或服务暂不可用：" + safeMessage(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String buildPhotonName(JSONObject properties) {
        StringBuilder name = new StringBuilder();
        if (properties != null) {
            String[] keys = {"name", "state", "city", "district", "street", "country"};
            for (String key : keys) {
                String part = properties.optString(key, "").trim();
                if (part.length() == 0) continue;
                boolean duplicate = false;
                String[] existingParts = name.toString().split("，");
                for (String existing : existingParts) {
                    if (existing.equalsIgnoreCase(part)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;
                if (name.length() > 0) name.append('，');
                name.append(part);
            }
        }
        return name.length() == 0 ? "未命名地点" : name.toString();
    }

    private String readBody(InputStream stream) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int total = 0;
            int count;
            while ((count = reader.read(buffer)) != -1) {
                total += count;
                if (total > 2_000_000) throw new Exception("响应过大");
                body.append(buffer, 0, count);
            }
        }
        return body.toString();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private void select(SearchResult result) {
        Intent data = new Intent();
        data.putExtra("name", result.name);
        data.putExtra("lat", result.lat);
        data.putExtra("lng", result.lng);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("query", queryInput.getText().toString());
        JSONArray array = new JSONArray();
        for (SearchResult result : results) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", result.name);
                object.put("lat", result.lat);
                object.put("lng", result.lng);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        outState.putString("results", array.toString());
        super.onSaveInstanceState(outState);
    }

    private void restoreResults(String json) {
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                double lat = object.optDouble("lat", Double.NaN);
                double lng = object.optDouble("lng", Double.NaN);
                if (!Double.isFinite(lat) || !Double.isFinite(lng)) continue;
                results.add(new SearchResult(object.optString("name", "未命名地点"), lat, lng));
            }
            adapter.notifyDataSetChanged();
            if (!results.isEmpty()) status.setText("已恢复 " + results.size() + " 条结果，点击使用");
        } catch (Exception ignored) {
            results.clear();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        requestGeneration++;
        executor.shutdownNow();
        super.onDestroy();
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

    private static final class SearchResult {
        final String name;
        final double lat;
        final double lng;

        SearchResult(String name, double lat, double lng) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private static final class SearchOutcome {
        final List<SearchResult> results;
        final String error;
        final String source;

        private SearchOutcome(List<SearchResult> results, String error, String source) {
            this.results = results;
            this.error = error;
            this.source = source;
        }

        static SearchOutcome success(List<SearchResult> results, String source) {
            return new SearchOutcome(results, null, source);
        }

        static SearchOutcome error(String message) {
            return new SearchOutcome(null, message, null);
        }
    }

    private final class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public SearchResult getItem(int position) { return results.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view;
            if (convertView instanceof TextView) {
                view = (TextView) convertView;
            } else {
                view = text("", 16, Color.BLACK);
                view.setPadding(dp(12), dp(16), dp(12), dp(16));
            }
            SearchResult result = getItem(position);
            view.setText(result.name + "\n" + String.format(java.util.Locale.US,
                    "WGS84  %.7f, %.7f", result.lat, result.lng));
            return view;
        }
    }
}
