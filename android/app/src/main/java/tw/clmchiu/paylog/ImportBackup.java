package tw.clmchiu.paylog;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 匯入備份：讀取本 App 匯出的 CSV 或 ZIP（表格 + 發票照片），還原消費紀錄與照片。
 * 以新增方式寫入，不會刪除現有資料；重複匯入同一份會產生重複紀錄。
 * 依欄位位置讀取前 13 欄；之後版本新增的欄位（例如「出差」）會略過。
 */
final class ImportBackup {

    static final class Result {
        int records;
        int photos;
        String error;
    }

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm");

    private ImportBackup() {
    }

    /** 從使用者選的檔案匯入；自動判斷是 ZIP 還是 CSV。 */
    static Result run(Context c, Uri uri) {
        Result r = new Result();
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                r.error = "無法開啟檔案";
                return r;
            }
            byte[] data = readAll(in);
            if (isZip(data)) {
                importZip(c, data, r);
            } else {
                importCsv(c, stripBom(new String(data, StandardCharsets.UTF_8)), null, r);
            }
        } catch (Exception e) {
            r.error = (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return r;
    }

    private static boolean isZip(byte[] d) {
        return d.length > 3 && d[0] == 'P' && d[1] == 'K';
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    /** ZIP：取出 CSV 與所有照片（以 zip 內路徑為 key），再交給 CSV 匯入。 */
    private static void importZip(Context c, byte[] data, Result r) throws IOException {
        String csv = null;
        Map<String, byte[]> photos = new HashMap<>();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8);
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if (e.isDirectory()) {
                zis.closeEntry();
                continue;
            }
            byte[] bytes = readEntry(zis);
            zis.closeEntry();
            String name = e.getName();
            if (name.toLowerCase().endsWith(".csv")) {
                csv = stripBom(new String(bytes, StandardCharsets.UTF_8));
            } else if (name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg")) {
                photos.put(name, bytes);
            }
        }
        if (csv == null) {
            r.error = "ZIP 內找不到 CSV 檔";
            return;
        }
        importCsv(c, csv, photos, r);
    }

    /**
     * 解析 CSV 並寫入資料庫。
     * @param photos zip 內照片（key = CSV「照片」欄列出的路徑）；純 CSV 匯入時為 null。
     */
    private static void importCsv(Context c, String csv, Map<String, byte[]> photos, Result r) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            r.error = "檔案沒有資料";
            return;
        }
        Db db = Db.get(c);
        int seq = 0;
        boolean headerSkipped = false;
        for (List<String> row : rows) {
            if (row.isEmpty()) continue;
            String first = row.get(0).trim();
            if (!headerSkipped && first.startsWith("日期")) {
                headerSkipped = true;
                continue;
            }
            if (first.isEmpty() && row.size() < 3) continue;

            Record rec = new Record();
            rec.timeMillis = toMillis(get(row, 0), get(row, 1));
            rec.amount = parseNum(get(row, 2));
            rec.card = get(row, 3);
            rec.category = get(row, 4);
            rec.note = get(row, 5);
            rec.merchant = get(row, 6);
            rec.address = get(row, 7);
            rec.lat = parseDoubleOrNaN(get(row, 8));
            rec.lng = parseDoubleOrNaN(get(row, 9));
            String src = get(row, 10);
            rec.sourceLabel = "手動".equals(src) ? "" : src;
            rec.pending = "待填寫".equals(get(row, 11));

            long id = db.insert(rec);
            r.records++;

            if (photos != null) {
                String photoCol = get(row, 12);
                if (!photoCol.isEmpty()) {
                    for (String token : photoCol.split(";")) {
                        String key = token.trim();
                        if (key.isEmpty()) continue;
                        byte[] bytes = photos.get(key);
                        if (bytes == null) continue; // 純張數（例如「3 張」）或找不到檔，略過
                        String fname = System.currentTimeMillis() + "_" + (seq++) + ".jpg";
                        try {
                            File out = Photos.file(c, fname);
                            try (OutputStream os = new FileOutputStream(out)) {
                                os.write(bytes);
                            }
                            db.addPhoto(id, fname);
                            r.photos++;
                        } catch (IOException ignore) {
                            // 單張失敗不影響整體匯入
                        }
                    }
                }
            }
        }
    }

    private static String get(List<String> row, int i) {
        return i < row.size() ? row.get(i).trim() : "";
    }

    private static long toMillis(String date, String time) {
        try {
            LocalDate d = LocalDate.parse(date, D);
            LocalTime t = (time == null || time.isEmpty()) ? LocalTime.MIDNIGHT : LocalTime.parse(time, T);
            return LocalDateTime.of(d, t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static double parseNum(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDoubleOrNaN(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ───────── CSV 解析（支援引號、逗號、換行、"" 跳脫） ─────────

    static List<List<String>> parseCsv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    cur.add(field.toString());
                    field.setLength(0);
                } else if (ch == '\r') {
                    // 忽略，交給 \n 收尾
                } else if (ch == '\n') {
                    cur.add(field.toString());
                    field.setLength(0);
                    rows.add(cur);
                    cur = new ArrayList<>();
                } else {
                    field.append(ch);
                }
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 讀取 ZipInputStream 目前這個 entry 的全部位元組（不關閉串流）。 */
    private static byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
