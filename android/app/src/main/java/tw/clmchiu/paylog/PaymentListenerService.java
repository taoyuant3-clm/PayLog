package tw.clmchiu.paylog;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * 監聽 Google 錢包 / Samsung Wallet / 銀行 App 的付款通知。
 * 手機 NFC 感應付款完成後，這些 App 會發出「已付款 NT$xxx」的通知；
 * 本服務讀到後建立一筆「待填寫」紀錄，並直接彈出填寫畫面。
 * 也辨識簡訊 App 的網購驗證碼通知（未授予簡訊權限時的備援）。
 *
 * Android 不允許一般 App 直接攔截 NFC 付款本身，這是官方唯一合規的偵測方式。
 */
public class PaymentListenerService extends NotificationListenerService {

    private static final String TAG = "PayLog";

    /** 非錢包 App 出現這些字通常是行銷推播，不是實際付款。 */
    private static final String[] PROMO_WORDS = {
            "優惠", "折扣", "折價", "活動", "抽獎", "限時", "登錄", "滿額", "加碼", "最高", "專屬", "邀請"
    };

    /** 聊天 / 社群 App 不偵測，避免把對話中的金額誤判成付款。 */
    private static final String[] IGNORED_PACKAGES = {
            "jp.naver.line.android", "com.facebook.orca", "com.facebook.katana", "com.whatsapp",
            "org.telegram.messenger", "com.instagram.android", "com.tencent.mm", "com.discord",
            "com.google.android.gm", "com.google.android.youtube"
    };

    /** 簡訊 App：只用來辨識網購驗證碼簡訊（未授予簡訊權限時的備援）。 */
    private static final String[] SMS_PACKAGES = {
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.android.messaging", "com.oneplus.mms", "com.asus.message", "com.sonyericsson.conversations"
    };

    private HandlerThread thread;
    private Handler worker;

    @Override
    public void onListenerConnected() {
        if (thread == null) {
            thread = new HandlerThread("paylog-listener");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            worker = null;
        }
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null) return;
        Handler h = worker;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    safeHandle(sbn);
                }
            });
        } else {
            safeHandle(sbn);
        }
    }

    private void safeHandle(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Throwable t) {
            Log.w(TAG, "handle notification failed", t);
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        for (String p : IGNORED_PACKAGES) if (p.equals(pkg)) return;
        if (Prefs.blocked(this).contains(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        if (isSmsApp(pkg)) {
            // 已授予簡訊權限時由 SmsReceiver 處理，避免重複
            if (!Prefs.smsEnabled(this) || MainActivity.hasSmsPermission(this)) return;
            String sms = collectText(n.extras);
            CharSequence title = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE);
            SmsReceiver.handle(this, sms, title == null ? "" : title.toString());
            return;
        }

        boolean wallet = Prefs.isWallet(pkg);
        if (!wallet && !Prefs.watchAllApps(this)) return;

        String text = collectText(n.extras);
        if (text.isEmpty()) return;

        PaymentParser.Result p = PaymentParser.parse(text);

        boolean isPayment;
        if (wallet) {
            isPayment = p.amount != null || p.keyword;
        } else {
            isPayment = p.amount != null && p.keyword && !looksLikePromo(text);
        }
        if (!isPayment) return;

        Detector.onPayment(this, p, pkg, appLabel(n.extras, pkg));
    }

    private static boolean isSmsApp(String pkg) {
        for (String p : SMS_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }

    private static boolean looksLikePromo(String text) {
        for (String w : PROMO_WORDS) if (text.contains(w)) return true;
        return false;
    }

    /** 合併通知內所有文字欄位。 */
    static String collectText(Bundle e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        append(sb, e.getCharSequence(Notification.EXTRA_TITLE));
        append(sb, e.getCharSequence(Notification.EXTRA_TITLE_BIG));
        append(sb, e.getCharSequence(Notification.EXTRA_TEXT));
        append(sb, e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        append(sb, e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        append(sb, e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence l : lines) append(sb, l);
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().trim();
        if (s.isEmpty() || sb.indexOf(s) >= 0) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(s);
    }

    /** 從通知本身取 App 名稱（不需額外的套件可見度權限）。 */
    @SuppressWarnings("deprecation")
    private String appLabel(Bundle e, String pkg) {
        try {
            Object o = e == null ? null : e.get("android.appInfo");
            if (o instanceof ApplicationInfo) {
                CharSequence l = getPackageManager().getApplicationLabel((ApplicationInfo) o);
                if (l != null && l.length() > 0) return l.toString();
            }
        } catch (Throwable ignored) {
        }
        if (pkg.contains("walletnfcrel") || pkg.contains("paisa")) return "Google 錢包";
        if (pkg.contains("samsung") && pkg.contains("pay")) return "Samsung Wallet";
        String[] parts = pkg.split("\\.");
        return parts[parts.length - 1].toUpperCase(Locale.ROOT);
    }
}
