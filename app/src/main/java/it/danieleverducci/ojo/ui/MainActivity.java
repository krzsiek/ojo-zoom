package it.danieleverducci.ojo.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import it.danieleverducci.ojo.R;

import it.danieleverducci.ojo.Settings;
import it.danieleverducci.ojo.SharedPreferencesManager;
import it.danieleverducci.ojo.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private ActivityMainBinding binding;
    private OnBackButtonPressedListener onBackButtonPressedListener;

    // Settings button is hidden while cameras are shown.
    // In grid view (or with a single camera) a long press anywhere shows it for a while.
    private boolean hasCameras = false;
    private static final long FAB_LONG_PRESS_MS = 1500;
    private static final long FAB_VISIBLE_MS = 5000;
    private final Handler fabHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideFabRunnable = () -> {
        if (binding != null && hasCameras)
            binding.fab.hide();
    };
    private final Runnable longPressRunnable = () -> {
        if (binding == null || isCameraFullscreen())
            return;
        binding.fab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showFabTemporarily();
    };
    private float touchDownX, touchDownY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Interface can go below notches
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            );
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.fab.setOnClickListener(view -> openSettings());
    }

    @Override
    protected void onStart() {
        boolean rotationEnabledSetting = SharedPreferencesManager.loadRotationEnabled(this);
        this.setRequestedOrientation(rotationEnabledSetting ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hasCameras = !Settings.fromDisk(this).getCameras().isEmpty();
        fabHandler.removeCallbacks(hideFabRunnable);
        fabHandler.removeCallbacks(longPressRunnable);
        if (hasCameras)
            binding.fab.hide();
        else
            binding.fab.show();   // Nothing to watch yet: settings button is the only thing on screen
    }

    @Override
    protected void onPause() {
        super.onPause();
        fabHandler.removeCallbacks(hideFabRunnable);
        fabHandler.removeCallbacks(longPressRunnable);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (hasCameras) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = ev.getX();
                    touchDownY = ev.getY();
                    fabHandler.removeCallbacks(longPressRunnable);
                    fabHandler.postDelayed(longPressRunnable, FAB_LONG_PRESS_MS);
                    break;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                    if (Math.abs(ev.getX() - touchDownX) > slop || Math.abs(ev.getY() - touchDownY) > slop)
                        fabHandler.removeCallbacks(longPressRunnable);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:   // Second finger: it's a pinch, not a long press
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    fabHandler.removeCallbacks(longPressRunnable);
                    break;
            }
        }
        boolean handled = super.dispatchTouchEvent(ev);
        // A tap may have just opened a camera fullscreen: no button there
        if (hasCameras && isCameraFullscreen())
            hideFabNow();
        return handled;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Remote control (Android TV): any key shows the button in grid view
        if (hasCameras && event.getAction() == KeyEvent.ACTION_DOWN && !isCameraFullscreen()
                && binding.fab.getVisibility() != android.view.View.VISIBLE)
            showFabTemporarily();
        return super.dispatchKeyEvent(event);
    }

    private boolean isCameraFullscreen() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_surveillance);
        return f instanceof SurveillanceFragment && ((SurveillanceFragment) f).isCameraFullscreen();
    }

    private void showFabTemporarily() {
        fabHandler.removeCallbacks(hideFabRunnable);
        binding.fab.show();
        fabHandler.postDelayed(hideFabRunnable, FAB_VISIBLE_MS);
    }

    private void hideFabNow() {
        fabHandler.removeCallbacks(hideFabRunnable);
        binding.fab.hide();
    }

    public void setOnBackButtonPressedListener(OnBackButtonPressedListener onBackButtonPressedListener) {
        this.onBackButtonPressedListener = onBackButtonPressedListener;
    }

    @Override
    public void onBackPressed() {
        if (this.onBackButtonPressedListener != null && this.onBackButtonPressedListener.onBackPressed())
            return;
        super.onBackPressed();
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivity(i);
    }

}