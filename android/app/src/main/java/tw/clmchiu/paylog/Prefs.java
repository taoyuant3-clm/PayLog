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

    /** 預設用途；使用者可在設定或填寫畫面新增 / 刪除。 */
    static final String[] DEFAULT_CATEGORIES = {
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

    private static final String K_CATEGORIES = "categories";

    /** 目前的用途清單（依使用者順序）。 */
    static java.util.List<String> categories(Context c) {
        String raw = sp(c).getString(K_CATEGORIES, null);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null) {
            java.util.Collections.addAll(out, DEFAULT_CATEGORIES);
        } else {
            for (String s : raw.split("\n")) {
                String t = s.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    private static void saveCategories(Context c, java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.replace('\n', ' ').trim());
        }
        sp(c).edit().putString(K_CATEGORIES, sb.toString()).apply();
    }

    /** 新增用途；已存在則不重複。回傳整理後的名稱（空字串 = 無效）。 */
    static String addCategory(Context c, String name) {
        String n = name == null ? "" : name.replace('\n', ' ').trim();
        if (n.isEmpty()) return "";
        if (n.length() > 20) n = n.substring(0, 20);
        java.util.List<String> list = categories(c);
        if (!list.contains(n)) {
            list.add(n);
            saveCategories(c, list);
        }
        return n;
    }

    static void removeCategory(Context c, String name) {
        java.util.List<String> list = categories(c);
        if (list.remove(name)) saveCategories(c, list);
    }

    static void resetCategories(Context c) {
        sp(c).edit().remove(K_CATEGORIES).apply();
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

    // ───────── 外幣匯率（1 外幣 = ? 台幣），出國刷卡時自動換算 ─────────

    private static final String K_RATE = "rate_";

    /** 預設匯率（約略值，2026/11 臺灣銀行牌告附近），使用者可在設定修改。 */
    static double defaultRate(String code) {
        switch (code) {
            case "JPY": return 0.21;
            case "USD": return 32.0;
            case "MYR": return 7.3;
            case "HKD": return 4.1;
            case "EUR": return 35.5;
            case "KRW": return 0.022;
            case "SGD": return 24.0;
            case "CNY": return 4.4;
            case "THB": return 0.95;
            case "GBP": return 41.0;
            case "VND": return 0.0012;
            case "PHP": return 0.55;
            default: return 0;
        }
    }

    static double rate(Context c, String code) {
        float v = sp(c).getFloat(K_RATE + code, -1f);
        return v > 0 ? v : defaultRate(code);
    }

    static void setRate(Context c, String code, double v) {
        if (v > 0) sp(c).edit().putFloat(K_RATE + code, (float) v).apply();
        else sp(c).edit().remove(K_RATE + code).apply();
    }

    // ───────── 略過的通知（診斷用，只存在本機，最多 30 則） ─────────

    private static final String K_SKIPPED = "skipped_notifications";
    static final int MAX_SKIPPED = 30;

    /** 每則：時間毫秒 \t App 名稱 \t 套件 \t 通知文字（換行改成 ⏎）。新的在前。 */
    static java.util.List<String[]> skipped(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = sp(c).getString(K_SKIPPED, "");
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", 4);
            if (f.length == 4) out.add(f);
        }
        return out;
    }

    static synchronized void addSkipped(Context c, String label, String pkg, String text) {
        String t = text.replace('\t', ' ').replace("\r", "").replace("\n", " ⏎ ");
        if (t.length() > 300) t = t.substring(0, 300);
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()).append('\t').append(label.replace('\t', ' '))
                .append('\t').append(pkg).append('\t').append(t);
        int n = 1;
        for (String[] f : skipped(c)) {
            if (n >= MAX_SKIPPED) break;
            if (f[3].equals(t)) continue;   // 同一則通知更新多次只留一則
            sb.append('\n').append(f[0]).append('\t').append(f[1]).append('\t').append(f[2]).append('\t').append(f[3]);
            n++;
        }
        sp(c).edit().putString(K_SKIPPED, sb.toString()).apply();
    }

    static void clearSkipped(Context c) {
        sp(c).edit().remove(K_SKIPPED).apply();
    }

    // ───────── 兩段期間比較：記住上次選的日期（epochDay；-1 = 未設定） ─────────

    private static final String K_RANGE = "compare_range_";

    /** idx 0..3 = 期間一起、期間一迄、期間二起、期間二迄。 */
    static long compareDay(Context c, int idx) {
        return sp(c).getLong(K_RANGE + idx, -1);
    }

    static void setCompareDays(Context c, long[] days) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int i = 0; i < days.length; i++) e.putLong(K_RANGE + i, days[i]);
        e.apply();
    }
}
