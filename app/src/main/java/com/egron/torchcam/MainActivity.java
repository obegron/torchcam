package com.egron.torchcam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int FOCUS_BOX_SIZE = 180;

    private final Semaphore cameraLock = new Semaphore(1);

    private TextureView previewView;
    private TextView messageView;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private Size previewSize;
    private String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCameraIfReady();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraLock.release();
            cameraDevice = camera;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraLock.release();
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraLock.release();
            camera.close();
            cameraDevice = null;
            showMessage("Camera error");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new TextureView(this);
        previewView.setSurfaceTextureListener(surfaceTextureListener);
        previewView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                focusAt(event.getX(), event.getY());
                view.performClick();
            }
            return true;
        });
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ImageButton closeButton = new ImageButton(this);
        closeButton.setImageResource(R.drawable.ic_close);
        closeButton.setBackgroundColor(Color.TRANSPARENT);
        closeButton.setColorFilter(Color.WHITE);
        closeButton.setContentDescription("Close");
        closeButton.setOnClickListener(view -> finish());
        int closeSize = dp(56);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(closeSize, closeSize);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.topMargin = dp(18);
        closeParams.rightMargin = dp(12);
        root.addView(closeButton, closeParams);

        messageView = new TextView(this);
        messageView.setTextColor(Color.WHITE);
        messageView.setTextSize(16);
        messageView.setGravity(Gravity.CENTER);
        messageView.setBackgroundColor(0x99000000);
        messageView.setVisibility(View.GONE);
        root.addView(messageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        hideSystemUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showMessage("Camera permission required");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        startCameraThread();
        if (previewView.isAvailable()) {
            openCameraIfReady();
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            messageView.setVisibility(View.GONE);
            openCameraIfReady();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            showMessage("Camera permission denied");
        }
    }

    private void openCameraIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || !previewView.isAvailable()
                || cameraDevice != null) {
            return;
        }

        try {
            selectCamera();
            if (cameraId == null) {
                showMessage("No back camera with flash found");
                return;
            }
            configureTransform(previewView.getWidth(), previewView.getHeight());
            if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                showMessage("Camera busy");
                return;
            }
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (CameraAccessException | InterruptedException e) {
            cameraLock.release();
            showMessage("Unable to open camera");
        }
    }

    private void selectCamera() throws CameraAccessException {
        if (cameraId != null) {
            return;
        }
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (facing != null
                    && facing == CameraCharacteristics.LENS_FACING_BACK
                    && Boolean.TRUE.equals(hasFlash)) {
                cameraId = id;
                cameraCharacteristics = characteristics;
                previewSize = choosePreviewSize(characteristics);
                return;
            }
        }
    }

    private Size choosePreviewSize(CameraCharacteristics characteristics) {
        Size[] sizes = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(SurfaceTexture.class);
        List<Size> candidates = new ArrayList<>(Arrays.asList(sizes));
        Collections.sort(candidates, Comparator.comparingInt(size -> size.getWidth() * size.getHeight()));
        for (Size size : candidates) {
            if (size.getWidth() >= 1280 && size.getHeight() >= 720) {
                return size;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null || cameraDevice == null) {
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            applyPreviewSettings(previewRequestBuilder);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                return;
                            }
                            captureSession = session;
                            startRepeatingPreview();
                            messageView.setVisibility(View.GONE);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            showMessage("Unable to start camera preview");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            showMessage("Unable to start camera preview");
        }
    }

    private void applyPreviewSettings(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
    }

    private void startRepeatingPreview() {
        try {
            if (captureSession != null && previewRequestBuilder != null) {
                applyPreviewSettings(previewRequestBuilder);
                captureSession.setRepeatingRequest(
                        previewRequestBuilder.build(),
                        null,
                        cameraHandler
                );
            }
        } catch (CameraAccessException e) {
            showMessage("Unable to keep torch enabled");
        }
    }

    private void focusAt(float x, float y) {
        if (captureSession == null || previewRequestBuilder == null || cameraCharacteristics == null) {
            return;
        }

        Rect sensorRect = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (sensorRect == null) {
            return;
        }

        MeteringRectangle focusArea = buildFocusArea(sensorRect, x, y);
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewRequestBuilder.build(), null, cameraHandler);

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{focusArea});
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            captureSession.capture(
                    previewRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(
                                CameraCaptureSession session,
                                CaptureRequest request,
                                TotalCaptureResult result
                        ) {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                            startRepeatingPreview();
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            startRepeatingPreview();
        }
    }

    private MeteringRectangle buildFocusArea(Rect sensorRect, float viewX, float viewY) {
        int viewWidth = Math.max(previewView.getWidth(), 1);
        int viewHeight = Math.max(previewView.getHeight(), 1);
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        float normalizedX = viewX / viewWidth;
        float normalizedY = viewY / viewHeight;
        int sensorX;
        int sensorY;

        if (sensorOrientation == 90) {
            sensorX = sensorRect.left + Math.round(sensorRect.width() * normalizedY);
            sensorY = sensorRect.top + Math.round(sensorRect.height() * (1f - normalizedX));
        } else if (sensorOrientation == 270) {
            sensorX = sensorRect.left + Math.round(sensorRect.width() * (1f - normalizedY));
            sensorY = sensorRect.top + Math.round(sensorRect.height() * normalizedX);
        } else {
            sensorX = sensorRect.left + Math.round(sensorRect.width() * normalizedX);
            sensorY = sensorRect.top + Math.round(sensorRect.height() * normalizedY);
        }

        int halfSize = FOCUS_BOX_SIZE;
        int left = clamp(sensorX - halfSize, sensorRect.left, sensorRect.right - 1);
        int top = clamp(sensorY - halfSize, sensorRect.top, sensorRect.bottom - 1);
        int right = clamp(sensorX + halfSize, left + 1, sensorRect.right);
        int bottom = clamp(sensorY + halfSize, top + 1, sensorRect.bottom);
        return new MeteringRectangle(new Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());

        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max(
                (float) viewHeight / previewSize.getHeight(),
                (float) viewWidth / previewSize.getWidth()
        );
        matrix.postScale(scale, scale, centerX, centerY);
        matrix.postRotate(90, centerX, centerY);
        previewView.setTransform(matrix);
    }

    private void closeCamera() {
        try {
            cameraLock.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            cameraLock.release();
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("TorchCamCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void showMessage(String message) {
        runOnUiThread(() -> {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
