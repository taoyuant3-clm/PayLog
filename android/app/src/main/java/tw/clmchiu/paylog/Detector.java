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
        String dedup = dedupKey(p, source + "|?");
        Db db = Db.get(c);
        Record dup = db.findRecent(dedup, now - DEDUP_WINDOW_MS);
        if (dup != null) {
            enrich(c, db, dup, p);
            return;
        }

        Record r = newRecord(c, now, p, source, sourceLabel, dedup);
        db.insert(r);
        popup(c, r);
    }

    /** 網購驗證碼簡訊。 */
    static void onOtp(Context c, PaymentParser.Result p, String sender) {
        long now = System.currentTimeMillis();
        String dedup = dedupKey(p, "otp|" + sender);
        Db db = Db.get(c);
        Record dup = db.findRecent(dedup, now - DEDUP_WINDOW_MS);
        if (dup != null) {
            enrich(c, db, dup, p);
            return;
        }

        String label = "網購驗證簡訊" + (sender == null || sender.isEmpty() ? "" : "（" + sender + "）");
        Record r = newRecord(c, now, p, SMS_SOURCE_PREFIX + (sender == null ? "" : sender), label, dedup);
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

    /** 外幣以「幣別 + 原幣金額」比對，台幣以金額比對。 */
    private static String dedupKey(PaymentParser.Result p, String fallback) {
        if (p.foreign != null) return "amt|" + p.currency + "|" + Fmt.plainNumber(p.foreign);
        if (p.amount != null) return "amt|" + Fmt.plainNumber(p.amount);
        return fallback;
    }

    /** 台幣金額：通知有台幣就用；只有外幣時依設定的匯率換算（四捨五入到元）。 */
    static double ntAmount(Context c, PaymentParser.Result p) {
        if (p.amount != null) return p.amount;
        if (p.foreign != null && p.currency != null) return Math.round(p.foreign * Prefs.rate(c, p.currency));
        return 0;
    }

    /** 銀行「刷卡消費通知」簡訊（出國刷實體卡時常只有這個）。 */
    static void onCardSpendSms(Context c, PaymentParser.Result p, String sender) {
        String label = "刷卡簡訊" + (sender == null || sender.isEmpty() ? "" : "（" + sender + "）");
        onPayment(c, p, SMS_SOURCE_PREFIX + (sender == null ? "" : sender), label);
    }

    private static Record newRecord(Context c, long now, PaymentParser.Result p, String source, String label, String dedup) {
        Record r = new Record();
        r.timeMillis = now;
        r.amount = ntAmount(c, p);
        if (p.foreign != null) {
            r.currency = p.currency;
            r.origAmount = p.foreign;
        }
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
        if (p.amount != null && (dup.amount <= 0 || !dup.currency.isEmpty())) {
            // 銀行通知附上的折合台幣比自己換算準
            if (dup.amount != p.amount) {
                dup.amount = p.amount;
                changed = true;
            }
        } else if (dup.amount <= 0 && p.foreign != null) {
            dup.amount = ntAmount(c, p);
            changed = true;
        }
        if (dup.currency.isEmpty() && p.foreign != null) {
            dup.currency = p.currency;
            dup.origAmount = p.foreign;
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
