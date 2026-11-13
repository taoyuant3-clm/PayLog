package tw.clmchiu.paylog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

public class RateParserTest {

    @Test
    public void botTextSameLine() {
        String t = "幣別        匯率             現金        即期     遠期10天 ...\n"
                + "USD         本行買入     32.44000    32.79000    32.76800    32.69900    32.59900    32.50000    32.40000    32.30000    32.20000         本行賣出     33.11000    32.89000    32.86800    32.79900    32.69900    32.60000    32.50000    32.40000    32.30000\n"
                + "JPY         本行買入      0.20250     0.20790     0.20800 0 0 0 0 0 0   本行賣出      0.21160     0.21010     0.21000 0 0 0 0 0 0\n"
                + "VND         本行買入      0.00103     0.00000 0 0 0 0 0 0 0  本行賣出      0.00143     0.00000 0 0 0 0 0 0 0\n";
        Map<String, Double> r = RateUpdater.parseBot(t);
        assertEquals(32.89, r.get("USD"), 1e-9);    // 即期賣出
        assertEquals(0.2101, r.get("JPY"), 1e-9);
        assertEquals(0.00143, r.get("VND"), 1e-9);  // 沒即期 → 現金賣出
    }

    @Test
    public void botCsvAndSplitLines() {
        String csv = "﻿幣別,匯率,現金,即期,遠期10天\nHKD,本行買入,4.05000,4.15600,4.15100,0,0,0,0,0,0,本行賣出,4.24600,4.21600,4.21300,0,0,0,0,0,0\n";
        assertEquals(4.216, RateUpdater.parseBot(csv).get("HKD"), 1e-9);
        String split = "CNY 本行買入 4.32 4.41 0 0 0 0 0 0 0\n    本行賣出 4.49 4.46 0 0 0 0 0 0 0\n";
        assertEquals(4.46, RateUpdater.parseBot(split).get("CNY"), 1e-9);
    }

    @Test
    public void erApiFallback() throws Exception {
        Map<String, Double> r = RateUpdater.parseErApi("{\"result\":\"success\",\"base_code\":\"TWD\",\"rates\":{\"TWD\":1,\"JPY\":4.76,\"USD\":0.0305,\"KRW\":44.2}}");
        assertEquals(0.2101, r.get("JPY"), 1e-4);
        assertEquals(32.7869, r.get("USD"), 1e-3);
        assertEquals(0.0226244, r.get("KRW"), 1e-6);
    }

    @Test
    public void next7amIsLocal() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZonedDateTime before = ZonedDateTime.of(2026, 11, 14, 6, 30, 0, 0, tokyo);
        ZonedDateTime after = ZonedDateTime.of(2026, 11, 14, 7, 0, 0, 0, tokyo);
        assertEquals(ZonedDateTime.of(2026, 11, 14, 7, 0, 0, 0, tokyo).toInstant().toEpochMilli(), RateUpdater.next7am(before));
        assertEquals(ZonedDateTime.of(2026, 11, 15, 7, 0, 0, 0, tokyo).toInstant().toEpochMilli(), RateUpdater.next7am(after));
    }
}
