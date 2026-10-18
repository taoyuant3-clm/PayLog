package tw.clmchiu.paylog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 驗證簡訊延遲時間到 → 彈出填寫畫面。 */
public class PopupReceiver extends BroadcastReceiver {

    static final String ACTION_POPUP = "tw.clmchiu.paylog.POPUP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_POPUP.equals(intent.getAction())) return;
        long id = intent.getLongExtra(RecordActivity.EXTRA_ID, 0);
        if (id <= 0) return;
        Record r = Db.get(context).find(id);
        // 已經填寫或略過就不再打擾
        if (r == null || !r.pending) return;
        Detector.popup(context, r);
    }
}
