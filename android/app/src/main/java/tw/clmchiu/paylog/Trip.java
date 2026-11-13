package tw.clmchiu.paylog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 一趟出差：一段離開原居地的日期區間（起迄日都含當天）。
 * 區間內的消費自動歸入這趟出差，另外顯示在出差頁；主畫面與總消費照常計入，不受影響。
 */
final class Trip {
    long id;            // 0 = 尚未存入資料庫
    String name = "";   // 例如：馬來西亞出差
    long startDay;      // LocalDate.toEpochDay()
    long endDay;        // 含當天
    String currency = "";   // 當地幣別（例如 HKD）；通知只寫「$」「元」時用；"" = 自動

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M/d");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy/M/d");

    LocalDate start() {
        return LocalDate.ofEpochDay(startDay);
    }

    LocalDate end() {
        return LocalDate.ofEpochDay(endDay);
    }

    long startMillis() {
        return start().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 迄日隔天 00:00（不含）。 */
    long endMillis() {
        return end().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    boolean contains(long millis) {
        return millis >= startMillis() && millis < endMillis();
    }

    boolean containsDay(LocalDate d) {
        long e = d.toEpochDay();
        return e >= startDay && e <= endDay;
    }

    int days() {
        return (int) (endDay - startDay + 1);
    }

    /** 今天是第幾天（1 起算）；不在期間內回傳 0。 */
    int dayIndex(LocalDate today) {
        if (!containsDay(today)) return 0;
        return (int) ChronoUnit.DAYS.between(start(), today) + 1;
    }

    /** 2026/11/10 – 11/19（10 天） */
    String rangeLabel() {
        LocalDate s = start(), e = end();
        String right = s.getYear() == e.getYear() ? e.format(MD) : e.format(YMD);
        return s.format(YMD) + " – " + right + "（" + days() + " 天）";
    }

    String displayName() {
        return name == null || name.trim().isEmpty() ? "出差" : name.trim();
    }
}
