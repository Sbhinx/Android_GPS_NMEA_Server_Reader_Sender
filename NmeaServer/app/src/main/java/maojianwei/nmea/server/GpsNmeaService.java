package maojianwei.nmea.server;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import win.maojianwei.nmea.nmeaserver.R;

import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaGGA;

public class GpsNmeaService extends Service {

    private static final String CHANNEL_ID = "NmeaServiceChannel";
    private static final int GPS_PORT = 8888;

    public interface NmeaCallback {
        void onLogMessage(String msg);
        void onNmeaMessage(String nmea);
    }
    public static NmeaCallback uiCallback = null;

    private PowerManager.WakeLock wakeLock;
    private LocationManager mLocationManager;
    private MaoGpsListener maoGpsListener;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback flpCallback;
    private int lastSatelliteCount = 8;

    private List<Socket> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private ExecutorService threadPool;
    private boolean exitServer;

    public static boolean needOutput = true;
    public static boolean useApiConvert = false;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS NMEA 引擎")
                .setContentText("正在后台运行...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
        startForeground(1, notification);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaoNmea::EngineWakeLock");
            wakeLock.acquire();
        }

        createNmeaServer();

        try {
            this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            startLocationEngines();
        } catch (Throwable t) {
            broadcastLog("⚠️ 警告: 定位引擎初始化失败: " + t.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        broadcastLog("⚠️ 检测到 App 被划走，正在彻底关闭后台引擎...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopLocationEngines();
        destroyNmeaServer();
        super.onDestroy();
    }

    @TargetApi(24)
    private void startLocationEngines() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            broadcastLog("❌ 错误: 缺少定位权限");
            return;
        }

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (mLocationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            maoGpsListener = new MaoGpsListener();
            mLocationManager.registerGnssStatusCallback(maoGpsListener, new Handler(Looper.getMainLooper()));
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100, 0, location -> {});
        }

        if (fusedLocationClient != null) {
            LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 100)
                    .setMinUpdateIntervalMillis(100)
                    .setMaxUpdateDelayMillis(0)
                    .setMinUpdateDistanceMeters(0)
                    .build();

            flpCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult == null) return;
                    if (!useApiConvert) {
                        for (Location location : locationResult.getLocations()) {
                            int safeSatCount = (lastSatelliteCount > 0) ? lastSatelliteCount : 8;
                            String gga = convertNmeaGGA(location, safeSatCount);
                            commitNmeaMessage(gga);
                        }
                    }
                }
            };
            fusedLocationClient.requestLocationUpdates(locationRequest, flpCallback, Looper.getMainLooper());
            broadcastLog("✅ 10Hz FLP 引擎启动成功");
        }
    }

    private void stopLocationEngines() {
        if (fusedLocationClient != null && flpCallback != null) {
            fusedLocationClient.removeLocationUpdates(flpCallback);
        }
        if (mLocationManager != null && maoGpsListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mLocationManager.unregisterGnssStatusCallback(maoGpsListener);
        }
    }

    // 🚀 核心增加：底层探测设备 IP 的方法（类似 ifconfig）
    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    // 过滤掉 127.0.0.1，只抓取真实的 IPv4 局域网地址
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            // 忽略异常，保底返回本地回环
        }
        return "127.0.0.1 (未连接网络)";
    }

    private void createNmeaServer() {
        exitServer = false;
        threadPool = Executors.newCachedThreadPool();

        threadPool.submit(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(GPS_PORT));

                // 获取本机的网卡 IP
                String deviceIp = getLocalIpAddress();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    broadcastLog("✅ Socket服务器已在端口 " + GPS_PORT + " 启动");
                    // 🚀 自动打印设备 IP 供连接使用
                    broadcastLog("🌐 本机通信 IP: " + deviceIp);
                    broadcastLog("⏳ 等待卫星...");
                }, 500);

                while (!exitServer) {
                    try {
                        Socket client = server.accept();
                        clients.add(client);
                        String ip = client.getInetAddress() != null ? client.getInetAddress().toString() : "未知IP";
                        broadcastLog("🔗 客户端已连接: " + ip);
                        broadcastLog("📡 当前连接设备数: " + clients.size());

                        threadPool.submit(() -> {
                            try {
                                if (client.getInputStream().read() == -1) {
                                    handleClientDisconnect(client, false);
                                }
                            } catch (IOException e) {
                                handleClientDisconnect(client, true);
                            }
                        });

                    } catch (IOException e) {
                        break;
                    }
                }
            } catch (IOException e) {
                broadcastLog("❌ 服务器创建失败: " + e.getMessage());
            }
        });
    }

    private void handleClientDisconnect(Socket client, boolean isException) {
        if (clients.remove(client)) {
            String ip = client.getInetAddress() != null ? client.getInetAddress().toString() : "未知IP";
            if (isException) {
                broadcastLog("❌ 客户端异常断开: " + ip);
            } else {
                broadcastLog("👋 客户端已断开: " + ip);
            }
            broadcastLog("📡 当前连接设备数: " + clients.size());
            try {
                client.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void destroyNmeaServer() {
        exitServer = true;
        try {
            if (server != null) server.close();
            for (Socket c : clients) c.close();
            clients.clear();
            if (threadPool != null) threadPool.shutdownNow();
        } catch (Exception e) {
            // ignore
        }
    }

    private void commitNmeaMessage(String nmea) {
        broadcastNmea(nmea);

        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> {
                for (Socket c : clients) {
                    try {
                        OutputStreamWriter writer = new OutputStreamWriter(c.getOutputStream());
                        writer.write(nmea, 0, nmea.length());
                        writer.write(0x0A);
                        writer.flush();
                    } catch (IOException e) {
                        handleClientDisconnect(c, true);
                    }
                }
            });
        }
    }

    private void broadcastLog(String log) {
        if (!needOutput) return;
        if (uiCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> uiCallback.onLogMessage(log));
        }
    }

    private void broadcastNmea(String nmea) {
        if (!needOutput) return;
        if (uiCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> uiCallback.onNmeaMessage(nmea));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "NMEA Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @TargetApi(24)
    private class MaoGpsListener extends GnssStatus.Callback {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            int count = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) count++;
            }
            lastSatelliteCount = count;
        }
    }
}