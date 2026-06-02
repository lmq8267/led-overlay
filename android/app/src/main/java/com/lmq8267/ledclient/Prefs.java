package com.lmq8267.ledclient;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.UUID;

final class Prefs {
    static final String ACTION_START = "com.lmq8267.ledclient.START";
    static final String ACTION_STOP = "com.lmq8267.ledclient.STOP";
    static final String KEY_SERVER = "server";
    static final String KEY_SERVER_HISTORY = "server_history";
    static final String KEY_BOOT = "boot";
    static final String KEY_CLIENT_ID = "client_id";
    static final String KEY_RUNNING = "running";
    static final String KEY_CONNECTION_STATE = "connection_state";
    static final String CONNECTION_CONNECTING = "connecting";
    static final String CONNECTION_CONNECTED = "connected";
    static final String CONNECTION_DISCONNECTED = "disconnected";
    static final String KEY_DONE_TASKS = "done_tasks";
    static final String KEY_SEEN_TASKS = "seen_tasks";
    static final String KEY_PENDING_ACKS = "pending_acks";
    static final String KEY_PENDING_COMPLETED_ACKS = "pending_completed_acks";

    private Prefs() {}

    static SharedPreferences store(Context context) {
        return context.getSharedPreferences("led", Context.MODE_PRIVATE);
    }

    static String server(Context context) {
        return store(context).getString(KEY_SERVER, "");
    }

    static ArrayList<String> serverHistory(Context context) {
        ArrayList<String> out = new ArrayList<String>();
        String raw = store(context).getString(KEY_SERVER_HISTORY, "");
        if (raw.length() == 0) return out;
        String[] items = raw.split("\\|");
        for (int i = 0; i < items.length && out.size() < 3; i++) {
            if (items[i].length() > 0 && !out.contains(items[i])) out.add(items[i]);
        }
        return out;
    }

    static void rememberServer(Context context, String server) {
        if (server == null || server.trim().length() == 0) return;
        ArrayList<String> history = serverHistory(context);
        history.remove(server);
        history.add(0, server);
        while (history.size() > 3) history.remove(history.size() - 1);
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) raw.append('|');
            raw.append(history.get(i));
        }
        store(context).edit().putString(KEY_SERVER_HISTORY, raw.toString()).apply();
    }

    static boolean boot(Context context) {
        return store(context).getBoolean(KEY_BOOT, false);
    }

    static boolean running(Context context) {
        return store(context).getBoolean(KEY_RUNNING, false);
    }

    static String connectionState(Context context) {
        return store(context).getString(KEY_CONNECTION_STATE, CONNECTION_DISCONNECTED);
    }

    static void setConnectionState(Context context, String state) {
        store(context).edit().putString(KEY_CONNECTION_STATE, state).apply();
    }

    static String clientId(Context context) {
        SharedPreferences prefs = store(context);
        String id = prefs.getString(KEY_CLIENT_ID, "");
        if (id.length() == 0) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_CLIENT_ID, id).apply();
        }
        return id;
    }

    static boolean isTaskDone(Context context, String taskId) {
        String done = store(context).getString(KEY_DONE_TASKS, "");
        String key = taskMemoryKey(context, taskId);
        return key.length() > 0 && done.contains("|" + key + "|");
    }

    static boolean isTaskSeen(Context context, String taskId) {
        String seen = store(context).getString(KEY_SEEN_TASKS, "");
        String key = taskMemoryKey(context, taskId);
        return (key.length() > 0 && seen.contains("|" + key + "|")) || isTaskDone(context, taskId);
    }

    static void markTaskSeen(Context context, String taskId) {
        appendTaskId(context, KEY_SEEN_TASKS, taskId);
    }

    static void markTaskDone(Context context, String taskId) {
        markTaskSeen(context, taskId);
        appendTaskId(context, KEY_DONE_TASKS, taskId);
    }

    private static void appendTaskId(Context context, String key, String taskId) {
        if (taskId == null || taskId.length() == 0) return;
        String scopedTaskId = taskMemoryKey(context, taskId);
        if (scopedTaskId.length() == 0) return;
        SharedPreferences prefs = store(context);
        String value = prefs.getString(key, "");
        if (value.contains("|" + scopedTaskId + "|")) return;
        value = value + "|" + scopedTaskId + "|";
        if (value.length() > 4000) value = value.substring(value.length() - 3000);
        prefs.edit().putString(key, value).apply();
    }

    private static String taskMemoryKey(Context context, String taskId) {
        if (taskId == null || taskId.length() == 0) return "";
        String server = WsClient.normalize(server(context));
        return server + "#" + taskId;
    }

    static String pendingAcks(Context context) {
        return store(context).getString(KEY_PENDING_ACKS, "");
    }

    static void addPendingAck(Context context, String taskId, String status) {
        if (taskId == null || taskId.length() == 0 || status == null || status.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_ACKS, "");
        String item = "|" + status + ":" + taskId + "|";
        if (pending.contains(item)) return;
        pending = pending + item;
        if (pending.length() > 4000) pending = pending.substring(pending.length() - 3000);
        prefs.edit().putString(KEY_PENDING_ACKS, pending).apply();
    }

    static void removePendingAck(Context context, String taskId, String status) {
        if (taskId == null || taskId.length() == 0 || status == null || status.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_ACKS, "");
        String next = pending.replace("|" + status + ":" + taskId + "|", "|");
        if ("|".equals(next)) next = "";
        prefs.edit().putString(KEY_PENDING_ACKS, next).apply();
    }

    static String pendingCompletedAcks(Context context) {
        return store(context).getString(KEY_PENDING_COMPLETED_ACKS, "");
    }

    static boolean hasPendingCompletedAck(Context context, String taskId) {
        if (taskId == null || taskId.length() == 0) return false;
        return pendingCompletedAcks(context).contains("|" + taskId + "|");
    }

    static void addPendingCompletedAck(Context context, String taskId) {
        if (taskId == null || taskId.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_COMPLETED_ACKS, "");
        if (pending.contains("|" + taskId + "|")) return;
        pending = pending + "|" + taskId + "|";
        if (pending.length() > 4000) pending = pending.substring(pending.length() - 3000);
        prefs.edit().putString(KEY_PENDING_COMPLETED_ACKS, pending).apply();
    }

    static void removePendingCompletedAck(Context context, String taskId) {
        removePendingAck(context, taskId, "completed");
        if (taskId == null || taskId.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_COMPLETED_ACKS, "");
        String next = pending.replace("|" + taskId + "|", "|");
        if ("|".equals(next)) next = "";
        prefs.edit().putString(KEY_PENDING_COMPLETED_ACKS, next).apply();
    }

    static void clearPendingAcks(Context context) {
        store(context).edit()
                .remove(KEY_PENDING_ACKS)
                .remove(KEY_PENDING_COMPLETED_ACKS)
                .apply();
    }
}
