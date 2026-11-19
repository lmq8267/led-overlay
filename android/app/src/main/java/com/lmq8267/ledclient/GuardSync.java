package com.lmq8267.ledclient;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.util.List;

/**
 * 第五条拉起通道：同步框架自愈（结构参考 StudyOnTV 的 GuardSync，MIT）。
 *
 * <p>为什么这条能穿过系统强停：force-stop 会杀掉进程、取消闹钟与作业、解绑通知监听，
 * 并给包打上 {@code FLAG_STOPPED}，此后隐式广播和 START_STICKY 全部失效。但同步调度由
 * system_server 的 SyncManager 持有，adapter 的注册信息在系统侧；到点时系统会显式 bind
 * 我们的 {@link GuardSyncService} 执行 onPerformSync。Android 对"系统主动拉起进程"会顺带
 * 清掉 stopped 标记，之后开机广播才重新进入派发名单。
 *
 * <p>与本项目原先的取舍不同：StudyOnTV 要求一次 {@code pm grant WRITE_SECURE_SETTINGS}，
 * 是因为它要用同一通道把被固件摘掉的无障碍服务写回去。本项目已移除无障碍
 * （见 README：MiTV 强停会清空无障碍授权且不再重绑），所以这里<b>不需要任何 adb 授权</b>，
 * 只用到 AUTHENTICATE_ACCOUNTS / READ_SYNC_SETTINGS / WRITE_SYNC_SETTINGS 三个安装时权限。
 *
 * <p>两个触发源：
 * <ul>
 *   <li>事件同步（LOCAL）：系统 SettingsProvider 每次写入都带 {@code SYNC_EXTRAS_UPLOAD}，
 *       开机、唤醒、调音量、切信号源都会写，等于"电视一有动作就顺手把我们拉起"；</li>
 *   <li>周期同步（PERIODIC）：请求 900 秒，静置无写入时的兜底。</li>
 * </ul>
 * 注意 {@code guard_sync.xml} 的 {@code supportsUploading} 必须为 true，否则 SyncManager
 * 会直接丢弃 upload 同步，事件通道完全失效（表现为 dumpsys content 里只有 PERIODIC）。
 */
final class GuardSync {
    private static final String TAG = "LedOverlay";
    /** 必须与 res/xml/guard_account.xml、res/xml/guard_sync.xml 里的 accountType 一致。 */
    private static final String ACCOUNT_TYPE = "com.lmq8267.ledclient";
    /** 挂在系统 settings provider 上才能蹭到系统自己的设置写入事件。 */
    private static final String AUTHORITY = "settings";
    /** 系统会把周期同步钳到最小 900 秒。 */
    private static final long PERIODIC_SECONDS = 900L;

    private GuardSync() { }

    /** 注册（或确认已注册）自愈账户与周期同步。幂等，可重复调用。 */
    static void ensure(Context context) {
        try {
            AccountManager manager = AccountManager.get(context);
            Account account = firstAccount(manager);
            boolean created = false;
            if (account == null) {
                Account candidate = new Account("LedOverlay", ACCOUNT_TYPE);
                if (manager.addAccountExplicitly(candidate, null, null)) {
                    account = candidate;
                    created = true;
                    Log.i(TAG, "同步自愈账户已注册");
                } else {
                    // 同名账户已存在时 add 会返回 false。个别固件的账户服务第一次查询读不到已有账户，
                    // 这里再查一次，避免“账户在、但同步配置永远没写”的半死状态。
                    account = firstAccount(manager);
                    if (account == null) {
                        // 固件裁掉账户服务时会走到这里；静默降级，不影响其他通道
                        Log.w(TAG, "同步自愈账户被系统拒绝注册");
                        return;
                    }
                }
            }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, AUTHORITY, 1);
            }
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) {
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true);
            }
            if (ContentResolver.getPeriodicSyncs(account, AUTHORITY).isEmpty()) {
                ContentResolver.addPeriodicSync(account, AUTHORITY, Bundle.EMPTY, PERIODIC_SECONDS);
            }
            if (created) {
                // 首次注册立刻走一轮：现场不用等 15 分钟周期，logcat 与 dumpsys content
                // 马上能看到一次 LOCAL 同步，也顺便确认这条通道真的被系统接受。
                ContentResolver.requestSync(account, AUTHORITY, Bundle.EMPTY);
            }
        } catch (Throwable t) {
            Log.w(TAG, "同步自愈注册失败", t);
        }
    }

    /** 被系统 bind 进来之后的恢复动作：只把"想运行"翻译成 KeepAlive，不直接起前台服务。 */
    static void restore(Context context) {
        try {
            // 同 Watchdog：重启后同步会被调度，不能绕过用户关闭的“开机自启”开关
            if (!Prefs.ranThisBoot(context) && !Prefs.boot(context)) {
                Log.i(TAG, "跳过同步恢复：开机自启已关闭且本次开机未运行过");
                return;
            }
            KeepAlive.restoreIfEnabled(context, "guard-sync");
        } catch (Throwable t) {
            Log.w(TAG, "同步自愈恢复失败", t);
        }
    }

    /**
     * 用户点"停止"时关掉自动/周期同步。
     *
     * <p>为什么不是只靠 KeepAlive 里的 enabled 判断：enabled=false 只能让恢复动作空转，
     * 进程仍会被周期作业 bind 起来，等于用户已经关了还在后台每 15 分钟醒一次。
     */
    static void pause(Context context) {
        try {
            AccountManager manager = AccountManager.get(context);
            Account account = firstAccount(manager);
            if (account == null) return;
            ContentResolver.setSyncAutomatically(account, AUTHORITY, false);
            ContentResolver.removePeriodicSync(account, AUTHORITY, Bundle.EMPTY);
            Log.i(TAG, "同步自愈已暂停");
        } catch (Throwable t) {
            Log.w(TAG, "暂停同步自愈失败", t);
        }
    }

    private static Account firstAccount(AccountManager manager) {
        Account[] accounts = manager.getAccountsByType(ACCOUNT_TYPE);
        return accounts != null && accounts.length > 0 ? accounts[0] : null;
    }

    /** 诊断用：账户是否注册、同步是否可达、周期同步是否存在。 */
    static String status(Context context) {
        try {
            // 系统“自动同步”总开关关掉时，账户配置再正确也不会被调度，必须一起报出来
            boolean master = ContentResolver.getMasterSyncAutomatically();
            Account account = firstAccount(AccountManager.get(context));
            if (account == null) return "account=false master=" + master;
            List<?> periodic = ContentResolver.getPeriodicSyncs(account, AUTHORITY);
            return "account=true master=" + master
                    + " syncable=" + (ContentResolver.getIsSyncable(account, AUTHORITY) > 0)
                    + " auto=" + ContentResolver.getSyncAutomatically(account, AUTHORITY)
                    + " periodic=" + !periodic.isEmpty();
        } catch (Throwable t) {
            return "account=error:" + t.getClass().getSimpleName();
        }
    }
}
