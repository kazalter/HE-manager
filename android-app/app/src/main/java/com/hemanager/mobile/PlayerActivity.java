package com.hemanager.mobile;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.Locale;

@UnstableApi
public class PlayerActivity extends Activity {
    private static final long DOUBLE_TAP_SEEK_MS = 10_000L;
    private static final int SCRUB_SLOP_DP = 18;
    private static final long TAP_DECISION_MS = 340L;
    private static final int DOUBLE_TAP_DISTANCE_DP = 90;
    private static final int PROGRESS_MAX = 1000;

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout root;
    private FrameLayout controlsLayer;
    private FrameLayout gestureLayer;
    private FrameLayout leftDoubleTapOverlay;
    private FrameLayout rightDoubleTapOverlay;
    private LinearLayout centerControls;
    private LinearLayout bottomControls;
    private FrameLayout settingsSheet;
    private SeekBar progressBar;
    private TextView playButton;
    private TextView timeText;
    private TextView scrubBubble;
    private TextView titleText;
    private TextView speedButton;
    private TextView subtitleButton;
    private TextView fullscreenButton;

    private boolean fullscreen = false;
    private boolean fullscreenChangeLocked = false;
    private boolean controlsVisible = true;
    private boolean scrubbing = false;
    private boolean gestureHandled = false;
    private boolean userDraggingProgress = false;
    private boolean subtitlesEnabled = false;
    private int speedIndex = 0;

    private String serverUrl;
    private String token;
    private int id;
    private int initialProgress;
    private int lastSavedProgress = -1;
    private String title;
    private float scrubStartX = 0f;
    private float scrubStartY = 0f;
    private long scrubStartPositionMs = 0L;
    private long pendingScrubPositionMs = 0L;
    private long lastTapUpTime = 0L;
    private float lastTapX = 0f;
    private float lastTapY = 0f;

    private final float[] speeds = new float[] {1f, 1.25f, 1.5f, 2f};
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable singleTapRunnable = () -> {
        if (!gestureHandled && !scrubbing) {
            setControlsVisible(!controlsVisible, true);
        }
    };
    private final Runnable hideControlsRunnable = () -> {
        if (player != null && player.isPlaying() && !scrubbing && !userDraggingProgress && !isSettingsVisible()) {
            setControlsVisible(false, true);
        }
    };
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgressUi();
            mainHandler.postDelayed(this, 500);
        }
    };
    private final Runnable saveProgressTick = new Runnable() {
        @Override
        public void run() {
            saveProgress(false);
            mainHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        disableRotationAnimation();

        serverUrl = ApiClient.trimSlash(getIntent().getStringExtra("server_url"));
        token = getIntent().getStringExtra("token");
        id = getIntent().getIntExtra("id", 0);
        initialProgress = Math.max(0, getIntent().getIntExtra("progress", 0));
        lastSavedProgress = initialProgress;
        title = getIntent().getStringExtra("title");
        String videoUrl = serverUrl + "/mobile/stream/" + id + "?" + ApiClient.tokenQuery(token);
        setTitle(title);

        buildPlayerLayout();

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(45_000, 120_000, 1_000, 2_000)
                .build();
        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)));
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(true);
                    setControlsVisible(true, true);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayButton();
                if (isPlaying) scheduleControlsHide();
            }
        });
        player.setPlayWhenReady(false);
        player.prepare();

        attachGestureLayer();
        loadLatestProgressAndStart();
        mainHandler.post(progressTick);
        mainHandler.postDelayed(saveProgressTick, 5000);
        scheduleControlsHide();
    }

    private void buildPlayerLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 5, 5));

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        controlsLayer = new FrameLayout(this);
        controlsLayer.setAlpha(1f);
        root.addView(controlsLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        addTopControls();
        addCenterControls();
        addBottomControls();
        addSettingsSheet();
        addGestureLayer();

        setContentView(root);
    }

    private void addTopControls() {
        View topGradient = gradientView(0xCC000000, 0x00000000);
        FrameLayout.LayoutParams gradientParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(104),
                Gravity.TOP);
        controlsLayer.addView(topGradient, gradientParams);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(24), dp(18), dp(24), 0);

        TextView back = roundTextButton("‹", 46, 26);
        back.setOnClickListener(view -> finishPlayer());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));

        titleText = new TextView(this);
        titleText.setText(title == null ? "" : title);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(15f);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setGravity(Gravity.CENTER);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(16);
        titleParams.rightMargin = dp(16);
        topBar.addView(titleText, titleParams);

        TextView more = roundTextButton("⋯", 46, 22);
        more.setOnClickListener(view -> toggleSettingsSheet());
        topBar.addView(more, new LinearLayout.LayoutParams(dp(46), dp(46)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(76),
                Gravity.TOP);
        controlsLayer.addView(topBar, topParams);
    }

    private void addCenterControls() {
        centerControls = new LinearLayout(this);
        centerControls.setGravity(Gravity.CENTER);
        centerControls.setOrientation(LinearLayout.HORIZONTAL);

        TextView rewind = roundTextButton("↶10", 52, 15);
        rewind.setTypeface(Typeface.DEFAULT_BOLD);
        rewind.setOnClickListener(view -> {
            seekBy(-DOUBLE_TAP_SEEK_MS);
            scheduleControlsHide();
        });

        playButton = roundTextButton("▶", 68, 30);
        playButton.setOnClickListener(view -> {
            togglePlayPause(true);
            scheduleControlsHide();
        });

        TextView forward = roundTextButton("10↷", 52, 15);
        forward.setTypeface(Typeface.DEFAULT_BOLD);
        forward.setOnClickListener(view -> {
            seekBy(DOUBLE_TAP_SEEK_MS);
            scheduleControlsHide();
        });

        centerControls.addView(rewind, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(68), dp(68));
        playParams.leftMargin = dp(34);
        playParams.rightMargin = dp(34);
        centerControls.addView(playButton, playParams);
        centerControls.addView(forward, new LinearLayout.LayoutParams(dp(52), dp(52)));

        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        controlsLayer.addView(centerControls, centerParams);
    }

    private void addBottomControls() {
        View bottomGradient = gradientView(0x00000000, 0xDD000000);
        FrameLayout.LayoutParams gradientParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(136),
                Gravity.BOTTOM);
        controlsLayer.addView(bottomGradient, gradientParams);

        bottomControls = new LinearLayout(this);
        bottomControls.setOrientation(LinearLayout.VERTICAL);
        bottomControls.setPadding(dp(24), 0, dp(24), dp(20));
        bottomControls.setGravity(Gravity.BOTTOM);

        progressBar = new SeekBar(this);
        progressBar.setMax(PROGRESS_MAX);
        styleSeekBar(3);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long target = positionForProgress(progress);
                    showScrubBubble(target);
                    updateTimeText(target);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userDraggingProgress = true;
                styleSeekBar(5);
                mainHandler.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userDraggingProgress = false;
                styleSeekBar(3);
                long target = positionForProgress(seekBar.getProgress());
                if (player != null) player.seekTo(target);
                saveProgress(true);
                hideScrubBubbleSoon();
                scheduleControlsHide();
            }
        });
        bottomControls.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28)));

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        timeText = new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(13f);
        timeText.setTypeface(Typeface.DEFAULT_BOLD);
        bottomRow.addView(timeText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        speedButton = functionButton("1x");
        speedButton.setOnClickListener(view -> cycleSpeed());
        bottomRow.addView(speedButton);

        subtitleButton = functionButton("CC");
        subtitleButton.setOnClickListener(view -> toggleSubtitleButton());
        bottomRow.addView(subtitleButton);

        TextView settings = functionButton("⚙");
        settings.setOnClickListener(view -> toggleSettingsSheet());
        bottomRow.addView(settings);

        fullscreenButton = functionButton("⛶");
        fullscreenButton.setOnClickListener(view -> setFullscreen(!fullscreen));
        bottomRow.addView(fullscreenButton);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46));
        bottomControls.addView(bottomRow, rowParams);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(120),
                Gravity.BOTTOM);
        controlsLayer.addView(bottomControls, bottomParams);
    }

    private void addScrubBubble() {
        scrubBubble = new TextView(this);
        scrubBubble.setTextColor(Color.WHITE);
        scrubBubble.setTextSize(14f);
        scrubBubble.setTypeface(Typeface.DEFAULT_BOLD);
        scrubBubble.setGravity(Gravity.CENTER);
        scrubBubble.setPadding(dp(14), dp(7), dp(14), dp(7));
        scrubBubble.setAlpha(0f);
        scrubBubble.setVisibility(View.GONE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xDD111318);
        background.setCornerRadius(dp(18));
        scrubBubble.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        gestureLayer.addView(scrubBubble, params);
    }

    private void addGestureLayer() {
        gestureLayer = new FrameLayout(this);
        gestureLayer.setClickable(false);
        root.addView(gestureLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        leftDoubleTapOverlay = doubleTapOverlay(true);
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(220), dp(300), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        leftParams.leftMargin = dp(-112);
        gestureLayer.addView(leftDoubleTapOverlay, leftParams);

        rightDoubleTapOverlay = doubleTapOverlay(false);
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(dp(220), dp(300), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rightParams.rightMargin = dp(-112);
        gestureLayer.addView(rightDoubleTapOverlay, rightParams);

        addScrubBubble();
    }

    private FrameLayout doubleTapOverlay(boolean left) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        overlay.setAlpha(0f);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x5CFFFFFF);
        overlay.setBackground(background);

        TextView icon = new TextView(this);
        icon.setText(left ? "↶ 10" : "10 ↷");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(20f);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(92), dp(56), Gravity.CENTER);
        iconParams.leftMargin = left ? dp(72) : dp(-72);
        overlay.addView(icon, iconParams);
        return overlay;
    }

    private void addSettingsSheet() {
        settingsSheet = new FrameLayout(this);
        settingsSheet.setVisibility(View.GONE);
        settingsSheet.setAlpha(0f);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1C1C1E);
        float r = dp(20);
        background.setCornerRadii(new float[] {r, r, r, r, 0f, 0f, 0f, 0f});
        settingsSheet.setBackground(background);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(22));
        settingsSheet.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        TextView title = sheetTitle("播放设置");
        content.addView(title);
        content.addView(sheetRow("清晰度", "自动"));
        TextView speedRow = sheetRow("倍速", speedLabel());
        speedRow.setOnClickListener(view -> {
            cycleSpeed();
            hideSettingsSheet();
        });
        content.addView(speedRow);
        TextView subtitleRow = sheetRow("字幕", subtitlesEnabled ? "开启" : "关闭");
        subtitleRow.setOnClickListener(view -> {
            toggleSubtitleButton();
            hideSettingsSheet();
        });
        content.addView(subtitleRow);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        controlsLayer.addView(settingsSheet, params);
    }

    private void attachGestureLayer() {
        root.setOnTouchListener((view, event) -> {
            if (player == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mainHandler.removeCallbacks(singleTapRunnable);
                    scrubStartX = event.getX();
                    scrubStartY = event.getY();
                    scrubStartPositionMs = player.getCurrentPosition();
                    pendingScrubPositionMs = scrubStartPositionMs;
                    scrubbing = false;
                    gestureHandled = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - scrubStartX;
                    float dy = event.getY() - scrubStartY;
                    if (!scrubbing && Math.abs(dx) > dp(SCRUB_SLOP_DP) && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        scrubbing = true;
                        gestureHandled = true;
                        mainHandler.removeCallbacks(singleTapRunnable);
                        mainHandler.removeCallbacks(hideControlsRunnable);
                    }
                    if (scrubbing) {
                        pendingScrubPositionMs = scrubPositionForDelta(dx);
                        showScrubBubble(pendingScrubPositionMs);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (scrubbing) {
                        player.seekTo(pendingScrubPositionMs);
                        saveProgress(true);
                        updateProgressPreview(pendingScrubPositionMs);
                        hideScrubBubbleSoon();
                        scrubbing = false;
                        gestureHandled = false;
                        scheduleControlsHide();
                        return true;
                    }
                    if (!gestureHandled && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        handleTapUp(event);
                        return true;
                    }
                    scrubbing = false;
                    gestureHandled = false;
                    return true;
                default:
                    break;
            }
            return true;
        });
    }

    private void handleTapUp(MotionEvent event) {
        long now = System.currentTimeMillis();
        float dx = event.getX() - lastTapX;
        float dy = event.getY() - lastTapY;
        boolean isDoubleTap = now - lastTapUpTime <= TAP_DECISION_MS
                && Math.hypot(dx, dy) <= dp(DOUBLE_TAP_DISTANCE_DP);

        if (isDoubleTap) {
            mainHandler.removeCallbacks(singleTapRunnable);
            lastTapUpTime = 0L;
            gestureHandled = true;
            handleDoubleTap(event.getX());
            return;
        }

        lastTapUpTime = now;
        lastTapX = event.getX();
        lastTapY = event.getY();
        mainHandler.postDelayed(singleTapRunnable, TAP_DECISION_MS);
    }

    private void handleDoubleTap(float x) {
        if (player == null || root == null) return;
        float width = Math.max(1f, root.getWidth());
        if (x < width * 0.42f) {
            seekBy(-DOUBLE_TAP_SEEK_MS);
            showDoubleTapOverlay(true);
            return;
        }
        if (x > width * 0.58f) {
            seekBy(DOUBLE_TAP_SEEK_MS);
            showDoubleTapOverlay(false);
            return;
        }
        togglePlayPause(false);
    }

    private void togglePlayPause(boolean revealControls) {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            saveProgress(true);
            if (revealControls) setControlsVisible(true, true);
        } else {
            player.play();
            if (revealControls) scheduleControlsHide();
        }
        updatePlayButton();
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long nextPosition = clampPosition(player.getCurrentPosition() + deltaMs);
        player.seekTo(nextPosition);
        lastSavedProgress = Math.max(0, (int) (nextPosition / 1000L));
        updateProgressPreview(nextPosition);
        hideSettingsSheet();
        scheduleControlsHide();
    }

    private long scrubPositionForDelta(float dx) {
        long duration = player == null ? 0L : player.getDuration();
        long window = duration > 0 ? Math.min(duration, 180_000L) : 120_000L;
        float width = Math.max(1f, root == null ? getResources().getDisplayMetrics().widthPixels : root.getWidth());
        long delta = Math.round((dx / width) * window);
        return clampPosition(scrubStartPositionMs + delta);
    }

    private long clampPosition(long value) {
        long duration = player == null ? 0L : player.getDuration();
        long max = duration > 0 ? duration : Long.MAX_VALUE;
        return Math.max(0L, Math.min(value, max));
    }

    private void setControlsVisible(boolean visible, boolean animate) {
        controlsVisible = visible;
        if (controlsLayer == null) return;
        float target = visible ? 1f : 0f;
        controlsLayer.animate().cancel();
        if (visible) {
            controlsLayer.setVisibility(View.VISIBLE);
        }
        if (animate) {
            controlsLayer.animate()
                    .alpha(target)
                    .setDuration(180)
                    .withEndAction(() -> {
                        if (!controlsVisible && controlsLayer != null) controlsLayer.setVisibility(View.GONE);
                    })
                    .start();
        } else {
            controlsLayer.setAlpha(target);
            controlsLayer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (visible) scheduleControlsHide();
    }

    private void scheduleControlsHide() {
        mainHandler.removeCallbacks(hideControlsRunnable);
        if (player != null && player.isPlaying()) {
            mainHandler.postDelayed(hideControlsRunnable, 2500);
        }
    }

    private void updatePlayButton() {
        if (playButton == null || player == null) return;
        playButton.setText(player.isPlaying() ? "Ⅱ" : "▶");
    }

    private void updateProgressUi() {
        if (player == null || userDraggingProgress || scrubbing) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        if (duration > 0) {
            int progress = Math.max(0, Math.min(PROGRESS_MAX, Math.round(PROGRESS_MAX * (position / (float) duration))));
            progressBar.setProgress(progress);
        }
        updateTimeText(position);
    }

    private void updateProgressPreview(long position) {
        long duration = player == null ? 0L : player.getDuration();
        if (duration > 0 && progressBar != null) {
            int progress = Math.max(0, Math.min(PROGRESS_MAX, Math.round(PROGRESS_MAX * (position / (float) duration))));
            progressBar.setProgress(progress);
        }
        updateTimeText(position);
    }

    private long positionForProgress(int progress) {
        long duration = player == null ? 0L : player.getDuration();
        if (duration <= 0) return 0L;
        return clampPosition(Math.round(duration * (progress / (float) PROGRESS_MAX)));
    }

    private void updateTimeText(long positionMs) {
        if (timeText == null) return;
        long duration = player == null ? 0L : player.getDuration();
        String current = formatDurationMs(positionMs);
        String total = duration > 0 ? formatDurationMs(duration) : "--:--";
        String value = current + " / " + total;
        SpannableString span = new SpannableString(value);
        span.setSpan(new ForegroundColorSpan(Color.WHITE), 0, current.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(0xA6FFFFFF), current.length(), value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        timeText.setText(span);
    }

    private void showScrubBubble(long positionMs) {
        if (scrubBubble == null) return;
        scrubBubble.animate().cancel();
        scrubBubble.setText(formatSeekHint(positionMs));
        if (scrubBubble.getVisibility() != View.VISIBLE) {
            scrubBubble.setVisibility(View.VISIBLE);
        }
        scrubBubble.setAlpha(1f);
    }

    private void showDoubleTapOverlay(boolean left) {
        FrameLayout overlay = left ? leftDoubleTapOverlay : rightDoubleTapOverlay;
        if (overlay == null) return;
        overlay.animate().cancel();
        overlay.setScaleX(0.96f);
        overlay.setScaleY(0.96f);
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120)
                .withEndAction(() -> overlay.animate()
                        .alpha(0f)
                        .setStartDelay(170)
                        .setDuration(220)
                        .withEndAction(() -> overlay.setVisibility(View.GONE))
                        .start())
                .start();
    }

    private void hideScrubBubbleSoon() {
        if (scrubBubble == null) return;
        scrubBubble.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> {
                    if (scrubBubble != null) scrubBubble.setVisibility(View.GONE);
                })
                .start();
    }

    private void toggleSettingsSheet() {
        if (isSettingsVisible()) {
            hideSettingsSheet();
        } else {
            showSettingsSheet();
        }
    }

    private void showSettingsSheet() {
        if (settingsSheet == null) return;
        setControlsVisible(true, true);
        mainHandler.removeCallbacks(hideControlsRunnable);
        settingsSheet.animate().cancel();
        settingsSheet.setTranslationY(dp(24));
        settingsSheet.setAlpha(0f);
        settingsSheet.setVisibility(View.VISIBLE);
        settingsSheet.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private void hideSettingsSheet() {
        if (settingsSheet == null || settingsSheet.getVisibility() != View.VISIBLE) return;
        settingsSheet.animate()
                .alpha(0f)
                .translationY(dp(24))
                .setDuration(160)
                .withEndAction(() -> {
                    if (settingsSheet != null) settingsSheet.setVisibility(View.GONE);
                    scheduleControlsHide();
                })
                .start();
    }

    private boolean isSettingsVisible() {
        return settingsSheet != null && settingsSheet.getVisibility() == View.VISIBLE;
    }

    private void cycleSpeed() {
        if (player == null) return;
        speedIndex = (speedIndex + 1) % speeds.length;
        player.setPlaybackSpeed(speeds[speedIndex]);
        if (speedButton != null) speedButton.setText(speedLabel());
    }

    private String speedLabel() {
        float speed = speeds[speedIndex];
        if (speed == 1f) return "1x";
        return String.format(Locale.ROOT, "%.2fx", speed).replace(".00", "").replace("0x", "x");
    }

    private void toggleSubtitleButton() {
        subtitlesEnabled = !subtitlesEnabled;
        if (subtitleButton != null) subtitleButton.setAlpha(subtitlesEnabled ? 1f : 0.72f);
    }

    private void styleSeekBar(int heightDp) {
        if (progressBar == null) return;
        GradientDrawable progress = new GradientDrawable();
        progress.setColor(Color.WHITE);
        progress.setCornerRadius(dp(999));
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x45FFFFFF);
        track.setCornerRadius(dp(999));
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {track, progress});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        layer.setLayerHeight(0, dp(heightDp));
        layer.setLayerHeight(1, dp(heightDp));
        layer.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        layer.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        progressBar.setProgressDrawable(layer);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setSize(dp(heightDp == 3 ? 10 : 12), dp(heightDp == 3 ? 10 : 12));
        progressBar.setThumb(thumb);
        progressBar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        progressBar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x45FFFFFF));
        progressBar.setSplitTrack(false);
    }

    private TextView roundTextButton(String label, int sizeDp, int textSizeSp) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAlpha(0.90f);
        button.setTextSize(textSizeSp);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x59000000);
        button.setBackground(background);
        button.setMinWidth(dp(44));
        button.setMinHeight(dp(44));
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(80).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).alpha(0.90f).setDuration(100).start();
            }
            return false;
        });
        return button;
    }

    private TextView functionButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAlpha(0.86f);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setMinWidth(dp(44));
        button.setMinHeight(dp(44));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.leftMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private TextView sheetTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(17f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView sheetRow(String label, String value) {
        TextView view = new TextView(this);
        view.setText(label + "    " + value);
        view.setTextColor(0xE6FFFFFF);
        view.setTextSize(15f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, dp(12), 0, dp(12));
        view.setMinHeight(dp(44));
        view.setClickable(true);
        return view;
    }

    private View gradientView(int startColor, int endColor) {
        View view = new View(this);
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {startColor, endColor});
        view.setBackground(gradient);
        return view;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && player.getPlayWhenReady()) player.play();
    }

    @Override
    protected void onStop() {
        saveProgress(true);
        if (player != null) player.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(progressTick);
        mainHandler.removeCallbacks(saveProgressTick);
        mainHandler.removeCallbacks(singleTapRunnable);
        mainHandler.removeCallbacks(hideControlsRunnable);
        saveProgress(true);
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        if (player != null) {
            ExoPlayer oldPlayer = player;
            player = null;
            mainHandler.postDelayed(oldPlayer::release, 180);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isSettingsVisible()) {
            hideSettingsSheet();
            return;
        }
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        finishPlayer();
    }

    private void finishPlayer() {
        if (player != null) {
            saveProgress(true);
            player.pause();
        }
        fullscreen = false;
        fullscreenChangeLocked = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private void setFullscreen(boolean value) {
        if (fullscreenChangeLocked || fullscreen == value) return;
        fullscreenChangeLocked = true;
        fullscreen = value;
        fullscreenButton.setText(value ? "⇱" : "⛶");

        View decor = getWindow().getDecorView();
        if (value) {
            disableRotationAnimation();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            disableRotationAnimation();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        mainHandler.postDelayed(() -> fullscreenChangeLocked = false, 520);
    }

    private void disableRotationAnimation() {
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
        getWindow().setAttributes(attrs);
    }

    private String formatSeekHint(long positionMs) {
        long duration = player == null ? 0L : player.getDuration();
        if (duration > 0L) {
            return formatDurationMs(positionMs) + " / " + formatDurationMs(duration);
        }
        return formatDurationMs(positionMs);
    }

    private String formatDurationMs(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveProgress(boolean force) {
        if (player == null || id <= 0) return;
        int progress = Math.max(0, (int) (player.getCurrentPosition() / 1000L));
        int duration = player.getDuration() > 0 ? (int) (player.getDuration() / 1000L) : 0;
        if (!force && Math.abs(progress - lastSavedProgress) < 3) return;
        lastSavedProgress = progress;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("progress", progress);
                if (duration > 0) body.put("duration", duration);
                new ApiClient(serverUrl, token).patchJson("/media/" + id, body, true);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void loadLatestProgressAndStart() {
        new Thread(() -> {
            int latestProgress = initialProgress;
            try {
                JSONObject media = new ApiClient(serverUrl, token).getJsonObject("/mobile/media/" + id);
                latestProgress = Math.max(0, media.optInt("progress", initialProgress));
            } catch (Exception ignored) {
            }

            final int targetProgress = latestProgress;
            mainHandler.post(() -> {
                if (player == null) return;
                if (targetProgress > 0) {
                    player.seekTo(targetProgress * 1000L);
                }
                lastSavedProgress = targetProgress;
                player.setPlayWhenReady(true);
                player.play();
                updateProgressUi();
            });
        }).start();
    }
}
