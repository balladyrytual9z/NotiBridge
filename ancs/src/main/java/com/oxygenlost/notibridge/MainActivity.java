package com.oxygenlost.notibridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oxygenlost.notibridge.dataprocess.Notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {

    //log TAG
    private static final String TAG = Constants.AUTHOR + "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1;

    HashMap<Integer, byte[]> mDataMap = new HashMap<>();

    private List<Notification> list = new ArrayList<Notification>();
    private List<ScannedDevice> scannedDeviceList = new ArrayList<ScannedDevice>();
    private DeviceListAdapter deviceListAdapter;
    private AlertDialog deviceSelectionDialog;

    //ui view
    private Button mConnectGattBtn;
    private ImageButton mSettingsBtn; // New settings button
    private TextView mShowTitleTextView;
    private FrameLayout mContentLayout;
    private ImageView mShowStateIV;
    private RecyclerView mRecyclerView;
    private LocalAdapter localAdapter;
    //MyOnClickListener
    private MyOnClickListener myOnClickListener = new MyOnClickListener();

    byte[] nid;

    //service connection
    LeService.LocalBinder mService;
    ServiceConnection conn = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.d(TAG, "onServiceConnected");
            mService = (LeService.LocalBinder) iBinder;

            // 设置设备扫描监听器
            mService.setOnDeviceScannedListener(new LeService.OnDeviceScannedListener() {
                @Override
                public void onDeviceScanned(String deviceName, String deviceAddress) {
                    Log.d(TAG, "onDeviceScanned callback: " + deviceName + " (" + deviceAddress + ")");

                    // 在主线程更新UI
                    runOnUiThread(() -> {
                        ScannedDevice device = new ScannedDevice(deviceName, deviceAddress);
                        if (!scannedDeviceList.contains(device)) {
                            scannedDeviceList.add(device);
                            Log.d(TAG, "Device added via callback. List size: " + scannedDeviceList.size());

                            if (deviceListAdapter != null) {
                                deviceListAdapter.notifyDataSetChanged();
                                Log.d(TAG, "Adapter notified");
                            }
                        }
                    });
                }
            });

            // 检查是否已经连接（处理 Service 创建前就已连接的情况）
            if (mService.isConnected()) {
                Log.d(TAG, "Already connected, updating UI");
                mConnectGattBtn.setVisibility(View.GONE);
                mShowStateIV.setVisibility(View.VISIBLE);
                mShowTitleTextView.setText("Connected Device"); // Use the correct string
            } else {
                // If not connected, try to auto-connect to the last device
                SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
                String lastDeviceAddress = prefs.getString(Constants.PREF_LAST_DEVICE_ADDRESS, null);

                if (lastDeviceAddress != null && !lastDeviceAddress.isEmpty()) {
                    Log.d(TAG, "Attempting to auto-connect to last device: " + lastDeviceAddress);
                    showMessage("Attempting to auto-connect to last device...");
                    mService.connectToDevice(lastDeviceAddress);
                } else {
                    Log.d(TAG, "No last connected device found for auto-connect.");
                    // Ensure UI is in "No Device" state if no auto-connect attempt
                    mConnectGattBtn.setVisibility(View.VISIBLE);
                    mShowStateIV.setVisibility(View.GONE);
                    mShowTitleTextView.setText("No Device");
                }
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName componentName) { }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Toast.makeText(this, "这是 ANCS 模块！", Toast.LENGTH_LONG).show();
        setContentView(R.layout.layout_main);
        initView();
        //开启蓝牙连接的服务
        Intent serviceIntent = new Intent(MainActivity.this, LeService.class);
        // For Android O and above, use startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(MainActivity.this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE);

        // 请求蓝牙权限 (Android 12+)
        requestBluetoothPermissions();

        // Request POST_NOTIFICATIONS permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要新的蓝牙权限
            String[] permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };

            boolean needRequest = false;
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }

            if (needRequest) {
                requestPermissions(permissions, PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-11 需要位置权限进行蓝牙扫描
            String[] permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };

            boolean needRequest = false;
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }

            if (needRequest) {
                requestPermissions(permissions, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                showMessage("蓝牙权限已授予");
            } else {
                showMessage("需要蓝牙权限才能使用此应用");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called");
        // Android 13+ (API 33+) requires specifying RECEIVER_NOT_EXPORTED
        // LocalBroadcastManager does not require RECEIVER_NOT_EXPORTED
        LocalBroadcastManager.getInstance(this).registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        Log.d(TAG, "LocalBroadcastManager Receiver registered");

        // 检查是否已经连接（可能在 Activity 启动前就连接了）
        if (mService != null) {
            // 可以在这里检查连接状态或更新 UI
            Log.d(TAG, "Service is bound, checking connection status");
        }
    }

    /**
     * 初始化界面
     */
    private void initView() {
        mConnectGattBtn = (Button) findViewById(R.id.connect_gatt_btn);
        mConnectGattBtn.setOnClickListener(myOnClickListener);

        mSettingsBtn = (ImageButton) findViewById(R.id.settings_btn); // Link settings button
        mSettingsBtn.setOnClickListener(myOnClickListener); // Add click listener

        mShowTitleTextView = (TextView) findViewById(R.id.title_text_textview);
        mContentLayout = (FrameLayout) findViewById(R.id.layout_content);
        mShowStateIV = (ImageView) findViewById(R.id.show_state_iv);

        // 拿到RecyclerView
        mRecyclerView = (RecyclerView) findViewById(R.id.list);
        // 设置LinearLayoutManager
        LinearLayoutManager mLinearLayoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(mLinearLayoutManager);
        // 设置ItemAnimator
        RecyclerView.ItemAnimator itemAnimator = new RecyclerView.ItemAnimator() {
            @Override
            public boolean animateDisappearance(@NonNull RecyclerView.ViewHolder viewHolder, @NonNull ItemHolderInfo preLayoutInfo, @Nullable ItemHolderInfo postLayoutInfo) {
                return false;
            }
            @Override
            public boolean animateAppearance(@NonNull RecyclerView.ViewHolder viewHolder, @Nullable ItemHolderInfo preLayoutInfo, @NonNull ItemHolderInfo postLayoutInfo) {
                return false;
            }
            @Override
            public boolean animatePersistence(@NonNull RecyclerView.ViewHolder viewHolder, @NonNull ItemHolderInfo preLayoutInfo, @NonNull ItemHolderInfo postLayoutInfo) {
                return false;
            }
            @Override
            public boolean animateChange(@NonNull RecyclerView.ViewHolder oldHolder, @NonNull RecyclerView.ViewHolder newHolder, @NonNull ItemHolderInfo preLayoutInfo, @NonNull ItemHolderInfo postLayoutInfo) {
                return false;
            }
            @Override
            public void runPendingAnimations() {

            }
            @Override
            public void endAnimation(RecyclerView.ViewHolder item) {
            }
            @Override
            public void endAnimations() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }
        };
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());
        // 设置固定大小
        mRecyclerView.setHasFixedSize(true);
        // 初始化自定义的适配器
        localAdapter = new LocalAdapter((ArrayList<Notification>) list);
        localAdapter.setOnItemClickListener(new LocalAdapter.OnRecyclerViewItemClickListener() {
            @Override
            public void onItemClick(View view, int positionNoUse, Notification no) {
                //获得被点击的按钮所在的cardview上的隐藏部分
                RelativeLayout moreLayout = (RelativeLayout) ((ViewGroup) view.getParent().getParent()).findViewById(R.id.more_info_layout);
                //获得被点击按钮所在的cardview
                CardView cardView = (CardView) moreLayout.getParent().getParent();
                int position = mRecyclerView.getChildAdapterPosition(cardView);

                //显示更多按钮被点击
                if (view.getId() == R.id.more_info_btn) {
                    if (moreLayout.getVisibility() == View.GONE) {//如果是gone状态，则改为visible
                        moreLayout.setVisibility(View.VISIBLE);
                    } else {//如果是visible状态，则改为gone
                        moreLayout.setVisibility(View.GONE);
                    }
                }
                //点击收起按钮，相应的morelayout消失
                if (view.getId() == R.id.back_btn) {
                    if (moreLayout.getVisibility() == View.VISIBLE) {
                        moreLayout.setVisibility(View.GONE);
                    }
                }
                //negative按钮被点击
                if (view.getId() == R.id.negative_btn) {
                    if (moreLayout.getVisibility() == View.VISIBLE) {
                        moreLayout.setVisibility(View.GONE);
                    }
                    mService.negativeResponseToNotification(list.get(position).getNid());
                    list.remove(position);
                    localAdapter.notifyDataSetChanged();
                }
                //positive按钮被点击
                if (view.getId() == R.id.positive_btn) {
                    mService.positiveResponseToNotification(list.get(position).getNid());
                }
            }
        });
        // 为mRecyclerView设置适配器
        mRecyclerView.setAdapter(localAdapter);
    }


    /**
     * 更新界面
     */
    private void updateUI(Notification notification) {
        Log.d(TAG, "updateUI called, eventId=" + notification.getEventId() + ", title=" + notification.getTitle() + ", message=" + notification.getMessage());
        Log.d(TAG, "Current notification list size: " + list.size());

        //新增消息
        if (notification.getEventId() == 0) {
                list.add(0, notification); // Add at the beginning
                Log.d(TAG, "Added new notification. List size now: " + list.size());
                localAdapter.notifyDataSetChanged();
                Log.d(TAG, "Adapter notified for new notification");
        }
        //消息更改
        if (notification.getEventId() == 1) {
            Log.d(TAG, "Notification modified event");
            for (Notification notification1 : list) {
                if (notification.getIntegerNid() == notification1.getIntegerNid()) {
                    list.remove(list.indexOf(notification1));
                    list.add(list.indexOf(notification1),notification);
                }
            }
            localAdapter.notifyDataSetChanged();
        }
        //消息删除
        if (notification.getEventId() == 2) {
            Log.d(TAG, "Notification removed event");
            for (Notification notification1 : list) {
                if (notification.getIntegerNid() == notification1.getIntegerNid()) {
                    list.remove(list.indexOf(notification1));
                }
            }
            localAdapter.notifyDataSetChanged();
        }

    }


    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "BroadcastReceiver received action: " + action); // Diagnostic log

            if (Constants.ACTION_NOTIFICATION_SOURCE.equals(action)) {
                Log.d(TAG, "ACTION_NOTIFICATION_SOURCE received");
                Notification notification = (Notification) intent.getSerializableExtra("notice");
                if (notification != null) {
                    Log.d(TAG, "Notification object received from notification source");
                    updateUI(notification);
                } else {
                    Log.e(TAG, "Notification object is null!");
                }
            }
            if (Constants.ACTION_DATA_SOURCE.equals(action)) {
                Log.d(TAG, "ACTION_DATA_SOURCE received");
                Notification notification = (Notification) intent.getSerializableExtra("content");
                if (notification != null) {
                    Log.d(TAG, "Notification object received from data source");
                    updateUI(notification);
                } else {
                    Log.e(TAG, "Notification content is null!");
                }
            }
            if (Constants.ACTION_DEVICE_FOUND.equals(action)) {
                String deviceName = intent.getStringExtra("device_name");
                String deviceAddress = intent.getStringExtra("device_address");

                Log.d(TAG, "ACTION_DEVICE_FOUND received: " + deviceName + " (" + deviceAddress + ")");
                Log.d(TAG, "scannedDeviceList size before: " + scannedDeviceList.size());
                Log.d(TAG, "deviceListAdapter is null: " + (deviceListAdapter == null));

                ScannedDevice device = new ScannedDevice(deviceName, deviceAddress);
                if (!scannedDeviceList.contains(device)) {
                    scannedDeviceList.add(device);
                    Log.d(TAG, "Device added to list. New size: " + scannedDeviceList.size());

                    if (deviceListAdapter != null) {
                        runOnUiThread(() -> {
                            Log.d(TAG, "Notifying adapter of data change");
                            deviceListAdapter.notifyDataSetChanged();
                        });
                    } else {
                        Log.w(TAG, "deviceListAdapter is null, cannot update UI");
                    }
                } else {
                    Log.d(TAG, "Device already in list, skipping");
                }
            }
            if (Constants.ACTION_STATE_INFO.equals(action)) {
                String state = intent.getStringExtra("state");
                Log.d(TAG, "Received ACTION_STATE_INFO with state: " + state);

                if (state == null) return;

                switch (state) {
                    case Constants.BOND_FAIL:
                        // Handle bond failure
                        break;
                    case Constants.DEVICE_FIND:
                        showMessage(Constants.DEVICE_FIND);
                        break;
                    case Constants.SCANNING:
                        showMessage("Scanning for devices...");
                        break;
                    case Constants.NO_BLUETOOTH:
                        showMessage("enable bluetooth");
                        break;
                    case Constants.DISCONNECTED:
                        mConnectGattBtn.setVisibility(View.VISIBLE);
                        mConnectGattBtn.setClickable(true);
                        mShowTitleTextView.setText("No Device");
                        list.clear();
                        if (localAdapter != null) {
                            localAdapter.notifyDataSetChanged();
                        }
                        break;
                    case Constants.CONNECT_SUCCESS:
                        Log.d(TAG, "CONNECT_SUCCESS received, updating UI");
                        showMessage(Constants.CONNECT_SUCCESS);
                        mConnectGattBtn.setVisibility(View.GONE);
                        mShowStateIV.setVisibility(View.VISIBLE);
                        mShowTitleTextView.setText("Connected Device");
                        Log.d(TAG, "Title text updated to: Connected Device");
                        break;
                }
            }
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) {
                    showMessage("Device Bonded");
                    mService.connectToGattServer();
                }
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                    if (mService != null) {
                        mService.connectToGattServer();
                    }
                }
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                    showMessage("Bluetooth OFF");
                }
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_DATA_SOURCE);
        intentFilter.addAction(Constants.ACTION_NOTIFICATION_SOURCE);
        intentFilter.addAction(Constants.ACTION_STATE_INFO);
        intentFilter.addAction(Constants.ACTION_DEVICE_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        return intentFilter;
    }

    private void showMessage(String message){
        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
    }

    private void showApiKeyInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Notification API Key");

        final EditText input = new EditText(this);
        input.setHint("Enter your Bark API Key (e.g., GfaMZkfgz...)");
        // Load existing key if any
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        String currentApiKey = prefs.getString(Constants.PREF_API_KEY, "");
        input.setText(currentApiKey);

        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newApiKey = input.getText().toString();
            prefs.edit().putString(Constants.PREF_API_KEY, newApiKey).apply();
            showMessage("API Key saved!");
            Log.d(TAG, "API Key saved: " + newApiKey);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private class MyOnClickListener implements View.OnClickListener {

        @Override
        public void onClick(View view) {
            int id = view.getId();
            if (id == R.id.connect_gatt_btn) {
                Log.d(TAG, "Connect button clicked");
                Toast.makeText(MainActivity.this, "start scan", Toast.LENGTH_SHORT).show();
                scannedDeviceList.clear();
                Log.d(TAG, "Cleared device list");

                if (mService != null) {
                    Log.d(TAG, "Starting scan via service");
                    mService.startLeScan();
                    showDeviceSelectionDialog();
                } else {
                    Log.e(TAG, "Service is null, cannot start scan");
                    Toast.makeText(MainActivity.this, "Service not ready", Toast.LENGTH_SHORT).show();
                }
            } else if (id == R.id.settings_btn) {
                Log.d(TAG, "Settings button clicked");
                showApiKeyInputDialog();
            }
        }
    }

    private void showDeviceSelectionDialog() {
        Log.d(TAG, "showDeviceSelectionDialog: current device list size = " + scannedDeviceList.size());

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Device (Scanning...)");
        builder.setCancelable(false);

        // Create a layout with ListView and empty view
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        TextView emptyView = new TextView(this);
        emptyView.setText("Scanning for devices...\nPlease wait.");
        emptyView.setGravity(android.view.Gravity.CENTER);
        emptyView.setPadding(20, 40, 20, 40);
        emptyView.setTextSize(16);
        layout.addView(emptyView);

        ListView listView = new ListView(this);
        deviceListAdapter = new DeviceListAdapter(this, scannedDeviceList);
        listView.setAdapter(deviceListAdapter);
        listView.setEmptyView(emptyView);
        layout.addView(listView);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            ScannedDevice device = scannedDeviceList.get(position);
            Log.d(TAG, "Selected device: " + device.getName() + " - " + device.getAddress());
            mService.stopLeScan();
            mService.connectToDevice(device.getAddress());
            if (deviceSelectionDialog != null) {
                deviceSelectionDialog.dismiss();
                deviceSelectionDialog = null;
            }
            showMessage("Connecting to " + device.getName());
        });

        builder.setView(layout);
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "Scan cancelled by user");
            mService.stopLeScan();
            if (deviceSelectionDialog != null) {
                deviceSelectionDialog.dismiss();
                deviceSelectionDialog = null;
            }
        });

        deviceSelectionDialog = builder.create();
        deviceSelectionDialog.show();
    }

    private class DeviceListAdapter extends ArrayAdapter<ScannedDevice> {
        public DeviceListAdapter(Context context, List<ScannedDevice> devices) {
            super(context, 0, devices);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ScannedDevice device = getItem(position);

            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.device_list_item, parent, false);
            }

            TextView deviceName = convertView.findViewById(R.id.device_name);
            TextView deviceAddress = convertView.findViewById(R.id.device_address);

            deviceName.setText(device.getName());
            deviceAddress.setText(device.getAddress());

            return convertView;
        }
    }

}
