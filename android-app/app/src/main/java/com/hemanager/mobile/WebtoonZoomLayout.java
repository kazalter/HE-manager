package com.hemanager.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.OverScroller;

import androidx.recyclerview.widget.RecyclerView;

public class WebtoonZoomLayout extends FrameLayout {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 6.0f;
    private static final float RESET_THRESHOLD = 1.04f;
    private static final long ZOOM_ANIMATION_MS = 220L;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;

    private RecyclerView recyclerView;
    private float currentScale = 1f;
    private float panX = 0f;
    private boolean isScaling = false;
    private float prevFocusX, prevFocusY;
    private int lastBottomPadding = 0;
    private ValueAnimator zoomAnimator;

    private Runnable onSingleTap;
    private OnZoomSettledListener onZoomSettledListener;

    public interface OnZoomSettledListener {
        void onZoomSettled(float scale);
    }

    public WebtoonZoomLayout(Context context) {
        super(context);
        scroller = new OverScroller(context);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelZoomAnimation();
                scroller.forceFinished(true);
                isScaling = true;
                prevFocusX = detector.getFocusX();
                prevFocusY = detector.getFocusY();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
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

                // Keep the content under the fingers anchored while allowing two-finger panning.
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
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (currentScale < RESET_THRESHOLD) {
                    animateZoomTo(1f, getWidth() / 2f, getHeight() / 2f);
                } else {
                    notifyZoomSettled();
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

    public void setOnZoomSettledListener(OnZoomSettledListener listener) {
        this.onZoomSettledListener = listener;
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
            notifyZoomSettled();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
        }
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        if (ev.getPointerCount() > 1 || scaleDetector.isInProgress() || isScaling || currentScale > 1.01f) {
            return true;
        }
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
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (currentScale < RESET_THRESHOLD) {
                        animateZoomTo(1f, getWidth() / 2f, getHeight() / 2f);
                    } else {
                        notifyZoomSettled();
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
        if (Math.abs(panX) < 0.5f) panX = 0f;
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
        desiredBottomPad = Math.round(desiredBottomPad / 8f) * 8;
        if (lastBottomPadding != desiredBottomPad || recyclerView.getPaddingBottom() != desiredBottomPad) {
            lastBottomPadding = desiredBottomPad;
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
        zoomAnimator.setDuration(ZOOM_ANIMATION_MS);
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
        zoomAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                notifyZoomSettled();
            }
        });
        zoomAnimator.start();
    }

    private int computeRvScroll() {
        if (recyclerView == null) return 0;
        return recyclerView.computeVerticalScrollOffset();
    }

    private void notifyZoomSettled() {
        if (onZoomSettledListener != null) {
            onZoomSettledListener.onZoomSettled(currentScale);
        }
    }
}
