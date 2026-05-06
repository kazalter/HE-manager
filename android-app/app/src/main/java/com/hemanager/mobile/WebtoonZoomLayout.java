package com.hemanager.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.OverScroller;

import androidx.recyclerview.widget.RecyclerView;

public class WebtoonZoomLayout extends FrameLayout {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 3.5f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;

    private RecyclerView recyclerView;
    private float currentScale = 1f;
    private float panX = 0f;
    private boolean isScaling = false;
    private float prevFocusX, prevFocusY;
    private ValueAnimator zoomAnimator;

    private Runnable onSingleTap;

    public WebtoonZoomLayout(Context context) {
        super(context);
        float touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scroller = new OverScroller(context);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelZoomAnimation();
                isScaling = true;
                prevFocusX = detector.getFocusX();
                prevFocusY = detector.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float fx = detector.getFocusX();
                float fy = detector.getFocusY();
                float sf = detector.getScaleFactor();

                float oldScale = currentScale;
                float newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, oldScale * sf));
                float effectiveSf = newScale / oldScale;

                panX = fx - (prevFocusX - panX) * effectiveSf;

                if (recyclerView != null) {
                    float scrollDelta = prevFocusY / oldScale - fy / newScale;
                    recyclerView.scrollBy(0, Math.round(scrollDelta));
                }

                currentScale = newScale;
                prevFocusX = fx;
                prevFocusY = fy;
                constrainPan();
                applyTransform();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
                if (currentScale < 1.05f) {
                    animateZoomTo(1f, getWidth() / 2f, getHeight() / 2f);
                }
            }
        });
        scaleDetector.setQuickScaleEnabled(false);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onSingleTap != null) onSingleTap.run();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float target = currentScale > 1.05f ? 1f : 2.5f;
                animateZoomTo(target, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (currentScale > 1.01f && !isScaling) {
                    panX -= distanceX;
                    constrainPan();
                    applyTransform();
                    if (recyclerView != null) {
                        recyclerView.scrollBy(0, Math.round(distanceY / currentScale));
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (currentScale > 1.01f) {
                    int width = getWidth();
                    int minPanX = Math.round(width - width * currentScale);
                    scroller.fling(
                            Math.round(panX), 0,
                            Math.round(velocityX), 0,
                            minPanX, 0,
                            0, 0);
                    postInvalidateOnAnimation();
                    if (recyclerView != null) {
                        recyclerView.fling(0, Math.round(-velocityY / currentScale));
                    }
                    return true;
                }
                return false;
            }
        });
    }

    public void setRecyclerView(RecyclerView rv) {
        this.recyclerView = rv;
    }

    public void setOnSingleTapListener(Runnable r) {
        this.onSingleTap = r;
    }

    public float getCurrentScale() {
        return currentScale;
    }

    public void resetZoom(boolean animate) {
        if (animate && currentScale > 1.01f) {
            animateZoomTo(1f, getWidth() / 2f, getHeight() / 2f);
        } else {
            cancelZoomAnimation();
            currentScale = 1f;
            panX = 0f;
            applyTransform();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (scaleDetector.isInProgress()) return true;
        if (currentScale > 1.01f) return true;
        if (ev.getPointerCount() > 1) return true;
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isScaling) {
                    isScaling = false;
                    if (currentScale < 1.05f) {
                        animateZoomTo(1f, getWidth() / 2f, getHeight() / 2f);
                    }
                }
                break;
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.getCurrX();
            constrainPan();
            applyTransform();
            postInvalidateOnAnimation();
        }
    }

    private void constrainPan() {
        if (currentScale <= 1f) {
            panX = 0f;
            return;
        }
        int width = getWidth();
        if (width <= 0) return;
        float minPanX = width - width * currentScale;
        panX = Math.max(minPanX, Math.min(0f, panX));
    }

    private void applyTransform() {
        if (recyclerView == null) return;
        recyclerView.setPivotX(0f);
        recyclerView.setPivotY(0f);
        recyclerView.setScaleX(currentScale);
        recyclerView.setScaleY(currentScale);
        recyclerView.setTranslationX(panX);
        applyZoomPadding();
    }

    private void applyZoomPadding() {
        if (recyclerView == null) return;
        int viewportH = getHeight();
        if (viewportH <= 0) return;
        int desiredBottomPad = currentScale > 1.001f
                ? Math.round(viewportH * (1f - 1f / currentScale))
                : 0;
        if (recyclerView.getPaddingBottom() != desiredBottomPad) {
            recyclerView.setClipToPadding(false);
            recyclerView.setPadding(
                    recyclerView.getPaddingLeft(),
                    recyclerView.getPaddingTop(),
                    recyclerView.getPaddingRight(),
                    desiredBottomPad);
        }
    }

    private void cancelZoomAnimation() {
        if (zoomAnimator != null && zoomAnimator.isRunning()) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
    }

    private void animateZoomTo(float targetScale, float focusX, float focusY) {
        cancelZoomAnimation();
        float startScale = currentScale;
        float startPanX = panX;

        float rawTargetPanX;
        if (targetScale <= 1.01f) {
            rawTargetPanX = 0f;
        } else {
            rawTargetPanX = focusX - (focusX - panX) * (targetScale / currentScale);
            int width = getWidth();
            float minPan = width - width * targetScale;
            rawTargetPanX = Math.max(minPan, Math.min(0f, rawTargetPanX));
        }
        final float targetPanX = rawTargetPanX;

        int startScroll = recyclerView != null ? computeRvScroll() : 0;
        float targetScrollF = startScroll + focusY * (1f / startScale - 1f / targetScale);
        int targetScroll = Math.max(0, Math.round(targetScrollF));

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
        zoomAnimator.setDuration(280);
        zoomAnimator.setInterpolator(new DecelerateInterpolator());
        int[] prevScroll = {startScroll};
        zoomAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            currentScale = startScale + (targetScale - startScale) * f;
            panX = startPanX + (targetPanX - startPanX) * f;
            constrainPan();
            applyTransform();
            if (recyclerView != null) {
                int wantScroll = Math.round(startScroll + (targetScroll - startScroll) * f);
                recyclerView.scrollBy(0, wantScroll - prevScroll[0]);
                prevScroll[0] = wantScroll;
            }
        });
        zoomAnimator.start();
    }

    private int computeRvScroll() {
        if (recyclerView == null) return 0;
        return recyclerView.computeVerticalScrollOffset();
    }
}
