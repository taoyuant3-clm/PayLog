package tw.clmchiu.paylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PaymentParserTest {

    @Test
    public void googleWalletEnglish() {
        PaymentParser.Result r = PaymentParser.parse("NT$120.00 at 7-ELEVEN\nVisa ••1234");
        assertEquals(120.0, r.amount, 0.001);
        assertEquals(PaymentParser.VISA, r.card);
        assertEquals("7-ELEVEN", r.merchant);
    }

    @Test
    public void walletChinese() {
        PaymentParser.Result r = PaymentParser.parse("已在 全家便利商店 付款 NT$85 · Mastercard ••5678");
        assertEquals(85.0, r.amount, 0.001);
        assertEquals(PaymentParser.MASTER, r.card);
        assertEquals("全家便利商店", r.merchant);
        assertTrue(r.keyword);
    }

    @Test
    public void bankNotice() {
        PaymentParser.Result r = PaymentParser.parse(
                "信用卡消費通知\n您的VISA卡末四碼1234於10/14 12:30於星巴克消費新臺幣1,250元");
        assertEquals(1250.0, r.amount, 0.001);
        assertEquals(PaymentParser.VISA, r.card);
        assertEquals("星巴克", r.merchant);
    }

    @Test
    public void merchantField() {
        PaymentParser.Result r = PaymentParser.parse("交易成功 金額：NTD 3,480 特約商店：台灣高鐵");
        assertEquals(3480.0, r.amount, 0.001);
        assertEquals("台灣高鐵", r.merchant);
        assertNull(r.card);
    }

    @Test
    public void easyCard() {
        PaymentParser.Result r = PaymentParser.parse("悠遊卡 扣款 35 元 捷運台北車站");
        assertEquals(35.0, r.amount, 0.001);
        assertEquals(PaymentParser.EASYCARD, r.card);
    }

    @Test
    public void noAmount() {
        PaymentParser.Result r = PaymentParser.parse("你的卡片已可使用");
        assertNull(r.amount);
        assertFalse(r.keyword);
    }

    @Test
    public void lastFourDigitsAreNotAmount() {
        assertNull(PaymentParser.findAmount("卡號末四碼 5678 已綁定"));
    }

    @Test
    public void otpWithAmount() {
        String sms = "【國泰世華】您的信用卡於蝦皮購物網路交易金額NT$1,280，OTP驗證碼：482913，請勿告知他人。";
        assertTrue(PaymentParser.isPurchaseOtp(sms, false));
        PaymentParser.Result r = PaymentParser.parse(sms);
        assertEquals(1280.0, r.amount, 0.001);
        assertEquals("蝦皮購物", r.merchant);
    }

    @Test
    public void otpAmountLabelWithoutCurrency() {
        String sms = "3D驗證 交易金額：2,350 特店：MOMO購物網 動態密碼 551203 有效時間5分鐘";
        assertTrue(PaymentParser.isPurchaseOtp(sms, false));
        PaymentParser.Result r = PaymentParser.parse(sms);
        assertEquals(2350.0, r.amount, 0.001);
        assertEquals("MOMO購物網", r.merchant);
    }

    @Test
    public void otpNoAmountButPurchaseWord() {
        assertTrue(PaymentParser.isPurchaseOtp("您正在進行網路交易，一次性密碼為 7731，請於3分鐘內輸入", false));
    }

    @Test
    public void loginOtpIgnored() {
        String sms = "您的登入驗證碼為 839201，請勿提供給他人";
        assertFalse(PaymentParser.isPurchaseOtp(sms, false));
        assertTrue(PaymentParser.isPurchaseOtp(sms, true));
    }

    @Test
    public void otpCodeIsNotAmount() {
        assertNull(PaymentParser.findAmount("您的驗證碼為 482913"));
    }

    @Test
    public void normalSmsIgnored() {
        assertFalse(PaymentParser.isPurchaseOtp("明天下午三點開會，記得帶 2 份文件", true));
    }

    // ───────── 出國刷卡（外幣） ─────────

    @Test
    public void googleWalletJapanYen() {
        PaymentParser.Result r = PaymentParser.parse("¥1,200 at LAWSON\nVisa ••1234");
        assertNull(r.amount);
        assertEquals("JPY", r.currency);
        assertEquals(1200.0, r.foreign, 0.001);
        assertEquals(PaymentParser.VISA, r.card);
        assertEquals("LAWSON", r.merchant);
        assertTrue(PaymentParser.hasAmount(r));
    }

    @Test
    public void yenSuffixAndChineseName() {
        assertEquals(3980.0, PaymentParser.parse("VISA卡 消費 3,980円").foreign, 0.001);
        PaymentParser.Result r = PaymentParser.parse("您的卡片於11/12國外消費日幣1,980元");
        assertEquals("JPY", r.currency);
        assertEquals(1980.0, r.foreign, 0.001);
        assertNull(r.amount);   // 「日幣1,980元」不可當成台幣 1,980
    }

    @Test
    public void bankNoticeWithTwdEquivalent() {
        PaymentParser.Result r = PaymentParser.parse("您的信用卡於日本消費 JPY 3,500，折合新臺幣約735元");
        assertEquals("JPY", r.currency);
        assertEquals(3500.0, r.foreign, 0.001);
        assertEquals(735.0, r.amount, 0.001);
    }

    @Test
    public void otherCurrencies() {
        assertEquals("USD", PaymentParser.parse("US$12.50 at Starbucks").currency);
        assertEquals("MYR", PaymentParser.parse("RM 25.90 at Grab").currency);
        assertNull(PaymentParser.parse("Please confirm 123 times").currency);
    }

    @Test
    public void cardSpendSms() {
        assertTrue(PaymentParser.isCardSpendSms("刷卡通知：VISA卡 11/12 消費 3,980円"));
        assertFalse(PaymentParser.isCardSpendSms("日本限時優惠！刷卡滿 ¥10,000 回饋 5%"));
        assertFalse(PaymentParser.isCardSpendSms("您的信用卡交易金額NT$1,280，OTP驗證碼：482913"));
    }

    // ───────── 多張卡 / 香港 / 大陸 / 微信 / 支付寶 ─────────

    @Test
    public void last4Formats() {
        assertEquals("1234", PaymentParser.findLast4("Visa ••1234"));
        assertEquals("5678", PaymentParser.findLast4("您的卡號末四碼5678消費"));
        assertEquals("8899", PaymentParser.findLast4("尾号8899的银联卡消费"));
        assertEquals("4321", PaymentParser.findLast4("Mastercard ending in 4321"));
        assertEquals("1111", PaymentParser.findLast4("卡號 ****1111"));
        assertNull(PaymentParser.findLast4("已付款 NT$1234"));
    }

    @Test
    public void hongKongCardDollarIsHkd() {
        String hk = "滙豐信用卡簽賬提示：Mastercard ••4321 於 7-ELEVEN 簽賬 $120.00";
        PaymentParser.Result tw = PaymentParser.parse(hk);
        assertEquals(120.0, tw.amount, 0.001);           // 沒提示：當台幣（舊行為）
        PaymentParser.Result r = PaymentParser.parse(hk, "HKD");
        assertNull(r.amount);
        assertEquals("HKD", r.currency);
        assertEquals(120.0, r.foreign, 0.001);
        assertEquals(PaymentParser.MASTER, r.card);
        assertTrue(r.keyword);
    }

    @Test
    public void explicitCurrencyBeatsHint() {
        PaymentParser.Result r = PaymentParser.parse("HK$58.00 at Maxim's", "CNY");
        assertEquals("HKD", r.currency);
        r = PaymentParser.parse("已付款 NT$85", "HKD");
        assertEquals(85.0, r.amount, 0.001);
        assertNull(r.currency);
    }

    @Test
    public void yenIsCnyWithHint() {
        assertEquals("JPY", PaymentParser.parse("¥550 at LAWSON").currency);
        PaymentParser.Result r = PaymentParser.parse("微信支付凭证 付款金额 ¥25.00 收款方：瑞幸咖啡", "CNY");
        assertEquals("CNY", r.currency);
        assertEquals(25.0, r.foreign, 0.001);
        assertEquals("瑞幸咖啡", r.merchant);
    }

    @Test
    public void alipaySimplified() {
        PaymentParser.Result r = PaymentParser.parse("你有一笔支出 36.50元 在全家便利店消费", "CNY");
        assertEquals("CNY", r.currency);
        assertEquals(36.5, r.foreign, 0.001);
        assertTrue(r.keyword);
        assertEquals("全家便利店", r.merchant);
    }

    @Test
    public void unionPaySmsRmb() {
        String sms = "【招商银行】您尾号8899的银联卡11月14日12:30消费人民币128.00元，商户：盒马鲜生";
        assertTrue(PaymentParser.isCardSpendSms(sms));
        PaymentParser.Result r = PaymentParser.parse(sms);
        assertEquals("CNY", r.currency);
        assertEquals(128.0, r.foreign, 0.001);
        assertEquals(Card.UNIONPAY, r.card);
        assertEquals("盒马鲜生", r.merchant);
    }

    @Test
    public void alipayPromoIgnored() {
        assertTrue(PaymentParser.looksLikePromo("恭喜你获得红包，立即领取"));
    }
}
