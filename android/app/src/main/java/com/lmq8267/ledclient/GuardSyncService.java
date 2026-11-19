package com.lmq8267.ledclient;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;

/**
 * 同步适配器宿主：系统每次调度到 settings authority 的同步都会 bind 到这里。
 *
 * <p>本类不做任何数据交换，存在的唯一意义是"被系统拉起来"这一次 bind 本身。
 * onPerformSync 在 binder 线程执行，必须立即返回，不能做网络或耗时检查。
 */
public class GuardSyncService extends Service {
    private AbstractThreadedSyncAdapter adapter;

    @Override
    public void onCreate() {
        super.onCreate();
        adapter = new Adapter(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter == null ? null : adapter.getSyncAdapterBinder();
    }

    private static final class Adapter extends AbstractThreadedSyncAdapter {
        Adapter(Context context) {
            super(context, false);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                                  ContentProviderClient provider, SyncResult syncResult) {
            Context context = getContext();
            // 用户点“停止”之后，停止前已排队的同步仍可能被调度进来。这里如果照常 ensure()，
            // 会把 setSyncAutomatically/addPeriodicSync 重新打开，用户按下的停止就失效了。
            if (!Prefs.enabled(context)) {
                GuardSync.pause(context);
                return;
            }
            // 被调度到就说明系统还记得我们：顺手确认账户配置仍然有效，再请求恢复服务
            GuardSync.ensure(context);
            GuardSync.restore(context);
        }
    }
}
