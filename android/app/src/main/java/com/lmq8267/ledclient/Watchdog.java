package com.lmq8267.ledclient;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 第三、四条拉起通道：JobScheduler 与 AlarmManager。
 *
 * <p>为什么需要它们：通知监听只在系统愿意重绑时才会把进程拉回来。电视静置、
 * 后台被系统按低内存回收、或固件整体掐掉 JobScheduler 时，没有这两条兜底，
 * 进程可能一直不回来。小米待机/关机时的 {@code killAllThirdApp} 会把包强停，
 * 这两条也会被连带取消（已在本项目 README 记录），但它们覆盖的是"只杀进程"那一档，
 * 属于与通知监听互补的通道，不是替代。
 *
 * <p>为什么统一走 {@link KeepAlive#restoreIfEnabled}：前台服务会把待机中的电视点亮
 * （quizgate 在 MiTV-MFTR0 实测），所以这里只发"恢复意图"，由 KeepAlive 判断屏幕状态。
 */
@android.annotation.TargetApi(21)
public class Watchdog extends JobService {
    private static final String TAG = "LedOverlay";
    private static final int JOB_ID = 7401;
    private static final int ALARM_REQUEST = 7402;
    static final String ACTION_REVIVE = "com.lmq8267.ledclient.REVIVE";
    /** 系统对 setPeriodic 的最小间隔就是 15 分钟，写小于它的值也会被钳到 15 分钟。 */
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    @Override
    public boolean onStartJob(JobParameters params) {
        // 重启后作业会被系统重新执行；用户关掉了“开机自启”就不该被它悄悄拉起来。
        // 本次开机内服务被杀（心跳里已是当前 BOOT_COUNT）属于“恢复”，不受开机开关限制。
        if (!Prefs.ranThisBoot(this) && !Prefs.boot(this)) {
            Log.i(TAG, "跳过作业恢复：开机自启已关闭且本次开机未运行过");
            return false;
        }
        KeepAlive.restoreIfEnabled(this, "job");
        return false;   // 启动请求已经发出，没有后续工作要等
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 被系统提前取消时要求重新排程，避免省电策略把这条通道悄悄关掉
        return true;
    }

    static void schedule(Context context) {
        scheduleJob(context);
        scheduleAlarm(context);
    }

    private static void scheduleJob(Context context) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            // 已排过就不再重复排：每次 onCreate 都调这里，重复 schedule 会让周期被重置
            for (JobInfo info : scheduler.getAllPendingJobs()) {
                if (info.getId() == JOB_ID) return;
            }
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, Watchdog.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)     // 跨重启保留；需要 RECEIVE_BOOT_COMPLETED
                    .build());
            Log.i(TAG, "JobScheduler 复活作业已排程");
        } catch (Throwable t) {
            Log.w(TAG, "JobScheduler 排程失败（部分固件会整体限制作业）", t);
        }
    }

    private static void scheduleAlarm(Context context) {
        try {
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) return;
            // 已排过就不再重设。setInexactRepeating 每次调用都会把首次触发时间推后 5 分钟，
            // 而 onStartCommand 每条恢复路径都会走到这里；不判断的话闹钟会被无限推迟，
            // 永远等不到触发（甚至变成"恢复一次就重置一次"的唤醒放大）。
            if (existingAlarm(context) != null) return;
            // 首次 5 分钟后再喊，避免服务刚起来就重复拉起自己；之后每 15 分钟一次。
            // setInexactRepeating 不需要精确闹钟权限，Doze 下会被合并延迟，这是可接受的代价。
            manager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5 * 60 * 1000L,
                    PERIOD_MS,
                    reviveIntent(context));
            Log.i(TAG, "AlarmManager 复活闹钟已排程");
        } catch (Throwable t) {
            Log.w(TAG, "复活闹钟排程失败", t);
        }
    }

    /** 用户点"停止"时调用：不能停完服务还留两条每 15 分钟把自己叫醒的定时器。 */
    static void cancel(Context context) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (scheduler != null) scheduler.cancel(JOB_ID);
            } catch (Throwable ignored) { }
        }
        try {
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager != null) {
                // AlarmManager.cancel 只移除闹钟，不销毁 PendingIntent；不一起 cancel 的话，
                // existingAlarm() 仍会认为"已排程"，用户重新启动后闹钟就再也排不上了。
                PendingIntent pending = existingAlarm(context);
                if (pending != null) {
                    manager.cancel(pending);
                    pending.cancel();
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 只查询，不创建：用于判断重复排程。不存在时返回 null。 */
    private static PendingIntent existingAlarm(Context context) {
        Intent intent = new Intent(context, BootReceiver.class).setAction(ACTION_REVIVE);
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        try {
            return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent, flags);
        } catch (Throwable t) {
            return null;
        }
    }

    private static PendingIntent reviveIntent(Context context) {
        Intent intent = new Intent(context, BootReceiver.class).setAction(ACTION_REVIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent, flags);
    }
}
