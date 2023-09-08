package com.example.zkfinger10demo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.zkfinger10demo.ZKUSBManager.ZKUSBManager;
import com.example.zkfinger10demo.ZKUSBManager.ZKUSBManagerListener;
import com.example.zkfinger10demo.util.PermissionUtils;
import com.zkteco.android.biometric.FingerprintExceptionListener;
import com.zkteco.android.biometric.core.device.ParameterHelper;
import com.zkteco.android.biometric.core.device.TransportType;
import com.zkteco.android.biometric.core.utils.LogHelper;
import com.zkteco.android.biometric.core.utils.ToolUtils;
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintCaptureListener;
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintSensor;
import com.zkteco.android.biometric.module.fingerprintreader.FingprintFactory;
import com.zkteco.android.biometric.module.fingerprintreader.ZKFingerService;
import com.zkteco.android.biometric.module.fingerprintreader.exception.FingerprintException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final int ZKTECO_VID =   0x1b55;
    private static final int LIVE20R_PID =   0x0120;
    private static final int LIVE10R_PID =   0x0124;
    private static final String TAG = "MainActivity";
    private final int REQUEST_PERMISSION_CODE = 9;
    private ZKUSBManager zkusbManager = null;
    private FingerprintSensor fingerprintSensor = null;
    private TextView textView = null;
    private EditText editText = null;
    private ImageView imageView = null;
    private int usb_vid = ZKTECO_VID;
    private int usb_pid = 0;
    private boolean bStarted = false;
    private int deviceIndex = 0;
    private boolean isReseted = false;
    private String strUid = null;
    private final static int ENROLL_COUNT   =   3;
    private int enroll_index = 0;
    private byte[][] regtemparray = new byte[3][2048];  //register template buffer array
    private boolean bRegister = false;
    private DBManager dbManager = new DBManager();
    private String dbFileName;


    void doRegister(byte[] template)
    {
        byte[] bufids = new byte[256];
        int ret = ZKFingerService.identify(template, bufids, 70, 1);
        if (ret > 0)
        {
            String strRes[] = new String(bufids).split("\t");
            setResult("the finger already enroll by " + strRes[0] + ",cancel enroll");
            bRegister = false;
            enroll_index = 0;
            return;
        }
        if (enroll_index > 0 && (ret = ZKFingerService.verify(regtemparray[enroll_index-1], template)) <= 0)
        {
            setResult("please press the same finger 3 times for the enrollment, cancel enroll, socre=" + ret);
            bRegister = false;
            enroll_index = 0;
            return;
        }
        System.arraycopy(template, 0, regtemparray[enroll_index], 0, 2048);
        enroll_index++;
        if (enroll_index == ENROLL_COUNT) {
            bRegister = false;
            enroll_index = 0;
            byte[] regTemp = new byte[2048];
            if (0 < (ret = ZKFingerService.merge(regtemparray[0], regtemparray[1], regtemparray[2], regTemp))) {
                int retVal = 0;
                retVal = ZKFingerService.save(regTemp, strUid);
                if (0 == retVal)
                {
                    String strFeature = Base64.encodeToString(regTemp, 0, ret, Base64.NO_WRAP);
                    dbManager.insertUser(strUid, strFeature);
                    setResult("enroll succ");
                }
                else
                {
                    setResult("enroll fail, add template fail, ret=" + retVal);
                }
            } else {
                setResult("enroll fail");
            }
            bRegister = false;
        } else {
            setResult("You need to press the " + (3 - enroll_index) + " times fingerprint");
        }
    }

    void doIdentify(byte[] template)
    {
        byte[] bufids = new byte[256];
        int ret = ZKFingerService.identify(template, bufids, 70, 1);
        if (ret > 0) {
            String strRes[] = new String(bufids).split("\t");
            setResult("identify succ, userid:" + strRes[0].trim() + ", score:" + strRes[1].trim());
        } else {
            setResult("identify fail, ret=" + ret);
        }
    }


    private FingerprintCaptureListener fingerprintCaptureListener = new FingerprintCaptureListener() {
        @Override
        public void captureOK(byte[] fpImage) {
            final Bitmap bitmap = ToolUtils.renderCroppedGreyScaleBitmap(fpImage, fingerprintSensor.getImageWidth(), fingerprintSensor.getImageHeight());
            runOnUiThread(new Runnable() {
                public void run() {
                    imageView.setImageBitmap(bitmap);
                }
            });
        }

        @Override
        public void captureError(FingerprintException e) {
            // nothing to do
        }

        @Override
        public void extractOK(byte[] fpTemplate) {
            if (bRegister)
            {
                doRegister(fpTemplate);
            }
            else
            {
                doIdentify(fpTemplate);
            }
        }

        @Override
        public void extractError(int i) {
            // nothing to do
        }
    };

    private FingerprintExceptionListener fingerprintExceptionListener = new FingerprintExceptionListener() {
        @Override
        public void onDeviceException() {
            LogHelper.e("usb exception!!!");
            if (!isReseted) {
                try {
                    fingerprintSensor.openAndReboot(deviceIndex);
                } catch (FingerprintException e) {
                    e.printStackTrace();
                }
                isReseted = true;
            }
        }
    };

    private ZKUSBManagerListener zkusbManagerListener = new ZKUSBManagerListener() {
        @Override
        public void onCheckPermission(int result) {
            afterGetUsbPermission();
        }

        @Override
        public void onUSBArrived(UsbDevice device) {
            if (bStarted)
            {
                closeDevice();
                tryGetUSBPermission();
            }
        }

        @Override
        public void onUSBRemoved(UsbDevice device) {
            LogHelper.d("usb removed!");
        }
    };

    private void initUI()
    {
        textView = (TextView)findViewById(R.id.txtResult);
        editText = (EditText)findViewById(R.id.editID);
        imageView = (ImageView)findViewById(R.id.imageFP);
    }

    /**
     * storage permission
     */
    private void checkStoragePermission() {
        String[] permission = new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        ArrayList<String> deniedPermissions = PermissionUtils.checkPermissions(this, permission);
        if (deniedPermissions.isEmpty()) {
            //permission all granted
            Log.i(TAG, "[checkStoragePermission]: all granted");
        } else {
            int size = deniedPermissions.size();
            String[] deniedPermissionArray = deniedPermissions.toArray(new String[size]);
            PermissionUtils.requestPermission(this, deniedPermissionArray, REQUEST_PERMISSION_CODE);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_PERMISSION_CODE:
                boolean granted = true;
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                    }
                }
                if (granted) {
                    Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Permission Denied,The application can't run on this device", Toast.LENGTH_SHORT).show();
                }
            default:
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dbFileName = getFilesDir().getAbsolutePath() + "/zkfinger10.db";
        initUI();
        checkStoragePermission();
        zkusbManager = new ZKUSBManager(this.getApplicationContext(), zkusbManagerListener);
        zkusbManager.registerUSBPermissionReceiver();
    }

    private void createFingerprintSensor()
    {
        if (null != fingerprintSensor)
        {
            FingprintFactory.destroy(fingerprintSensor);
            fingerprintSensor = null;
        }
        // Define output log level
        LogHelper.setLevel(Log.VERBOSE);
        LogHelper.setNDKLogLevel(Log.ASSERT);
        // Start fingerprint sensor
        Map deviceParams = new HashMap();
        //set vid
        deviceParams.put(ParameterHelper.PARAM_KEY_VID, usb_vid);
        //set pid
        deviceParams.put(ParameterHelper.PARAM_KEY_PID, usb_pid);
        fingerprintSensor = FingprintFactory.createFingerprintSensor(getApplicationContext(), TransportType.USB, deviceParams);
    }

    private boolean enumSensor()
    {
        UsbManager usbManager = (UsbManager)this.getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            int device_vid = device.getVendorId();
            int device_pid = device.getProductId();
            if (device_vid == ZKTECO_VID && (device_pid == LIVE20R_PID || device_pid == LIVE10R_PID))
            {
                usb_pid = device_pid;
                return true;
            }
        }
        return false;
    }


    private void tryGetUSBPermission() {
        zkusbManager.initUSBPermission(usb_vid, usb_pid);
    }

    private void afterGetUsbPermission()
    {
        openDevice();
    }

    private void openDevice()
    {
        createFingerprintSensor();
        bRegister = false;
        enroll_index = 0;
        isReseted = false;
        try {
            //fingerprintSensor.setCaptureMode(1);
            fingerprintSensor.open(deviceIndex);
            //load all templates form db
            if (dbManager.opendb(dbFileName) && dbManager.getCount() > 0)
            {
                HashMap<String, String> vUserList;
                vUserList = dbManager.queryUserList();
                int ret = 0;
                if (vUserList.size() > 0)
                {
                    for (Map.Entry<String, String> entry : vUserList.entrySet()) {
                        String strID = entry.getKey();
                        String strFeature = entry.getValue();
                        byte[] blobFeature = Base64.decode(strFeature, Base64.NO_WRAP);
                        ret = ZKFingerService.save(blobFeature, strID);
                        if (0 != ret)
                        {
                            LogHelper.e("add [" + strID + "] template failed, ret=" + ret);
                        }
                    }
                }
            }
            {
                // device parameter
                LogHelper.d("sdk version" + fingerprintSensor.getSDK_Version());
                LogHelper.d("firmware version" + fingerprintSensor.getFirmwareVersion());
                LogHelper.d("serial:" + fingerprintSensor.getStrSerialNumber());
                LogHelper.d("width=" + fingerprintSensor.getImageWidth() + ", height=" + fingerprintSensor.getImageHeight());
            }
            fingerprintSensor.setFingerprintCaptureListener(deviceIndex, fingerprintCaptureListener);
            fingerprintSensor.SetFingerprintExceptionListener(fingerprintExceptionListener);
            fingerprintSensor.startCapture(deviceIndex);
            bStarted = true;
            textView.setText("connect success!");
        } catch (FingerprintException e) {
            e.printStackTrace();
            // try to  reboot the sensor
            try {
                fingerprintSensor.openAndReboot(deviceIndex);
            } catch (FingerprintException ex) {
                ex.printStackTrace();
            }
            textView.setText("connect failed!");
        }
    }

    private void closeDevice()
    {
        if (bStarted)
        {
            try {
                fingerprintSensor.stopCapture(deviceIndex);
                fingerprintSensor.close(deviceIndex);
            } catch (FingerprintException e) {
                e.printStackTrace();
            }
            bStarted = false;
        }
    }

    public void onBnStart(View view)
    {
        if (bStarted)
        {
            textView.setText("Device already connected!");
            return;
        }
        if (!enumSensor())
        {
            textView.setText("Device not found!");
            return;
        }
        tryGetUSBPermission();
    }

    public void onBnStop(View view)
    {
        if (!bStarted)
        {
            textView.setText("Device not connected!");
            return;
        }
        closeDevice();
        textView.setText("Device closed!");
    }

    public void onBnRegister(View view)
    {
        if (bStarted) {
            strUid = editText.getText().toString();
            if (null == strUid || strUid.isEmpty()) {
                textView.setText("Please input your user id");
                bRegister = false;
                return;
            }
            if (dbManager.isUserExited(strUid)) {
                bRegister = false;
                textView.setText("The user[" + strUid + "] had registered!");
                return;
            }
            bRegister = true;
            enroll_index = 0;
            textView.setText("Please press your finger 3 times.");
        } else {
            textView.setText("Please start capture first");
        }
    }

    public void onBnIdentify(View view)
    {
        if (bStarted) {
            bRegister = false;
            enroll_index = 0;
        } else {
            textView.setText("Please start capture first");
        }
    }

    private void setResult(String result)
    {
        final String mStrText = result;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.setText(mStrText);
            }
        });
    }

    public void onBnDelete(View view)
    {
        if (bStarted) {
            strUid = editText.getText().toString();
            if (null == strUid || strUid.isEmpty()) {
                textView.setText("Please input your user id");
                return;
            }
            if (!dbManager.isUserExited(strUid)) {
                textView.setText("The user no registered");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Do you want to delete the user ?")
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (dbManager.deleteUser(strUid)) {
                                ZKFingerService.del(strUid);
                                setResult("Delete success !");
                            } else {
                                setResult("Open db fail !");
                            }
                        }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    }).show();
        }
    }

    public void onBnClear(View view)
    {
        if (bStarted) {
            new AlertDialog.Builder(this)
                    .setTitle("Do you want to delete all the users ?")
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (dbManager.clear()) {
                                ZKFingerService.clear();
                                setResult("Clear success！");
                            } else {
                                setResult("Open db fail！");
                            }
                        }
                    })
                    .setNegativeButton("no", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                        }
                    })
                    .show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bStarted)
        {
            closeDevice();
        }
        zkusbManager.unRegisterUSBPermissionReceiver();
    }
}