package io.rtek.rtvoice;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GcmListenerService;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public class Main extends AppCompatActivity implements IControlChannelListener{

    Snackbar sb;
    ControlChannel cc;
    int callerId = 0;
    String server = Settings.server;
    final Activity main = this;
    private BroadcastReceiver receiver;
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        cc = new ControlChannel(this, server);
        cc.start();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        try {
            // Yeah, this is hidden field.
            field = PowerManager.class.getClass().getField("PROXIMITY_SCREEN_OFF_WAKE_LOCK").getInt(null);
        } catch (Throwable ignored) {
        }

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(field, getLocalClassName());

        setMainView();
        //Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        //setSupportActionBar(toolbar);

        AudioManager m_amAudioManager;
        m_amAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        m_amAudioManager.setMode(AudioManager.MODE_IN_CALL);
        m_amAudioManager.setSpeakerphoneOn(false);
        m_amAudioManager.setRouting(AudioManager.MODE_NORMAL,AudioManager.ROUTE_EARPIECE, AudioManager.ROUTE_ALL);

        requestRecordAudioPermission();

        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);

        IntentFilter filter = new IntentFilter();
        filter.addAction("INCOMING");
        final Activity parent = this;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra("number")) {
                    String nr = intent.getStringExtra("number");
                    cc.createIncoming(Integer.parseInt(nr));
                }
            }
        };
        registerReceiver(receiver, filter);
    }

    @Override
    public void onResume() {
        Intent intent = getIntent();
        if (intent.hasExtra("number")) {
            String nr = intent.getStringExtra("number");
            cc.createIncoming(Integer.parseInt(nr));
            hideKeyboard();
        }
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        cc.close();
        super.onDestroy();
    }
    private static final int RESULT_PICK_CONTACT = 10;
    private void setMainView(){
        setContentView(R.layout.activity_main);
        TextView tv = (TextView) findViewById(R.id.textView2);
        final EditText number = (EditText) findViewById(R.id.editText);
        number.requestFocus();
        InputMethodManager imm = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
        sb = Snackbar.make(findViewById(R.id.content_main), "Unknown error", Snackbar.LENGTH_INDEFINITE);
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        final IControlChannelListener main = this;
        tv.setText(Integer.toString(cc.getNumber()));
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(cc.activeCall()){
                    cc.hangup();
                    return;
                }
                if (number.getText().length() != 0){
                    cc.dial(Integer.parseInt(number.getText().toString()));
                    setInCallView();
                }
            }
        });
        final Button contactSelect =  (Button) findViewById(R.id.buttonContact);
        contactSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent contactPickerIntent = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                startActivityForResult(contactPickerIntent, RESULT_PICK_CONTACT);
            }
        });
        if(wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // check whether the result is ok
        if (resultCode == RESULT_OK) {
            // Check for the request code, we might be usign multiple startActivityForReslut
            switch (requestCode) {
                case RESULT_PICK_CONTACT:
                    Uri uri = data.getData();
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    cursor.moveToFirst();
                    String number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    number = number.replace("-", "");
                    number = number.replace(" ", "");
                    while(number.length() != 6 && !cursor.isLast()){
                        cursor.moveToNext();
                        number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        number = number.replace("-", "");
                        number = number.replace(" ", "");
                    }
                    if (number.length() != 6){
                        Toast.makeText(this, "No valid RTVOICE number was found for selected contact", Toast.LENGTH_LONG).show();
                        break;
                    }
                    final EditText numberTextView = (EditText) findViewById(R.id.editText);
                    numberTextView.setText(number);
                    break;
            }
        } else {
            Log.e("MainActivity", "Failed to pick contact");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        hideKeyboard();
    }

    @Override
    public void onStop() {
        super.onStop();
        hideKeyboard();
    }

    int createIncoming = 0;


    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private int field = 0x00000020;

    private void setInCallView(){
        hideKeyboard();
        setContentView(R.layout.in_call);
        final Button hangupBtn = (Button) findViewById(R.id.buttonHangup);
        hangupBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                cc.hangup();
            }
        });
        if(!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        final TextView encrypted = (TextView) findViewById(R.id.textViewCallEncrypted);
        encrypted.setVisibility(View.INVISIBLE);

    }

    private void setConnectingView(){
        hideKeyboard();
        setContentView(R.layout.connecting);
    }
    Ringtone r;
    private void setIncomingCallView(final int number){
        hideKeyboard();
        setContentView(R.layout.incoming_call);
        final Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        r = RingtoneManager.getRingtone(getApplicationContext(), notification);
        r.play();

        if(isAppIsInBackground(this)) {
            Intent intent = new Intent(this, Main.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            startActivity(intent);
        }

        final Button answerBtn = (Button) findViewById(R.id.buttonAnswer);
        answerBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                cc.answer(number);
                r.stop();
                setInCallView();
            }
        });
        final Button declineBtn = (Button) findViewById(R.id.buttonDecline);
        declineBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                cc.decline(number);
                r.stop();
                setMainView();
            }
        });
        final TextView tv = (TextView) findViewById(R.id.textViewIncoming);
        tv.setText("Incoming call from " + Integer.toString(number));
        hideKeyboard();
    }

    private void hideKeyboard(){
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    @Override
    public void setNumber(final int number) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (number == 0){ //If malformed number retry
                    cc.close();
                    cc.start();
                }
                TextView tv = (TextView) findViewById(R.id.textView2);
                if(tv != null) {
                    tv.setText(Integer.toString(number));
                }
            }
        });
    }

    @Override
    public void incoming(final int number) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.w("CC ", "Incomming call");
                setIncomingCallView(number);
            }
        });
    }

    @Override
    public void initializeCall(int number) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.w("CC ", "Call initialized");
            }
        });
    }

    @Override
    public void callEnded(int number) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.w("MSG ", "Call ended");
                setMainView();
                if(r != null && r.isPlaying())
                    r.stop();
                if(wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        });
    }

    @Override
    public void callStatusChanged(final CallStatus status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.w("MSG ", status.toString());
                TextView tv = (TextView) findViewById(R.id.textView_CallStatus);
                ProgressBar pb = (ProgressBar) findViewById(R.id.progressBar2);
                if(tv != null) {
                    switch (status) {
                        case DIALING:
                            tv.setText("Dialing...");
                            pb.setVisibility(View.VISIBLE);
                            break;
                        case ANSWERED:
                            tv.setText("Answered by other party...");
                            pb.setVisibility(View.VISIBLE);
                            break;
                        case CONNECTING:
                            tv.setText("Connecting...");
                            pb.setVisibility(View.VISIBLE);
                            break;
                        case CONNECTED:
                            tv.setText("Ongoing call");
                            pb.setVisibility(View.INVISIBLE);
                            break;
                        case ERROR:
                            setMainView();
                            Toast.makeText(main, "A call error occurred", Toast.LENGTH_LONG).show();
                            break;
                        case BUSY:
                            setMainView();
                            Toast.makeText(main, "Recipient busy", Toast.LENGTH_LONG).show();
                            break;
                        case DECLINED:
                            setMainView();
                            Toast.makeText(main, "Recipient busy", Toast.LENGTH_LONG).show();
                            break;
                    }
                }
            }
        });
    }

    @Override
    public void messageReceived(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(message != null)
                    Log.w("MSG ", message);
            }
        });

    }

    @Override
    public void callSecure(final boolean state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final TextView encrypted = (TextView) findViewById(R.id.textViewCallEncrypted);
                if(encrypted != null && state)
                    encrypted.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public String getString(String key) {
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        return sharedPref.getString(key, "");
    }

    @Override
    public void saveString(String key, String value) {
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(key, value);
        editor.apply();
    }

    @Override
    public void ControlChannelConnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(!cc.activeCall()) {
                    setMainView();
                }
            }
        });
    }

    @Override
    public void ControlChannelDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(!cc.activeCall())
                    setConnectingView();
            }
        });
    }

    @Override
    public Pair<TrustManagerFactory, KeyManagerFactory> getTrustManagerFactory() {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null);
            InputStream stream = this.getAssets().open("server.crt");
            BufferedInputStream bis = new BufferedInputStream(stream);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (bis.available() > 0) {
                Certificate cert = cf.generateCertificate(bis);
                trustStore.setCertificateEntry("cert" + bis.available(), cert);
            }
            KeyManagerFactory kmfactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmfactory.init(trustStore, "1234".toCharArray());
            TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            return new Pair<>(tmf, kmfactory);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    @Override
    public String getGcmDeviceId() {
        try {
            GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(this);
            return gcm.register("211109104165");
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void requestRecordAudioPermission() {
        //check API version, do nothing if API version < 23!
        int currentapiVersion = android.os.Build.VERSION.SDK_INT;
        if (currentapiVersion > android.os.Build.VERSION_CODES.LOLLIPOP){

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

                // Should we show an explanation?
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {

                    // Show an expanation to the user *asynchronously* -- don't block
                    // this thread waiting for the user's response! After the user
                    // sees the explanation, try again to request the permission.

                } else {

                    // No explanation needed, we can request the permission.

                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    Log.d("Activity", "Granted!");

                } else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Log.d("Activity", "Denied!");
                    finish();
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    private boolean isAppIsInBackground(Context context) {
        boolean isInBackground = true;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT_WATCH) {
            List<ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    for (String activeProcess : processInfo.pkgList) {
                        if (activeProcess.equals(context.getPackageName())) {
                            isInBackground = false;
                        }
                    }
                }
            }
        } else {
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getRunningTasks(1);
            ComponentName componentInfo = taskInfo.get(0).topActivity;
            if (componentInfo.getPackageName().equals(context.getPackageName())) {
                isInBackground = false;
            }
        }

        return isInBackground;
    }

    public Context getActivity() {
        return this;
    }
}
