package tw.clmchiu.paylog;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 顯示格式。 */
final class Fmt {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Fmt() {
    }

    static String money(double v) {
        DecimalFormat f = new DecimalFormat(v == Math.rint(v) ? "#,##0" : "#,##0.##");
        return "NT$" + f.format(v);
    }

    static String plainNumber(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static String format(long millis, DateTimeFormatter f) {
        return f.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }
}
