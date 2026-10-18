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
}
