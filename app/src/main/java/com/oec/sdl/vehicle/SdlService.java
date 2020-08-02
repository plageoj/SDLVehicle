package com.oec.sdl.vehicle;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.permission.PermissionElement;
import com.smartdevicelink.managers.permission.PermissionStatus;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.DateTime;
import com.smartdevicelink.proxy.rpc.DisplayCapabilities;
import com.smartdevicelink.proxy.rpc.GetVehicleData;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.OnVehicleData;
import com.smartdevicelink.proxy.rpc.SubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.UnsubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.PRNDL;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.enums.VehicleDataEventStatus;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public class SdlService extends Service {

    private static final String TAG = "SDL Vehicle";

    private static final String APP_NAME = "The 直線君";
    private static final String APP_ID = "com.straight.drive.the";

    private static final String ICON_FILENAME = "hello_sdl_icon.png";

    private static final int FOREGROUND_SERVICE_ID = 113;

    // TCP/IP transport config
    // The default port is 12345
    // The IP is of the machine that is running SDL Core
    private int TCP_PORT = 11021;
    private static final String DEV_MACHINE_IP_ADDRESS = "m.sdl.tools";

    // variable to create and call functions of the SyncProxy
    private SdlManager sdlManager = null;

    private Double preSteeringAngle = 0.0, originalSteeringAngle = 0.0;
    private Double preSpeed = 0.0, originalSpeed = 0.0;
    private String preBrake = "NO";
    private Instant startTime;
    private boolean isMeasuring = false;
    private Double currentScore = 0.0;
    private Double diffSpeed = 0.0, diffAngle = 0.0;

    @Override
    public IBinder onBind(Intent intent) {
        int result = intent.getIntExtra("port", 0);
        Log.d("onBind", String.valueOf(result));
        TCP_PORT = result;
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterForeground();
        }
    }


    // Helper method to let the service enter foreground mode
    @SuppressLint("NewApi")
    public void enterForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Notification serviceNotification = new Notification.Builder(this, channel.getId())
                        .setContentTitle("Connected through SDL")
                        .setSmallIcon(R.drawable.ic_sdl)
                        .build();
                startForeground(FOREGROUND_SERVICE_ID, serviceNotification);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int result = intent.getIntExtra("port", 0);
        Log.d("onStartCommand", String.valueOf(result));
        TCP_PORT = result;
        startProxy();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }

        if (sdlManager != null) {
            sdlManager.dispose();
        }

        super.onDestroy();
    }

    private void startProxy() {

        if (sdlManager == null) {
            Log.i(TAG, "Starting SDL Proxy");

            BaseTransportConfig transport = null;
            if (BuildConfig.TRANSPORT.equals("MULTI")) {
                int securityLevel;
                if (BuildConfig.SECURITY.equals("HIGH")) {
                    securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
                } else if (BuildConfig.SECURITY.equals("MED")) {
                    securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
                } else if (BuildConfig.SECURITY.equals("LOW")) {
                    securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
                } else {
                    securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
                }
                transport = new MultiplexTransportConfig(this, APP_ID, securityLevel);
            } else if (BuildConfig.TRANSPORT.equals("TCP")) {
                transport = new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true);
            } else if (BuildConfig.TRANSPORT.equals("MULTI_HB")) {
                MultiplexTransportConfig mtc = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
                mtc.setRequiresHighBandwidth(true);
                transport = mtc;
            }

            // The app type to be used
            final Vector<AppHMIType> appType = new Vector<>();
            appType.add(AppHMIType.MEDIA);


            // The manager listener helps you know when certain events that pertain to the SDL Manager happen
            // Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
            SdlManagerListener listener = new SdlManagerListener() {
                private PRNDL beforePrndl = null;

                @Override
                public void onStart() {

                    // HMI Status Listener
                    sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
                        @Override
                        public void onNotified(RPCNotification notification) {

                            OnHMIStatus status = (OnHMIStatus) notification;
                            if (status.getHmiLevel() == HMILevel.HMI_FULL && ((OnHMIStatus) notification).getFirstRun()) {

                                checkTemplateType();

                                checkPermission();

                                setDisplayDefault();
                            }
                        }
                    });
                    //https://github.com/tanaka3/SDLDisplaySampleProjection/tree/SDLBootCamp_googlemap_version
                    //これをすると定期的にデータが取得可能
                    sdlManager.addOnRPCNotificationListener(FunctionID.ON_VEHICLE_DATA, new OnRPCNotificationListener() {
                        @RequiresApi(api = Build.VERSION_CODES.O)
                        @Override
                        public void onNotified(RPCNotification notification) {
                            OnVehicleData onVehicleDataNotification = (OnVehicleData) notification;

                            sdlManager.getScreenManager().beginTransaction();

                            SdlArtwork artwork = null;

                            Double speed = onVehicleDataNotification.getSpeed();
                            VehicleDataEventStatus eventStatus = onVehicleDataNotification.getDriverBraking();
                            Double steeringWheelAngle = onVehicleDataNotification.getSteeringWheelAngle();

                            // threshold values to enter special mode
                            final Double thrSteeringWheelAngle = 5.0, thrDiffSpeed = 2.0, thrSpeed = 50.0;
                            final Integer thrDuration = 30;

                            // threshold values to escape from special mode
                            final Double thrOrigSteeringWheelAngle = 10.0, thrOrigSpeed = 10.0;

                            if (eventStatus != null) {
                                Log.w("eventStatus.name()", eventStatus.name());
                                preBrake = eventStatus.name();
                                sdlManager.getScreenManager().setTextField2("Brake: " + preBrake);
                            }
                            if (speed != null) {
                                //テキストを登録する場合
                                sdlManager.getScreenManager().setTextField1("Speed: " + speed);

                                diffSpeed = speed - preSpeed;
                                preSpeed = speed;
                            }
                            if (steeringWheelAngle != null) {
                                //テキストを登録する場合
                                steeringWheelAngle /= 100;
                                sdlManager.getScreenManager().setTextField3("S. Angle: " + steeringWheelAngle);

                                // Calculating steering angle difference
                                diffAngle = steeringWheelAngle - preSteeringAngle;
                                preSteeringAngle = steeringWheelAngle;
                            }

                            sendMessage(
                                    preSpeed.intValue() + ","
                                            + preSteeringAngle.intValue() + ","
                                            + currentScore.intValue()
                            );

                            sdlManager.getScreenManager().commit(new CompletionListener() {
                                @Override
                                public void onComplete(boolean success) {
                                    if (success) {
                                        Log.i(TAG, "change successful");
                                    }
                                }
                            });

                            Instant currentTime = Instant.now();

                            if (isMeasuring) {
                                currentScore += ((currentTime.toEpochMilli() - startTime.toEpochMilli()) - Math.abs(diffAngle)) / 20.0;
                                startTime = currentTime;

                                if (Math.abs(originalSteeringAngle - preSteeringAngle) > thrOrigSteeringWheelAngle
                                        || Math.abs(originalSpeed - preSpeed) > thrOrigSpeed
                                        || preSpeed < thrSpeed
                                        || (eventStatus != null && eventStatus.name().equals("YES"))) {
                                    isMeasuring = false;
                                    startTime = null;
                                }
                            } else {
                                if (diffAngle < thrSteeringWheelAngle && diffSpeed < thrDiffSpeed && preSpeed > thrSpeed) {
                                    if (startTime == null) {
                                        startTime = currentTime;
                                    }

                                    if (currentTime.toEpochMilli() - startTime.toEpochMilli() > thrDuration) {
                                        isMeasuring = true;
                                        startTime = currentTime;
                                        originalSpeed = preSpeed;
                                        originalSteeringAngle = preSteeringAngle;
                                    }
                                }
                            }
                        }
                    });
                }

                @Override
                public void onDestroy() {
                    UnsubscribeVehicleData unsubscribeRequest = new UnsubscribeVehicleData();
                    unsubscribeRequest.setSpeed(true);
                    unsubscribeRequest.setPrndl(true);
                    unsubscribeRequest.setSteeringWheelAngle(true);
                    unsubscribeRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
                        @Override
                        public void onResponse(int correlationId, RPCResponse response) {
                            if (response.getSuccess()) {
                                Log.i("SdlService", "Successfully unsubscribed to vehicle data.");
                            } else {
                                Log.i("SdlService", "Request to unsubscribe to vehicle data was rejected.");
                            }
                        }
                    });
                    sdlManager.sendRPC(unsubscribeRequest);

                    SdlService.this.stopSelf();
                }

                @Override
                public void onError(String info, Exception e) {
                }
            };

            // Create App Icon, this is set in the SdlManager builder
            SdlArtwork appIcon = new SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true);

            // The manager builder sets options for your session
            SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
            builder.setAppTypes(appType);
            //builder.setTransportType(transport);
            builder.setTransportType(new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true));
            builder.setAppIcon(appIcon);
            sdlManager = builder.build();
            sdlManager.start();


        }
    }


    /**
     * 利用可能なテンプレートをチェックする
     */
    private void checkTemplateType() {

        Object result = sdlManager.getSystemCapabilityManager().getCapability(SystemCapabilityType.DISPLAY);
        if (result instanceof DisplayCapabilities) {
            List<String> templates = ((DisplayCapabilities) result).getTemplatesAvailable();

            Log.i("Template", templates.toString());

        }
    }

    /**
     * 利用する項目が利用可能かどうか
     */
    private void checkPermission() {
        List<PermissionElement> permissionElements = new ArrayList<>();

        //チェックを行う項目
        List<String> keys = new ArrayList<>();
        keys.add(GetVehicleData.KEY_SPEED);
        keys.add(GetVehicleData.KEY_DRIVER_BRAKING);
        keys.add(GetVehicleData.KEY_STEERING_WHEEL_ANGLE);
        permissionElements.add(new PermissionElement(FunctionID.GET_VEHICLE_DATA, keys));

        Map<FunctionID, PermissionStatus> status = sdlManager.getPermissionManager().getStatusOfPermissions(permissionElements);

        //すべてが許可されているかどうか
        Log.i("Permission", "Allowed:" + status.get(FunctionID.GET_VEHICLE_DATA).getIsRPCAllowed());

        //各項目ごとも可能
        Log.i("Permission", "KEY_RPM　Allowed:" + status.get(FunctionID.GET_VEHICLE_DATA).getAllowedParameters().get(GetVehicleData.KEY_RPM));

    }

    /**
     * DEFAULTテンプレートのサンプル
     */
    private void setDisplayDefault() {

        sdlManager.getScreenManager().beginTransaction();

        //テキストを登録する場合
        sdlManager.getScreenManager().setTextField1("Speed: None");
        sdlManager.getScreenManager().setTextField2("Brake: None");
        sdlManager.getScreenManager().setTextField3("S.Angle: None");

        //画像を登録する
        SdlArtwork artwork = new SdlArtwork("normal.png", FileType.GRAPHIC_PNG, R.drawable.normal, true);

        sdlManager.getScreenManager().setPrimaryGraphic(artwork);
        sdlManager.getScreenManager().commit(new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                if (success) {
                    //定期受信用のデータを設定する
                    SubscribeVehicleData subscribeRequest = new SubscribeVehicleData();
                    subscribeRequest.setSpeed(true);                          //エンジン回転数
                    subscribeRequest.setDriverBraking(true);
                    subscribeRequest.setSteeringWheelAngle(true);
                    subscribeRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
                        @Override
                        public void onResponse(int correlationId, RPCResponse response) {
                            if (response.getSuccess()) {
                                Log.i("SdlService", "Successfully subscribed to vehicle data.");
                            } else {
                                Log.i("SdlService", "Request to subscribe to vehicle data was rejected.");
                            }
                        }
                    });
                    sdlManager.sendRPC(subscribeRequest);

                }
            }
        });
    }

    protected void sendMessage(String msg) {
        Intent broadcast = new Intent();
        broadcast.putExtra("message", msg);
        broadcast.setAction("UPDATE_VIEW");
        getBaseContext().sendBroadcast(broadcast);
    }

}
