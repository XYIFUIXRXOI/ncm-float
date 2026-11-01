package com.example.ncmfloat;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatWindowService extends Service {

    private static final String CHANNEL_ID = "ncm_float_channel";
    private static final String ACTION_STOP = "com.example.ncmfloat.STOP";

    // Material 3 (Google) palette
    private static final int COLOR_PRIMARY = 0xFF0B57D0;            // Google blue
    private static final int COLOR_ON_PRIMARY = 0xFFFFFFFF;         // white icon on primary
    private static final int COLOR_PRIMARY_CONTAINER = 0xFFD3E3FD;  // light tonal
    private static final int COLOR_OUTLINE = 0xFFDADCE0;            // hairline outline

    private WindowManager mWm;
    private WindowManager.LayoutParams mParams;
    private LinearLayout mRoot;
    private View mHandle;
    private LinearLayout mPanel;
    private ImageButton mBtnPrev;
    private ImageButton mBtnPlayPause;
    private ImageButton mBtnNext;

    private boolean mExpanded = false;
    private boolean mIsPlaying = false;
    private int mRestY = 0;

    private boolean mMoved = false;
    private float mDownRawX, mDownRawY;
    private float mStartX, mStartY;
    private int mStatusBar;
    private int mScreenW, mScreenH;
    private int mDp;

    private Handler mHandler = new Handler();
    private Runnable mLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            stopSelf();
        }
    };
    private Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mExpanded) {
                mIsPlaying = mAudioManager.isMusicActive();
                updatePlayPauseIcon();
                mHandler.postDelayed(this, 2000);
            }
        }
    };

    private AudioManager mAudioManager;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDp = Math.round(getResources().getDisplayMetrics().density);
        startForegroundCompat();
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mIsPlaying = mAudioManager.isMusicActive();
        initWindow();
    }

    // ---------- foreground notification ----------

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "悬浮窗控制", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent stopIntent = new Intent(this, FloatWindowService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("网易云音乐悬浮窗")
                .setContentText("点击可回到控制界面")
                .setContentIntent(openPi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 23) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi).build());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Notification n = builder.build();
        startForeground(1, n);
    }

    // ---------- floating window ----------

    private void initWindow() {
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mScreenW = mWm.getDefaultDisplay().getWidth();
        mScreenH = mWm.getDefaultDisplay().getHeight();
        mStatusBar = getStatusBarHeight();

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);

        // collapsed handle on TOP: a tonal round ball with a music note
        mHandle = buildHandle();
        mRoot.addView(mHandle);

        // expanded panel BELOW the handle, so the ball never moves when expanding
        mPanel = buildPanel();
        mRoot.addView(mPanel);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = mScreenW - 60 * mDp;
        mParams.y = mScreenH / 3;
        mRestY = mParams.y;

        mWm.addView(mRoot, mParams);
    }

    private View buildHandle() {
        TextView handle = new TextView(this);
        handle.setText("♪");
        handle.setTextSize(22);
        handle.setTextColor(COLOR_PRIMARY);
        handle.setGravity(Gravity.CENTER);
        handle.setLayoutParams(new LinearLayout.LayoutParams(48 * mDp, 48 * mDp));

        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(COLOR_PRIMARY_CONTAINER);
        handle.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x330B57D0), oval, oval));

        handle.setOnTouchListener(mHandleTouchListener);
        return handle;
    }

    private LinearLayout buildPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(16 * mDp);
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(Math.max(1, mDp / 2), COLOR_OUTLINE);
        panel.setBackground(bg);
        panel.setPadding(6 * mDp, 6 * mDp, 6 * mDp, 6 * mDp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 6 * mDp;
        panel.setLayoutParams(lp);
        panel.setVisibility(View.GONE);

        // Material 3 icon buttons, vertical, 4dp apart:
        //  prev / next = tonal circles, play-pause = prominent primary circle
        mBtnPrev = makeMediaButton(R.drawable.ic_skip_previous, false);
        mBtnPrev.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        mBtnPlayPause = makeMediaButton(R.drawable.ic_play_arrow, true);
        mBtnPlayPause.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        mBtnNext = makeMediaButton(R.drawable.ic_skip_next, false);
        mBtnNext.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));

        panel.addView(mBtnPrev);
        LinearLayout.LayoutParams lpPlay = (LinearLayout.LayoutParams) mBtnPlayPause.getLayoutParams();
        lpPlay.topMargin = 4 * mDp;
        LinearLayout.LayoutParams lpNext = (LinearLayout.LayoutParams) mBtnNext.getLayoutParams();
        lpNext.topMargin = 4 * mDp;
        panel.addView(mBtnPlayPause);
        panel.addView(mBtnNext);
        return panel;
    }

    private ImageButton makeMediaButton(int iconRes, boolean primary) {
        ImageButton btn = new ImageButton(this);
        int size = 42 * mDp;
        btn.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        btn.setImageResource(iconRes);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(primary ? COLOR_PRIMARY : COLOR_PRIMARY_CONTAINER);
        btn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x330B57D0), circle, circle));
        btn.setColorFilter(primary ? COLOR_ON_PRIMARY : COLOR_PRIMARY, PorterDuff.Mode.SRC_IN);
        return btn;
    }

    private View.OnTouchListener mHandleTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownRawX = event.getRawX();
                    mDownRawY = event.getRawY();
                    mStartX = mParams.x;
                    mStartY = mParams.y;
                    mMoved = false;
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).start();
                    mHandler.postDelayed(mLongPressRunnable, 600);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - mDownRawX;
                    float dy = event.getRawY() - mDownRawY;
                    if (Math.hypot(dx, dy) > 8 * mDp) {
                        mMoved = true;
                        mHandler.removeCallbacks(mLongPressRunnable);
                    }
                    if (mMoved) {
                        int w = Math.max(mRoot.getWidth(), 48 * mDp);
                        int h = Math.max(mRoot.getHeight(), 48 * mDp);
                        mParams.x = clamp(Math.round(mStartX + dx), 0, mScreenW - w);
                        mParams.y = clamp(Math.round(mStartY + dy), mStatusBar, mScreenH - h);
                        mWm.updateViewLayout(mRoot, mParams);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mHandler.removeCallbacks(mLongPressRunnable);
                    if (mMoved) {
                        mRestY = mParams.y;
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                        toggleExpand();
                    }
                    return true;
            }
            return false;
        }
    };

    private void toggleExpand() {
        mExpanded = !mExpanded;
        if (mExpanded) {
            expand();
        } else {
            collapse();
        }
    }

    private void expand() {
        mRestY = mParams.y;
        mPanel.setVisibility(View.VISIBLE);
        mPanel.setAlpha(0f);
        mPanel.setTranslationY(24 * mDp);
        mPanel.setScaleX(0.92f);
        mPanel.setScaleY(0.92f);

        // make sure the panel fits on screen: if not, shift the whole window up
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(mScreenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int panelH = mPanel.getMeasuredHeight();
        int expandedH = 48 * mDp + 6 * mDp + panelH;
        int targetY = mParams.y;
        int maxY = mScreenH - expandedH - 8 * mDp;
        if (targetY > maxY) {
            targetY = Math.max(mStatusBar, maxY);
        }

        // panel entrance animation (fade + slide + scale)
        mPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        // window position: ball stays in place; only shifts up if needed at the bottom edge
        if (targetY != mParams.y) {
            animateWindowTo(targetY, 220);
        } else {
            mWm.updateViewLayout(mRoot, mParams);
        }

        mIsPlaying = mAudioManager.isMusicActive();
        updatePlayPauseIcon();
        mHandler.postDelayed(mPollRunnable, 2000);
    }

    private void collapse() {
        mHandler.removeCallbacks(mPollRunnable);
        mPanel.animate()
                .alpha(0f)
                .translationY(20 * mDp)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> mPanel.setVisibility(View.GONE))
                .start();
        if (mParams.y != mRestY) {
            animateWindowTo(mRestY, 160);
        } else {
            mWm.updateViewLayout(mRoot, mParams);
        }
    }

    private void animateWindowTo(int targetY, int duration) {
        int startY = mParams.y;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(duration);
        va.setInterpolator(new DecelerateInterpolator(1.4f));
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            mParams.y = Math.round(startY + (targetY - startY) * f);
            mWm.updateViewLayout(mRoot, mParams);
        });
        va.start();
    }

    private void updatePlayPauseIcon() {
        if (mBtnPlayPause == null || mPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        mBtnPlayPause.setImageResource(
                mIsPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        mBtnPlayPause.setColorFilter(COLOR_ON_PRIMARY, PorterDuff.Mode.SRC_IN);
    }

    private void sendMediaKey(int keyCode) {
        long t = SystemClock.uptimeMillis();
        mAudioManager.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        mAudioManager.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            // refresh icon after the command lands
            mHandler.postDelayed(() -> {
                mIsPlaying = mAudioManager.isMusicActive();
                updatePlayPauseIcon();
            }, 400);
        }
    }

    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return 24 * mDp;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        if (mRoot != null && mWm != null) {
            try {
                mWm.removeView(mRoot);
            } catch (Exception ignored) {
            }
        }
    }
}
