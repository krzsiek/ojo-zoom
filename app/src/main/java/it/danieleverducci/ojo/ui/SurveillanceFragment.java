package it.danieleverducci.ojo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.danieleverducci.ojo.R;
import it.danieleverducci.ojo.Settings;
import it.danieleverducci.ojo.databinding.FragmentSurveillanceBinding;
import it.danieleverducci.ojo.entities.Camera;
import it.danieleverducci.ojo.utils.DpiUtils;

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
            "--avcodec-codec=h264",
            //"--file-logging",
            //"--logfile=vlc-log.txt"
    };

    private FragmentSurveillanceBinding binding;
    private List<CameraView> cameraViews = new ArrayList<>();
    private boolean fullscreenCameraView = false;
    private LinearLayout.LayoutParams cameraViewLayoutParams;
    private LinearLayout.LayoutParams rowLayoutParams;
    private LinearLayout.LayoutParams hiddenLayoutParams;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        int viewMargin = DpiUtils.DpToPixels(container.getContext(), 2);
        cameraViewLayoutParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.0f
        );
        cameraViewLayoutParams.setMargins(viewMargin,viewMargin,viewMargin,viewMargin);

        rowLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
        );

        // 1,1 instead of 0,0 because the latter doesn't work on android 13+
        hiddenLayoutParams = new LinearLayout.LayoutParams(1, 1);

        binding = FragmentSurveillanceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();

        leanbackMode(true);

        fullscreenCameraView = false;
        addAllCameras();

        // Start playback for all streams
        for (CameraView cv : cameraViews) {
            cv.startPlayback();
        }

        expandToCameraViewIfRequired();

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
                if(fullscreenCameraView && cameraViews.size() > 1) {
                    fullscreenCameraView = false;
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

        disposeAllCameras();
    }


    private void addAllCameras() {
        Settings settings = Settings.fromDisk(getContext());
        List<Camera> cc = settings.getCameras();

        int[] gridSize = calcGridDimensionsBasedOnNumberOfElements(cc.size());
        int camIdx = 0;
        for (int r = 0; r < gridSize[0]; r++) {
            // Create row and add to row container
            LinearLayout row = new LinearLayout(getContext());
            binding.gridRowContainer.addView(row, rowLayoutParams);
            // Add camera viewers to the row
            for (int c = 0; c < gridSize[1]; c++) {
                if ( camIdx < cc.size() ) {
                    Camera cam = cc.get(camIdx);
                    CameraView cv = addCameraView(cam, row);
                    cv.startPlayback();
                    cv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Toggle single/multi camera views
                            fullscreenCameraView = !fullscreenCameraView;
                            if (fullscreenCameraView) {
                                hideAllCameraViewsButNot(v);
                            } else {
                                showAllCameras();
                            }
                        }
                    });
                } else {
                    // Cameras are less than the maximum number of cells in grid: fill remaining cells with empty views
                    View ev = new View(getContext());
                    ev.setBackgroundColor(getResources().getColor(R.color.purple_700));
                    row.addView(ev, cameraViewLayoutParams);
                }
                camIdx++;
            }
        }
    }

    private void disposeAllCameras() {
        // Destroy players, libs etc
        for (CameraView cv : cameraViews) {
            cv.destroy();
        }
        cameraViews.clear();
        // Remove views
        binding.gridRowContainer.removeAllViews();
    }

    protected void hideAllCameraViewsButNot(View cameraView) {
        for (int i = 0; i < binding.gridRowContainer.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) binding.gridRowContainer.getChildAt(i);
            boolean emptyRow = true;
            for (int j = 0; j < row.getChildCount(); j++) {
                View cam = row.getChildAt(j);
                if (cameraView == cam)
                    emptyRow = false;
                else
                    cam.setLayoutParams(hiddenLayoutParams);
            }
            if (emptyRow)
                row.setLayoutParams(hiddenLayoutParams);
        }
    }

    protected void showAllCameras() {
        for (CameraView cv : cameraViews)
            cv.resetZoom();
        for (int i = 0; i < binding.gridRowContainer.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) binding.gridRowContainer.getChildAt(i);
            row.setLayoutParams(rowLayoutParams);
            for (int j = 0; j < row.getChildCount(); j++) {
                View cam = row.getChildAt(j);
                cam.setLayoutParams(cameraViewLayoutParams);
            }
        }
    }

    private CameraView addCameraView(Camera camera, LinearLayout rowContainer) {
        CameraView cv = new CameraView(
                getContext(),
                camera
        );

        // Add to layout
        rowContainer.addView(cv.container, cameraViewLayoutParams);

        cameraViews.add(cv);
        return cv;
    }

    /**
     * Returns the dimensions of the grid based on the number of elements.
     * Es: to display 3 elements is needed a 4-element grid, with 2 elements per side (a 2x2 grid)
     * Es: to display 6 elements is needed a 9-element grid, with 3 elements per side (a 2x3 grid)
     * Es: to display 7 elements is needed a 9-element grid, with 3 elements per side (a 3x3 grid)
     * @param elements
     */
    private int[] calcGridDimensionsBasedOnNumberOfElements(int elements) {
        int rows = 1;
        int cols = 1;
        while (rows * cols < elements) {
            cols += 1;
            if (rows * cols >= elements) break;
            rows += 1;
        }
        int[] dimensions = {rows, cols};
        return dimensions;
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
        if (index < 0 || cameraViews.size() <= index) {
            return;
        }
        fullscreenCameraView = true;
        hideAllCameraViewsButNot(cameraViews.get(index).container);
    }

    private void expandByName(String name) {
        for(CameraView cameraView: cameraViews) {
            if (cameraView.camera.getName().equals(name)) {
                fullscreenCameraView = true;
                hideAllCameraViewsButNot(cameraView.container);
                break;
            }
        }
    }

    /**
     * True when one camera, picked from the grid, fills the screen
     */
    public boolean isCameraFullscreen() {
        return fullscreenCameraView && cameraViews.size() > 1;
    }

    /**
     * Zoom is allowed only when a single camera fills the screen
     */
    private boolean isZoomAllowed() {
        return fullscreenCameraView || cameraViews.size() == 1;
    }

    /**
     * Contains all entities (views and java entities) related to a camera stream viewer
     */
    private class CameraView {
        final static private float MAX_ZOOM = 8f;
        final static private float DOUBLE_TAP_ZOOM = 2.5f;

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
        private boolean surfaceAttached = false;   // VLC output connected to the TextureView surface
        private boolean playRequested = false;

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

            // Video output: connect VLC only once the TextureView surface really exists
            // (libVLC 2.x crashes if given a TextureView whose surface isn't created yet)
            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                    if (mediaPlayer == null || surfaceAttached)
                        return;
                    ivlcVout.setVideoSurface(surfaceTexture);
                    ivlcVout.attachViews();
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
                }
            });
        }

        private void setupGestures(Context context) {
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
                    });

            container.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                    tapAlreadyHandled = false;
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
            libvlc.release();
            libvlc = null;
            mediaPlayer.release();
            mediaPlayer = null;
            ivlcVout = null;
        }
    }
}