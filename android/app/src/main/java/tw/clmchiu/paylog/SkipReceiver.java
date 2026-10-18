package tw.clmchiu.paylog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知上的「略過」/「不再偵測此App」按鈕。 */
public class SkipReceiver extends BroadcastReceiver {

    static final String ACTION_SKIP = "tw.clmchiu.paylog.SKIP";
    static final String ACTION_BLOCK = "tw.clmchiu.paylog.BLOCK";
    static final String EXTRA_PKG = "pkg";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(RecordActivity.EXTRA_ID, 0);
        if (ACTION_BLOCK.equals(intent.getAction())) {
            String pkg = intent.getStringExtra(EXTRA_PKG);
            if (pkg != null && !pkg.isEmpty()) Prefs.block(context, pkg);
        }
        if (id > 0) {
            Db db = Db.get(context);
            Record r = db.find(id);
            if (r != null && r.pending) db.delete(id);
            Notifier.cancel(context, id);
        }
    }
}
