package tw.clmchiu.paylog;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用者自己的一張卡 / 一種付款方式，例如「滙豐 Master ••1234（HKD）」。
 * 同一卡別可以有很多張（台灣、香港、大陸發行），用末四碼區分；
 * 結算幣別決定通知只寫「$」「¥」「元」時算哪一種貨幣。
 */
final class Card {
    long id;
    String name = "";       // 自訂名稱，例如：滙豐 Visa Signature
    String brand = "";      // 卡別 / 付款方式，見 BRANDS
    String last4 = "";      // 末四碼（可空）
    String bank = "";       // 發卡銀行（可空）
    String currency = "";   // 結算幣別：TWD / HKD / CNY …；"" = 台幣

    /** 卡別 / 付款方式。前三個是舊版的固定選項，舊紀錄沿用。 */
    static final String UNIONPAY = "銀聯";
    static final String JCB = "JCB";
    static final String AMEX = "AMEX";
    static final String OCTOPUS = "八達通";
    static final String WECHAT = "微信支付";
    static final String ALIPAY = "支付寶";
    static final String PAYME = "PayMe";
    static final String CASH = "現金";
    static final String[] BRANDS = {
            PaymentParser.VISA, PaymentParser.MASTER, UNIONPAY, JCB, AMEX,
            PaymentParser.EASYCARD, OCTOPUS, WECHAT, ALIPAY, PAYME, CASH
    };

    /** 結算幣別選項。 */
    static final String[] CURRENCIES = {"TWD", "HKD", "CNY", "JPY", "USD", "MYR", "SGD", "KRW", "THB", "EUR", "GBP"};
    static final String[] CURRENCY_NAMES = {"台幣", "港幣", "人民幣", "日圓", "美金", "令吉", "新幣", "韓圜", "泰銖", "歐元", "英鎊"};

    static String currencyName(String code) {
        if (TextUtils.isEmpty(code)) return "台幣";
        for (int i = 0; i < CURRENCIES.length; i++) if (CURRENCIES[i].equals(code)) return CURRENCY_NAMES[i];
        return code;
    }

    /** 記帳時寫進紀錄的名稱：自訂名稱優先，否則「卡別 ••末四碼」。 */
    String label() {
        if (!TextUtils.isEmpty(name)) return name.trim();
        return brand + (TextUtils.isEmpty(last4) ? "" : " ••" + last4);
    }

    /** 管理清單顯示用的說明。 */
    String detail() {
        List<String> p = new ArrayList<>();
        p.add(brand);
        if (!TextUtils.isEmpty(last4)) p.add("••" + last4);
        if (!TextUtils.isEmpty(bank)) p.add(bank);
        p.add(currencyName(currency));
        return TextUtils.join(" · ", p);
    }

    /** 本卡的結算幣別；台幣回傳 null（不需換算）。 */
    String foreignCurrency() {
        return TextUtils.isEmpty(currency) || "TWD".equals(currency) ? null : currency;
    }

    // ───────── 依 App 判斷付款方式 ─────────

    static final String PKG_WECHAT = "com.tencent.mm";
    static final String[] PKG_ALIPAY = {"com.eg.android.AlipayGphone", "hk.alipay.wallet"};
    static final String PKG_PAYME = "com.hsbc.paymefromhsbc";
    static final String PKG_OCTOPUS = "com.octopuscards.nfc_reader";

    /** 這個 App 本身就是付款方式（微信、支付寶、八達通、PayMe）；否則 null。 */
    static String methodForPackage(String pkg) {
        if (pkg == null) return null;
        if (PKG_WECHAT.equals(pkg)) return WECHAT;
        for (String p : PKG_ALIPAY) if (p.equals(pkg)) return ALIPAY;
        if (PKG_PAYME.equals(pkg)) return PAYME;
        if (PKG_OCTOPUS.equals(pkg)) return OCTOPUS;
        return null;
    }

    /** App 預設幣別（通知只寫 ¥ / 元 / $ 時用）；否則 null。 */
    static String currencyForPackage(String pkg) {
        if (pkg == null) return null;
        if (PKG_WECHAT.equals(pkg) || "com.eg.android.AlipayGphone".equals(pkg)) return "CNY";
        if ("hk.alipay.wallet".equals(pkg) || PKG_PAYME.equals(pkg) || PKG_OCTOPUS.equals(pkg)) return "HKD";
        return null;
    }

    // ───────── 偵測付款時：認出是哪張卡，再用該卡幣別解析金額 ─────────

    /**
     * 解析通知 / 簡訊：
     * 1. 末四碼對到「我的卡片」→ 用該卡名稱與結算幣別
     * 2. 微信 / 支付寶 / 八達通 / PayMe 的通知 → 該付款方式（大陸版預設人民幣、香港版預設港幣）
     * 3. 卡別只有一張符合 → 用那張卡
     * 4. 都沒有 → 出差設定的幣別（例如香港出差 = 港幣）
     * 結果的 card 欄位是要寫入紀錄的名稱。
     */
    static PaymentParser.Result analyze(Context c, String text, String pkg, long when) {
        PaymentParser.Result p0 = PaymentParser.parse(text);
        List<Card> cards = Db.get(c).cards();
        Card card = null;

        String last4 = PaymentParser.findLast4(text);
        if (last4 != null) {
            for (Card k : cards) {
                if (last4.equals(k.last4)) {
                    card = k;
                    break;
                }
            }
        }
        String method = methodForPackage(pkg);
        if (card == null && method != null) {
            for (Card k : cards) {
                if (method.equals(k.brand)) {
                    card = k;
                    break;
                }
            }
        }
        if (card == null && p0.card != null) {
            Card only = null;
            int n = 0;
            for (Card k : cards) {
                if (p0.card.equals(k.brand)) {
                    only = k;
                    n++;
                }
            }
            if (n == 1) card = only;
        }

        String hint = null;
        if (card != null) hint = card.foreignCurrency();
        if (hint == null && (card == null || TextUtils.isEmpty(card.currency))) hint = currencyForPackage(pkg);
        if (hint == null && card == null) {
            Trip t = Db.get(c).tripAt(when);
            if (t != null && !TextUtils.isEmpty(t.currency)) hint = "TWD".equals(t.currency) ? null : t.currency;
        }

        PaymentParser.Result p = hint != null ? PaymentParser.parse(text, hint) : p0;
        if (card != null) p.card = card.label();
        else if (method != null) p.card = method;
        else if (p.card != null && last4 != null) p.card = p.card + " ••" + last4;
        return p;
    }

    /** 填寫畫面的選項：我的卡片 + 一般卡別（去重）+ 目前紀錄的值。 */
    static List<String> choices(Context c, String current) {
        List<String> out = new ArrayList<>();
        for (Card k : Db.get(c).cards()) if (!out.contains(k.label())) out.add(k.label());
        for (String b : BRANDS) if (!out.contains(b)) out.add(b);
        if (!TextUtils.isEmpty(current) && !out.contains(current)) out.add(0, current);
        return out;
    }

    /** 某名稱對應的我的卡片（填寫頁選卡後顯示幣別用）；沒有則 null。 */
    static Card byLabel(Context c, String label) {
        if (TextUtils.isEmpty(label)) return null;
        for (Card k : Db.get(c).cards()) if (label.equals(k.label())) return k;
        return null;
    }
}
