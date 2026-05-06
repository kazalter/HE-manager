import re

with open('app/src/main/java/com/hemanager/mobile/MangaActivity.java', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Imports
code = code.replace('import java.net.URL;', 'import java.net.URL;\n\nimport androidx.annotation.NonNull;\nimport androidx.recyclerview.widget.RecyclerView;\nimport androidx.viewpager2.widget.ViewPager2;\nimport android.view.ViewGroup;')

# 2. Fields
code = code.replace('private SubsamplingScaleImageView image;\n    private ScrollView scrollView;\n    private LinearLayout scrollPages;\n    private GestureDetector gestures;\n    private GestureDetector scrollGestures;', 'private ViewPager2 pager;\n    private ScrollView scrollView;\n    private LinearLayout scrollPages;\n    private GestureDetector scrollGestures;')

# 3. onCreate pager initialization
old_image_init = '''        image = new SubsamplingScaleImageView(this);
        image.setBackgroundColor(COLOR_BG);
        image.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE);
        image.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER);
        image.setDoubleTapZoomScale(2.5f);
        image.setMaxScale(8f);
        image.setPanEnabled(true);
        image.setZoomEnabled(true);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
                if (readingMode != MODE_PAGE) return false;
                if (down == null || up == null || totalPages <= 1 || isZoomed()) return false;
                float dx = up.getX() - down.getX();
                float dy = up.getY() - down.getY();
                if (Math.abs(dx) < SWIPE_DISTANCE || Math.abs(dx) < Math.abs(dy) * 1.4f) return false;
                if (Math.abs(velocityX) < SWIPE_VELOCITY) return false;
                if (dx < 0) return nextPage();
                return previousPage();
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                showControls();
                return true;
            }
        });
        image.setOnTouchListener((view, event) -> {
            gestures.onTouchEvent(event);
            return false;
        });'''

new_pager_init = '''        pager = new ViewPager2(this);
        pager.setBackgroundColor(COLOR_BG);
        pager.setAdapter(new MangaPagerAdapter());
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
            }
        });'''

code = code.replace(old_image_init, new_pager_init)

# 4. root.addView(pager)
code = code.replace('root.addView(image, new FrameLayout.LayoutParams(', 'root.addView(pager, new FrameLayout.LayoutParams(')

# 5. pager.getAdapter().notifyDataSetChanged()
old_load_count_post = '''                if (page < 0) page = 0;
                progressReady = true;
                renderReadingMode();'''
new_load_count_post = '''                if (page < 0) page = 0;
                progressReady = true;
                if (pager != null && pager.getAdapter() != null) {
                    pager.getAdapter().notifyDataSetChanged();
                }
                renderReadingMode();'''
code = code.replace(old_load_count_post, new_load_count_post)

# 6. Remove loadPage()
code = re.sub(r'    private void loadPage\(\) \{.*?\n    \}\n', '', code, flags=re.DOTALL)

# 7. renderReadingMode()
old_render = '''    private void renderReadingMode() {
        dismissControls();
        updateModeButtons();
        if (readingMode == MODE_SCROLL) {
            image.setVisibility(View.GONE);
            scrollView.setVisibility(View.VISIBLE);
            loadContinuousPages();
        } else {
            scrollView.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            loadPage();
        }
    }'''
new_render = '''    private void renderReadingMode() {
        dismissControls();
        updateModeButtons();
        if (readingMode == MODE_SCROLL) {
            pager.setVisibility(View.GONE);
            scrollView.setVisibility(View.VISIBLE);
            loadContinuousPages();
        } else {
            scrollView.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            pager.setCurrentItem(page, false);
        }
    }'''
code = code.replace(old_render, new_render)

# 8. continuousImageView pan and zoom
old_continuous = '''        view.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP);
        view.setPanEnabled(false);
        view.setZoomEnabled(false);'''
new_continuous = '''        view.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP);
        view.setPanEnabled(true);
        view.setZoomEnabled(true);'''
code = code.replace(old_continuous, new_continuous)

# 9. Remove previousPage, nextPage, isZoomed
code = re.sub(r'    private boolean previousPage\(\) \{.*?\n    \}\n', '', code, flags=re.DOTALL)
code = re.sub(r'    private boolean nextPage\(\) \{.*?\n    \}\n', '', code, flags=re.DOTALL)
code = re.sub(r'    private boolean isZoomed\(\) \{.*?\n    \}\n', '', code, flags=re.DOTALL)

# 10. MangaPagerAdapter
adapter_code = '''
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
                imageView.setMaxScale(8f);
                imageView.setPanEnabled(true);
                imageView.setZoomEnabled(true);
                
                GestureDetector tapDetector = new GestureDetector(MangaActivity.this, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        showControls();
                        return true;
                    }
                });
                imageView.setOnTouchListener((view, event) -> {
                    tapDetector.onTouchEvent(event);
                    return false;
                });

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
            holder.imageView.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setText("\\u52a0\\u8f7d\\u4e2d...");

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
                        holder.statusText.setText(error == null ? "\\u52a0\\u8f7d\\u5931\\u8d25" : error);
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
'''

code = code.rsplit('}', 1)[0] + adapter_code

with open('app/src/main/java/com/hemanager/mobile/MangaActivity.java', 'w', encoding='utf-8') as f:
    f.write(code)
print('Done!')
