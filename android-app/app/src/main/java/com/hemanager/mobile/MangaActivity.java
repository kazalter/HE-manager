package com.hemanager.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import android.view.ViewGroup;

public class MangaActivity extends Activity {
    private static final int COLOR_BG = Color.BLACK;
    private static final int MODE_PAGE = 0;
    private static final int MODE_SCROLL = 1;
    private static final String PREF_READING_MODE = "viewer_reading_mode";

    private String serverUrl;
    private String token;
    private int id;
    private String mediaType;
    private int page = 0;
    private int totalPages = 1;
    private int loadGeneration = 0;
    private int lastSavedServerProgress = -1;
    private int readingMode = MODE_PAGE;
    private boolean restartFromBeginning = false;
    private boolean progressReady = false;
    private boolean restoringScroll = false;
    private static final int PREFETCH_AHEAD = 6;
    private static final int PREFETCH_BEHIND = 2;
    private final Set<Integer> prefetchInFlight = new HashSet<>();
    private final Map<Integer, Object> pageDownloadLocks = new ConcurrentHashMap<>();
    private LruCache<Integer, Bitmap> bitmapCache;

    private FrameLayout root;
    private ViewPager2 pager;
    private WebtoonZoomLayout zoomLayout;
    private RecyclerView continuousRecycler;
    private TextView pageIndicator;
    private TextView settingsButton;
    private LinearLayout modePanel;
    private TextView pageModeButton;
    private TextView scrollModeButton;
    private final Runnable hideControls = () -> {
        if (pageIndicator == null) return;
        pageIndicator.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    if (pageIndicator != null) pageIndicator.setVisibility(View.GONE);
                })
                .start();
        if (settingsButton != null) {
            settingsButton.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction(() -> {
                        if (settingsButton != null) settingsButton.setVisibility(View.GONE);
                    })
                    .start();
        }
        if (modePanel != null) {
            modePanel.animate()
                    .alpha(0f)
                    .setDuration(160)
                    .withEndAction(() -> {
                        if (modePanel != null) modePanel.setVisibility(View.GONE);
                    })
                    .start();
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);

        int cacheSize = (int) Math.max(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
        bitmapCache = new LruCache<Integer, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value == null ? 0 : value.getByteCount();
            }
        };

        serverUrl = ApiClient.trimSlash(getIntent().getStringExtra("server_url"));
        token = getIntent().getStringExtra("token");
        id = getIntent().getIntExtra("id", 0);
        mediaType = getIntent().getStringExtra("media_type");
        setTitle(getIntent().getStringExtra("title"));
        readingMode = getSharedPreferences("he_manager", MODE_PRIVATE).getInt(PREF_READING_MODE, MODE_PAGE);
        page = Math.max(0, getIntent().getIntExtra("progress", 0));
        lastSavedServerProgress = page;

        restartFromBeginning = getIntent().getBooleanExtra("restart", false);
        if (restartFromBeginning) {
            page = 0;
            lastSavedServerProgress = -1;
            getSharedPreferences("he_manager", MODE_PRIVATE)
                .edit()
                .remove("progress_" + id)
                .apply();
        }

        pager = new ViewPager2(this);
        pager.setBackgroundColor(COLOR_BG);
        pager.setAdapter(new MangaPagerAdapter());
        pager.setOffscreenPageLimit(2);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (page != position) {
                    page = position;
                    saveProgressToServer(false);
                    if (pageIndicator != null && pageIndicator.getVisibility() == View.VISIBLE) {
                        pageIndicator.setText((page + 1) + " / " + totalPages);
                    }
                }
                prefetchPagesAround(position);
            }
        });

        continuousRecycler = new RecyclerView(this);
        continuousRecycler.setBackgroundColor(COLOR_BG);
        LinearLayoutManager continuousLayout = new LinearLayoutManager(this);
        continuousLayout.setInitialPrefetchItemCount(4);
        continuousRecycler.setLayoutManager(continuousLayout);
        continuousRecycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        continuousRecycler.setItemAnimator(null);
        continuousRecycler.setItemViewCacheSize(8);
        continuousRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (Math.abs(dy) > 2) dismissControls();
                updatePageFromScroll();
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null) {
                    int last = lm.findLastVisibleItemPosition();
                    if (last != RecyclerView.NO_POSITION) {
                        prefetchPagesAround(last);
                    }
                }
            }
        });

        zoomLayout = new WebtoonZoomLayout(this);
        zoomLayout.setBackgroundColor(COLOR_BG);
        zoomLayout.setRecyclerView(continuousRecycler);
        zoomLayout.setOnSingleTapListener(() -> {
            updatePageFromScroll();
            showControls();
        });
        zoomLayout.addView(continuousRecycler, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        zoomLayout.setVisibility(View.GONE);

        root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        root.addView(pager, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(zoomLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        pageIndicator = new TextView(this);
        pageIndicator.setTextColor(Color.WHITE);
        pageIndicator.setTextSize(15f);
        pageIndicator.setGravity(Gravity.CENTER);
        pageIndicator.setPadding(dp(14), dp(7), dp(14), dp(7));
        pageIndicator.setAlpha(0f);
        pageIndicator.setVisibility(View.GONE);

        GradientDrawable indicatorBackground = new GradientDrawable();
        indicatorBackground.setColor(0xCC111318);
        indicatorBackground.setCornerRadius(dp(18));
        pageIndicator.setBackground(indicatorBackground);

        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        indicatorParams.topMargin = dp(28);
        root.addView(pageIndicator, indicatorParams);

        settingsButton = new TextView(this);
        settingsButton.setText("设置");
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setTextSize(13f);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setPadding(dp(12), dp(7), dp(12), dp(7));
        settingsButton.setAlpha(0f);
        settingsButton.setVisibility(View.GONE);
        GradientDrawable settingsBackground = new GradientDrawable();
        settingsBackground.setColor(0xCC111318);
        settingsBackground.setCornerRadius(dp(18));
        settingsButton.setBackground(settingsBackground);
        settingsButton.setOnClickListener(view -> toggleModePanel());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        settingsParams.topMargin = dp(28);
        settingsParams.rightMargin = dp(18);
        root.addView(settingsButton, settingsParams);

        modePanel = new LinearLayout(this);
        modePanel.setOrientation(LinearLayout.VERTICAL);
        modePanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        modePanel.setAlpha(0f);
        modePanel.setVisibility(View.GONE);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xF012151C);
        panelBackground.setCornerRadius(dp(8));
        panelBackground.setStroke(dp(1), 0x33FFFFFF);
        modePanel.setBackground(panelBackground);

        pageModeButton = modeButton("翻页阅读", MODE_PAGE);
        scrollModeButton = modeButton("纵向连续", MODE_SCROLL);
        modePanel.addView(pageModeButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollButtonParams.topMargin = dp(6);
        modePanel.addView(scrollModeButton, scrollButtonParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(136),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        panelParams.topMargin = dp(70);
        panelParams.rightMargin = dp(18);
        root.addView(modePanel, panelParams);

        setContentView(root);
        loadPageCount();
    }

    private void loadPageCount() {
        if (!"manga".equals(mediaType)) {
            totalPages = 1;
            renderReadingMode();
            return;
        }
        new AsyncTask<Void, Void, JSONObject>() {
            @Override
            protected JSONObject doInBackground(Void... voids) {
                JSONObject result = new JSONObject();
                try {
                    ApiClient client = new ApiClient(serverUrl, token);
                    if (!restartFromBeginning) {
                        JSONObject media = client.getJsonObject("/mobile/media/" + id);
                        result.put("progress", Math.max(0, media.optInt("progress", page)));
                    }
                    JSONObject pages = client.getJsonObject("/mobile/manga/" + id + "/pages");
                    result.put("total_pages", Math.max(1, pages.optInt("total_pages", 1)));
                } catch (Exception e) {
                    try {
                        result.put("total_pages", 1);
                        result.put("progress", page);
                    } catch (Exception ignored) {
                    }
                }
                return result;
            }

            @Override
            protected void onPostExecute(JSONObject result) {
                totalPages = Math.max(1, result.optInt("total_pages", 1));
                if (!restartFromBeginning) {
                    page = Math.max(0, result.optInt("progress", page));
                    lastSavedServerProgress = page;
                }
                if (page >= totalPages) {
                    page = Math.max(0, totalPages - 1);
                }
                if (page < 0) page = 0;
                progressReady = true;
                if (pager != null && pager.getAdapter() != null) {
                    pager.getAdapter().notifyDataSetChanged();
                }
                renderReadingMode();
                if (restartFromBeginning) {
                    saveProgressToServer(true);
                }
            }
        }.execute();
    }

    private String pageUrl(int pageIndex, boolean trackProgress) {
        String path = "manga".equals(mediaType)
                ? "/mobile/manga/" + id + "/page/" + pageIndex
                : "/mobile/stream/" + id;
        String url = serverUrl + path + "?" + ApiClient.tokenQuery(token);
        if ("manga".equals(mediaType) && trackProgress) {
            url += "&track_progress=true";
        }
        return url;
    }

    private File cachedImageFile(String url, int pageIndex) throws Exception {
        File dir = new File(getCacheDir(), "viewer-images");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Cannot create image cache");
        }
        String key = "media_" + id + "_page_" + pageIndex + ".img";
        File file = new File(dir, key);
        if (file.exists() && file.length() > 0) return file;

        Object lock = pageDownloadLocks.computeIfAbsent(pageIndex, k -> new Object());
        synchronized (lock) {
            if (file.exists() && file.length() > 0) return file;

            File temp = new File(dir, key + "." + System.nanoTime() + ".tmp");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "image/*,*/*");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code);
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            } catch (Exception e) {
                if (temp.exists()) temp.delete();
                throw e;
            } finally {
                conn.disconnect();
            }

            if (file.exists() && file.length() > 0) {
                temp.delete();
                return file;
            }
            if (!temp.renameTo(file)) {
                if (file.exists() && file.length() > 0) {
                    temp.delete();
                    return file;
                }
                temp.delete();
                throw new RuntimeException("Cannot save image cache");
            }
            return file;
        }
    }

    private void renderReadingMode() {
        dismissControls();
        updateModeButtons();
        if (readingMode == MODE_SCROLL) {
            pager.setVisibility(View.GONE);
            zoomLayout.setVisibility(View.VISIBLE);
            loadContinuousPages();
        } else {
            zoomLayout.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            pager.setCurrentItem(page, false);
        }
        prefetchPagesAround(page);
    }

    private void setReadingMode(int mode) {
        if (readingMode == mode) {
            dismissControls();
            return;
        }
        if (mode == MODE_PAGE) {
            updatePageFromScroll();
        }
        readingMode = mode;
        getSharedPreferences("he_manager", MODE_PRIVATE)
                .edit()
                .putInt(PREF_READING_MODE, readingMode)
                .apply();
        renderReadingMode();
    }

    private void loadContinuousPages() {
        loadGeneration++;
        zoomLayout.resetZoom(false);
        continuousRecycler.setAdapter(new ContinuousAdapter());
        final int targetPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        restoringScroll = true;
        continuousRecycler.post(() -> {
            if (readingMode != MODE_SCROLL) return;
            LinearLayoutManager lm = (LinearLayoutManager) continuousRecycler.getLayoutManager();
            if (lm != null) lm.scrollToPositionWithOffset(targetPage, 0);
            continuousRecycler.postDelayed(() -> restoringScroll = false, 700);
        });
    }

    private void updatePageFromScroll() {
        if (continuousRecycler == null || readingMode != MODE_SCROLL) return;
        if (restoringScroll) return;
        LinearLayoutManager lm = (LinearLayoutManager) continuousRecycler.getLayoutManager();
        if (lm == null) return;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return;

        int viewportCenter = continuousRecycler.getHeight() / 2;
        int bestPage = first;
        int minDist = Integer.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            View child = lm.findViewByPosition(i);
            if (child == null) continue;
            int childCenter = (child.getTop() + child.getBottom()) / 2;
            int dist = Math.abs(childCenter - viewportCenter);
            if (dist < minDist) {
                minDist = dist;
                bestPage = i;
            }
        }
        int nextPage = Math.max(0, Math.min(bestPage, totalPages - 1));
        if (nextPage != page) {
            page = nextPage;
            saveProgressToServer(false);
        }
    }

    private TextView modeButton(String label, int mode) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(14f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(9), dp(10), dp(9));
        button.setOnClickListener(view -> setReadingMode(mode));
        return button;
    }

    private void updateModeButtons() {
        styleModeButton(pageModeButton, readingMode == MODE_PAGE);
        styleModeButton(scrollModeButton, readingMode == MODE_SCROLL);
    }

    private void styleModeButton(TextView button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? Color.BLACK : Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? 0xFF8EA7FF : 0x22111318);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), selected ? 0x668EA7FF : 0x22FFFFFF);
        button.setBackground(background);
    }

    private void showControls() {
        if (pageIndicator == null) return;
        if (readingMode == MODE_SCROLL) {
            updatePageFromScroll();
        }
        
        pageIndicator.removeCallbacks(hideControls);
        pageIndicator.animate().cancel();
        pageIndicator.setText((page + 1) + " / " + totalPages);
        updateModeButtons();

        if (pageIndicator.getVisibility() != View.VISIBLE) {
            pageIndicator.setAlpha(0f);
            pageIndicator.setVisibility(View.VISIBLE);
        }
        pageIndicator.animate()
                .alpha(1f)
                .setDuration(160)
                .withEndAction(() -> pageIndicator.postDelayed(hideControls, 2500))
                .start();

        if (settingsButton != null) {
            settingsButton.animate().cancel();
            if (settingsButton.getVisibility() != View.VISIBLE) {
                settingsButton.setAlpha(0f);
                settingsButton.setVisibility(View.VISIBLE);
            }
            settingsButton.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void toggleModePanel() {
        if (modePanel == null) return;
        pageIndicator.removeCallbacks(hideControls);
        updateModeButtons();
        if (modePanel.getVisibility() == View.VISIBLE) {
            modePanel.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction(() -> modePanel.setVisibility(View.GONE))
                    .start();
            pageIndicator.postDelayed(hideControls, 1200);
            return;
        }
        modePanel.animate().cancel();
        modePanel.setAlpha(0f);
        modePanel.setVisibility(View.VISIBLE);
        modePanel.animate()
                .alpha(1f)
                .setDuration(150)
                .withEndAction(() -> pageIndicator.postDelayed(hideControls, 2500))
                .start();
    }

    private void dismissControls() {
        if (pageIndicator == null) return;
        pageIndicator.removeCallbacks(hideControls);
        pageIndicator.animate().cancel();
        pageIndicator.setAlpha(0f);
        pageIndicator.setVisibility(View.GONE);
        if (settingsButton != null) {
            settingsButton.animate().cancel();
            settingsButton.setAlpha(0f);
            settingsButton.setVisibility(View.GONE);
        }
        if (modePanel != null) {
            modePanel.animate().cancel();
            modePanel.setAlpha(0f);
            modePanel.setVisibility(View.GONE);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgressToServer(true);
    }

    @Override
    protected void onDestroy() {
        if (pageIndicator != null) {
            pageIndicator.removeCallbacks(hideControls);
            pageIndicator.animate().cancel();
        }
        if (settingsButton != null) settingsButton.animate().cancel();
        if (modePanel != null) modePanel.animate().cancel();
        super.onDestroy();
    }

    private void saveProgressToServer(boolean force) {
        if (!progressReady) return;
        final int progress = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        if (!force && progress == lastSavedServerProgress) return;
        lastSavedServerProgress = progress;
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    JSONObject body = new JSONObject().put("progress", progress);
                    new ApiClient(serverUrl, token).patchJson("/media/" + id, body, true);
                } catch (Exception ignored) {
                }
                return null;
            }
        }.execute();
    }

    private String pageHeightKey(int pageIndex) {
        return "page_height_" + id + "_" + pageIndex;
    }

    private void prefetchPagesAround(int center) {
        if (!"manga".equals(mediaType) || totalPages <= 1) return;
        int from = Math.max(0, center - PREFETCH_BEHIND);
        int to = Math.min(totalPages - 1, center + PREFETCH_AHEAD);
        for (int p = center; p <= to; p++) prefetchPage(p);
        for (int p = center - 1; p >= from; p--) prefetchPage(p);
    }

    private void prefetchPage(final int pageIndex) {
        if (pageIndex < 0 || pageIndex >= totalPages) return;
        if (bitmapCache != null && bitmapCache.get(pageIndex) != null) return;
        synchronized (prefetchInFlight) {
            if (!prefetchInFlight.add(pageIndex)) return;
        }
        final String url = pageUrl(pageIndex, false);
        final int reqWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    File file = cachedImageFile(url, pageIndex);
                    if (readingMode == MODE_SCROLL && bitmapCache != null
                            && bitmapCache.get(pageIndex) == null) {
                        Bitmap bm = decodeBitmapForPage(file, reqWidth);
                        if (bm != null) bitmapCache.put(pageIndex, bm);
                    }
                } catch (Exception ignored) {}
                return null;
            }
            @Override
            protected void onPostExecute(Void v) {
                synchronized (prefetchInFlight) { prefetchInFlight.remove(pageIndex); }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Bitmap decodeBitmapForPage(File file, int reqWidth) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / sample > reqWidth * 2) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private class ContinuousAdapter extends RecyclerView.Adapter<ContinuousAdapter.PageHolder> {
        private final int generation = loadGeneration;

        class PageHolder extends RecyclerView.ViewHolder {
            FrameLayout container;
            ImageView imageView;
            TextView statusText;
            int bindGen = 0;

            PageHolder(FrameLayout v) {
                super(v);
                container = v;
                imageView = new ImageView(MangaActivity.this);
                imageView.setBackgroundColor(COLOR_BG);
                imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                imageView.setAdjustViewBounds(true);

                statusText = new TextView(MangaActivity.this);
                statusText.setTextColor(0x99FFFFFF);
                statusText.setTextSize(13f);
                statusText.setGravity(Gravity.CENTER);

                container.addView(imageView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                container.addView(statusText, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(MangaActivity.this);
            frame.setBackgroundColor(COLOR_BG);
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
            return new PageHolder(frame);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.bindGen++;
            final int thisGen = holder.bindGen;
            prefetchPagesAround(position);

            final int viewportWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int cachedHeight = getSharedPreferences("he_manager", MODE_PRIVATE)
                    .getInt(pageHeightKey(position), 0);
            int height = cachedHeight > 0 ? cachedHeight : viewportWidth;
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            lp.height = height;
            holder.itemView.setLayoutParams(lp);

            Bitmap cached = bitmapCache != null ? bitmapCache.get(position) : null;
            if (cached != null && !cached.isRecycled()) {
                holder.statusText.setVisibility(View.GONE);
                holder.imageView.setVisibility(View.VISIBLE);
                holder.imageView.setImageBitmap(cached);
                adjustHeightForBitmap(holder, position, cached);
                return;
            }

            holder.imageView.setVisibility(View.GONE);
            holder.imageView.setImageBitmap(null);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setText((position + 1) + " / " + totalPages);

            final String url = pageUrl(position, false);
            new AsyncTask<Void, Void, Bitmap>() {
                String error;
                @Override
                protected Bitmap doInBackground(Void... voids) {
                    try {
                        File file = cachedImageFile(url, position);
                        Bitmap bm = decodeBitmapForPage(file, viewportWidth);
                        if (bm != null && bitmapCache != null) bitmapCache.put(position, bm);
                        return bm;
                    } catch (Exception e) {
                        error = e.getMessage();
                        return null;
                    }
                }
                @Override
                protected void onPostExecute(Bitmap bm) {
                    if (holder.bindGen != thisGen) return;
                    if (generation != loadGeneration) return;
                    if (bm == null) {
                        holder.statusText.setText(error == null ? "加载失败" : error);
                        return;
                    }
                    holder.statusText.setVisibility(View.GONE);
                    holder.imageView.setVisibility(View.VISIBLE);
                    holder.imageView.setImageBitmap(bm);
                    adjustHeightForBitmap(holder, position, bm);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        private void adjustHeightForBitmap(PageHolder holder, int position, Bitmap bm) {
            int iw = bm.getWidth();
            int ih = bm.getHeight();
            if (iw <= 0 || ih <= 0) return;
            int targetWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int targetHeight = Math.max(dp(120), Math.round(targetWidth * (ih / (float) iw)));
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            if (lp.height != targetHeight) {
                lp.height = targetHeight;
                holder.itemView.setLayoutParams(lp);
                getSharedPreferences("he_manager", MODE_PRIVATE)
                        .edit()
                        .putInt(pageHeightKey(position), targetHeight)
                        .apply();
            }
        }

        @Override
        public int getItemCount() {
            return totalPages;
        }
    }

    private class MangaPagerAdapter extends RecyclerView.Adapter<MangaPagerAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            FrameLayout container;
            SubsamplingScaleImageView imageView;
            TextView statusText;
            int loadGen = 0;

            ViewHolder(FrameLayout v) {
                super(v);
                container = v;
                imageView = new SubsamplingScaleImageView(MangaActivity.this);
                imageView.setBackgroundColor(COLOR_BG);
                imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE);
                imageView.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER);
                imageView.setDoubleTapZoomScale(2.5f);
                imageView.setDoubleTapZoomDuration(220);
                imageView.setMaxScale(8f);
                imageView.setPanEnabled(true);
                imageView.setZoomEnabled(true);
                imageView.setQuickScaleEnabled(false);
                imageView.setClickable(true);
                imageView.setOnClickListener(view -> showControls());

                statusText = new TextView(MangaActivity.this);
                statusText.setTextColor(Color.WHITE);
                statusText.setGravity(Gravity.CENTER);

                container.addView(imageView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                container.addView(statusText, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frameLayout = new FrameLayout(MangaActivity.this);
            frameLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frameLayout.setBackgroundColor(COLOR_BG);
            return new ViewHolder(frameLayout);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.loadGen++;
            final int currentGen = holder.loadGen;
            prefetchPagesAround(position);
            holder.imageView.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setText("\u52a0\u8f7d\u4e2d...");

            String url = pageUrl(position, true);
            new android.os.AsyncTask<String, Void, File>() {
                String error;
                @Override
                protected File doInBackground(String... urls) {
                    try {
                        return cachedImageFile(urls[0], position);
                    } catch (Exception e) {
                        error = e.getMessage();
                        return null;
                    }
                }
                @Override
                protected void onPostExecute(File file) {
                    if (holder.loadGen != currentGen) return;
                    if (file == null) {
                        holder.statusText.setText(error == null ? "加载失败" : error);
                        return;
                    }
                    holder.statusText.setVisibility(View.GONE);
                    holder.imageView.setVisibility(View.VISIBLE);
                    holder.imageView.setImage(ImageSource.uri(file.getAbsolutePath()));
                }
            }.execute(url);
        }

        @Override
        public int getItemCount() {
            return totalPages;
        }
    }
}
