package tw.clmchiu.paylog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 偵測到付款後的共用流程：建立「待填寫」紀錄 → 自動彈出填寫畫面。
 *
 * 兩種來源：
 * 1. NFC 感應付款：錢包 / 銀行 App 的付款通知 → 立即彈出
 * 2. 網購雙重認證簡訊（OTP）→ 延遲彈出，讓你先把驗證碼輸入完成
 */
final class Detector {

    static final long DEDUP_WINDOW_MS = 10 * 60 * 1000L;
    static final String SMS_SOURCE_PREFIX = "sms:";

    private Detector() {
    }

    /** NFC / App 付款通知。 */
    static void onPayment(Context c, PaymentParser.Result p, String source, String sourceLabel) {
        long now = System.currentTimeMillis();
        // 同金額 10 分鐘內只算一筆（錢包、銀行 App、驗證簡訊常對同一筆付款各發一則）
        String dedup = p.amount != null ? "amt|" + Fmt.plainNumber(p.amount) : source + "|?";
        Db db = Db.get(c);
        Record dup = db.findRecent(dedup, now - DEDUP_WINDOW_MS);
        if (dup != null) {
            enrich(c, db, dup, p);
            return;
        }

        Record r = newRecord(now, p, source, sourceLabel, dedup);
        db.insert(r);
        popup(c, r);
    }

    /** 網購驗證碼簡訊。 */
    static void onOtp(Context c, PaymentParser.Result p, String sender) {
        long now = System.currentTimeMillis();
        String dedup = p.amount != null ? "amt|" + Fmt.plainNumber(p.amount) : "otp|" + sender;
        Db db = Db.get(c);
        Record dup = db.findRecent(dedup, now - DEDUP_WINDOW_MS);
        if (dup != null) {
            enrich(c, db, dup, p);
            return;
        }

        String label = "網購驗證簡訊" + (sender == null || sender.isEmpty() ? "" : "（" + sender + "）");
        Record r = newRecord(now, p, SMS_SOURCE_PREFIX + (sender == null ? "" : sender), label, dedup);
        r.category = "購物";
        r.note = "網購";
        db.insert(r);

        int delay = Prefs.otpDelaySeconds(c);
        if (delay <= 0) {
            popup(c, r);
        } else {
            schedulePopup(c, r.id, delay);
        }
    }

    private static Record newRecord(long now, PaymentParser.Result p, String source, String label, String dedup) {
        Record r = new Record();
        r.timeMillis = now;
        r.amount = p.amount == null ? 0 : p.amount;
        r.card = p.card == null ? "" : p.card;
        r.merchant = p.merchant == null ? "" : p.merchant;
        r.source = source;
        r.sourceLabel = label;
        r.pending = true;
        r.dedupKey = dedup;
        return r;
    }

    /** 重複的通知：只補上卡別 / 商家，不再彈出（避免把正在填寫的畫面洗掉）。 */
    private static void enrich(Context c, Db db, Record dup, PaymentParser.Result p) {
        if (!dup.pending) return;
        boolean changed = false;
        if (dup.card.isEmpty() && p.card != null) {
            dup.card = p.card;
            changed = true;
        }
        if (dup.merchant.isEmpty() && p.merchant != null) {
            dup.merchant = p.merchant;
            changed = true;
        }
        if (dup.amount <= 0 && p.amount != null) {
            dup.amount = p.amount;
            changed = true;
        }
        if (changed) db.update(dup);
    }

    /** 立即彈出填寫畫面，並留一則通知備用。 */
    static void popup(Context c, Record r) {
        boolean shown = Prefs.autoPopup(c) && Popup.show(c, r.id);
        Notifier.prompt(c, r, shown);
    }

    static void schedulePopup(Context c, long recordId, int delaySeconds) {
        Intent i = new Intent(c, PopupReceiver.class)
                .setAction(PopupReceiver.ACTION_POPUP)
                .putExtra(RecordActivity.EXTRA_ID, recordId);
        PendingIntent pi = PendingIntent.getBroadcast(c, Notifier.notificationId(recordId), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = SystemClock.elapsedRealtime() + delaySeconds * 1000L;
        if (am != null) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }
}
