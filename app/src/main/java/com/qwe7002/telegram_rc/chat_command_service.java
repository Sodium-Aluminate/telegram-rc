package com.qwe7002.telegram_rc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class chat_command_service extends Service {
    private static long offset = 0;
    private static int magnification = 1;
    private static int error_magnification = 1;
    private String chat_id;
    private String bot_token;
    private Context context;
    private OkHttpClient okhttp_client;
    private broadcast_receiver broadcast_receiver;
    private PowerManager.WakeLock wakelock;
    private WifiManager.WifiLock wifiLock;
    private int send_sms_status = -1;
    private int send_slot_temp = -1;
    private String send_to_temp;
    private SharedPreferences sharedPreferences;
    private String bot_username = "";
    private boolean privacy_mode;
    final String TAG = "chat_command";
    static Thread thread_main;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = public_func.get_notification_obj(getApplicationContext(), getString(R.string.chat_command_service_name));
        startForeground(2, notification);
        return START_STICKY;
    }

    @SuppressLint({"InvalidWakeLockTag", "WakelockTimeout"})
    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();

        Paper.init(context);

        sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);

        chat_id = sharedPreferences.getString("chat_id", "");
        bot_token = sharedPreferences.getString("bot_token", "");
        okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));
        privacy_mode = sharedPreferences.getBoolean("privacy_mode", false);
        wifiLock = ((WifiManager) Objects.requireNonNull(context.getApplicationContext().getSystemService(Context.WIFI_SERVICE))).createWifiLock(WifiManager.WIFI_MODE_FULL, "bot_command_polling_wifi");
        wakelock = ((PowerManager) Objects.requireNonNull(context.getSystemService(Context.POWER_SERVICE))).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bot_command_polling");
        wifiLock.setReferenceCounted(false);
        wakelock.setReferenceCounted(false);
        if (!wifiLock.isHeld()) {
            wifiLock.acquire();
        }
        if (!wakelock.isHeld()) {
            wakelock.acquire();
        }
        thread_main = new Thread(new thread_main_runnable());
        thread_main.start();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(public_func.broadcast_stop_service);
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        broadcast_receiver = new broadcast_receiver();
        registerReceiver(broadcast_receiver, intentFilter);

    }

    private void receive_handle(JsonObject result_obj) {
        String message_type = "";
        long update_id = result_obj.get("update_id").getAsLong();
        offset = update_id + 1;
        final message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        JsonObject message_obj = null;
        if (result_obj.has("message")) {
            message_obj = result_obj.get("message").getAsJsonObject();
            message_type = message_obj.get("chat").getAsJsonObject().get("type").getAsString();
        }
        if (result_obj.has("channel_post")) {
            message_type = "channel";
            message_obj = result_obj.get("channel_post").getAsJsonObject();
        }
        if (message_obj == null) {
            public_func.write_log(context, "Request type is not allowed by security policy.");
            return;
        }
        JsonObject from_obj = null;
        final boolean message_type_is_private = message_type.equals("private");
        if (!message_type_is_private && bot_username == null) {
            Log.i(TAG, "receive_handle: Did not successfully get bot_username.");
            get_me();
        }
        if (message_obj.has("from")) {
            from_obj = message_obj.get("from").getAsJsonObject();
            if (!message_type_is_private && from_obj.get("is_bot").getAsBoolean()) {
                Log.i(TAG, "receive_handle: receive from bot.");
                return;
            }
        }
        if (message_obj.has("chat")) {
            from_obj = message_obj.get("chat").getAsJsonObject();
        }

        assert from_obj != null;
        String from_id = from_obj.get("id").getAsString();
        if (!Objects.equals(chat_id, from_id)) {
            public_func.write_log(context, "Chat ID[" + from_id + "] not allow");
            return;
        }

        String command = "";
        String command_bot_username = "";
        String request_msg = "";
        if (message_obj.has("text")) {
            request_msg = message_obj.get("text").getAsString();
        }
        if (message_obj.has("reply_to_message")) {
            message_item save_item = Paper.book().read(message_obj.get("reply_to_message").getAsJsonObject().get("message_id").getAsString(), null);
            if (save_item != null) {
                String phone_number = save_item.phone;
                int card_slot = save_item.card;
                int sub_id = save_item.sub_id;
                if (card_slot != -1 && sub_id == -1) {
                    sub_id = public_func.get_sub_id(context, card_slot);
                }
                public_func.send_sms(context, phone_number, request_msg, card_slot, sub_id);
                return;
            }
            if (!message_type_is_private) {
                Log.i(TAG, "receive_handle: The message id could not be found, ignored.");
                return;
            }
        }
        if (message_obj.has("entities")) {
            String temp_command;
            JsonArray entities_arr = message_obj.get("entities").getAsJsonArray();
            JsonObject entities_obj_command = entities_arr.get(0).getAsJsonObject();
            if (entities_obj_command.get("type").getAsString().equals("bot_command")) {
                int command_offset = entities_obj_command.get("offset").getAsInt();
                int command_end_offset = command_offset + entities_obj_command.get("length").getAsInt();
                temp_command = request_msg.substring(command_offset, command_end_offset).trim().toLowerCase();
                command = temp_command;
                if (temp_command.contains("@")) {
                    int command_at_location = temp_command.indexOf("@");
                    command = temp_command.substring(0, command_at_location);
                    command_bot_username = temp_command.substring(command_at_location + 1);
                }

            }
        }

        if (!message_type_is_private && privacy_mode && !command_bot_username.equals(bot_username)) {
            Log.i(TAG, "receive_handle: Privacy mode, no username found.");
            return;
        }


        boolean has_command = false;
        switch (command) {
            case "/help":
            case "/start":
                String dual_card = "\n" + getString(R.string.sendsms);
                if (public_func.get_active_card(context) == 2) {
                    dual_card = "\n" + getString(R.string.sendsms_dual);
                }
                String config_adb = "";
                if (sharedPreferences.getBoolean("root", false)) {
                    config_adb = "\n" + context.getString(R.string.config_adb_message);
                }
                String switch_ap = "";
                if (is_vpn_hotsport_exist()) {
                    switch_ap = "\n" + getString(R.string.switch_ap_message);
                }
                request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.available_command) + dual_card + switch_ap + config_adb;
                if (!message_type_is_private && privacy_mode && !bot_username.equals("")) {
                    request_body.text = request_body.text.replace(" -", "@" + bot_username + " -");
                }
                has_command = true;
                break;
            case "/ping":
            case "/getinfo":
                String card_info = "";
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    card_info = "\nSIM:" + public_func.get_sim_display_name(context, 0);
                    if (public_func.get_active_card(context) == 2) {
                        String data_card = "";
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            data_card = "\n" + getString(R.string.current_data_card) + ":SIM" + public_func.get_data_sim_id(context);
                        }
                        card_info = data_card + "\nSIM1:" + public_func.get_sim_display_name(context, 0) + "\nSIM2:" + public_func.get_sim_display_name(context, 1);

                    }
                }
                request_body.text = getString(R.string.system_message_head) + "\n" + context.getString(R.string.current_battery_level) + get_battery_info(context) + "\n" + getString(R.string.current_network_connection_status) + public_func.get_network_type(context) + card_info;
                has_command = true;
                break;
            case "/log":
                request_body.text = getString(R.string.system_message_head) + public_func.read_log(context, 10);
                has_command = true;
                break;
            case "/config_adb":
            case "/configadb":
                if (sharedPreferences.getBoolean("root", false)) {
                    String[] command_list = request_msg.split(" ");
                    StringBuilder result = new StringBuilder();
                    result.append(getString(R.string.system_message_head)).append("\n").append(getString(R.string.adb_config));
                    if (command_list.length > 1 && uk.reall.root_kit.nadb.set_nadb(command_list[1])) {
                        result.append(getString(R.string.action_success));
                    } else {
                        result.append(getString(R.string.action_failed));
                    }
                    request_body.text = result.toString();
                } else {
                    request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.not_getting_root);
                }
                has_command = true;
                break;
            case "/switch_ap":
            case "/switchap":
                if (sharedPreferences.getBoolean("root", false)) {
                    if (!is_vpn_hotsport_exist()) {
                        break;
                    }
                    boolean wifi_open = false;
                    WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    assert wifiManager != null;
                    if (wifiManager.isWifiEnabled()) {
                        String status = context.getString(R.string.action_failed);
                        if (uk.reall.root_kit.network.wifi_set_enable(false)) {
                            status = context.getString(R.string.action_success);
                        }

                        request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.close_wifi) + status;
                    } else {
                        String status = context.getString(R.string.action_failed);
                        if (uk.reall.root_kit.network.wifi_set_enable(true)) {
                            status = context.getString(R.string.action_success);
                        }
                        wifi_open = true;
                        request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.open_wifi) + status;
                    }
                    request_body.text += "\n" + context.getString(R.string.current_battery_level) + get_battery_info(context);
                    if (wifi_open) {
                        new Thread(() -> {
                            try {
                                int count = 0;
                                while (wifiManager.getWifiState() != WifiManager.WIFI_STATE_ENABLED) {
                                    if (count == 600) {
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
                    }
                } else {
                    request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.not_getting_root);
                }
                has_command = true;
                break;
            case "/switch_data":
            case "/switchdata":
            case "/restart_network":
            case "/close_ap":
                if (sharedPreferences.getBoolean("root", false)) {
                    switch (command) {
                        case "/restart_network":
                            request_body.text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.restart_network);
                            break;
                        case "/close_ap":
                            request_body.text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.close_wifi) + context.getString(R.string.action_success);
                            break;
                        default:
                            request_body.text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.switch_data);
                            break;
                    }
                } else {
                    request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.not_getting_root);
                }
                has_command = true;
                break;
            case "/sendsms":
            case "/sendsms1":
            case "/sendsms2":
                has_command = true;
                String[] msg_send_list = request_msg.split("\n");
                if (msg_send_list.length > 2) {
                    String msg_send_to = public_func.get_send_phone_number(msg_send_list[1]);
                    if (public_func.is_phone_number(msg_send_to)) {
                        StringBuilder msg_send_content = new StringBuilder();
                        for (int i = 2; i < msg_send_list.length; ++i) {
                            if (msg_send_list.length != 3 && i != 2) {
                                msg_send_content.append("\n");
                            }
                            msg_send_content.append(msg_send_list[i]);
                        }
                        if (public_func.get_active_card(context) == 1) {
                            public_func.send_sms(context, msg_send_to, msg_send_content.toString(), -1, -1);
                            return;
                        }
                        int slot = -1;
                        switch (command) {
                            case "/sendsms":
                            case "/sendsms1":
                                slot = 0;
                                break;
                            case "/sendsms2":
                                slot = 1;
                                break;
                        }
                        int sub_id = public_func.get_sub_id(context, slot);
                        if (sub_id != -1) {
                            public_func.send_sms(context, msg_send_to, msg_send_content.toString(), slot, sub_id);
                            return;
                        }
                    }
                } else {
                    if (message_type_is_private) {
                        send_sms_status = 0;
                        send_slot_temp = -1;
                        if (public_func.get_active_card(context) > 1) {
                            switch (command) {
                                case "/sendsms":
                                case "/sendsms1":
                                    send_slot_temp = 0;
                                    break;
                                case "/sendsms2":
                                    send_slot_temp = 1;
                                    break;
                            }
                        }
                        has_command = false;
                    }
                }
                request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.failed_to_get_information);
                break;
            default:
                if (!message_type_is_private) {
                    Log.i(TAG, "receive_handle: The conversation is not Private and does not prompt an error.");
                    return;
                }
                request_body.text = context.getString(R.string.system_message_head) + "\n" + getString(R.string.unknown_command);
                break;
        }
        if (!has_command) {
            switch (send_sms_status) {
                case 0:
                    send_sms_status = 1;
                    request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.enter_number);
                    Log.i(TAG, "receive_handle: Enter the interactive SMS sending mode.");
                    break;
                case 1:
                    String temp_to = public_func.get_send_phone_number(request_msg);
                    Log.d(TAG, "receive_handle: " + temp_to);
                    if (public_func.is_phone_number(temp_to)) {
                        send_to_temp = temp_to;
                        request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.enter_content);
                        send_sms_status = 2;
                    } else {
                        send_sms_status = -1;
                        send_slot_temp = -1;
                        send_to_temp = null;
                        request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.unable_get_phone_number);

                    }
                    break;
                case 2:
                    if (public_func.get_active_card(context) == 1) {
                        public_func.send_sms(context, send_to_temp, request_msg, -1, -1);
                        return;
                    }
                    int sub_id = public_func.get_sub_id(context, send_slot_temp);
                    if (sub_id != -1) {
                        public_func.send_sms(context, send_to_temp, request_msg, send_slot_temp, sub_id);
                        send_sms_status = -1;
                        send_slot_temp = -1;
                        send_to_temp = null;
                        return;
                    }
                    request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.failed_to_get_information);
                    break;
            }
        } else {
            send_sms_status = -1;
            send_slot_temp = -1;
            send_to_temp = null;
        }

        String request_uri = public_func.get_url(bot_token, "sendMessage");
        RequestBody body = RequestBody.create(new Gson().toJson(request_body), public_func.JSON);
        Request send_request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(send_request);
        final String error_head = "Send reply failed:";
        final boolean final_has_command = has_command;
        final String final_command = command;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                public_func.write_log(context, error_head + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() != 200) {
                    assert response.body() != null;
                    public_func.write_log(context, error_head + response.code() + " " + Objects.requireNonNull(response.body()).string());
                }
                if (final_has_command && sharedPreferences.getBoolean("root", false)) {
                    switch (final_command) {
                        case "/switch_data":
                        case "/switchdata":
                        case "/close_ap":
                            if (!public_func.get_data_enable(context)) {
                                uk.reall.root_kit.network.data_set_enable(true);
                            } else {
                                if (final_command.equals("/close_ap")) {
                                    WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                                    assert wifiManager != null;
                                    if (wifiManager.isWifiEnabled()) {
                                        uk.reall.root_kit.network.wifi_set_enable(false);
                                    }
                                }
                                uk.reall.root_kit.network.data_set_enable(false);
                            }
                            break;
                        case "/restart_network":
                            public_func.restart_network();
                            break;
                    }
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        wifiLock.release();
        wakelock.release();
        unregisterReceiver(broadcast_receiver);
        stopForeground(true);
        super.onDestroy();
    }

    private void get_me() {
        OkHttpClient okhttp_client_new = okhttp_client;
        String request_uri = public_func.get_url(bot_token, "getMe");
        Request request = new Request.Builder().url(request_uri).build();
        Call call = okhttp_client_new.newCall(request);
        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            e.printStackTrace();
            public_func.write_log(context, "Get username failed:" + e.getMessage());
            return;
        }
        if (response.code() == 200) {
            String result = null;
            try {
                result = Objects.requireNonNull(response.body()).string();
            } catch (IOException e) {
                e.printStackTrace();
            }
            assert result != null;
            JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
            if (result_obj.get("ok").getAsBoolean()) {
                bot_username = result_obj.get("result").getAsJsonObject().get("username").getAsString();
                sharedPreferences.edit().putString("bot_username", bot_username).apply();
                Log.d(TAG, "bot_username: " + bot_username);
            }
        }

    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    boolean is_vpn_hotsport_exist() {
        ApplicationInfo info;
        try {
            info = getPackageManager().getApplicationInfo(public_func.VPN_HOTSPOT_PACKAGE_NAME, 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            info = null;
        }

        return info != null;
    }


    private String get_battery_info(Context context) {
        BatteryManager batteryManager = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
        assert batteryManager != null;
        int battery_level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (battery_level > 100) {
            Log.d(TAG, "The previous battery is over 100%, and the correction is 100%.");
            battery_level = 100;
        }
        IntentFilter intentfilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, intentfilter);
        assert batteryStatus != null;
        int charge_status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        StringBuilder battery_string_builder = new StringBuilder().append(battery_level).append("%");
        switch (charge_status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
            case BatteryManager.BATTERY_STATUS_FULL:
                battery_string_builder.append(" (").append(context.getString(R.string.charging)).append(")");
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                switch (batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    case BatteryManager.BATTERY_PLUGGED_AC:
                    case BatteryManager.BATTERY_PLUGGED_USB:
                    case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                        battery_string_builder.append(" (").append(context.getString(R.string.not_charging)).append(")");
                        break;
                }
                break;
        }
        return battery_string_builder.toString();
    }

    class thread_main_runnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "run: thread main start");
            if (public_func.parse_int(chat_id) < 0) {
                bot_username = sharedPreferences.getString("bot_username", null);
                Log.d(TAG, "Load bot username from storage: " + bot_username);
                if (bot_username == null) {
                    new Thread(chat_command_service.this::get_me).start();
                }
            }
            while (true) {
                int timeout = 5 * magnification;
                int http_timeout = timeout + 5;
                OkHttpClient okhttp_client_new = okhttp_client.newBuilder()
                        .readTimeout(http_timeout, TimeUnit.SECONDS)
                        .writeTimeout(http_timeout, TimeUnit.SECONDS)
                        .build();
                Log.d(TAG, "run: Current timeout:" + timeout);
                String request_uri = public_func.get_url(bot_token, "getUpdates");
                polling_json request_body = new polling_json();
                request_body.offset = offset;
                request_body.timeout = timeout;
                RequestBody body = RequestBody.create(new Gson().toJson(request_body), public_func.JSON);
                Request request = new Request.Builder().url(request_uri).method("POST", body).build();
                Call call = okhttp_client_new.newCall(request);
                Response response;
                try {
                    response = call.execute();
                    error_magnification = 1;
                } catch (IOException e) {
                    e.printStackTrace();
                    if (!public_func.check_network_status(context)) {
                        public_func.write_log(context, "No network connections available. ");
                        error_magnification = 1;
                        magnification = 1;
                        break;
                    }
                    int sleep_time = 5 * error_magnification;
                    public_func.write_log(context, "Connection to the Telegram API service failed,try again after " + sleep_time + " seconds.");
                    magnification = 1;
                    if (error_magnification <= 59) {
                        ++error_magnification;
                    }
                    try {
                        Thread.sleep(sleep_time * 1000);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                    continue;

                }
                if (response.code() == 200) {
                    assert response.body() != null;
                    String result;
                    try {
                        result = Objects.requireNonNull(response.body()).string();
                    } catch (IOException e) {
                        e.printStackTrace();
                        continue;
                    }
                    JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
                    if (result_obj.get("ok").getAsBoolean()) {
                        JsonArray result_array = result_obj.get("result").getAsJsonArray();
                        for (JsonElement item : result_array) {
                            receive_handle(item.getAsJsonObject());
                        }
                    }
                    if (magnification <= 11) {
                        ++magnification;
                    }
                } else {
                    public_func.write_log(context, "response code:" + response.code());
                    if (response.code() == 409) {
                        message_json error_request_body = new message_json();
                        error_request_body.chat_id = chat_id;
                        error_request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.error_message_head) + getString(R.string.conflict_error);
                        RequestBody error_request = RequestBody.create(new Gson().toJson(error_request_body), public_func.JSON);
                        Request send_request = new Request.Builder().url(public_func.get_url(bot_token, "sendMessage")).method("POST", error_request).build();
                        Call error_call = okhttp_client.newCall(send_request);
                        try {
                            error_call.execute();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    private class broadcast_receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive: " + intent.getAction());
            assert intent.getAction() != null;
            switch (intent.getAction()) {
                case public_func.broadcast_stop_service:
                    Log.i(TAG, "Received stop signal, quitting now...");
                    stopSelf();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    break;
                case ConnectivityManager.CONNECTIVITY_ACTION:
                    if (public_func.check_network_status(context)) {
                        if (!thread_main.isAlive()) {
                            public_func.write_log(context, "Network connections has been restored.");
                            thread_main = new Thread(new thread_main_runnable());
                            thread_main.start();
                        }
                    }
                    break;

            }
        }
    }
}

