package tw.clmchiu.paylog;

import android.content.Context;
import android.content.SharedPreferences;

/** 使用者設定。 */
final class Prefs {

    /** 預設監聽的手機錢包 App。 */
    static final String[] WALLET_PACKAGES = {
            "com.google.android.apps.walletnfcrel",   // Google 錢包
            "com.google.android.apps.nbu.paisa.user", // Google Pay（部分地區）
            "com.samsung.android.spay",               // Samsung Wallet
            "com.samsung.android.samsungpay.gear",    // Galaxy Watch 錢包
    };

    static final String[] CATEGORIES = {
            "餐飲", "交通", "日用品", "購物", "娛樂", "醫療", "工作/公務", "其他"
    };

    private static final String FILE = "paylog";
    private static final String K_WATCH_ALL = "watch_all_apps";
    private static final String K_LAST_CARD = "last_card";
    private static final String K_LAST_CATEGORY = "last_category";
    private static final String K_DISCLOSED = "listener_disclosed";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isWallet(String pkg) {
        for (String p : WALLET_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }

    /** 也偵測銀行、悠遊付等其他 App 的付款通知。 */
    static boolean watchAllApps(Context c) {
        return sp(c).getBoolean(K_WATCH_ALL, true);
    }

    static void setWatchAllApps(Context c, boolean v) {
        sp(c).edit().putBoolean(K_WATCH_ALL, v).apply();
    }

    static String lastCard(Context c) {
        return sp(c).getString(K_LAST_CARD, "");
    }

    static String lastCategory(Context c) {
        return sp(c).getString(K_LAST_CATEGORY, "");
    }

    static void remember(Context c, String card, String category) {
        sp(c).edit().putString(K_LAST_CARD, card).putString(K_LAST_CATEGORY, category).apply();
    }

    private static final String K_BLOCKED = "blocked_packages";

    /** 使用者按「不再偵測此 App」的套件。 */
    static java.util.Set<String> blocked(Context c) {
        return new java.util.HashSet<>(sp(c).getStringSet(K_BLOCKED, new java.util.HashSet<>()));
    }

    static void block(Context c, String pkg) {
        java.util.Set<String> s = blocked(c);
        s.add(pkg);
        sp(c).edit().putStringSet(K_BLOCKED, s).apply();
    }

    static void clearBlocked(Context c) {
        sp(c).edit().remove(K_BLOCKED).apply();
    }

    private static final String K_POPUP = "auto_popup";
    private static final String K_SMS = "sms_enabled";
    private static final String K_OTP_ALL = "otp_all";
    private static final String K_OTP_DELAY = "otp_delay_sec";
    static final int[] OTP_DELAY_CHOICES = {30, 60, 120, 0};

    /** 偵測到付款後直接彈出填寫畫面。 */
    static boolean autoPopup(Context c) {
        return sp(c).getBoolean(K_POPUP, true);
    }

    static void setAutoPopup(Context c, boolean v) {
        sp(c).edit().putBoolean(K_POPUP, v).apply();
    }

    /** 網購驗證碼簡訊也觸發記帳。 */
    static boolean smsEnabled(Context c) {
        return sp(c).getBoolean(K_SMS, true);
    }

    static void setSmsEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SMS, v).apply();
    }

    /** 任何驗證碼簡訊都觸發（含登入 / 註冊）。 */
    static boolean otpAll(Context c) {
        return sp(c).getBoolean(K_OTP_ALL, false);
    }

    static void setOtpAll(Context c, boolean v) {
        sp(c).edit().putBoolean(K_OTP_ALL, v).apply();
    }

    /** 收到驗證簡訊後幾秒才彈出（讓你先輸入驗證碼）；0 = 立即。 */
    static int otpDelaySeconds(Context c) {
        return sp(c).getInt(K_OTP_DELAY, 60);
    }

    static void setOtpDelaySeconds(Context c, int v) {
        sp(c).edit().putInt(K_OTP_DELAY, v).apply();
    }

    static boolean disclosed(Context c) {
        return sp(c).getBoolean(K_DISCLOSED, false);
    }

    static void setDisclosed(Context c) {
        sp(c).edit().putBoolean(K_DISCLOSED, true).apply();
    }
}
