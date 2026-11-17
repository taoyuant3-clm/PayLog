package tw.clmchiu.paylog;

import android.content.Context;
import android.telephony.TelephonyManager;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 「現在人在哪裡」→ 當地幣別。
 * 錢包（Google 錢包 / Samsung Wallet）的付款通知顯示的是「店家當地的貨幣」，不是卡片的結算幣別：
 * 香港卡在台灣刷 $120 是台幣 120，不是港幣 120。所以通知只寫「$」「元」時要看所在地。
 */
final class Region {

    private Region() {
    }

    private static final String EURO = "at be cy de ee es fi fr gr hr ie it lt lu lv mt nl pt si sk";

    /** 國碼（ISO 3166-1 alpha-2）→ 幣別；不支援的地區回傳 null。 */
    static String currencyForCountry(String iso) {
        if (iso == null) return null;
        String c = iso.trim().toLowerCase(Locale.ROOT);
        if (c.length() != 2) return null;
        switch (c) {
            case "tw": return "TWD";
            case "hk": return "HKD";
            case "cn": return "CNY";
            case "jp": return "JPY";
            case "us": return "USD";
            case "my": return "MYR";
            case "sg": return "SGD";
            case "kr": return "KRW";
            case "th": return "THB";
            case "gb": return "GBP";
            case "vn": return "VND";
            case "ph": return "PHP";
            default: return (" " + EURO + " ").contains(" " + c + " ") ? "EUR" : null;
        }
    }

    /** 手機時區 → 幣別（沒有行動網路時的備援）；無法判斷回傳 null。 */
    static String currencyForZone(String zoneId) {
        if (zoneId == null) return null;
        switch (zoneId) {
            case "Asia/Taipei": return "TWD";
            case "Asia/Hong_Kong": return "HKD";
            case "Asia/Shanghai": case "Asia/Chongqing": case "Asia/Harbin": case "Asia/Urumqi": return "CNY";
            case "Asia/Tokyo": return "JPY";
            case "Asia/Kuala_Lumpur": case "Asia/Kuching": return "MYR";
            case "Asia/Singapore": return "SGD";
            case "Asia/Seoul": return "KRW";
            case "Asia/Bangkok": return "THB";
            case "Europe/London": return "GBP";
            case "Asia/Ho_Chi_Minh": case "Asia/Saigon": return "VND";
            case "Asia/Manila": return "PHP";
            default: return null;
        }
    }

    /** 目前連上的行動網路所在國家的幣別（漫遊時是當地，不需要任何權限）；沒訊號回傳 null。 */
    static String networkCurrency(Context c) {
        try {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            return tm == null ? null : currencyForCountry(tm.getNetworkCountryIso());
        } catch (Throwable t) {
            return null;
        }
    }

    static String zoneCurrency() {
        try {
            return currencyForZone(ZoneId.systemDefault().getId());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * 通知只寫「$」「元」「¥」時，這個符號代表哪種貨幣。回傳 null = 台幣（不換算）。
     *
     * @param wallet     來源是 Google 錢包 / Samsung Wallet（顯示店家當地貨幣）
     * @param hasCard    有對到「我的卡片」
     * @param cardCur    該卡結算幣別（"" / TWD = 台幣）
     * @param appCur     App 本身的幣別（微信 = CNY、PayMe / 八達通 = HKD）；一般 App 為 null
     * @param networkCur 行動網路所在地幣別
     * @param tripCur    目前出差設定的幣別
     * @param zoneCur    手機時區的幣別
     */
    static String pickHint(boolean wallet, boolean hasCard, String cardCur, String appCur,
                           String networkCur, String tripCur, String zoneCur) {
        String cardForeign = hasCard && !empty(cardCur) && !"TWD".equals(cardCur) ? cardCur : null;
        if (appCur != null) {
            if (cardForeign != null) return cardForeign;
            return !hasCard || empty(cardCur) ? appCur : null;
        }
        if (wallet) {
            // 錢包通知 = 店家當地貨幣：先看人在哪（行動網路 → 出差設定 → 時區），不看卡片
            String place = !empty(networkCur) ? networkCur : !empty(tripCur) ? tripCur : zoneCur;
            if (!empty(place)) return "TWD".equals(place) ? null : place;
        }
        // 發卡銀行的 App / 簡訊（或錢包但不知道人在哪）：依卡片結算幣別
        if (cardForeign != null) return cardForeign;
        if (!hasCard && !empty(tripCur)) return "TWD".equals(tripCur) ? null : tripCur;
        return null;
    }
}
