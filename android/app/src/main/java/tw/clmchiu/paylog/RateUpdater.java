package tw.clmchiu.paylog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 每天當地時間早上 7:00 自動更新外幣匯率（手機時區跟著出國地點變，所以是「在地」7 點）。
 * 來源：臺灣銀行牌告「即期賣出」（沒有即期報價的幣別用現金賣出）；失敗時改用 open.er-api.com。
 * 只下載公開匯率表，不上傳任何資料。
 */
public class RateUpdater extends BroadcastReceiver {

    static final String ACTION_UPDATE = "tw.clmchiu.paylog.UPDATE_RATES";
    private static final String TAG = "PayLog";
    static final int HOUR = 7;

    static final String BOT_URL = "https://rate.bot.com.tw/xrt/fltxt/0/day";
    static final String FALLBACK_URL = "https://open.er-api.com/v6/latest/TWD";

    @Override
    public void onReceive(final Context context, Intent intent) {
        String a = intent.getAction();
        final Context c = context.getApplicationContext();
        if (ACTION_UPDATE.equals(a)) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean ok = updateNow(c);
                        if (ok) scheduleNext(c);
                        else scheduleRetry(c);   // 沒網路（飛航模式、剛落地）：1 小時後再試
                    } finally {
                        pr.finish();
                    }
                }
            }, "paylog-rates").start();
        } else {
            // 開機、時區改變（出國）、更新 App 後：重新排定當地 7 點
            scheduleNext(c);
        }
    }

    // ───────── 排程 ─────────

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, RateUpdater.class).setAction(ACTION_UPDATE);
        return PendingIntent.getBroadcast(c, 7007, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 下一個當地早上 7:00。 */
    static long next7am(ZonedDateTime now) {
        ZonedDateTime t = now.toLocalDate().atTime(LocalTime.of(HOUR, 0)).atZone(now.getZone());
        if (!t.isAfter(now)) t = t.plusDays(1);
        return t.toInstant().toEpochMilli();
    }

    static void scheduleNext(Context c) {
        if (!Prefs.autoRates(c)) {
            cancel(c);
            return;
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = next7am(ZonedDateTime.now(ZoneId.systemDefault()));
        // 10 分鐘彈性視窗：不需要「精確鬧鐘」權限，也比較省電
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi(c));
    }

    private static void scheduleRetry(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null || !Prefs.autoRates(c)) return;
        long at = System.currentTimeMillis() + 60 * 60 * 1000L;
        long next = next7am(ZonedDateTime.now(ZoneId.systemDefault()));
        if (at >= next) at = next;
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi(c));
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pi(c));
    }

    /** 開 App 時呼叫：排好排程；今天過了 7 點還沒更新就馬上補一次。 */
    static void ensure(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (!Prefs.autoRates(c)) return;
        scheduleNext(c);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long today7 = now.toLocalDate().atTime(LocalTime.of(HOUR, 0)).atZone(now.getZone()).toInstant().toEpochMilli();
        long last = Prefs.ratesUpdatedAt(c);
        boolean due = now.toInstant().toEpochMilli() >= today7 ? last < today7 : last < today7 - 24 * 3600 * 1000L;
        if (!due) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                updateNow(c);
            }
        }, "paylog-rates").start();
    }

    // ───────── 下載與解析 ─────────

    /** 立即更新；成功回傳 true。可在背景執行緒呼叫。 */
    static synchronized boolean updateNow(Context c) {
        Map<String, Double> rates = null;
        String source = null;
        try {
            rates = parseBot(fetch(BOT_URL));
            source = "臺灣銀行即期賣出";
        } catch (Exception e) {
            Log.w(TAG, "BOT rates failed", e);
        }
        if (rates == null || rates.size() < 5) {
            try {
                rates = parseErApi(fetch(FALLBACK_URL));
                source = "open.er-api.com";
            } catch (Exception e) {
                Log.w(TAG, "fallback rates failed", e);
                return false;
            }
        }
        if (rates == null || rates.isEmpty()) return false;
        int n = 0;
        for (String[] f : PaymentParser.FOREIGN) {
            Double v = rates.get(f[0]);
            if (v != null && v > 0) {
                Prefs.setRate(c, f[0], v);
                n++;
            }
        }
        if (n == 0) return false;
        Prefs.setRatesUpdated(c, System.currentTimeMillis(), source);
        return true;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(15000);
        h.setRequestProperty("User-Agent", "PayLog-Android");
        try {
            if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int k;
                while ((k = in.read(buf)) > 0) bos.write(buf, 0, k);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            h.disconnect();
        }
    }

    /**
     * 臺灣銀行牌告（fltxt / flcsv）：每列「幣別 本行買入 現金 即期 遠期… 本行賣出 現金 即期 遠期…」，
     * 以空白或逗號分隔；賣出也可能在下一列。回傳 幣別 → 1 外幣 = ? 台幣。
     */
    static Map<String, Double> parseBot(String text) {
        Map<String, Double> out = new HashMap<>();
        String code = null;
        for (String line : text.split("\\r?\\n")) {
            String[] tok = line.trim().replace('﻿', ' ').trim().split("[\\s,]+");
            if (tok.length == 0 || tok[0].isEmpty()) continue;
            if (tok[0].matches("[A-Z]{3}")) code = tok[0];
            if (code == null) continue;
            for (int i = 0; i < tok.length; i++) {
                if (!tok[i].contains("賣出")) continue;
                double cash = num(tok, i + 1), spot = num(tok, i + 2);
                double v = spot > 0 ? spot : cash;
                if (v > 0) out.put(code, v);
                break;
            }
        }
        return out;
    }

    private static double num(String[] t, int i) {
        if (i >= t.length) return 0;
        try {
            return Double.parseDouble(t[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** open.er-api.com：{"rates":{"JPY":4.76,...}} 以台幣為基準 → 取倒數。 */
    static Map<String, Double> parseErApi(String json) throws Exception {
        Map<String, Double> out = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([A-Z]{3})\"\\s*:\\s*([0-9.]+(?:[eE][-+]?[0-9]+)?)").matcher(json);
        while (m.find()) {
            double perTwd = Double.parseDouble(m.group(2));
            if (perTwd > 0) out.put(m.group(1), round(1.0 / perTwd));
        }
        out.remove("TWD");
        return out;
    }

    private static double round(double v) {
        if (v >= 1) return Math.round(v * 10000) / 10000.0;
        return Double.parseDouble(String.format(java.util.Locale.ROOT, "%.6g", v));
    }

    /** 顯示用：今天幾點更新。 */
    static String updatedLabel(Context c) {
        long t = Prefs.ratesUpdatedAt(c);
        if (t <= 0) return "尚未自動更新";
        String day = LocalDate.now().equals(java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate())
                ? "今天 " + Fmt.format(t, Fmt.TIME) : Fmt.format(t, Fmt.SHORT);
        return "更新於 " + day + "（" + Prefs.ratesSource(c) + "）";
    }
}
