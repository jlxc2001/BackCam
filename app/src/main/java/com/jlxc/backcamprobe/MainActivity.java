package com.jlxc.backcamprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "BackCamAHDProbe";
    private static final int REQ_CAMERA = 1001;

    private TextureView textureView;
    private TextView logView;
    private final StringBuilder logBuffer = new StringBuilder();

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private final ArrayList<String> camera2Ids = new ArrayList<>();
    private int camera2Index = 0;
    private int camera1Index = 0;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Camera legacyCamera;

    private final ArrayList<String> candidatePackages = new ArrayList<>();
    private int candidateIndex = 0;

    private Size lastChosenSize = new Size(1280, 720);

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            log("TextureView 已就绪: " + width + "x" + height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            log("TextureView 尺寸变化: " + width + "x" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            closePreview();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildUi();
        startCameraThread();
        requestCameraPermissionIfNeeded();

        log("BackCam AHD Probe 启动");
        log("设备: Android " + android.os.Build.VERSION.RELEASE
                + " / SDK " + android.os.Build.VERSION.SDK_INT
                + " / ABI " + Arrays.toString(android.os.Build.SUPPORTED_ABIS));
        log("说明: 本 Demo 尝试读取 Android 已暴露出来的 Camera2/Camera1/AV视频节点。AHD_1080P_25 的模拟信号必须先由车机硬件解码，普通 App 不能直接把模拟电信号软件解码成画面。");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f5f5);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(textureListener);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 5f);
        root.addView(textureView, previewLp);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(8, 6, 8, 6);

        buttons.addView(makeButton("一键安全扫描", new View.OnClickListener() {
            @Override public void onClick(View v) { safeScanAll(); }
        }));
        buttons.addView(makeButton("Camera2打开", new View.OnClickListener() {
            @Override public void onClick(View v) { openCamera2Selected(); }
        }));
        buttons.addView(makeButton("Camera1打开", new View.OnClickListener() {
            @Override public void onClick(View v) { openCamera1Selected(); }
        }));
        buttons.addView(makeButton("下一个ID", new View.OnClickListener() {
            @Override public void onClick(View v) { nextCameraIndex(); }
        }));
        buttons.addView(makeButton("关闭预览", new View.OnClickListener() {
            @Override public void onClick(View v) { closePreview(); }
        }));
        buttons.addView(makeButton("查找原厂影像App", new View.OnClickListener() {
            @Override public void onClick(View v) { findVendorVideoApps(); }
        }));
        buttons.addView(makeButton("打开候选App", new View.OnClickListener() {
            @Override public void onClick(View v) { launchNextCandidateApp(); }
        }));
        buttons.addView(makeButton("复制日志", new View.OnClickListener() {
            @Override public void onClick(View v) { copyLogToClipboard(); }
        }));
        buttons.addView(makeButton("相机权限设置", new View.OnClickListener() {
            @Override public void onClick(View v) { openAppSettings(); }
        }));

        hsv.addView(buttons);
        root.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextColor(0xff202124);
        logView.setPadding(12, 8, 12, 8);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this);
        sv.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 4f));

        setContentView(root);
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setOnClickListener(listener);
        b.setPadding(16, 4, 16, 4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 0, 6, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("camera-worker");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void requestCameraPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("正在请求 CAMERA 权限");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            log("CAMERA 权限结果: " + ok);
        }
    }

    private boolean hasCameraPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void safeScanAll() {
        log("========== 一键安全扫描开始 ==========");
        scanCamera2();
        scanCamera1();
        scanDevVideo();
        new Thread(new Runnable() {
            @Override public void run() {
                final String props = runShell("getprop | grep -i -E 'camera|cam|video|avin|avm|ahd|back|rear|mcu|car' 2>&1");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        log("--- getprop 关键词 ---\n" + trimLong(props, 12000));
                        log("========== 一键安全扫描结束 ==========");
                    }
                });
            }
        }).start();
    }

    private void scanCamera2() {
        camera2Ids.clear();
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                log("CameraManager 为空");
                return;
            }
            String[] ids = manager.getCameraIdList();
            log("Camera2 ID 数量: " + ids.length);
            for (String id : ids) {
                camera2Ids.add(id);
                try {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    String facingText = facingToText(facing);
                    log("Camera2 ID=" + id + " facing=" + facingText);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                        log("  SurfaceTexture尺寸: " + summarizeSizes(sizes));
                    } else {
                        log("  StreamConfigurationMap 为空");
                    }
                } catch (Throwable t) {
                    log("  读取 Camera2 ID=" + id + " 特征失败: " + stackShort(t));
                }
            }
            if (camera2Ids.isEmpty()) log("没有发现标准 Camera2 设备。AV视频可能是厂商私有通道。");
        } catch (Throwable t) {
            log("扫描 Camera2 失败: " + stackShort(t));
        }
    }

    private void scanCamera1() {
        try {
            int n = Camera.getNumberOfCameras();
            log("Camera1 数量: " + n);
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < n; i++) {
                try {
                    Camera.getCameraInfo(i, info);
                    log("Camera1 index=" + i + " facing=" + (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT" : "BACK")
                            + " orientation=" + info.orientation);
                } catch (Throwable t) {
                    log("  读取 Camera1 index=" + i + " 失败: " + stackShort(t));
                }
            }
        } catch (Throwable t) {
            log("扫描 Camera1 失败: " + stackShort(t));
        }
    }

    private void scanDevVideo() {
        try {
            File dev = new File("/dev");
            File[] files = dev.listFiles();
            if (files == null) {
                log("/dev 无法列出");
            } else {
                ArrayList<File> videos = new ArrayList<>();
                for (File f : files) {
                    if (f.getName().startsWith("video") || f.getName().toLowerCase(Locale.US).contains("v4l")) {
                        videos.add(f);
                    }
                }
                Collections.sort(videos, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
                });
                log("/dev video/v4l 节点数量: " + videos.size());
                for (File f : videos) {
                    log("  " + f.getAbsolutePath() + " canRead=" + f.canRead() + " canWrite=" + f.canWrite());
                }
            }
        } catch (Throwable t) {
            log("扫描 /dev 失败: " + stackShort(t));
        }

        new Thread(new Runnable() {
            @Override public void run() {
                final String ls = runShell("ls -l /dev/video* /dev/v4l/by-path/* 2>&1");
                runOnUiThread(new Runnable() {
                    @Override public void run() { log("--- shell ls /dev/video* ---\n" + trimLong(ls, 12000)); }
                });
            }
        }).start();
    }

    private void openCamera2Selected() {
        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded();
            toast("请先允许相机权限");
            return;
        }
        if (!textureView.isAvailable()) {
            log("TextureView 还没准备好，稍后再点一次");
            return;
        }
        if (camera2Ids.isEmpty()) scanCamera2();
        if (camera2Ids.isEmpty()) {
            log("没有 Camera2 ID 可打开");
            return;
        }
        closePreview();
        final String id = camera2Ids.get(camera2Index % camera2Ids.size());
        log("尝试打开 Camera2 ID=" + id + "，优先请求 1920x1080 预览");
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) throw new RuntimeException("CameraManager null");
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    log("Camera2 已打开: " + id);
                    cameraDevice = camera;
                    createCamera2Preview(id);
                }

                @Override public void onDisconnected(CameraDevice camera) {
                    log("Camera2 断开: " + id);
                    safeClose(camera);
                }

                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera2 打开错误: ID=" + id + " error=" + error);
                    safeClose(camera);
                }
            }, cameraHandler);
        } catch (Throwable t) {
            log("openCamera2Selected 异常: " + stackShort(t));
        }
    }

    private void createCamera2Preview(String id) {
        try {
            if (cameraDevice == null) return;
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) {
                log("SurfaceTexture 为空");
                return;
            }
            lastChosenSize = chooseCamera2Size(id);
            st.setDefaultBufferSize(lastChosenSize.getWidth(), lastChosenSize.getHeight());
            final Surface surface = new Surface(st);
            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        log("Camera2 预览已启动，尺寸 " + lastChosenSize.getWidth() + "x" + lastChosenSize.getHeight());
                    } catch (Throwable t) {
                        log("Camera2 setRepeatingRequest 失败: " + stackShort(t));
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Camera2 预览配置失败");
                }
            }, cameraHandler);
        } catch (Throwable t) {
            log("createCamera2Preview 异常: " + stackShort(t));
        }
    }

    private Size chooseCamera2Size(String id) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(1280, 720);
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) return new Size(1280, 720);
            for (Size s : sizes) {
                if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
            }
            Size best = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                    if (s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
                }
            }
            return best;
        } catch (Throwable t) {
            log("选择 Camera2 尺寸失败: " + stackShort(t));
            return new Size(1280, 720);
        }
    }

    private void openCamera1Selected() {
        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded();
            toast("请先允许相机权限");
            return;
        }
        if (!textureView.isAvailable()) {
            log("TextureView 还没准备好，稍后再点一次");
            return;
        }
        closePreview();
        try {
            int count = Camera.getNumberOfCameras();
            if (count <= 0) {
                log("Camera1 没有可用设备");
                return;
            }
            int idx = camera1Index % count;
            log("尝试打开 Camera1 index=" + idx + "，优先请求 1920x1080 预览");
            legacyCamera = Camera.open(idx);
            Camera.Parameters p = legacyCamera.getParameters();
            Camera.Size chosen = chooseLegacySize(p.getSupportedPreviewSizes());
            if (chosen != null) {
                p.setPreviewSize(chosen.width, chosen.height);
                log("Camera1 选择尺寸: " + chosen.width + "x" + chosen.height);
            }
            try {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } catch (Throwable ignored) { }
            legacyCamera.setParameters(p);
            legacyCamera.setPreviewTexture(textureView.getSurfaceTexture());
            legacyCamera.startPreview();
            log("Camera1 预览已启动");
        } catch (Throwable t) {
            log("openCamera1Selected 异常: " + stackShort(t));
            closePreview();
        }
    }

    private Camera.Size chooseLegacySize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        for (Camera.Size s : sizes) {
            if (s.width == 1920 && s.height == 1080) return s;
        }
        Camera.Size best = sizes.get(0);
        for (Camera.Size s : sizes) {
            if (s.width <= 1920 && s.height <= 1080) {
                if (s.width * s.height > best.width * best.height) best = s;
            }
        }
        return best;
    }

    private void nextCameraIndex() {
        camera2Index++;
        camera1Index++;
        String c2 = camera2Ids.isEmpty() ? "无" : camera2Ids.get(camera2Index % camera2Ids.size());
        log("切换到下一个：Camera2 ID=" + c2 + " / Camera1 index=" + camera1Index);
    }

    private void closePreview() {
        try {
            if (captureSession != null) {
                try { captureSession.stopRepeating(); } catch (Throwable ignored) { }
                try { captureSession.close(); } catch (Throwable ignored) { }
                captureSession = null;
            }
        } catch (Throwable t) {
            log("关闭 Camera2 session 异常: " + stackShort(t));
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
                log("Camera2 已关闭");
            }
        } catch (Throwable t) {
            log("关闭 Camera2 device 异常: " + stackShort(t));
        }
        try {
            if (legacyCamera != null) {
                try { legacyCamera.stopPreview(); } catch (Throwable ignored) { }
                legacyCamera.release();
                legacyCamera = null;
                log("Camera1 已关闭");
            }
        } catch (Throwable t) {
            log("关闭 Camera1 异常: " + stackShort(t));
        }
    }

    private void findVendorVideoApps() {
        candidatePackages.clear();
        candidateIndex = 0;
        log("========== 查找原厂影像/倒车/AVIN App ==========");
        final String[] keys = new String[]{
                "camera", "cam", "front", "rear", "back", "reverse", "avin", "avm", "dvr", "video", "car",
                "影像", "摄像", "倒车", "后视", "前视", "全景", "视频", "车辆"
        };
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                String pkg = ai.packageName == null ? "" : ai.packageName;
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Throwable ignored) { }
                String low = (pkg + " " + label).toLowerCase(Locale.US);
                boolean hit = false;
                for (String k : keys) {
                    if (low.contains(k.toLowerCase(Locale.US))) { hit = true; break; }
                }
                if (hit) {
                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    String launchable = launch == null ? "不可直接启动" : "可启动";
                    log("候选: " + label + " / " + pkg + " / " + launchable);
                    if (launch != null) candidatePackages.add(pkg);
                }
            }
            log("可直接启动的候选 App 数量: " + candidatePackages.size());
            if (!candidatePackages.isEmpty()) log("点“打开候选App”会逐个尝试启动。启动后若能看到原厂前视/倒车界面，说明更适合走调用原厂 App 路线。");
        } catch (Throwable t) {
            log("查找候选 App 失败: " + stackShort(t));
        }
    }

    private void launchNextCandidateApp() {
        if (candidatePackages.isEmpty()) findVendorVideoApps();
        if (candidatePackages.isEmpty()) {
            log("没有可启动候选 App");
            return;
        }
        String pkg = candidatePackages.get(candidateIndex % candidatePackages.size());
        candidateIndex++;
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                log("候选 App 无启动 Intent: " + pkg);
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            log("正在启动候选 App: " + pkg);
            startActivity(i);
        } catch (Throwable t) {
            log("启动候选 App 失败: " + pkg + " / " + stackShort(t));
        }
    }

    private void copyLogToClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("BackCamAHDProbeLog", logBuffer.toString()));
                toast("日志已复制");
            }
        } catch (Throwable t) {
            log("复制日志失败: " + stackShort(t));
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            log("打开设置失败: " + stackShort(t));
        }
    }

    private String facingToText(Integer facing) {
        if (facing == null) return "UNKNOWN";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (android.os.Build.VERSION.SDK_INT >= 23 && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return String.valueOf(facing);
    }

    private String summarizeSizes(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return "无";
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < sizes.length && i < 18; i++) {
            out.add(sizes[i].getWidth() + "x" + sizes[i].getHeight());
        }
        if (sizes.length > 18) out.add("...共" + sizes.length + "个");
        return out.toString();
    }

    private void safeClose(CameraDevice camera) {
        try { camera.close(); } catch (Throwable ignored) { }
        if (cameraDevice == camera) cameraDevice = null;
    }

    private String runShell(String cmd) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines < 500) {
                sb.append(line).append('\n');
                lines++;
            }
            try { p.waitFor(); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            sb.append("runShell异常: ").append(stackShort(t)).append('\n');
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
        return sb.toString();
    }

    private String trimLong(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...已截断，总长度=" + s.length();
    }

    private String stackShort(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        StackTraceElement[] arr = t.getStackTrace();
        for (int i = 0; i < arr.length && i < 4; i++) {
            sb.append("\n  at ").append(arr[i].toString());
        }
        return sb.toString();
    }

    private void log(final String msg) {
        Log.d(TAG, msg);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendLog(msg);
        } else {
            runOnUiThread(new Runnable() {
                @Override public void run() { appendLog(msg); }
            });
        }
    }

    private void appendLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logBuffer.append('[').append(time).append("] ").append(msg).append('\n');
        logView.setText(logBuffer.toString());
        int scrollAmount = logView.getLayout() == null ? 0 : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
        if (scrollAmount > 0) logView.scrollTo(0, scrollAmount); else logView.scrollTo(0, 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        closePreview();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        super.onDestroy();
    }
}
