package maojianwei.nmea.server;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import win.maojianwei.nmea.nmeaserver.R;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 940110;

    private TextView logView;
    private TextView nmeaView;
    private Switch outputSwitch;
    private Switch originSwitch;
    private Switch screenSwitch;

    private int logCount = 0;
    private List<String> logQueue = new ArrayList<>();
    private int nmeaCount = 0;
    private List<String> nmeaLogQueue = new ArrayList<>();

    private boolean hasLoggedStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 🚀 删除了默认的 FLAG_KEEP_SCREEN_ON，现在由开关完全控制，默认不常亮

        initUI();

        GpsNmeaService.uiCallback = new GpsNmeaService.NmeaCallback() {
            @Override
            public void onLogMessage(String msg) {
                programLog(msg);
            }

            @Override
            public void onNmeaMessage(String nmea) {
                nmeaLog(nmea);
            }
        };

        checkAndroidPermission();
    }

    private void initUI() {
        this.logView = findViewById(R.id.logText);
        this.nmeaView = findViewById(R.id.nmeaText);

        TextView maoText = findViewById(R.id.maoText);
        maoText.setText("fork by sbhinx@" + win.maojianwei.nmea.nmeaserver.BuildConfig.VERSION_NAME);

        this.logView.setMovementMethod(new ScrollingMovementMethod());
        this.nmeaView.setMovementMethod(new ScrollingMovementMethod());

        this.nmeaView.setOnClickListener(v -> {
            ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboardManager.setPrimaryClip(ClipData.newPlainText("NMEA", nmeaView.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });

        this.outputSwitch = findViewById(R.id.OutputSwitch);
        this.outputSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GpsNmeaService.needOutput = isChecked;
            programLog("👉 报文输出: " + (isChecked ? "已打开" : "已关闭"));
            if (!isChecked) {
                hasLoggedStart = false;
            }
        });

        this.originSwitch = findViewById(R.id.OriginSwitch);
        this.originSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GpsNmeaService.useApiConvert = isChecked;
            // 恢复原名
            programLog("👉 原生转换: " + (isChecked ? "已打开" : "已关闭"));
        });

        this.screenSwitch = findViewById(R.id.ScreenSwitch);
        this.screenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                programLog("👉 屏幕常亮: 已打开");
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                programLog("👉 屏幕常亮: 已关闭");
            }
        });

        GpsNmeaService.needOutput = true;
        GpsNmeaService.useApiConvert = false;
    }

    private void checkAndroidPermission() {
        List<String> permissionsToRequest = new ArrayList<>();

        // 1. 基础定位权限 (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        // 2. 通知栏显示权限 (Android 13+ 必须，否则前台服务会被悄悄杀掉)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 如果有缺失的权限，统一申请；如果全都有了，直接启动服务
        if (!permissionsToRequest.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(permissionsToRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        } else {
            startEngineService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startEngineService();
            } else {
                Toast.makeText(this, "需要定位和通知权限才能在后台运行", Toast.LENGTH_LONG).show();
                programLog("❌ 权限被拒绝，无法启动引擎");
            }
        }
    }

    private void startEngineService() {
        programLog("正在启动后台 NMEA 引擎...");
        Intent serviceIntent = new Intent(this, GpsNmeaService.class);
        try {
            android.content.ComponentName name;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                name = startForegroundService(serviceIntent);
            } else {
                name = startService(serviceIntent);
            }

            if (name == null) {
                programLog("❌ 致命错误：系统找不到 GpsNmeaService 服务！");
            } else {
                programLog("✅ 已向系统发送引擎启动指令");
            }
        } catch (Exception e) {
            programLog("❌ 启动异常: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        GpsNmeaService.uiCallback = null;
        super.onDestroy();
    }

    public void programLog(String log) {
        if (logQueue.size() >= 50) logQueue.remove(0);
        logQueue.add(++logCount + "  " + log + "\n");
        StringBuilder temp = new StringBuilder();
        for (String msg : logQueue) temp.append(msg);
        logView.setText(temp.toString());
        scrollToBottom(logView);
    }

    public void nmeaLog(String nmea) {
        if (!hasLoggedStart) {
            programLog("✅ 开始输出报文");
            hasLoggedStart = true;
        }

        if (nmeaLogQueue.size() >= 50) nmeaLogQueue.remove(0);
        nmeaLogQueue.add(++nmeaCount + "  " + nmea + "\n\n");
        StringBuilder temp = new StringBuilder();
        for (String msg : nmeaLogQueue) temp.append(msg);
        nmeaView.setText(temp.toString());
        scrollToBottom(nmeaView);
    }

    private void scrollToBottom(TextView textView) {
        textView.post(() -> {
            if (textView.getLayout() != null) {
                int scrollAmount = textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight();
                if (scrollAmount > 0) {
                    textView.scrollTo(0, scrollAmount);
                } else {
                    textView.scrollTo(0, 0);
                }
            }
        });
    }
}