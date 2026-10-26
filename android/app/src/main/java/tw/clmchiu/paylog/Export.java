package tw.clmchiu.paylog;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 匯出：CSV 或 CSV + 發票照片 ZIP（報帳用）。 */
final class Export {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private Export() {
    }

    /** 只匯出 CSV 時，「照片」欄填張數。 */
    static Map<Long, String> photoCountsAsNames(Db db, List<Record> rows) {
        Map<Long, Integer> counts = db.photoCounts();
        Map<Long, String> out = new HashMap<>();
        for (Record r : rows) {
            Integer n = counts.get(r.id);
            if (n != null && n > 0) out.put(r.id, n + " 張");
        }
        return out;
    }

    /**
     * ZIP 內容：嗶記帳.csv + 發票照片/日期_金額_序號.jpg；CSV「照片」欄列出對應檔名。
     * @return 實際寫入的照片數
     */
    static int zip(Context c, Db db, List<Record> rows, OutputStream os) throws IOException {
        Map<Long, String> names = new HashMap<>();
        int count = 0;
        ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8);
        byte[] buf = new byte[16 * 1024];
        for (Record r : rows) {
            List<String> files = db.photos(r.id);
            StringBuilder list = new StringBuilder();
            int seq = 0;
            for (String f : files) {
                File src = Photos.file(c, f);
                if (!src.exists()) continue;
                seq++;
                String entry = "發票照片/" + Fmt.format(r.timeMillis, FILE_TS)
                        + "_" + Fmt.plainNumber(r.amount) + "元_" + r.id + "_" + seq + ".jpg";
                zos.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new FileInputStream(src)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
                count++;
                if (list.length() > 0) list.append("; ");
                list.append(entry);
            }
            if (list.length() > 0) names.put(r.id, list.toString());
        }
        zos.putNextEntry(new ZipEntry("嗶記帳.csv"));
        zos.write(MainActivity.Csv.build(rows, names).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.finish();
        zos.flush();
        return count;
    }
}
