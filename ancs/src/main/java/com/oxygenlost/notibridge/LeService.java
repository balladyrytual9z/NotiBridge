package com.oxygenlost.notibridge;

import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import com.oxygenlost.notibridge.dataprocess.DataHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/**
 * 负责扫描和连接gatt,并处理ANCS　response
 */
public class LeService extends Service {
    //TAG
    private static final String TAG = Constants.AUTHOR + "LeService";

    private LocalBroadcastManager mLocalBroadcastManager;

    //自定义binder，用于service绑定activity之后为activity提供操作service的接口
    private LocalBinder mBinder = new LocalBinder();


    //bluetooth
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;

    private BluetoothDevice mIphoneDevice;
    private BluetoothGatt mConnectedGatt;
    private BluetoothGattService mANCSService;
    private BluetoothGattCharacteristic mNotificationSourceChar;
    private BluetoothGattCharacteristic mPointControlChar;
    private BluetoothGattCharacteristic mDataSourceChar;

    //scanned devices list
    private List<BluetoothDevice> mScannedDevices = new ArrayList<>();

    private boolean isAncsReady = false;

    //device scan callback interface
    public interface OnDeviceScannedListener {
        void onDeviceScanned(String deviceName, String deviceAddress);
    }
    private OnDeviceScannedListener mDeviceScannedListener;

    //log constants
    private static final String BLUETOOTH_NOT_ENABLED = "bluetooth not support";
    private static final String BLUETOOTH_ENABLED = "bluetooth adpter enabled";

    //callback
    private LocalScanCallBack mScanCallback = new LocalScanCallBack();
    private LocalBluetoothGattCallback mGattCallback = new LocalBluetoothGattCallback();

    //data source respose packet handler
    private DataHandler mDataHandler;
    private Map<Integer, DataHandler> mDataHandlers = new HashMap<>(); // Changed to Map

    //data
    private byte[] uid = new byte[4];

    //broadcast
    private Intent mNotificationIntent;
    private Intent mDataIntent;
    private Intent mStateIntent;
    private Intent mDeviceFoundIntent;

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "service onBind");
        return mBinder;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "service onCreate");

        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);

        // Create Notification Channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_ID,
                    Constants.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW // Use IMPORTANCE_LOW for less intrusive notification
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        // Build the Notification for Foreground Service
        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("ANCS Service Running")
                .setContentText("Connected to device and monitoring notifications.")
                .setSmallIcon(R.mipmap.ic_launcher) // Use your app's icon
                .setPriority(NotificationCompat.PRIORITY_LOW) // Use PRIORITY_LOW for less intrusive notification
                .setOngoing(true) // Makes the notification un-dismissable
                .build();

        // Start Foreground Service
        startForeground(Constants.NOTIFICATION_ID, notification);

        //init bluetoothadapter.api 18 above
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        //bluetooth is enabled or not
        if (mBluetoothAdapter != null) {
            Log.d(TAG, BLUETOOTH_ENABLED);
        } else {
            Log.d(TAG, BLUETOOTH_NOT_ENABLED);
        }

        //data source 解析类
        mDataHandler = new DataHandler();
        //广播intent
        mNotificationIntent = new Intent();
        mNotificationIntent.setAction(Constants.ACTION_NOTIFICATION_SOURCE);
        mDataIntent = new Intent();
        mDataIntent.setAction(Constants.ACTION_DATA_SOURCE);
        mStateIntent = new Intent();
        mStateIntent.setAction(Constants.ACTION_STATE_INFO);
        mDeviceFoundIntent = new Intent();
        mDeviceFoundIntent.setAction(Constants.ACTION_DEVICE_FOUND);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "service onStartCommand");
        return START_STICKY; // Service will be recreated if killed
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service onDestroy");
        stopForeground(true); // Remove notification when service is destroyed
        if (mConnectedGatt != null) {
            mConnectedGatt.close();
        }
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter = null;
        }
    }

    /**
     * 继承Binder类，实现localbinder,为activity提供操作接口
     */
    public class LocalBinder extends Binder {

        public void setOnDeviceScannedListener(OnDeviceScannedListener listener) {
            mDeviceScannedListener = listener;
            Log.d(TAG, "Device scanned listener set");
        }

        public boolean isConnected() {
            return isAncsReady;
        }

        public void startLeScan() {
            Log.d(TAG, "startLeScan");

            if (mBluetoothAdapter.getState() == BluetoothAdapter.STATE_OFF) {
                mBluetoothAdapter.enable();
            } else {
                if (mConnectedGatt != null) {
                    Log.d(TAG, "连接存在");
                } else {
                    //清空之前的扫描设备列表
                    mScannedDevices.clear();
                    //获取leScanner
                    //api above 21
                    mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
                    //开始扫描，并将扫描结果交给mScanCallback处理
                    mBluetoothLeScanner.startScan(mScanCallback);
                    //广播扫描状态
                    mStateIntent.putExtra("state", Constants.SCANNING);
                    mLocalBroadcastManager.sendBroadcast(mStateIntent);
                }
            }
        }

        public void stopLeScan() {
            Log.d(TAG, "stopLeScan");
            if (mBluetoothLeScanner != null) {
                mBluetoothLeScanner.stopScan(mScanCallback);
            }
        }

        public void connectToDevice(String deviceAddress) {
            Log.d(TAG, "connectToDevice: " + deviceAddress);
            if (mBluetoothLeScanner != null) {
                mBluetoothLeScanner.stopScan(mScanCallback);
            }

            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceAddress);
            if (device != null) {
                mIphoneDevice = device;
                //已经绑定，该设备在绑定的设备名单里面
                if (mBluetoothAdapter.getBondedDevices().contains(device)) {
                    device.connectGatt(getApplicationContext(), false, mGattCallback);
                } else {//未绑定的设备
                    device.createBond();
                }
            }
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void connectToGattServer() {
            if (mIphoneDevice != null) {
                Log.d(TAG, "connect gatt");
                mIphoneDevice.connectGatt(getApplicationContext(), false, mGattCallback);
            }
        }

        public void negativeResponseToNotification(byte[] nid) {

            byte[] action = {
                    (byte) 0x02,
                    //UID
                    nid[0], nid[1], nid[2], nid[3],
                    //action id
                    (byte) 0x01,

            };

            //如果已经绑定，而且此时未断开
            if (mConnectedGatt != null) {
                BluetoothGattService service = mConnectedGatt.getService(UUID.fromString(Constants.service_ancs));
                if (service == null) {
                    Log.d(TAG, "cant find service");
                } else {
                    Log.d(TAG, "find service");
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(Constants.characteristics_control_point));
                    if (characteristic == null) {
                        Log.d(TAG, "cant find chara");
                    } else {
                        Log.d(TAG, "find chara");
                        characteristic.setValue(action);
                        mConnectedGatt.writeCharacteristic(characteristic);
                    }
                }
            }
        }

        public void positiveResponseToNotification(byte[] nid) {

            byte[] action = {
                    (byte) 0x02,
                    //UID
                    nid[0], nid[1], nid[2], nid[3],
                    //action id
                    (byte) 0x00,

            };

            //如果已经绑定，而且此时未断开
            if (mConnectedGatt != null) {
                BluetoothGattService service = mConnectedGatt.getService(UUID.fromString(Constants.service_ancs));
                if (service == null) {
                    Log.d(TAG, "cant find service");
                } else {
                    Log.d(TAG, "find service");
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(Constants.characteristics_control_point));
                    if (characteristic == null) {
                        Log.d(TAG, "cant find chara");
                    } else {
                        Log.d(TAG, "find chara");
                        characteristic.setValue(action);
                        mConnectedGatt.writeCharacteristic(characteristic);
                    }
                }
            }
        }

        public void retrieveMoreInfo(byte[] nid) {

            byte[] getNotificationAttribute = {
                    (byte) 0x00,
                    //UID
                    nid[0], nid[1], nid[2], nid[3],

                    //title
                    (byte) 0x01, (byte) 0xff, (byte) 0xff,
                    //message
                    (byte) 0x03, (byte) 0xff, (byte) 0xff
            };


            //如果已经绑定，而且此时未断开
            if (mConnectedGatt != null) {
                BluetoothGattService service = mConnectedGatt.getService(UUID.fromString(Constants.service_ancs));
                if (service == null) {
                    Log.d(TAG, "cant find service");
                } else {
                    Log.d(TAG, "find service");
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(Constants.characteristics_control_point));
                    if (characteristic == null) {
                        Log.d(TAG, "cant find chara");
                    } else {
                        Log.d(TAG, "find chara");
                        characteristic.setValue(getNotificationAttribute);
                        mConnectedGatt.writeCharacteristic(characteristic);
                    }
                }
            }
        }
    }


    private class LocalScanCallBack extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();
            String deviceAddress = device.getAddress();

            Log.d(TAG, "onScanResult Device: " + deviceName + " Address: " + deviceAddress);

            //添加到扫描设备列表（避免重复）
            if (!mScannedDevices.contains(device)) {
                mScannedDevices.add(device);

                String finalDeviceName = deviceName != null ? deviceName : "Unknown Device";

                // 直接回调通知MainActivity
                if (mDeviceScannedListener != null) {
                    mDeviceScannedListener.onDeviceScanned(finalDeviceName, deviceAddress);
                    Log.d(TAG, "Callback invoked for device: " + finalDeviceName + " (" + deviceAddress + ")");
                } else {
                    Log.w(TAG, "Device scanned listener is null");
                }

                //同时发送广播（保持兼容）
                Intent deviceFoundBroadcast = new Intent(Constants.ACTION_DEVICE_FOUND);
                deviceFoundBroadcast.putExtra("device_name", finalDeviceName);
                deviceFoundBroadcast.putExtra("device_address", deviceAddress);
                mLocalBroadcastManager.sendBroadcast(deviceFoundBroadcast);
            }
        }
    }

    /**
     * 连接gatt server结果处理回调类
     */
    private class LocalBluetoothGattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "connected");

                // mStateIntent.putExtra("state", Constants.CONNECT_SUCCESS);
                // sendBroadcast(mStateIntent);

                mConnectedGatt = gatt;
                gatt.discoverServices();
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "disconnected");

                mConnectedGatt = null;
                isAncsReady = false;
                mStateIntent.putExtra("state", Constants.DISCONNECTED);
                mLocalBroadcastManager.sendBroadcast(mStateIntent);

                // Clear last connected device address
                SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
                prefs.edit().remove(Constants.PREF_LAST_DEVICE_ADDRESS).apply();
                Log.d(TAG, "Cleared last connected device address.");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService ancsService = gatt.getService(UUID.fromString(Constants.service_ancs));
                if (ancsService == null) {
                    Log.d(TAG, "ANCS cannot find");
                } else {
                    Log.d(TAG, "ANCS find");

                    // 找到 ANCS 服务，说明连接成功，发送广播更新 UI
                    // mStateIntent.putExtra("state", Constants.CONNECT_SUCCESS);
                    // sendBroadcast(mStateIntent);
                    // Log.d(TAG, "Sent CONNECT_SUCCESS broadcast");

                    mANCSService = ancsService;
                    mDataSourceChar = ancsService.getCharacteristic(UUID.fromString(Constants.characteristics_data_source));
                    mPointControlChar = ancsService.getCharacteristic(UUID.fromString(Constants.characteristics_control_point));
                    mNotificationSourceChar = ancsService.getCharacteristic(UUID.fromString(Constants.characteristics_notification_source));

                    setNotificationEnabled(mDataSourceChar);
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, " onDescriptorWrite:: " + status);

            // Notification source
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.getCharacteristic().getUuid().equals(UUID.fromString(Constants.characteristics_data_source))) {
                    setNotificationEnabled(mNotificationSourceChar);
                    Log.d(TAG, "data_source 订阅成功 ");
                }
                if (descriptor.getCharacteristic().getUuid().equals(UUID.fromString(Constants.characteristics_notification_source))) {
                    Log.d(TAG, "notification_source　订阅成功 ");

                    // ANCS 完全就绪，发送连接成功广播更新 UI
                    isAncsReady = true;
                    mStateIntent.putExtra("state", Constants.CONNECT_SUCCESS);
                    mLocalBroadcastManager.sendBroadcast(mStateIntent);
                    Log.d(TAG, "ANCS ready, sent CONNECT_SUCCESS broadcast");

                    // Save last connected device address
                    if (mConnectedGatt != null && mConnectedGatt.getDevice() != null) {
                        String deviceAddress = mConnectedGatt.getDevice().getAddress();
                        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
                        prefs.edit().putString(Constants.PREF_LAST_DEVICE_ADDRESS, deviceAddress).apply();
                        Log.d(TAG, "Saved last connected device address: " + deviceAddress);
                    }
                }
            }

        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite");
            if (Constants.characteristics_control_point.toString().equals(characteristic.getUuid().toString())) {
                Log.d(TAG, "control_point  Write successful");

            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorRead");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Constants.characteristics_notification_source.toString().equals(characteristic.getUuid().toString())) {
//                Log.d(TAG, "notification_source Changed");

                byte[] nsData = characteristic.getValue();
                com.oxygenlost.notibridge.dataprocess.Notification notification = new com.oxygenlost.notibridge.dataprocess.Notification(nsData);
                int notificationId = notification.getIntegerNid(); // Get integer NID

                // Create a new DataHandler for this notification ID
                DataHandler dataHandler = new DataHandler();
                mDataHandlers.put(notificationId, dataHandler);
                Log.d(TAG, "New DataHandler created for NID: " + notificationId + ". Map size: " + mDataHandlers.size());

                mNotificationIntent.putExtra("notice", notification);

//                Log.d(TAG, "EventId:" + String.format("%d", nsData[0]) +
//                        " EventFlags:" + String.format("%02x", nsData[1]) +
//                        " Category id:" + String.format("%d", nsData[2]) +
//                        " Category Count:" + String.format("%d", nsData[3]) +
//                        " NotificationUId:" + String.format("%02X%02X%02X%02X", nsData[4], nsData[5], nsData[6], nsData[7]));

                if ((nsData[0] & 0x02) > 0) {
                    Log.d(TAG, "Sending broadcast for removed notification");
                    mLocalBroadcastManager.sendBroadcast(mNotificationIntent);
                    mDataHandlers.remove(notificationId); // Remove handler if notification is removed
                } else {
//                    Log.d(TAG, "Requesting more info about notification");
                    getMoreAboutNotification(nsData);
                }
            }
            if (Constants.characteristics_data_source.toString().equals(characteristic.getUuid().toString())) {
                Log.d(TAG, "characteristics_data_source changed");

                byte[] get_data = characteristic.getValue();
                Log.d(TAG, "Received data length: " + get_data.length);

                // Extract NID from data_source packet (bytes 1-4 after CommandID)
                if (get_data.length < 5) {
                    Log.e(TAG, "Data source packet too short to contain NID.");
                    return;
                }
                int notificationId = (get_data[4] & 0xFF) << 24 | (get_data[3] & 0xFF) << 16 | (get_data[2] & 0xFF) << 8 | (get_data[1] & 0xFF);

                DataHandler dataHandler = mDataHandlers.get(notificationId);

                if (dataHandler == null) {
                    Log.w(TAG, "No DataHandler found for NID: " + notificationId + ". Skipping data processing.");
                    return;
                }

                // 打印原始数据（hex格式）
                StringBuilder hexString = new StringBuilder();
                for (byte b : get_data) {
                    hexString.append(String.format("%02X ", b));
                }
                Log.d(TAG, "Raw data (hex): " + hexString.toString());

                // 打印可读的ASCII部分
                StringBuilder asciiString = new StringBuilder();
                for (byte b : get_data) {
                    if (b >= 32 && b < 127) {
                        asciiString.append((char)b);
                    } else {
                        asciiString.append(".");
                    }
                }
                Log.d(TAG, "ASCII representation: " + asciiString.toString());

                if (!dataHandler.isStarted()) {
                    Log.d(TAG, "DataHandler init for NID: " + notificationId);
                    dataHandler.init(get_data, notificationId); // Pass notificationId
                } else {
                    Log.d(TAG, "DataHandler append data for NID: " + notificationId);
                    dataHandler.appendData(get_data);
                }

                Log.d(TAG, "DataHandler isFinished for NID " + notificationId + ": " + dataHandler.isFinished());

                if (dataHandler.isFinished()) {
                    dataHandler.parseData();
                    String title = dataHandler.getAttributeTitle();
                    String message = dataHandler.getAttributeMessage();
                    Log.d(TAG, "**Parsed title for NID " + notificationId + ": " + title);
                    Log.d(TAG, "**Parsed message for NID " + notificationId + ": " + message);

                    com.oxygenlost.notibridge.dataprocess.Notification notification = new com.oxygenlost.notibridge.dataprocess.Notification(dataHandler.getNotificationId()); // Re-create notification object with original NID
                    notification.setTitle(title);
                    notification.setMessage(message);
                    mDataIntent.putExtra("content", notification);
                    Log.d(TAG, "Sending ACTION_DATA_SOURCE broadcast for NID: " + notificationId);
                    mLocalBroadcastManager.sendBroadcast(mDataIntent);

                    // Send HTTP request
                    sendNotificationHttpRequest(title, message);

                    mDataHandlers.remove(notificationId); // Remove handler after processing
                    Log.d(TAG, "DataHandler for NID " + notificationId + " removed. Map size: " + mDataHandlers.size());
                }
            }

            if (Constants.characteristics_control_point.toString().equals(characteristic.getUuid().toString())) {
                Log.d(TAG, "characteristics_control_point changed");
                byte[] cpData = characteristic.getValue();
            }
        }
    }

    private void setNotificationEnabled(BluetoothGattCharacteristic characteristic) {
        mConnectedGatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(Constants.descriptor_config));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mConnectedGatt.writeDescriptor(descriptor);
        }
    }

    private void getMoreAboutNotification(byte[] nsData) {
        byte[] getNotificationAttribute = {
                (byte) 0x00,
                //UID
                nsData[4], nsData[5], nsData[6], nsData[7],
                //app id
                //(byte) 0x00,
                //title
                (byte) 0x01, (byte) 0xff, (byte) 0xff,
                //message
                (byte) 0x03, (byte) 0xff, (byte) 0xff
        };

        if (mConnectedGatt != null) {
            mPointControlChar.setValue(getNotificationAttribute);
            mConnectedGatt.writeCharacteristic(mPointControlChar);
        }
    }

    private void sendNotificationHttpRequest(String title, String message) {
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        String apiKey = prefs.getString(Constants.PREF_API_KEY, "");

        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key is not set. Skipping HTTP notification.");
            return;
        }

        new Thread(() -> {
            HttpURLConnection urlConnection = null;
            try {
                String encodedTitle = URLEncoder.encode(title, "UTF-8");
                String encodedMessage = URLEncoder.encode(message, "UTF-8");
                String urlString = Constants.NOTIFY_BASE_URL + apiKey + "/" + encodedTitle + "/" + encodedMessage;
                URL url = new URL(urlString);

                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setConnectTimeout(5000); // 5 seconds
                urlConnection.setReadTimeout(5000);    // 5 seconds

                int responseCode = urlConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    String inputLine;
                    StringBuilder response = new StringBuilder();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();
                    Log.d(TAG, "HTTP Notification Success: " + response.toString());
                } else {
                    Log.e(TAG, "HTTP Notification Failed: Response Code " + responseCode);
                }

            } catch (Exception e) {
                Log.e(TAG, "HTTP Notification Error: " + e.getMessage());
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }).start();
    }
}
