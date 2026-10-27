package it.danieleverducci.ojo.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Locale;

import it.danieleverducci.ojo.R;

import it.danieleverducci.ojo.Settings;
import it.danieleverducci.ojo.SharedPreferencesManager;
import it.danieleverducci.ojo.databinding.ActivityMainBinding;
import it.danieleverducci.ojo.utils.MediaSaver;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 1;

    private ActivityMainBinding binding;
    private OnBackButtonPressedListener onBackButtonPressedListener;

    // Buttons are hidden while cameras are shown. A long press anywhere (or a long press of OK
    // on a remote) shows them for a while: settings in the grid, snapshot and record on a camera
    // filling the screen. While recording the record button stays on screen.
    private boolean hasCameras = false;
    private boolean recording = false;
    private boolean okLongPressHandled = false;
    private static final long FAB_LONG_PRESS_MS = 1500;
    private static final long FAB_VISIBLE_MS = 5000;
    private static final long FLASH_MS = 150;
    private final Handler fabHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideControlsNow;
    private final Runnable longPressRunnable = () -> {
        if (binding == null)
            return;
        binding.fab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showControlsTemporarily();
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
        binding.fabSnapshot.setOnClickListener(view -> withStoragePermission(this::takeSnapshot));
        binding.fabRecord.setOnClickListener(view -> withStoragePermission(this::toggleRecording));
    }

    @Override
    protected void onStart() {
        boolean rotationEnabledSetting = SharedPreferencesManager.loadRotationEnabled(this);
        this.setRequestedOrientation(rotationEnabledSetting ? ActivityInfo.SCREEN_ORIENTATION_FULL_USER : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hasCameras = !Settings.fromDisk(this).getCameras().isEmpty();
        fabHandler.removeCallbacks(hideControlsRunnable);
        fabHandler.removeCallbacks(longPressRunnable);
        hideFab(binding.fabSnapshot);
        hideFab(binding.fabRecord);
        if (hasCameras)
            hideFab(binding.fab);
        else
            showFab(binding.fab);   // Nothing to watch yet: settings button is the only thing on screen
    }

    @Override
    protected void onPause() {
        super.onPause();
        fabHandler.removeCallbacks(hideControlsRunnable);
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
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (hasCameras) {
            // Remote control (Android TV): long press OK on a camera filling the screen shows snapshot and record
            if (isConfirmKey(event.getKeyCode()) && canCapture() && !isControlFocused()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                    if (!okLongPressHandled) {
                        okLongPressHandled = true;
                        binding.fab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showControlsTemporarily();
                        binding.fabSnapshot.requestFocus();
                    }
                    return true;
                }
            }
            if (okLongPressHandled && isConfirmKey(event.getKeyCode())) {
                // Swallow the rest of the long press, it must not click the button that just got the focus
                if (event.getAction() == KeyEvent.ACTION_UP)
                    okLongPressHandled = false;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (areControlsVisible())
                    keepControlsVisible();   // Moving between the buttons with the remote
                else if (!isCameraFullscreen())
                    showControlsTemporarily();   // Any key shows the settings button in grid view
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean isControlFocused() {
        return binding.fab.hasFocus() || binding.fabSnapshot.hasFocus() || binding.fabRecord.hasFocus();
    }

    private SurveillanceFragment getSurveillanceFragment() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_surveillance);
        return f instanceof SurveillanceFragment ? (SurveillanceFragment) f : null;
    }

    private boolean isCameraFullscreen() {
        SurveillanceFragment f = getSurveillanceFragment();
        return f != null && f.isCameraFullscreen();
    }

    private boolean canCapture() {
        SurveillanceFragment f = getSurveillanceFragment();
        return f != null && f.canCapture();
    }

    private boolean areControlsVisible() {
        return binding.fab.getVisibility() == View.VISIBLE || binding.fabSnapshot.getVisibility() == View.VISIBLE;
    }

    private void showControlsTemporarily() {
        if (isCameraFullscreen())
            hideFab(binding.fab);   // Fullscreen camera: back returns to the grid, where settings are
        else
            showFab(binding.fab);
        if (canCapture()) {
            showFab(binding.fabSnapshot);
            showFab(binding.fabRecord);
        }
        keepControlsVisible();
    }

    private void keepControlsVisible() {
        fabHandler.removeCallbacks(hideControlsRunnable);
        fabHandler.postDelayed(hideControlsRunnable, FAB_VISIBLE_MS);
    }

    private void hideControlsNow() {
        fabHandler.removeCallbacks(hideControlsRunnable);
        if (binding == null || !hasCameras)
            return;
        hideFab(binding.fab);
        hideFab(binding.fabSnapshot);
        if (!recording)
            hideFab(binding.fabRecord);
    }

    private static void showFab(FloatingActionButton fab) {
        fab.show();
    }

    private static void hideFab(FloatingActionButton fab) {
        // GONE instead of INVISIBLE: the other buttons move to the screen edge
        fab.hide(new FloatingActionButton.OnVisibilityChangedListener() {
            @Override
            public void onHidden(FloatingActionButton fab) {
                fab.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Called by SurveillanceFragment when a camera goes fullscreen or back to the grid
     */
    public void onCameraModeChanged() {
        if (!hasCameras)
            return;
        if (isCameraFullscreen())
            hideFab(binding.fab);
        if (!canCapture()) {
            hideFab(binding.fabSnapshot);
            if (!recording)
                hideFab(binding.fabRecord);
        }
    }

    /**
     * Called by SurveillanceFragment when a recording starts or stops, and every second while recording
     */
    public void onRecordingStateChanged(boolean recording, long elapsedMs, long maxMs) {
        boolean wasRecording = this.recording;
        this.recording = recording;
        int color = ContextCompat.getColor(this, recording ? R.color.recording_red : R.color.fab_background);
        binding.fabRecord.setBackgroundTintList(ColorStateList.valueOf(color));
        if (recording) {
            showFab(binding.fabRecord);
            binding.recordingTime.setVisibility(View.VISIBLE);
            binding.recordingTime.setText("● " + formatTime(elapsedMs) + " / " + formatTime(maxMs));
        } else {
            binding.recordingTime.setVisibility(View.GONE);
            if (wasRecording) {
                if (canCapture())
                    keepControlsVisible();   // Hide with the other buttons
                else
                    hideFab(binding.fabRecord);
            }
        }
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void takeSnapshot() {
        SurveillanceFragment f = getSurveillanceFragment();
        if (f == null)
            return;
        keepControlsVisible();
        if (f.takeSnapshot()) {
            binding.fabSnapshot.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            flash();
        }
    }

    private void toggleRecording() {
        SurveillanceFragment f = getSurveillanceFragment();
        if (f == null)
            return;
        binding.fabRecord.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        f.toggleRecording();
        keepControlsVisible();
    }

    /**
     * Camera-like white flash: the snapshot has been taken
     */
    private void flash() {
        View v = binding.snapshotFlash;
        v.animate().cancel();
        v.setAlpha(0.8f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(0f).setStartDelay(FLASH_MS).setDuration(FLASH_MS * 2)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    /**
     * Android 6 - 9: asks for the storage permission before saving snapshots or recordings
     * (the action does not run then)
     */
    private void withStoragePermission(Runnable action) {
        if (!MediaSaver.needsStoragePermission(this)) {
            action.run();
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION)
            return;
        // The cameras restart after the permission dialog: the user presses the button again
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, granted ? R.string.storage_permission_granted : R.string.storage_permission_denied,
                Toast.LENGTH_LONG).show();
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
