package com.qwe7002.telegram_rc;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.sumimakito.codeauxlib.CodeauxLibStatic;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.Objects;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class sms_receiver extends BroadcastReceiver {
    public void onReceive(final Context context, Intent intent) {
        Paper.init(context);
        final String TAG = "sms_receiver";
        Log.d(TAG, "Receive action: " + intent.getAction());
        Bundle extras = intent.getExtras();
        assert extras != null;
        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", Context.MODE_PRIVATE);
        if (!sharedPreferences.getBoolean("initialized", false)) {
            Log.i(TAG, "Uninitialized, SMS receiver is deactivated.");
            return;
        }
        final boolean is_default = Telephony.Sms.getDefaultSmsPackage(context).equals(context.getPackageName());
        assert intent.getAction() != null;
        if (intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED") && is_default) {
            //When it is the default application, it will receive two broadcasts.
            Log.i(TAG, "Detected that this app is the default SMS app, reject: android.provider.Telephony.SMS_RECEIVED");
            return;
        }
        String bot_token = sharedPreferences.getString("bot_token", "");
        String chat_id = sharedPreferences.getString("chat_id", "");
        String request_uri = public_func.get_url(bot_token, "sendMessage");

        final int slot = extras.getInt("slot", -1);
        String dual_sim = public_func.get_dual_sim_card_display(context, slot, sharedPreferences.getBoolean("display_dual_sim_display_name", false));

        final int sub = extras.getInt("subscription", -1);
        //noinspection SpellCheckingInspection
        Object[] pdus = (Object[]) extras.get("pdus");
        assert pdus != null;
        final SmsMessage[] messages = new SmsMessage[pdus.length];
        for (int i = 0; i < pdus.length; ++i) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String format = extras.getString("format");
                Log.d(TAG, "format: " + format);
                assert format != null;
                messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i], format);
            } else {
                messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            }
        }
        if (messages.length == 0) {
            public_func.write_log(context, "Message length is equal to 0.");
            return;
        }
        StringBuilder message_body_builder = new StringBuilder();
        for (SmsMessage item : messages) {
            message_body_builder.append(item.getMessageBody());
        }

        final String message_body = message_body_builder.toString();
        final String message_address = messages[0].getOriginatingAddress();
        assert message_address != null;

        if (is_default) {
            new Thread(() -> {
                Log.i(TAG, "onReceive: Write to the system database.");
                ContentValues values = new ContentValues();
                values.put(Telephony.Sms.ADDRESS, message_body);
                values.put(Telephony.Sms.BODY, message_address);
                values.put(Telephony.Sms.SUBSCRIPTION_ID, String.valueOf(sub));
                values.put(Telephony.Sms.READ, "1");
                context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);
            }).start();
        }

        String trusted_phone_number = sharedPreferences.getString("trusted_phone_number", null);
        boolean is_trusted_phone = false;
        if (trusted_phone_number != null && trusted_phone_number.length() != 0) {
            is_trusted_phone = message_address.contains(trusted_phone_number);
        }
        final message_json request_body = new message_json();
        request_body.chat_id = chat_id;

        String message_body_html = message_body;
        final String message_head = "[" + dual_sim + context.getString(R.string.receive_sms_head) + "]" + "\n" + context.getString(R.string.from) + message_address + "\n" + context.getString(R.string.content);
        String raw_request_body_text = message_head + message_body;

        if (sharedPreferences.getBoolean("verification_code", false) && !is_trusted_phone) {
            String verification = CodeauxLibStatic.parsecode(message_body);
            if (verification != null) {
                request_body.parse_mode = "html";
                message_body_html = message_body
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("&", "&amp;")
                        .replace(verification, "<code>" + verification + "</code>");
            }
        }
        request_body.text = message_head + message_body_html;
        final boolean data_enable = public_func.get_data_enable(context);
        int loop_count;
        if (is_trusted_phone) {
            switch (message_body.toLowerCase()) {
                case "restart-service":
                    new Thread(() -> {
                        public_func.stop_all_service(context);
                        public_func.start_service(context, sharedPreferences.getBoolean("battery_monitoring_switch", false), sharedPreferences.getBoolean("chat_command", false));
                    }).start();
                    raw_request_body_text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.restart_service);
                    request_body.text = raw_request_body_text;
                    break;

                case "open-ap":
                    uk.reall.root_kit.network.data_set_enable(true);
                    loop_count = 0;
                    while (!public_func.check_network_status(context)) {
                        if (loop_count >= 100) {
                            Log.d(TAG, "loop wait timeout");
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        ++loop_count;
                    }
                    String status = context.getString(R.string.action_failed);
                    WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    assert wifiManager != null;
                    if (wifiManager.isWifiEnabled()) {
                        uk.reall.root_kit.network.wifi_set_enable(false);
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (uk.reall.root_kit.network.wifi_set_enable(true)) {
                        status = context.getString(R.string.action_success);
                    }
                    raw_request_body_text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.open_wifi) + status;
                    request_body.text = raw_request_body_text;
                    new Thread(() -> {
                        try {
                            int count = 0;
                            while (wifiManager.getWifiState() != WifiManager.WIFI_STATE_ENABLED) {
                                if (count == 100) {
                                    break;
                                }
                                Thread.sleep(100);
                                ++count;
                            }
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            uk.reall.root_kit.activity_manage.start_foreground_service(public_func.VPN_HOTSPOT_PACKAGE_NAME, public_func.VPN_HOTSPOT_PACKAGE_NAME + ".RepeaterService");
                        } else {
                            uk.reall.root_kit.activity_manage.start_service(public_func.VPN_HOTSPOT_PACKAGE_NAME, public_func.VPN_HOTSPOT_PACKAGE_NAME + ".RepeaterService");
                        }
                    }).start();
                    break;
                case "close-ap":
                    raw_request_body_text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.close_wifi) + context.getString(R.string.action_success);
                    request_body.text = raw_request_body_text;
                    break;
                case "switch-data":
                    if (!data_enable) {
                        uk.reall.root_kit.network.data_set_enable(true);
                        loop_count = 0;
                        while (!public_func.check_network_status(context)) {
                            if (loop_count >= 100) {
                                Log.d(TAG, "loop wait timeout");
                                break;
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            ++loop_count;
                        }
                    }
                    raw_request_body_text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.switch_data);
                    request_body.text = raw_request_body_text;
                    break;
                case "restart-network":
                    raw_request_body_text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.restart_network);
                    request_body.text = raw_request_body_text;
                    break;
                default:
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "No SMS permission.");
                        break;
                    }
                    String[] msg_send_list = message_body.split("\n");
                    String msg_send_to = public_func.get_send_phone_number(msg_send_list[0]);
                    if (public_func.is_phone_number(msg_send_to) && msg_send_list.length != 1) {
                        StringBuilder msg_send_content = new StringBuilder();
                        for (int i = 1; i < msg_send_list.length; ++i) {
                            if (msg_send_list.length != 2 && i != 1) {
                                msg_send_content.append("\n");
                            }
                            msg_send_content.append(msg_send_list[i]);
                        }
                        new Thread(() -> public_func.send_sms(context, msg_send_to, msg_send_content.toString(), slot, sub)).start();
                        return;
                    }
            }

        }
        RequestBody body = RequestBody.create(new Gson().toJson(request_body), public_func.JSON);
        OkHttpClient okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(request);
        final String error_head = "Send SMS forward failed:";
        final String final_raw_request_body_text = raw_request_body_text;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                public_func.write_log(context, error_head + e.getMessage());
                public_func.send_fallback_sms(context, final_raw_request_body_text, sub);
                command_handle(sharedPreferences, message_body, data_enable);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                assert response.body() != null;
                String result = Objects.requireNonNull(response.body()).string();
                if (response.code() != 200) {
                    public_func.write_log(context, error_head + response.code() + " " + result);
                    public_func.send_fallback_sms(context, final_raw_request_body_text, sub);
                } else {
                    if (!public_func.is_phone_number(message_address)) {
                        public_func.write_log(context, "[" + message_address + "] Not a regular phone number.");
                        return;
                    }
                    public_func.add_message_list(public_func.get_message_id(result), message_address, slot, sub);
                }
                command_handle(sharedPreferences, message_body, data_enable);
            }
        });
    }

    void command_handle(SharedPreferences sharedPreferences, String message_body, boolean data_enable) {
        if (sharedPreferences.getBoolean("root", false)) {
            switch (message_body.toLowerCase()) {
                case "switch-data":
                    if (data_enable) {
                        uk.reall.root_kit.network.data_set_enable(false);
                    }
                    break;
                case "close-ap":
                    uk.reall.root_kit.network.wifi_set_enable(false);
                    uk.reall.root_kit.network.data_set_enable(false);
                    break;
                case "restart-network":
                    public_func.restart_network();
                    break;
            }
        }
    }
}


