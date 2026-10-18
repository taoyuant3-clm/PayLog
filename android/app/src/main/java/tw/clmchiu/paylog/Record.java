package tw.clmchiu.paylog;

/** 一筆消費紀錄。 */
final class Record {
    long id;            // 0 = 尚未存入資料庫
    long timeMillis;    // 付款時間（自動帶入，可修改）
    double amount;      // 消費總值（NT$）
    String card = "";   // VISA / MASTER / 悠遊卡
    String category = "";
    String note = "";
    String merchant = "";   // 由付款通知解析出的商家（自動）
    String address = "";    // 由定位反查的地址（自動）
    double lat = Double.NaN;
    double lng = Double.NaN;
    String source = "";     // 觸發 App 的套件名稱；手動 = ""
    String sourceLabel = "";// 觸發 App 的顯示名稱，例如 Google 錢包
    boolean pending;        // true = 已偵測付款、尚未填寫
    String dedupKey = "";

    boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lng);
    }
}
