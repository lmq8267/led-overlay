package com.lmq8267.ledclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.UUID;

final class Prefs {
    static final String ACTION_START = "com.lmq8267.ledclient.START";
    static final String ACTION_RESTORE = "com.lmq8267.ledclient.RESTORE";
    static final String ACTION_CLEAN_IDLE_OVERLAY = "com.lmq8267.ledclient.CLEAN_IDLE_OVERLAY";
    static final String KEY_SERVER = "server";
    static final String KEY_SERVER_HISTORY = "server_history";
    static final String KEY_BOOT = "boot";
    static final String KEY_CLIENT_ID = "client_id";
    /** 用户是否希望客户端运行。必须与“服务此刻是否活着”分开，否则系统强停后 UI 会误判。 */
    static final String KEY_ENABLED = "enabled";
    /** 服务最后一次心跳，存 elapsedRealtime，用于判断进程是否真的还活着。 */
    static final String KEY_HEARTBEAT = "heartbeat";
    /** elapsedRealtime 重启归零；同时记录 BOOT_COUNT，避免巧合的数值被当成“新鲜心跳”。 */
    static final String KEY_BOOT_COUNT = "boot_count";
    /** 黑屏时登记过恢复请求；进程若在亮屏前被杀，下次锚点重绑还能补上。 */
    static final String KEY_PENDING_RESTORE = "pending_restore";
    static final String KEY_RUNNING = "running";
    static final String KEY_CONNECTION_STATE = "connection_state";
    /** 心跳超过这个时间没更新，就认为服务已被系统杀掉，需要由锚点拉起。 */
    static final long HEARTBEAT_FRESH_MS = 15_000L;
    static final String CONNECTION_CONNECTING = "connecting";
    static final String CONNECTION_CONNECTED = "connected";
    static final String CONNECTION_DISCONNECTED = "disconnected";
    static final String KEY_DONE_TASKS = "done_tasks";
    static final String KEY_PENDING_ACKS = "pending_acks";
    static final String KEY_PENDING_COMPLETED_ACKS = "pending_completed_acks";
    /** 任务记忆所属的开机批次；断电重启后与当前 BOOT_COUNT 不一致就整体作废。 */
    static final String KEY_TASK_BOOT_COUNT = "task_boot_count";
    /** 拿不到 BOOT_COUNT 的老固件用开机计时兜底：重启后 elapsedRealtime 会变小。 */
    static final String KEY_TASK_BOOT_ELAPSED = "task_boot_elapsed";

    private Prefs() {}

    /** BOOT_COUNT 在进程生命周期内不会变，缓存起来，避免首页 ticker 每秒做几次 ContentProvider 查询。 */
    private static int cachedBootCount = Integer.MIN_VALUE;

    private static int bootCount(Context context) {
        if (cachedBootCount != Integer.MIN_VALUE) return cachedBootCount;
        int value = -1;
        if (Build.VERSION.SDK_INT >= 24) {
            value = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        }
        cachedBootCount = value;
        return value;
    }

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

    static synchronized void rememberServer(Context context, String server) {
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

    /**
     * 用户是否希望客户端运行。
     * 旧版本只有 running 一个字段，升级后第一次读取时用它做迁移，避免已经开着的设备被误判成“用户没启动”。
     */
    static boolean enabled(Context context) {
        SharedPreferences prefs = store(context);
        if (prefs.contains(KEY_ENABLED)) return prefs.getBoolean(KEY_ENABLED, false);
        boolean migrated = prefs.getBoolean(KEY_RUNNING, false);
        // 只读不写会出问题：服务 onDestroy 会把 running 置 false，之后 enabled 又变回 false。
        // 第一次读到旧数据时就固化下来，避免升级后设备“自己变成已停止”。
        prefs.edit().putBoolean(KEY_ENABLED, migrated).commit();
        return migrated;
    }

    static void setEnabled(Context context, boolean value) {
        // 用户意图必须同步落盘：开机广播/强停恢复的进程可能紧接着就被系统回收，
        // apply() 的异步写有概率还没落盘，导致重启后又按旧状态判断。
        store(context).edit().putBoolean(KEY_ENABLED, value).commit();
    }

    static boolean running(Context context) {
        return store(context).getBoolean(KEY_RUNNING, false);
    }

    static boolean pendingRestore(Context context) {
        return store(context).getBoolean(KEY_PENDING_RESTORE, false);
    }

    static void setPendingRestore(Context context, boolean value) {
        store(context).edit().putBoolean(KEY_PENDING_RESTORE, value).commit();
    }

    static void setRunning(Context context, boolean value) {
        store(context).edit().putBoolean(KEY_RUNNING, value).apply();
    }

    /** 服务心跳用 elapsedRealtime：改系统时间不会制造出“假存活”。 */
    static void touchHeartbeat(Context context) {
        int boot = bootCount(context);
        store(context).edit()
                .putLong(KEY_HEARTBEAT, android.os.SystemClock.elapsedRealtime())
                .putInt(KEY_BOOT_COUNT, boot)
                .apply();
    }

    static boolean isHeartbeatFresh(Context context) {
        SharedPreferences prefs = store(context);
        long heartbeat = prefs.getLong(KEY_HEARTBEAT, 0L);
        if (heartbeat <= 0L) return false;
        int storedBoot = prefs.getInt(KEY_BOOT_COUNT, -1);
        int currentBoot = bootCount(context);
        // 只有两次都拿到有效 BOOT_COUNT 时才强校验；旧版本数据没有该字段。
        if (storedBoot >= 0 && currentBoot >= 0 && storedBoot != currentBoot) return false;
        long age = android.os.SystemClock.elapsedRealtime() - heartbeat;
        return age >= 0L && age < HEARTBEAT_FRESH_MS;
    }

    /**
     * 本次开机是否已经运行过服务。
     *
     * <p>用途：JobScheduler / 同步框架在重启后也会被系统调起，如果直接恢复，就会绕过用户的
     * “开机自启”开关。心跳里记录了写入时的 BOOT_COUNT，与当前值比对即可区分
     * “重启后第一次被拉起”和“本次开机内服务被杀后的恢复”，后者不应该受开机开关限制。
     */
    static boolean ranThisBoot(Context context) {
        int stored = store(context).getInt(KEY_BOOT_COUNT, -1);
        int current = bootCount(context);
        // API 24 以下没有 Settings.Global.BOOT_COUNT，无法区分“重启后第一次”和“本次开机内被杀”。
        // 这种情况下宁可恢复（返回 true，不受开机开关约束），也不要让老设备的保活通道被误关。
        if (stored < 0 || current < 0) return true;
        return stored == current;
    }

    /** 只清运行态，不动 enabled：服务被系统杀掉时用户意图仍然应该保留，交给通知监听锚点拉起。 */
    static void clearRuntimeState(Context context) {
        store(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_CONNECTION_STATE, CONNECTION_DISCONNECTED)
                .apply();
    }

    static String connectionState(Context context) {
        return store(context).getString(KEY_CONNECTION_STATE, CONNECTION_DISCONNECTED);
    }

    static void setConnectionState(Context context, String state) {
        store(context).edit().putString(KEY_CONNECTION_STATE, state).apply();
    }

    static synchronized String clientId(Context context) {
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

    static synchronized void markTaskDone(Context context, String taskId) {
        appendTaskId(context, KEY_DONE_TASKS, taskId);
    }

    private static synchronized void appendTaskId(Context context, String key, String taskId) {
        if (taskId == null || taskId.length() == 0) return;
        String scopedTaskId = taskMemoryKey(context, taskId);
        if (scopedTaskId.length() == 0) return;
        SharedPreferences prefs = store(context);
        String value = prefs.getString(key, "");
        if (value.contains("|" + scopedTaskId + "|")) return;
        value = value + "|" + scopedTaskId + "|";
        value = tailTokens(value, 3000);
        prefs.edit().putString(key, value).apply();
    }

    /**
     * 按 '|' 边界保留尾部。直接 substring(len-3000) 会从 token 中间切开，
     * 被切坏的旧记录永远匹配不上，重复任务会被当成新任务再次显示/ACK。
     */
    private static String tailTokens(String value, int keep) {
        if (value.length() <= keep) return value;
        int cut = value.indexOf('|', value.length() - keep);
        return cut >= 0 ? value.substring(cut) : "";
    }

    private static String taskMemoryKey(Context context, String taskId) {
        if (taskId == null || taskId.length() == 0) return "";
        String server = WsClient.normalize(server(context));
        return server + "#" + taskId;
    }

    /**
     * 断电 / 关机重启后作废上一轮运行留下的任务记忆。
     *
     * <p>为什么必须清：done_tasks 在“显示完成”时就落盘，但 completed ACK 可能因为断电、
     * 断网没有送到服务端（2026-11-20 实机断电复现：服务端下发 t8 后立刻变成已完成，
     * 电视却没有显示）。而服务端如果在断电时丢了状态、任务编号从头复用，旧任务记忆会和
     * 新任务撞上同一个 taskId；不跨开机作废的话，这一轮的状态很容易被上一轮“污染”。
     *
     * <p>pending_completed_acks 也一起清：completed 现在已经不做事先/定时补发，
     * 但旧版本或异常退出可能留下标记，跨开机清掉能保证本次开机只按真实显示结果上报。
     *
     * <p>同一次开机内被系统杀进程不会触发清理（BOOT_COUNT 不变），避免服务自愈重启后
     * 把已经显示完的任务再放一遍。
     *
     * @return 是否真的按“新开机”清理过
     */
    static synchronized boolean resetTaskMemoryIfNewBoot(Context context) {
        SharedPreferences prefs = store(context);
        int currentBoot = bootCount(context);
        int storedBoot = prefs.getInt(KEY_TASK_BOOT_COUNT, Integer.MIN_VALUE);
        long currentElapsed = android.os.SystemClock.elapsedRealtime();
        long storedElapsed = prefs.getLong(KEY_TASK_BOOT_ELAPSED, -1L);
        boolean newBoot;
        if (storedBoot == Integer.MIN_VALUE && storedElapsed < 0L) {
            // 旧版本没有记录：升级后第一次启动按新开机处理，顺手清掉可能已经对不上的旧数据。
            newBoot = true;
        } else if (storedBoot >= 0 && currentBoot >= 0) {
            newBoot = storedBoot != currentBoot;
        } else {
            // 没有 BOOT_COUNT 的固件只能看开机计时：重启后 elapsedRealtime 归零变小。
            newBoot = storedElapsed < 0L || currentElapsed < storedElapsed;
        }
        SharedPreferences.Editor marker = prefs.edit()
                .putInt(KEY_TASK_BOOT_COUNT, currentBoot)
                .putLong(KEY_TASK_BOOT_ELAPSED, currentElapsed);
        if (!newBoot) {
            marker.apply();
            return false;
        }
        // 断电后进程可能马上又被回收，这里必须同步落盘，不能留异步写窗口。
        marker.remove(KEY_DONE_TASKS)
                .remove(KEY_PENDING_ACKS)
                .remove(KEY_PENDING_COMPLETED_ACKS)
                .commit();
        return true;
    }

    static String pendingAcks(Context context) {
        return store(context).getString(KEY_PENDING_ACKS, "");
    }

    static synchronized void addPendingAck(Context context, String taskId, String status) {
        if (taskId == null || taskId.length() == 0 || status == null || status.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_ACKS, "");
        String item = "|" + status + ":" + taskId + "|";
        if (pending.contains(item)) return;
        pending = pending + item;
        pending = tailTokens(pending, 3000);
        prefs.edit().putString(KEY_PENDING_ACKS, pending).apply();
    }

    static synchronized void removePendingAck(Context context, String taskId, String status) {
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

    static synchronized void addPendingCompletedAck(Context context, String taskId) {
        if (taskId == null || taskId.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_COMPLETED_ACKS, "");
        if (pending.contains("|" + taskId + "|")) return;
        pending = pending + "|" + taskId + "|";
        pending = tailTokens(pending, 3000);
        prefs.edit().putString(KEY_PENDING_COMPLETED_ACKS, pending).apply();
    }

    static synchronized void removePendingCompletedAck(Context context, String taskId) {
        removePendingAck(context, taskId, "completed");
        if (taskId == null || taskId.length() == 0) return;
        SharedPreferences prefs = store(context);
        String pending = prefs.getString(KEY_PENDING_COMPLETED_ACKS, "");
        String next = pending.replace("|" + taskId + "|", "|");
        if ("|".equals(next)) next = "";
        prefs.edit().putString(KEY_PENDING_COMPLETED_ACKS, next).apply();
    }

    static synchronized void clearPendingAcks(Context context) {
        store(context).edit()
                .remove(KEY_PENDING_ACKS)
                .remove(KEY_PENDING_COMPLETED_ACKS)
                .apply();
    }
}
