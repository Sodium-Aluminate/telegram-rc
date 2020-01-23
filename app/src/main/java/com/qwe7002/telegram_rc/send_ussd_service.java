package com.qwe7002.telegram_rc;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class send_ussd_service extends Service {
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = public_func.get_notification_obj(getApplicationContext(), getString(R.string.ussd_code_running));
        startForeground(4, notification);
        Handler handler = new Handler();
        Context context = getApplicationContext();
        String ussd = intent.getStringExtra("ussd");
        SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        String TAG = "Send ussd";

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "send_ussd: No permission.");
        }

        String bot_token = sharedPreferences.getString("bot_token", "");
        String chat_id = sharedPreferences.getString("chat_id", "");
        String request_uri = public_func.get_url(bot_token, "sendMessage");
        message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        request_body.text = context.getString(R.string.send_ussd_head) + "\n" + context.getString(R.string.ussd_code_running);
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        assert telephonyManager != null;
        String request_body_raw = new Gson().toJson(request_body);
        RequestBody body = RequestBody.create(request_body_raw, public_func.JSON);
        OkHttpClient okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(request);
        new Thread(() -> {
            String message_id_string = "-1";
            try {
                Response response = call.execute();
                message_id_string = public_func.get_message_id(Objects.requireNonNull(response.body()).string());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                telephonyManager.sendUssdRequest(ussd, new ussd_request_callback(context, sharedPreferences, Long.parseLong(message_id_string)), handler);
            }
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


}
