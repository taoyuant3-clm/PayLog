package tw.clmchiu.paylog;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 從錢包 / 銀行 App 的通知文字解析：金額、支付卡、商家。
 * 純 Java，無 Android 相依，可直接單元測試。
 */
final class PaymentParser {

    static final String VISA = "VISA";
    static final String MASTER = "MASTER";
    static final String EASYCARD = "悠遊卡";
    static final String[] CARDS = {VISA, MASTER, EASYCARD};

    static final class Result {
        Double amount;      // null = 找不到金額
        String card;        // null = 無法判斷
        String merchant;    // null = 無法判斷
        boolean keyword;    // 通知內含付款關鍵字
    }

    private static final String NUM = "([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]{1,2}))?";

    private static final Pattern AMOUNT_PREFIX = Pattern.compile(
            "(?:NT\\s?\\$|NTD|TWD|新[臺台]幣|[臺台]幣|＄|\\$)\\s*" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile(
            NUM + "\\s*(?:元|NTD|TWD)", Pattern.CASE_INSENSITIVE);
    /** 「交易金額：1,280」這類沒有幣別符號的寫法（驗證簡訊常見）。 */
    private static final Pattern AMOUNT_LABEL = Pattern.compile(
            "(?:交易|消費|刷卡|付款)?金額\\s*[:：為为]?\\s*(?:NT\\s?\\$|NTD|TWD|新[臺台]幣|[臺台]幣)?\\s*" + NUM,
            Pattern.CASE_INSENSITIVE);

    private static final Pattern[] MERCHANT = {
            Pattern.compile("(?:特約商店|特約商|商店|商家|特店|商戶)(?:名稱)?\\s*[:：]\\s*([^\\n,，。;；]{1,40})"),
            Pattern.compile("(?:^|\\s)(?:at|@)\\s+([^\\n,，。;；]{1,40}?)(?:\\s+(?:with|using|on)\\b|$|[\\n,，。;；])",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:於|在)\\s*([^\\n,，。;；]{1,30}?)\\s*(?:消費|付款|支付|交易|刷卡|扣款)"),
    };

    /** 「於10/14 12:30消費」這類日期時間不是商家。 */
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\s*(?:[0-9]{1,4}[/\\-.年][0-9]{1,2}|[0-9]{1,2}:[0-9]{2}|[0-9]{1,2}月|今[天日]|昨[天日])");

    private static final String[] KEYWORDS = {
            "付款", "支付", "消費", "交易", "刷卡", "扣款", "感應", "嗶",
            "payment", "paid", "purchase", "transaction", "spent", "charged"
    };

    private static final Pattern MERCHANT_STOP = Pattern.compile(
            "\\s(?=\\S*(?:驗證|密碼|認證|OTP|金額|有效|請勿|卡號|末四碼))|[，,。;；:：(（]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERCHANT_SUFFIX = Pattern.compile("(?:網路|線上|網購|進行)+$");

    /** 去掉商家名稱後面黏著的驗證碼 / 金額等文字，以及「網路交易」的「網路」。 */
    static String cleanMerchant(String v) {
        Matcher m = MERCHANT_STOP.matcher(v);
        if (m.find()) v = v.substring(0, m.start());
        v = v.trim();
        v = MERCHANT_SUFFIX.matcher(v).replaceAll("").trim();
        return v;
    }

    // ───────── 雙重認證（OTP）簡訊 ─────────

    private static final Pattern OTP_WORD = Pattern.compile(
            "驗證碼|認證碼|動態密碼|一次性密碼|交易密碼|安全碼|OTP|3D\\s*驗證|3-?D\\s*Secure|verification code|passcode|one[- ]time",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OTP_CODE = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");
    private static final String[] PURCHASE_WORDS = {
            "交易", "消費", "刷卡", "付款", "支付", "購物", "購買", "網路", "網購", "線上", "訂單", "商店", "特店", "扣款",
            "purchase", "transaction", "payment"
    };
    /** 這些通常是登入 / 註冊 / 綁定的驗證碼，不是消費。 */
    private static final String[] NON_PURCHASE_WORDS = {
            "登入", "登錄", "註冊", "綁定", "變更", "重設", "修改密碼", "開通", "啟用", "申請",
            "login", "log in", "sign in", "sign-in", "register"
    };

    /**
     * 是否為網購消費的雙重認證簡訊。
     * @param all true = 任何驗證碼簡訊都算（不排除登入 / 註冊類）
     */
    static boolean isPurchaseOtp(String text, boolean all) {
        if (text == null || text.isEmpty()) return false;
        if (!OTP_WORD.matcher(text).find() || !OTP_CODE.matcher(text).find()) return false;
        if (all) return true;
        if (findAmount(text) != null) return true;     // 有金額就一定是交易
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : NON_PURCHASE_WORDS) if (lower.contains(w)) return false;
        for (String w : PURCHASE_WORDS) if (lower.contains(w)) return true;
        return false;
    }

    private PaymentParser() {
    }

    static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        String t = text.replace(' ', ' ');

        r.amount = findAmount(t);
        r.card = detectCard(t);
        r.merchant = findMerchant(t);

        String lower = t.toLowerCase(Locale.ROOT);
        for (String k : KEYWORDS) {
            if (lower.contains(k)) {
                r.keyword = true;
                break;
            }
        }
        return r;
    }

    static Double findAmount(String t) {
        Matcher m = AMOUNT_PREFIX.matcher(t);
        if (m.find()) return toNumber(m);
        m = AMOUNT_SUFFIX.matcher(t);
        if (m.find()) return toNumber(m);
        m = AMOUNT_LABEL.matcher(t);
        if (m.find()) return toNumber(m);
        return null;
    }

    private static Double toNumber(Matcher m) {
        String whole = m.group(1).replace(",", "");
        String frac = m.group(2);
        try {
            double v = Double.parseDouble(frac == null ? whole : whole + "." + frac);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String detectCard(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        if (s.contains("悠遊") || s.contains("easycard") || s.contains("easy card")) return EASYCARD;
        if (s.contains("mastercard") || s.contains("master card") || s.contains("萬事達")
                || s.matches("(?s).*\\bmaster\\b.*")) return MASTER;
        if (s.contains("visa")) return VISA;
        return null;
    }

    static String findMerchant(String t) {
        for (Pattern p : MERCHANT) {
            Matcher m = p.matcher(t);
            while (m.find()) {
                String v = m.group(1);
                // 「於10/14 12:30於星巴克消費」取最後一個「於/在」之後的文字
                int cut = Math.max(v.lastIndexOf('於'), v.lastIndexOf('在'));
                if (cut >= 0) v = v.substring(cut + 1);
                v = cleanMerchant(v);
                if (!v.isEmpty() && findAmount(v) == null && !DATE_LIKE.matcher(v).find()) return v;
            }
        }
        return null;
    }
}
