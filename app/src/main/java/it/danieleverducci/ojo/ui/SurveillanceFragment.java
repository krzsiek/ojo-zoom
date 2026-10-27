package it.danieleverducci.ojo.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.danieleverducci.ojo.R;
import it.danieleverducci.ojo.Settings;
import it.danieleverducci.ojo.SharedPreferencesManager;
import it.danieleverducci.ojo.databinding.FragmentSurveillanceBinding;
import it.danieleverducci.ojo.entities.Camera;
import it.danieleverducci.ojo.utils.DpiUtils;
import it.danieleverducci.ojo.utils.MediaSaver;

/**
 * Some streams to test:
 * rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mov
 * rtsp://demo:demo@ipvmdemo.dyndns.org:5541/onvif-media/media.amp?profile=profile_1_h264&sessiontimeout=60&streamtype=unicast
 */
public class SurveillanceFragment extends Fragment {

    final static private String TAG = "SurveillanceFragment";
    final static private String[] VLC_OPTIONS = new String[]{
            "--aout=opensles",
            //"--audio-time-stretch", // time stretching
            //"-vvv", // verbosity
            //"--file-logging",
            //"--logfile=vlc-log.txt"
    };
    // Recording safety: don't start below MIN_FREE_TO_START_RECORDING, and stop while
    // there is still room to copy the file to the gallery plus MIN_FREE_WHILE_RECORDING
    final static private long MIN_FREE_TO_START_RECORDING = 1024L * 1024 * 1024;
    final static private long MIN_FREE_WHILE_RECORDING = 512L * 1024 * 1024;
    final static private long RECORDING_TICK_MS = 1000;
    final static private int MAX_SNAPSHOT_SIDE = 4096;

    private FragmentSurveillanceBinding binding;
    private List<CameraView> cameraViews = new ArrayList<>();
    private CameraView fullscreenCamera;   // Camera opened fullscreen from the grid, null in grid view

    // Recording in progress (one at a time)
    private CameraView recordingCamera;
    private File recordingDir;
    private long recordingStartMs;
    private long recordingMaxMs;
    private final Handler recordingHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordingTick = new Runnable() {
        @Override
        public void run() {
            if (recordingCamera == null)
                return;
            long elapsed = SystemClock.elapsedRealtime() - recordingStartMs;
            if (elapsed >= recordingMaxMs) {
                stopRecording(R.string.recording_stopped_time_limit);
                return;
            }
            if (MediaSaver.getFreeBytes(requireContext()) < MIN_FREE_WHILE_RECORDING + MediaSaver.getSize(recordingDir)) {
                stopRecording(R.string.recording_stopped_low_space);
                return;
            }
            notifyRecordingState();
            recordingHandler.postDelayed(this, RECORDING_TICK_MS);
        }
    };

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentSurveillanceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();

        leanbackMode(true);

        fullscreenCamera = null;
        addAllCameras();

        // Start playback for all streams
        for (CameraView cv : cameraViews) {
            cv.startPlayback();
        }

        expandToCameraViewIfRequired();

        // Recordings interrupted by a crash are still in the app storage: move them to the gallery
        MediaSaver.publishLeftoverRecordings(requireContext());

        // Register for back pressed events
        ((MainActivity)getActivity()).setOnBackButtonPressedListener(new OnBackButtonPressedListener() {
            @Override
            public boolean onBackPressed() {
                // First back press while zoomed: just reset the zoom
                for (CameraView cv : cameraViews) {
                    if (cv.isZoomed()) {
                        cv.resetZoom();
                        return true;
                    }
                }
                if(isCameraFullscreen()) {
                    showAllCameras();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Goes fullscreen igoring the device screen insets (camera etc)
     */
    private void leanbackMode(boolean leanback) {
        Window w = requireActivity().getWindow();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            return;

        if (leanback) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

            // Hide system bar
            WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(w, w.getDecorView());
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            // System bar is hidden when not touched for a while
            windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            // Show system bar
            //WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(w, w.getDecorView());
            //windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        leanbackMode(false);

        stopRecording(0);
        disposeAllCameras();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotation: grid in landscape, list in portrait. Views stay where they are, so streams keep playing.
        if (binding != null)
            binding.cameraGrid.requestLayout();
    }


    private void addAllCameras() {
        Settings settings = Settings.fromDisk(getContext());
        List<Camera> cc = settings.getCameras();

        int[] gridSize = CameraGridLayout.calcGridDimensionsBasedOnNumberOfElements(cc.size());
        binding.cameraGrid.setCameraCount(cc.size());
        for (Camera cam : cc) {
            CameraView cv = addCameraView(cam);
            cv.startPlayback();
            cv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Toggle single/multi camera views
                    if (isCameraFullscreen())
                        showAllCameras();
                    else if (cameraViews.size() > 1)
                        showFullscreen(cv);
                }
            });
        }
        // Cameras are less than the maximum number of cells in grid: fill remaining cells with empty views
        for (int i = cc.size(); i < gridSize[0] * gridSize[1]; i++) {
            View ev = new View(getContext());
            ev.setBackgroundColor(getResources().getColor(R.color.purple_700));
            binding.cameraGrid.addView(ev);
        }
    }

    private void disposeAllCameras() {
        // Destroy players, libs etc
        for (CameraView cv : cameraViews) {
            cv.destroy();
        }
        cameraViews.clear();
        fullscreenCamera = null;
        // Remove views
        binding.cameraGrid.setFullscreenChild(null);
        binding.cameraGrid.removeAllViews();
    }

    protected void showFullscreen(CameraView cameraView) {
        fullscreenCamera = cameraView;
        binding.cameraGrid.setFullscreenChild(cameraView.container);
        binding.gridScroll.scrollTo(0, 0);
        cameraView.container.requestFocus();
        ((MainActivity) requireActivity()).onCameraModeChanged();
    }

    protected void showAllCameras() {
        stopRecording(0);
        CameraView previous = fullscreenCamera;
        fullscreenCamera = null;
        for (CameraView cv : cameraViews)
            cv.resetZoom();
        binding.cameraGrid.setFullscreenChild(null);
        // Portrait list: scroll back to the camera that was fullscreen
        if (previous != null)
            binding.gridScroll.post(() -> {
                if (binding != null && fullscreenCamera == null)
                    binding.gridScroll.scrollTo(0, previous.container.getTop());
            });
        ((MainActivity) requireActivity()).onCameraModeChanged();
    }

    /**
     * Fullscreen: shows the next (+1) or previous (-1) camera
     */
    private void switchFullscreenCamera(int direction) {
        if (fullscreenCamera == null || cameraViews.size() < 2)
            return;
        stopRecording(0);
        int n = cameraViews.size();
        int next = (cameraViews.indexOf(fullscreenCamera) + direction + n) % n;
        fullscreenCamera.resetZoom();
        showFullscreen(cameraViews.get(next));
    }

    private CameraView addCameraView(Camera camera) {
        CameraView cv = new CameraView(
                getContext(),
                camera
        );

        // Add to layout
        binding.cameraGrid.addView(cv.container);

        cameraViews.add(cv);
        return cv;
    }

    private void expandToCameraViewIfRequired() {
        final String EXTRA_CAMERA_NUMBER = "it.danieleverducci.ojo.CAMERA_NUMBER";
        final String EXTRA_CAMERA_NAME = "it.danieleverducci.ojo.CAMERA_NAME";
        final String OPEN_CAMERA = "it.danieleverducci.ojo.OPEN_CAMERA";

        if (this.getActivity() == null) {
            return;
        }

        Intent intent = this.getActivity().getIntent();

        if (OPEN_CAMERA.equals(intent.getAction())) {
            String cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME);
            if (cameraName == null) {
                int cameraNumber = intent.getIntExtra(EXTRA_CAMERA_NUMBER, 0) - 1;
                expandByIndex(cameraNumber);
                return;
            }
            expandByName(cameraName);
        }
    }

    private void expandByIndex(int index) {
        if (index < 0 || cameraViews.size() <= index || cameraViews.size() < 2) {
            return;
        }
        showFullscreen(cameraViews.get(index));
    }

    private void expandByName(String name) {
        if (cameraViews.size() < 2)
            return;
        for(CameraView cameraView: cameraViews) {
            if (cameraView.camera.getName().equals(name)) {
                showFullscreen(cameraView);
                break;
            }
        }
    }

    /**
     * True when one camera, picked from the grid, fills the screen
     */
    public boolean isCameraFullscreen() {
        return fullscreenCamera != null && cameraViews.size() > 1;
    }

    /**
     * The camera filling the screen (fullscreen or the only one), null in grid view
     */
    private CameraView getActiveCamera() {
        if (fullscreenCamera != null)
            return fullscreenCamera;
        return cameraViews.size() == 1 ? cameraViews.get(0) : null;
    }

    /**
     * Snapshots and recordings are possible when a single camera fills the screen
     */
    public boolean canCapture() {
        return getActiveCamera() != null;
    }

    /**
     * Zoom is allowed only when a single camera fills the screen
     */
    private boolean isZoomAllowed() {
        return canCapture();
    }

    /**
     * Saves the current frame of the camera filling the screen to Pictures/OjoZoom
     * @return false if there is no picture to save yet
     */
    public boolean takeSnapshot() {
        CameraView cv = getActiveCamera();
        Bitmap frame = cv == null ? null : cv.captureFrame();
        if (frame == null) {
            Toast.makeText(getContext(), R.string.snapshot_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
        Context appContext = requireContext().getApplicationContext();
        MediaSaver.saveSnapshot(appContext, frame, cv.camera.getName(), success ->
                Toast.makeText(appContext, success ? R.string.snapshot_saved : R.string.snapshot_failed, Toast.LENGTH_SHORT).show());
        return true;
    }

    public boolean isRecording() {
        return recordingCamera != null;
    }

    public void toggleRecording() {
        if (isRecording())
            stopRecording(R.string.recording_saving);
        else
            startRecording();
    }

    private void startRecording() {
        CameraView cv = getActiveCamera();
        if (cv == null || isRecording())
            return;
        if (MediaSaver.getFreeBytes(requireContext()) < MIN_FREE_TO_START_RECORDING) {
            Toast.makeText(getContext(), R.string.recording_low_space, Toast.LENGTH_LONG).show();
            return;
        }
        File dir = MediaSaver.newRecordingDir(requireContext(), cv.camera.getName());
        if (!cv.startRecording(dir)) {
            MediaSaver.finishRecording(requireContext(), dir, success -> {});
            Toast.makeText(getContext(), R.string.recording_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        recordingCamera = cv;
        recordingDir = dir;
        recordingStartMs = SystemClock.elapsedRealtime();
        recordingMaxMs = SharedPreferencesManager.loadMaxRecordingMinutes(requireContext()) * 60_000L;
        recordingHandler.postDelayed(recordingTick, RECORDING_TICK_MS);
        notifyRecordingState();
    }

    /**
     * @param messageRes message to show, 0 for none
     */
    private void stopRecording(int messageRes) {
        if (recordingCamera == null)
            return;
        recordingHandler.removeCallbacks(recordingTick);
        recordingCamera.stopRecording();
        Context appContext = requireContext().getApplicationContext();
        MediaSaver.finishRecording(appContext, recordingDir, success ->
                Toast.makeText(appContext, success ? R.string.recording_saved : R.string.recording_failed, Toast.LENGTH_SHORT).show());
        if (messageRes != 0)
            Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show();
        recordingCamera = null;
        recordingDir = null;
        notifyRecordingState();
    }

    private void notifyRecordingState() {
        if (getActivity() instanceof MainActivity) {
            long elapsed = isRecording() ? SystemClock.elapsedRealtime() - recordingStartMs : 0;
            ((MainActivity) getActivity()).onRecordingStateChanged(isRecording(), elapsed, recordingMaxMs);
        }
    }

    /**
     * Contains all entities (views and java entities) related to a camera stream viewer
     */
    private class CameraView {
        final static private float MAX_ZOOM = 8f;
        final static private float DOUBLE_TAP_ZOOM = 2.5f;
        final static private int SWIPE_MIN_DISTANCE_DP = 80;

        protected FrameLayout container;   // Grid cell: handles focus, clicks and gestures, clips zoomed video
        protected TextureView videoView;   // Video output (TextureView supports scale/translation, SurfaceView doesn't)
        protected MediaPlayer mediaPlayer;
        protected IVLCVout ivlcVout;
        protected Camera camera;
        protected LibVLC libvlc;

        private float zoom = 1f;
        private float panX = 0f;
        private float panY = 0f;
        private boolean tapAlreadyHandled = false;
        private boolean multiTouch = false;        // More than one finger in the current gesture
        private boolean surfaceAttached = false;   // VLC output connected to the TextureView surface
        private boolean playRequested = false;
        private int videoWidth = 0;                // Displayed video size (sample aspect ratio applied)
        private int videoHeight = 0;

        public CameraView(Context context, Camera camera) {
            this.camera = camera;
            this.libvlc = new LibVLC(context, new ArrayList<>(Arrays.asList(VLC_OPTIONS)));

            container = new FrameLayout(context);
            container.setClipChildren(true);
            container.setFocusable(true);
            // Foreground, not background: the video would cover a background drawable
            container.setOnFocusChangeListener((view, hasFocus) -> container.setForeground(
                    hasFocus ? ContextCompat.getDrawable(context, R.drawable.focus_border) : null));

            videoView = new TextureView(context);
            videoView.setPivotX(0f);
            videoView.setPivotY(0f);
            videoView.setKeepScreenOn(true);
            container.addView(videoView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            setupGestures(context);

            // Create media player
            mediaPlayer = new MediaPlayer(libvlc);

            ivlcVout = mediaPlayer.getVLCVout();

            // Load media
            Media m = new Media(libvlc, Uri.parse(camera.getRtspUrl()));
            mediaPlayer.setMedia(m);
            m.release();

            // Video output: connect VLC only once the TextureView surface really exists
            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                    if (mediaPlayer == null || surfaceAttached)
                        return;
                    ivlcVout.setVideoSurface(surfaceTexture);
                    // VLC tells the video size, the aspect ratio is kept with the TextureView transform
                    ivlcVout.attachViews((vout, w, h, visibleWidth, visibleHeight, sarNum, sarDen) -> {
                        if (visibleWidth <= 0 || visibleHeight <= 0)
                            return;
                        double sar = sarNum > 0 && sarDen > 0 ? (double) sarNum / sarDen : 1.0;
                        videoWidth = (int) Math.round(visibleWidth * sar);
                        videoHeight = visibleHeight;
                        container.post(() -> applyVideoAspect());
                    });
                    ivlcVout.setWindowSize(width, height);
                    surfaceAttached = true;
                    if (playRequested)
                        mediaPlayer.play();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                    if (mediaPlayer != null && width > 0 && height > 0)
                        ivlcVout.setWindowSize(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    if (mediaPlayer != null && surfaceAttached) {
                        mediaPlayer.stop();
                        ivlcVout.detachViews();
                    }
                    surfaceAttached = false;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                }
            });

            // Register for view resize events
            container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                    // Size changed (fullscreen toggle, rotation): set rendering size and reset zoom
                    if (mediaPlayer != null && r - l > 0 && b - t > 0)
                        ivlcVout.setWindowSize(r - l, b - t);
                    resetZoom();
                    applyVideoAspect();
                }
            });
        }

        /**
         * Fits the video in the view keeping its aspect ratio (black bars on the sides or top and bottom)
         */
        private void applyVideoAspect() {
            float viewW = container.getWidth();
            float viewH = container.getHeight();
            Matrix matrix = new Matrix();
            if (videoWidth > 0 && videoHeight > 0 && viewW > 1 && viewH > 1) {
                float videoAspect = (float) videoWidth / videoHeight;
                float fitW = viewW;
                float fitH = viewW / videoAspect;
                if (fitH > viewH) {
                    fitH = viewH;
                    fitW = viewH * videoAspect;
                }
                matrix.setScale(fitW / viewW, fitH / viewH);
                matrix.postTranslate((viewW - fitW) / 2f, (viewH - fitH) / 2f);
            }
            videoView.setTransform(matrix);
        }

        private void setupGestures(Context context) {
            final int swipeMinDistance = DpiUtils.DpToPixels(context, SWIPE_MIN_DISTANCE_DP);
            final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            if (!isZoomAllowed())
                                return false;
                            zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                            return true;
                        }
                    });

            final GestureDetector gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }

                        @Override
                        public boolean onSingleTapUp(MotionEvent e) {
                            // In grid mode toggle immediately (no double tap there)
                            if (!isZoomAllowed()) {
                                tapAlreadyHandled = true;
                                container.performClick();
                                return true;
                            }
                            return false;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            // Ignore single taps while zoomed to avoid leaving fullscreen by accident
                            if (!tapAlreadyHandled && !isZoomed())
                                container.performClick();
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (!isZoomAllowed())
                                return false;
                            if (isZoomed())
                                resetZoom();
                            else
                                zoomBy(DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                            return true;
                        }

                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                            if (!isZoomed())
                                return false;
                            panX -= distanceX;
                            panY -= distanceY;
                            applyTransform();
                            return true;
                        }

                        @Override
                        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                            // Fullscreen without zoom: swipe left for the next camera, right for the previous one
                            if (e1 == null || multiTouch || isZoomed() || fullscreenCamera != CameraView.this)
                                return false;
                            float dx = e2.getX() - e1.getX();
                            float dy = e2.getY() - e1.getY();
                            if (Math.abs(dx) < swipeMinDistance || Math.abs(dx) < 1.5f * Math.abs(dy))
                                return false;
                            switchFullscreenCamera(dx < 0 ? 1 : -1);
                            return true;
                        }
                    });

            container.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tapAlreadyHandled = false;
                        multiTouch = false;
                        // A camera filling the screen gets all the gestures, the list must not scroll
                        if (isZoomAllowed())
                            container.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        multiTouch = true;
                        break;
                }
                scaleDetector.onTouchEvent(event);
                gestureDetector.onTouchEvent(event);
                return true;
            });
        }

        /**
         * Multiplies the zoom by factor, keeping the point (focusX, focusY) still on screen
         */
        private void zoomBy(float factor, float focusX, float focusY) {
            float newZoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
            float realFactor = newZoom / zoom;
            panX = focusX - realFactor * (focusX - panX);
            panY = focusY - realFactor * (focusY - panY);
            zoom = newZoom;
            applyTransform();
        }

        private void applyTransform() {
            int w = container.getWidth();
            int h = container.getHeight();
            // Do not allow panning outside the image
            panX = Math.max(w - w * zoom, Math.min(0f, panX));
            panY = Math.max(h - h * zoom, Math.min(0f, panY));
            if (zoom <= 1f) {
                zoom = 1f;
                panX = 0f;
                panY = 0f;
            }
            videoView.setScaleX(zoom);
            videoView.setScaleY(zoom);
            videoView.setTranslationX(panX);
            videoView.setTranslationY(panY);
        }

        public boolean isZoomed() {
            return zoom > 1.001f;
        }

        public void resetZoom() {
            zoom = 1f;
            panX = 0f;
            panY = 0f;
            applyTransform();
        }

        public void setOnClickListener(View.OnClickListener listener) {
            container.setOnClickListener(listener);
        }

        /**
         * The whole current frame (not only the zoomed part), at the video resolution when known
         * @return null if no picture has been shown yet
         */
        public Bitmap captureFrame() {
            if (!videoView.isAvailable() || mediaPlayer == null || !mediaPlayer.isPlaying())
                return null;
            try {
                // getBitmap() ignores the aspect transform: asking for the video size gives the full frame
                if (videoWidth > 0 && videoHeight > 0) {
                    float scale = Math.min(1f, (float) MAX_SNAPSHOT_SIDE / Math.max(videoWidth, videoHeight));
                    return videoView.getBitmap(Math.round(videoWidth * scale), Math.round(videoHeight * scale));
                }
                return videoView.getBitmap();
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.e(TAG, "Unable to capture frame", e);
                return null;
            }
        }

        /**
         * Starts saving the stream, as received from the camera, to a file in dir
         */
        public boolean startRecording(File dir) {
            if (mediaPlayer == null || !mediaPlayer.isPlaying())
                return false;
            return mediaPlayer.record(dir.getAbsolutePath());
        }

        public void stopRecording() {
            if (mediaPlayer != null)
                mediaPlayer.record(null);
        }

        /**
         * Starts the playback.
         */
        public void startPlayback() {
            playRequested = true;
            if (mediaPlayer != null && surfaceAttached && !mediaPlayer.isPlaying())
                mediaPlayer.play();
        }

        /**
         * Destroys the object and frees the memory
         */
        public void destroy() {
            if (libvlc == null) {
                Log.e(TAG, this.toString() + " already destroyed");
                return;
            }

            playRequested = false;
            mediaPlayer.stop();
            if (surfaceAttached) {
                ivlcVout.detachViews();
                surfaceAttached = false;
            }
            mediaPlayer.release();
            mediaPlayer = null;
            libvlc.release();
            libvlc = null;
            ivlcVout = null;
        }
    }
}
