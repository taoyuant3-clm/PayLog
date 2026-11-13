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
        String currency;    // 外幣代碼（JPY、USD…）；null = 台幣
        Double foreign;     // 外幣金額；amount 為 null 時需依匯率換算成台幣
    }

    // ───────── 外幣（出國刷卡：¥1,200、JPY 1,200、1,200円、日幣1,200元…） ─────────

    /** 代碼 → 通知中可能出現的寫法（regex）。順序重要：US$、HK$、S$ 要在單獨的 $ 之前處理。 */
    static final String[][] FOREIGN = {
            {"JPY", "JPY|JP[¥￥]|日[圓圆幣币元]", "円|日[圓圆幣币元]|JPY"},
            {"USD", "USD|US\\s?\\$|美[金元]", "USD|美[金元]"},
            {"MYR", "MYR|(?-i:\\bRM)|馬幣|令吉", "MYR|令吉|馬幣"},
            {"HKD", "HKD|HK\\s?\\$|港[幣币元紙纸]", "HKD|港[幣币元]"},
            {"EUR", "EUR|€|歐元", "EUR|歐元"},
            {"KRW", "KRW|₩|韓[圓圆幣币元]", "KRW|韓[圓圆幣币元]"},
            {"SGD", "SGD|S\\$|新加坡幣|星幣", "SGD|新加坡幣"},
            {"CNY", "CNY|RMB|CN[¥￥]|人民[幣币]", "CNY|RMB|人民[幣币]"},
            {"THB", "THB|泰銖|฿", "THB|泰銖"},
            {"GBP", "GBP|£|英鎊", "GBP|英鎊"},
            {"VND", "VND|越南盾|₫", "VND|越南盾"},
            {"PHP", "PHP|菲律賓披索|₱", "PHP"},
    };
    static final String[] FOREIGN_SYMBOL = {
            "¥", "US$", "RM", "HK$", "€", "₩", "S$", "¥", "฿", "£", "₫", "₱"};

    /** 單獨的 ¥ / ￥：日圓或人民幣，依提示幣別決定（預設日圓）。 */
    private static final Pattern YEN = Pattern.compile(
            "[¥￥]\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]{1,2}))?");

    private static final Pattern[][] FOREIGN_PATTERNS = new Pattern[FOREIGN.length][2];
    static {
        for (int i = 0; i < FOREIGN.length; i++) {
            FOREIGN_PATTERNS[i][0] = Pattern.compile("(?:" + FOREIGN[i][1] + ")\\s*[:：]?\\s*" + "([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]{1,2}))?",
                    Pattern.CASE_INSENSITIVE);
            FOREIGN_PATTERNS[i][1] = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]{1,2}))?\\s*(?:" + FOREIGN[i][2] + ")",
                    Pattern.CASE_INSENSITIVE);
        }
    }

    /** 顯示用：JPY 1,200 → ¥1,200 */
    static String foreignLabel(String code, double v) {
        for (int i = 0; i < FOREIGN.length; i++) {
            if (FOREIGN[i][0].equals(code)) {
                java.text.DecimalFormat f = new java.text.DecimalFormat(v == Math.rint(v) ? "#,##0" : "#,##0.##");
                return FOREIGN_SYMBOL[i] + f.format(v);
            }
        }
        return code + " " + v;
    }

    /**
     * 找外幣金額；找到時回傳 {代碼, 金額, 起, 迄}，否則 null。
     * 取文字中最先出現的外幣金額。
     */
    static Object[] findForeign(String t) {
        return findForeign(t, null);
    }

    static Object[] findForeign(String t, String hint) {
        Object[] best = null;
        Matcher ym = YEN.matcher(t);
        if (ym.find()) {
            Double v = toNumber(ym);
            if (v != null) best = new Object[]{"CNY".equals(hint) ? "CNY" : "JPY", v, ym.start(), ym.end()};
        }
        for (int i = 0; i < FOREIGN.length; i++) {
            for (Pattern p : FOREIGN_PATTERNS[i]) {
                Matcher m = p.matcher(t);
                while (m.find()) {
                    Double v = toNumber(m);
                    if (v == null) continue;
                    if (isFourDigitsAfterCardWord(t, m.start())) continue;
                    if (best == null || m.start() < (Integer) best[2]) {
                        best = new Object[]{FOREIGN[i][0], v, m.start(), m.end()};
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** 「末四碼1234」不是金額。 */
    private static boolean isFourDigitsAfterCardWord(String t, int start) {
        int from = Math.max(0, start - 4);
        String before = t.substring(from, start);
        return before.contains("末四碼") || before.contains("卡號") || before.endsWith("••");
    }

    private static final String NUM = "([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]{1,2}))?";

    private static final Pattern AMOUNT_PREFIX = Pattern.compile(
            "(?:NT\\s?\\$|NTD|TWD|新[臺台]幣|[臺台]幣|＄|\\$)\\s*" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile(
            NUM + "\\s*(?:元|NTD|TWD)", Pattern.CASE_INSENSITIVE);
    /** 「交易金額：1,280」這類沒有幣別符號的寫法（驗證簡訊常見）。 */
    private static final Pattern AMOUNT_LABEL = Pattern.compile(
            "(?:交易|消費|消费|刷卡|付款|支付|簽賬|签账)?金[額额]\\s*[:：為为]?\\s*(?:NT\\s?\\$|NTD|TWD|新[臺台]幣|[臺台]幣)?\\s*" + NUM,
            Pattern.CASE_INSENSITIVE);

    private static final Pattern[] MERCHANT = {
            Pattern.compile("(?:特約商店|特約商|商店|商家|特店|商戶|商户|收款方|商[戶户]名稱|商户名称)(?:名稱|名称)?\\s*[:：]\\s*([^\\n,，。;；]{1,40})"),
            Pattern.compile("向\\s*([^\\n,，。;；]{1,30}?)\\s*(?:付款|支付|付钱|付錢)"),
            Pattern.compile("(?:^|\\s)(?:at|@)\\s+([^\\n,，。;；]{1,40}?)(?:\\s+(?:with|using|on)\\b|$|[\\n,，。;；])",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:於|在|于)\\s*([^\\n,，。;；]{1,30}?)\\s*(?:消費|消费|付款|支付|交易|刷卡|扣款|簽賬|签账)"),
    };

    /** 「於10/14 12:30消費」這類日期時間不是商家。 */
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\s*(?:[0-9]{1,4}[/\\-.年][0-9]{1,2}|[0-9]{1,2}:[0-9]{2}|[0-9]{1,2}月|今[天日]|昨[天日])");

    private static final String[] KEYWORDS = {
            "付款", "支付", "消費", "交易", "刷卡", "扣款", "感應", "嗶",
            "国外", "國外", "海外",
            "消费", "支出", "已支付", "支付成功", "付款成功", "扣款成功", "签账", "簽賬", "簽帳", "交易成功",
            "支払", "ご利用", "決済", "購入", "利用金額",
            "payment", "paid", "purchase", "transaction", "spent", "charged", "approved"
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
        return parse(text, null);
    }

    /**
     * @param hint 通知只寫「$」「元」「¥」時代表的幣別（例如香港卡 = HKD、微信 = CNY）；
     *             null = 台灣習慣（$ / 元 = 台幣，¥ = 日圓）。明確寫出的幣別（NT$、HK$、JPY…）不受影響。
     */
    static Result parse(String text, String hint) {
        Result r = new Result();
        if (text == null) return r;
        if ("TWD".equals(hint)) hint = null;
        String t = stripBalance(text.replace('\u00A0', ' '));

        // 先找外幣，避免「日幣1,200元」「US$12」被當成台幣
        Object[] f = findForeign(t, hint);
        String rest = t;
        if (f != null) {
            r.currency = (String) f[0];
            r.foreign = (Double) f[1];
            int a = (Integer) f[2], b = (Integer) f[3];
            StringBuilder sb = new StringBuilder(t);
            for (int i = a; i < b; i++) sb.setCharAt(i, ' ');
            rest = sb.toString();
        }
        // 銀行通知常同時附上折合台幣金額，有就直接用
        Double nt = findExplicitTwd(rest);
        if (nt != null) {
            r.amount = nt;
        } else {
            Double amb = findAmount(rest);   // 「$120」「120元」「金額 120」
            if (amb != null && hint != null && f == null) {
                r.currency = hint;           // 香港卡的 $ = 港幣、微信的 元 = 人民幣
                r.foreign = amb;
            } else {
                r.amount = amb;
            }
        }
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

    // ───────── 判斷「真的是一筆付款」：避免新聞、幣價、餘額、加值通知被當成消費 ─────────

    private static final Pattern BALANCE = Pattern.compile(
            "(?:餘額|余额|結餘|结余|可用額度|可用额度|剩餘|剩余|balance|available)\\s*(?:為|为|是)?\\s*[:：]?\\s*"
                    + "(?:NT\\s?\\$|HK\\s?\\$|US\\s?\\$|[A-Z]{3}|新[臺台]幣|人民[幣币]|港[幣币])?\\s*[$＄¥￥]?\\s*"
                    + "[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*(?:元|円)?",
            Pattern.CASE_INSENSITIVE);

    /** 去掉「餘額 NT$2,000」這類金額（不是這筆的消費金額）。 */
    static String stripBalance(String t) {
        Matcher m = BALANCE.matcher(t);
        StringBuilder sb = new StringBuilder(t);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) sb.setCharAt(i, ' ');
        }
        return sb.toString();
    }

    private static final Pattern STRONG_PAY = Pattern.compile(
            "付款成功|支付成功|交易成功|扣款成功|付款完成|支付完成|已付款|已支付|已扣款|"
                    + "消費通知|消费通知|刷卡通知|交易通知|消費提醒|消费提醒|交易提醒|簽帳|簽賬|签账|"
                    + "刷卡消費|刷卡消费|消費金額|消费金额|交易金額|交易金额|付款金[額额]|支付金[額额]|授權成功|授权成功|"
                    + "國外消費|国外消费|海外消費|海外消费|支出|"
                    + "您的\\S{0,12}卡|您尾[號号]|卡[號号]末|末四[碼码]|尾[號号]\\s*[0-9]{4}|[•·*＊]{2,}\\s*[0-9]{4}|"
                    + "you paid|you spent|payment of|purchase of|was charged|been charged|card ending|transaction at|\\bpaid\\b.{0,40}\\bat\\b",
            Pattern.CASE_INSENSITIVE);

    /** 明確的付款字樣（「付款成功」「刷卡通知」「您的信用卡」「••1234」…）。單獨的「交易」「消費」不算，新聞常出現。 */
    static boolean strongPayment(String text) {
        return text != null && STRONG_PAY.matcher(text).find();
    }

    private static final String[] MARKET_WORDS = {
            "漲", "涨", "跌", "突破", "價格", "价格", "行情", "報價", "报价", "股價", "股价", "幣價", "币价",
            "指數", "指数", "市值", "現價", "现价", "收盤", "收盘", "開盤", "开盘", "目標價", "到價", "到价",
            "比特幣", "比特币", "以太幣", "以太币", "加密貨幣", "加密货币",
            "price alert", "bitcoin", "crypto", "stock price"
    };
    private static final Pattern PERCENT = Pattern.compile("[0-9]+(?:\\.[0-9]+)?\\s*%");
    private static final Pattern TICKER = Pattern.compile("\\b(?:BTC|ETH|USDT|BNB|SOL|XRP|DOGE)\\b", Pattern.CASE_INSENSITIVE);

    /** 股價、幣價、匯率等行情提醒。 */
    static boolean looksLikeMarket(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : MARKET_WORDS) if (lower.contains(w)) return true;
        return TICKER.matcher(text).find() || PERCENT.matcher(text).find();
    }

    private static final String[] NON_SPEND_WORDS = {
            "加值", "儲值", "储值", "充值", "退款", "退貨", "退货", "退刷", "取消交易", "交易取消", "交易失敗", "交易失败",
            "付款失敗", "付款失败", "收款", "入帳", "入账", "轉入", "转入", "收益", "回饋金", "現金回饋",
            "已新增", "已加入", "新增卡片", "卡片已", "已啟用", "已启用", "已設定", "即將到期", "已到期", "餘額不足", "余额不足",
            "餘額", "余额",
            "refund", "top up", "top-up", "topped up", "reload", "declined", "received", "added to"
    };

    /** 加值、退款、收款、餘額通知、卡片設定、交易失敗等，不是消費。 */
    /** 「扣款 NT$35，餘額 NT$500」是消費（有扣款字樣）；只有餘額 / 加值才排除。 */
    static boolean looksLikeNonSpendOnly(String text) {
        if (!looksLikeNonSpend(text)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        String[] spend = {"扣款", "消費", "消费", "付款", "支付", "支出", "簽賬", "签账", "簽帳", "刷卡", "paid", "spent", "purchase"};
        for (String w : spend) if (lower.contains(w)) return looksLikeHardNonSpend(lower);
        return true;
    }

    private static final String[] HARD_NON_SPEND = {
            "加值", "儲值", "储值", "充值", "退款", "退貨", "退货", "退刷", "取消", "失敗", "失败", "收款", "入帳", "入账",
            "轉入", "转入", "收益", "回饋金", "已新增", "已加入", "新增卡片", "已啟用", "已启用", "已設定", "到期", "不足",
            "refund", "top up", "top-up", "reload", "declined", "received", "added to"
    };

    private static boolean looksLikeHardNonSpend(String lower) {
        for (String w : HARD_NON_SPEND) if (lower.contains(w)) return true;
        return false;
    }

    static boolean looksLikeNonSpend(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : NON_SPEND_WORDS) if (lower.contains(w)) return true;
        return false;
    }

    /**
     * 這則 App 通知是否算一筆付款。
     * 錢包：有這筆的金額（餘額不算）或明確付款字樣；加值、餘額、新增卡片、退款不算。
     * 其他 App：金額 + 明確付款字樣，且不是廣告、行情、加值 / 退款。
     */
    static boolean acceptNotification(String text, Result p, boolean wallet) {
        boolean has = hasAmount(p);
        boolean strong = strongPayment(text);
        if (wallet) return (has || strong) && !looksLikeNonSpendOnly(text);
        return has && strong && !looksLikePromo(text) && !looksLikeMarket(text) && !looksLikeNonSpendOnly(text);
    }

    /** 有金額（台幣或外幣）。 */
    static boolean hasAmount(Result r) {
        return r.amount != null || r.foreign != null;
    }

    private static final String[] PROMO_WORDS = {
            "優惠", "折扣", "折價", "活動", "抽獎", "限時", "登錄", "滿額", "加碼", "最高", "專屬", "邀請", "回饋",
            "优惠", "红包", "紅包", "领取", "領取", "抽奖", "活动", "立减", "立減", "返现", "返現", "积分", "簽到", "签到", "福利"
    };

    /** 行銷推播常見字，不是實際付款。 */
    static boolean looksLikePromo(String text) {
        for (String w : PROMO_WORDS) if (text.contains(w)) return true;
        return false;
    }

    private static final Pattern CARD_SPEND = Pattern.compile(
            "卡|card|visa|master|jcb|刷卡|消費|消费|簽帳|簽賬|签账|授權|授权|銀聯|银联", Pattern.CASE_INSENSITIVE);

    /** 銀行「刷卡消費通知」簡訊（不是驗證碼）：有金額、有付款字樣、提到卡片、不是廣告。 */
    static boolean isCardSpendSms(String text) {
        if (text == null || text.isEmpty()) return false;
        if (OTP_WORD.matcher(text).find()) return false;
        Result r = parse(text);
        return hasAmount(r) && strongPayment(text) && CARD_SPEND.matcher(text).find()
                && !looksLikePromo(text) && !looksLikeMarket(text) && !looksLikeNonSpendOnly(text);
    }

    private static final Pattern EXPLICIT_TWD = Pattern.compile(
            "(?:NT\\s?\\$|NTD|TWD|新[臺台]幣|[臺台]幣)\\s*(?:約|约)?\\s*" + NUM + "|" + NUM + "\\s*(?:NTD|TWD)",
            Pattern.CASE_INSENSITIVE);

    /** 明確標示台幣的金額（NT$、新臺幣、TWD）。 */
    static Double findExplicitTwd(String t) {
        Matcher m = EXPLICIT_TWD.matcher(t);
        if (!m.find()) return null;
        if (m.group(1) != null) return toNumber(m);
        String whole = m.group(3).replace(",", "");
        String frac = m.group(4);
        try {
            double v = Double.parseDouble(frac == null ? whole : whole + "." + frac);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern LAST4 = Pattern.compile(
            "(?:[•·*＊xX]{2,}|末四碼|末4碼|末四码|末4码|尾[號号數数]|卡[號号]末|ending in|ending|card no\\.?)\\s*[:：]?\\s*([0-9]{4})(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    /** 通知裡的卡號末四碼（••1234、末四碼1234、尾號1234、ending in 1234）；沒有則 null。 */
    static String findLast4(String t) {
        if (t == null) return null;
        Matcher m = LAST4.matcher(t);
        return m.find() ? m.group(1) : null;
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
        if (s.contains("八達通") || s.contains("八达通") || s.contains("octopus")) return Card.OCTOPUS;
        if (s.contains("銀聯") || s.contains("银联") || s.contains("unionpay") || s.contains("union pay")) return Card.UNIONPAY;
        if (s.matches("(?s).*\\bjcb\\b.*")) return Card.JCB;
        if (s.contains("american express") || s.contains("美國運通") || s.contains("美国运通") || s.matches("(?s).*\\bamex\\b.*")) return Card.AMEX;
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
