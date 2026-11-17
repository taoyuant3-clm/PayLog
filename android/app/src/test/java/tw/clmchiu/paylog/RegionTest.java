package tw.clmchiu.paylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RegionTest {

    @Test
    public void hongKongCardInTaiwanWalletIsTwd() {
        // 1.4.5 的錯誤：香港卡（HKD）在台灣用 Google 錢包刷 $120 被當成港幣 120 → NT$492
        String hint = Region.pickHint(true, true, "HKD", null, "TWD", null, "Asia/Taipei".isEmpty() ? null : "TWD");
        assertNull(hint);
        PaymentParser.Result r = PaymentParser.parse("全家便利商店\n$120.00 • Visa ••2679", hint);
        assertEquals(120.0, r.amount, 0.001);
        assertNull(r.currency);
    }

    @Test
    public void taiwanCardInHongKongWalletIsHkd() {
        String hint = Region.pickHint(true, true, "TWD", null, "HKD", null, "HKD");
        assertEquals("HKD", hint);
        PaymentParser.Result r = PaymentParser.parse("Maxim's\n$58.00 • Mastercard ••8529", hint);
        assertNull(r.amount);
        assertEquals("HKD", r.currency);
        assertEquals(58.0, r.foreign, 0.001);
    }

    @Test
    public void walletFallsBackToTripThenZoneThenCard() {
        assertEquals("JPY", Region.pickHint(true, false, "", null, null, "JPY", "TWD"));   // 沒訊號：出差設定
        assertNull(Region.pickHint(true, true, "HKD", null, null, null, "TWD"));           // 只剩時區：台灣
        assertEquals("HKD", Region.pickHint(true, true, "HKD", null, null, null, null));   // 完全不知道：照卡片
    }

    @Test
    public void bankAppAndSmsStillUseCardCurrency() {
        assertEquals("HKD", Region.pickHint(false, true, "HKD", null, "TWD", null, "TWD"));
        assertNull(Region.pickHint(false, true, "TWD", null, "HKD", "HKD", "HKD"));
        assertEquals("HKD", Region.pickHint(false, false, "", null, "TWD", "HKD", "TWD"));  // 沒認出卡：出差設定
        assertNull(Region.pickHint(false, false, "", null, "TWD", null, "TWD"));
    }

    @Test
    public void superAppsKeepOwnCurrency() {
        assertEquals("CNY", Region.pickHint(false, false, "", "CNY", "TWD", null, "TWD"));
        assertEquals("HKD", Region.pickHint(false, true, "HKD", "CNY", "TWD", null, "TWD"));
        assertNull(Region.pickHint(false, true, "TWD", "CNY", "TWD", null, "TWD"));
    }

    @Test
    public void countryAndZone() {
        assertEquals("TWD", Region.currencyForCountry("tw"));
        assertEquals("HKD", Region.currencyForCountry("HK"));
        assertEquals("EUR", Region.currencyForCountry("de"));
        assertNull(Region.currencyForCountry(""));
        assertNull(Region.currencyForCountry("au"));
        assertEquals("TWD", Region.currencyForZone("Asia/Taipei"));
        assertNull(Region.currencyForZone("Australia/Sydney"));
    }

    @Test
    public void implausibleRatesRejected() {
        assertTrue(Prefs.plausibleRate("HKD", 4.216));
        assertTrue(Prefs.plausibleRate("JPY", 0.2101));
        assertFalse(Prefs.plausibleRate("HKD", 32.89));     // 抓到別的幣別 / 欄位
        assertFalse(Prefs.plausibleRate("JPY", 21.0));
        assertFalse(Prefs.plausibleRate("USD", 0.0305));    // 沒取倒數
    }

    @Test
    public void sgdNeedsBoundary() {
        assertEquals("SGD", PaymentParser.parse("Paid S$12.50 at Ya Kun").currency);
        PaymentParser.Result r = PaymentParser.parse("已付款 POINTS$120");
        assertNull(r.currency);
    }
}
