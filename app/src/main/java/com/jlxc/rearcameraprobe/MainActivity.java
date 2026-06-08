package com.jlxc.rearcameraprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "RearCameraProbe";
    private static final int REQ_CAMERA = 1001;

    private TextureView textureView;
    private TextView logView;
    private Spinner cameraSpinner;
    private ArrayAdapter<String> spinnerAdapter;

    private final List<String> cameraIds = new ArrayList<>();
    private int selectedIndex = 0;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();
        startCameraThread();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            scanAll();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF101010);

        textureView = new TextureView(this);
        textureView.setBackgroundColor(0xFF000000);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { log("TextureView ready " + width + "x" + height); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        root.addView(textureView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(16, 16, 16, 16);
        panel.setBackgroundColor(0xDD202020);
        root.addView(panel, new LinearLayout.LayoutParams(dp(430), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Rear/AHD Camera Probe Demo");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        cameraSpinner = new Spinner(this);
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(spinnerAdapter);
        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selectedIndex = position; }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        panel.addView(cameraSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(row1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        addButton(row1, "扫描", v -> scanAll());
        addButton(row1, "打开", v -> openSelectedCamera());
        addButton(row1, "关闭", v -> closeCamera());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        addButton(row2, "下一个", v -> openNextCamera());
        addButton(row2, "系统相机", v -> openSystemCameraIntent());

        TextView hint = new TextView(this);
        hint.setText("用途：验证车机的 AHD/倒车视频输入是否被 Android Camera2 暴露。若能看到画面，说明可从普通 App 预览；若扫不到或打开失败，说明大概率是厂商私有 AVIN/MCU 通道。");
        hint.setTextColor(0xFFCCCCCC);
        hint.setTextSize(12);
        hint.setPadding(0, 10, 0, 10);
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(0xFF00FF99);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void scanAll() {
        log("========== scan start ==========");
        scanDevVideoNodes();
        scanCamera2Devices();
        log("========== scan end ==========");
    }

    private void scanDevVideoNodes() {
        log("/dev/video* scan:");
        File dev = new File("/dev");
        File[] nodes = dev.listFiles((dir, name) -> name.startsWith("video"));
        if (nodes == null || nodes.length == 0) {
            log("  no /dev/video* visible to app");
            return;
        }
        Arrays.sort(nodes, Comparator.comparing(File::getName));
        for (File f : nodes) {
            log("  " + f.getAbsolutePath() + " read=" + f.canRead() + " write=" + f.canWrite() + " exec=" + f.canExecute());
        }
    }

    private void scanCamera2Devices() {
        cameraIds.clear();
        spinnerAdapter.clear();
        try {
            String[] ids = cameraManager.getCameraIdList();
            log("Camera2 ids count=" + ids.length);
            for (String id : ids) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                String facing = facingToString(c.get(CameraCharacteristics.LENS_FACING));
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] previewSizes = map == null ? new Size[0] : map.getOutputSizes(SurfaceTexture.class);

                String item = "ID " + id + " | " + facing + " | " + hardwareLevelToString(level);
                cameraIds.add(id);
                spinnerAdapter.add(item);

                log(item);
                log("  capabilities=" + intArrayToString(caps));
                log("  previewSizes=" + sizesToShortString(previewSizes));
            }
            spinnerAdapter.notifyDataSetChanged();
            if (!cameraIds.isEmpty()) {
                selectedIndex = 0;
                cameraSpinner.setSelection(0);
            }
        } catch (Exception e) {
            log("Camera2 scan error: " + e);
        }
    }

    private void openSelectedCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (cameraIds.isEmpty()) {
            log("No Camera2 device found. Scan first.");
            Toast.makeText(this, "没有扫到 Camera2 设备", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, cameraIds.size() - 1));
        openCameraById(cameraIds.get(selectedIndex));
    }

    private void openNextCamera() {
        if (cameraIds.isEmpty()) {
            scanAll();
            return;
        }
        selectedIndex = (selectedIndex + 1) % cameraIds.size();
        cameraSpinner.setSelection(selectedIndex);
        openCameraById(cameraIds.get(selectedIndex));
    }

    private void openCameraById(String cameraId) {
        closeCamera();
        try {
            log("Opening camera id=" + cameraId);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    log("Camera opened: " + camera.getId());
                    cameraDevice = camera;
                    startPreview(camera);
                }

                @Override public void onDisconnected(CameraDevice camera) {
                    log("Camera disconnected: " + camera.getId());
                    camera.close();
                    if (cameraDevice == camera) cameraDevice = null;
                }

                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera error id=" + camera.getId() + " error=" + error);
                    camera.close();
                    if (cameraDevice == camera) cameraDevice = null;
                }
            }, cameraHandler);
        } catch (SecurityException se) {
            log("Camera permission denied: " + se);
        } catch (CameraAccessException cae) {
            log("Camera access error: " + cae);
        } catch (Exception e) {
            log("Open camera error: " + e);
        }
    }

    private void startPreview(CameraDevice camera) {
        if (!textureView.isAvailable()) {
            log("TextureView not ready yet.");
            return;
        }
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(camera.getId());
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size previewSize = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            log("Preview size=" + previewSize.getWidth() + "x" + previewSize.getHeight());

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            camera.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                        log("Preview started. If this is the AHD/rear input, you should see the image now.");
                    } catch (Exception e) {
                        log("setRepeatingRequest error: " + e);
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Capture session configure failed");
                }
            }, cameraHandler);
        } catch (Exception e) {
            log("Start preview error: " + e);
        }
    }

    private Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            // Prefer 1920x1080 or nearest 16:9 HD size, because AHD_1080P_25 is likely 1920x1080.
            long aspectPenalty = Math.abs((long) s.getWidth() * 9 - (long) s.getHeight() * 16) * 1000L;
            long sizePenalty = Math.abs(pixels - 1920L * 1080L);
            long score = aspectPenalty + sizePenalty;
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception ignored) {}
        try {
            if (cameraDevice != null) {
                log("Closing camera " + cameraDevice.getId());
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception ignored) {}
    }

    private void openSystemCameraIntent() {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivity(intent);
        } catch (Exception e) {
            log("System camera intent failed: " + e);
        }
    }

    private String facingToString(Integer facing) {
        if (facing == null) return "UNKNOWN";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return "FACING_" + facing;
    }

    private String hardwareLevelToString(Integer level) {
        if (level == null) return "LEVEL_UNKNOWN";
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL_LEVEL";
            default: return "LEVEL_" + level;
        }
    }

    private String intArrayToString(int[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String sizesToShortString(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(sizes.length, 12);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(sizes[i].getWidth()).append('x').append(sizes[i].getHeight());
        }
        if (sizes.length > limit) sb.append(", ... total=").append(sizes.length);
        sb.append(']');
        return sb.toString();
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
        Log.i(TAG, line);
        runOnUiThread(() -> {
            logView.append(line + "\n");
        });
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(); } catch (InterruptedException ignored) {}
            cameraThread = null;
            cameraHandler = null;
        }
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        stopCameraThread();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanAll();
        } else {
            log("Camera permission not granted.");
        }
    }
}
