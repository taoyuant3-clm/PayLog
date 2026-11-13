package tw.clmchiu.paylog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本機 SQLite 資料庫（資料只存在手機上）。 */
final class Db extends SQLiteOpenHelper {

    private static final String NAME = "paylog.db";
    private static final int VERSION = 4;
    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;

    private Db(Context c) {
        super(c, NAME, null, VERSION);
        ctx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"
                + "amount REAL NOT NULL DEFAULT 0,"
                + "card TEXT NOT NULL DEFAULT '',"
                + "category TEXT NOT NULL DEFAULT '',"
                + "note TEXT NOT NULL DEFAULT '',"
                + "merchant TEXT NOT NULL DEFAULT '',"
                + "address TEXT NOT NULL DEFAULT '',"
                + "lat REAL,"
                + "lng REAL,"
                + "source TEXT NOT NULL DEFAULT '',"
                + "source_label TEXT NOT NULL DEFAULT '',"
                + "pending INTEGER NOT NULL DEFAULT 0,"
                + "dedup TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_records_ts ON records(ts)");
        createPhotos(db);
        createTrips(db);
        addCurrency(db);
    }

    /** v4：外幣（出國刷卡時記下原幣與原始金額，amount 為換算後台幣）。 */
    private static void addCurrency(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE records ADD COLUMN currency TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE records ADD COLUMN orig_amount REAL NOT NULL DEFAULT 0");
    }

    /** v3：出差（一段日期區間，區間內的消費歸入該趟出差）。 */
    private static void createTrips(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "start_day INTEGER NOT NULL,"
                + "end_day INTEGER NOT NULL)");
    }

    /** v2：每筆消費可附多張照片（發票、收據）。檔案存在 App 私有目錄 files/photos/。 */
    private static void createPhotos(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE photos ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "record_id INTEGER NOT NULL,"
                + "file TEXT NOT NULL,"
                + "ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_photos_record ON photos(record_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createPhotos(db);
        if (oldVersion < 3) createTrips(db);
        if (oldVersion < 4) addCurrency(db);
    }

    // ───────── 出差 ─────────

    private static Trip readTrip(Cursor c) {
        Trip t = new Trip();
        t.id = c.getLong(0);
        t.name = c.getString(1);
        t.startDay = c.getLong(2);
        t.endDay = c.getLong(3);
        return t;
    }

    private static ContentValues tripValues(Trip t) {
        ContentValues v = new ContentValues();
        v.put("name", t.name == null ? "" : t.name.trim());
        v.put("start_day", t.startDay);
        v.put("end_day", t.endDay);
        return v;
    }

    /** 全部出差，由新到舊。 */
    List<Trip> trips() {
        List<Trip> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("trips",
                new String[]{"id", "name", "start_day", "end_day"},
                null, null, null, null, "start_day DESC, id DESC")) {
            while (c.moveToNext()) out.add(readTrip(c));
        }
        return out;
    }

    Trip findTrip(long id) {
        try (Cursor c = getReadableDatabase().query("trips",
                new String[]{"id", "name", "start_day", "end_day"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? readTrip(c) : null;
        }
    }

    long saveTrip(Trip t) {
        if (t.id > 0) {
            getWritableDatabase().update("trips", tripValues(t), "id=?", new String[]{String.valueOf(t.id)});
        } else {
            t.id = getWritableDatabase().insert("trips", null, tripValues(t));
        }
        return t.id;
    }

    /** 這個時間點所屬的出差；不在任何出差期間回傳 null。 */
    Trip tripAt(long millis) {
        for (Trip t : trips()) if (t.contains(millis)) return t;
        return null;
    }

    /** 只刪除出差設定；期間內的消費紀錄保留在主畫面。 */
    void deleteTrip(long id) {
        getWritableDatabase().delete("trips", "id=?", new String[]{String.valueOf(id)});
    }

    /** 與指定日期區間重疊的其他出差（排除 exceptId）；沒有則回傳 null。 */
    Trip overlappingTrip(long startDay, long endDay, long exceptId) {
        try (Cursor c = getReadableDatabase().query("trips",
                new String[]{"id", "name", "start_day", "end_day"},
                "id<>? AND start_day<=? AND end_day>=?",
                new String[]{String.valueOf(exceptId), String.valueOf(endDay), String.valueOf(startDay)},
                null, null, "start_day ASC", "1")) {
            return c.moveToFirst() ? readTrip(c) : null;
        }
    }

    /** 區間內已填寫紀錄依用途加總，金額大到小。 */
    Map<String, Double> categoryTotals(long fromMillis, long toMillis) {
        Map<String, Double> out = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount) FROM records WHERE pending=0 AND ts>=? AND ts<? "
                        + "GROUP BY category ORDER BY SUM(amount) DESC",
                new String[]{String.valueOf(fromMillis), String.valueOf(toMillis)})) {
            while (c.moveToNext()) out.put(c.getString(0), c.getDouble(1));
        }
        return out;
    }

    // ───────── 照片 ─────────

    void addPhoto(long recordId, String file) {
        ContentValues v = new ContentValues();
        v.put("record_id", recordId);
        v.put("file", file);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("photos", null, v);
    }

    void removePhoto(long recordId, String file) {
        getWritableDatabase().delete("photos", "record_id=? AND file=?",
                new String[]{String.valueOf(recordId), file});
    }

    List<String> photos(long recordId) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("photos", new String[]{"file"}, "record_id=?",
                new String[]{String.valueOf(recordId)}, null, null, "id ASC")) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** 每筆紀錄的照片數（主畫面清單用）。 */
    Map<Long, Integer> photoCounts() {
        Map<Long, Integer> out = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT record_id, COUNT(*) FROM photos GROUP BY record_id", null)) {
            while (c.moveToNext()) out.put(c.getLong(0), c.getInt(1));
        }
        return out;
    }

    private static ContentValues values(Record r) {
        ContentValues v = new ContentValues();
        v.put("ts", r.timeMillis);
        v.put("amount", r.amount);
        v.put("card", r.card);
        v.put("category", r.category);
        v.put("note", r.note);
        v.put("merchant", r.merchant);
        v.put("address", r.address);
        if (r.hasLocation()) {
            v.put("lat", r.lat);
            v.put("lng", r.lng);
        } else {
            v.putNull("lat");
            v.putNull("lng");
        }
        v.put("source", r.source);
        v.put("source_label", r.sourceLabel);
        v.put("pending", r.pending ? 1 : 0);
        v.put("dedup", r.dedupKey);
        v.put("currency", r.currency == null ? "" : r.currency);
        v.put("orig_amount", r.origAmount);
        return v;
    }

    private static Record read(Cursor c) {
        Record r = new Record();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.timeMillis = c.getLong(c.getColumnIndexOrThrow("ts"));
        r.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        r.card = c.getString(c.getColumnIndexOrThrow("card"));
        r.category = c.getString(c.getColumnIndexOrThrow("category"));
        r.note = c.getString(c.getColumnIndexOrThrow("note"));
        r.merchant = c.getString(c.getColumnIndexOrThrow("merchant"));
        r.address = c.getString(c.getColumnIndexOrThrow("address"));
        int iLat = c.getColumnIndexOrThrow("lat");
        int iLng = c.getColumnIndexOrThrow("lng");
        r.lat = c.isNull(iLat) ? Double.NaN : c.getDouble(iLat);
        r.lng = c.isNull(iLng) ? Double.NaN : c.getDouble(iLng);
        r.source = c.getString(c.getColumnIndexOrThrow("source"));
        r.sourceLabel = c.getString(c.getColumnIndexOrThrow("source_label"));
        r.pending = c.getInt(c.getColumnIndexOrThrow("pending")) != 0;
        r.dedupKey = c.getString(c.getColumnIndexOrThrow("dedup"));
        r.currency = c.getString(c.getColumnIndexOrThrow("currency"));
        r.origAmount = c.getDouble(c.getColumnIndexOrThrow("orig_amount"));
        return r;
    }

    long insert(Record r) {
        r.id = getWritableDatabase().insert("records", null, values(r));
        return r.id;
    }

    void update(Record r) {
        getWritableDatabase().update("records", values(r), "id=?", new String[]{String.valueOf(r.id)});
    }

    /** 刪除紀錄，連同照片檔。 */
    void delete(long id) {
        for (String f : photos(id)) Photos.deleteFile(ctx, f);
        getWritableDatabase().delete("photos", "record_id=?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete("records", "id=?", new String[]{String.valueOf(id)});
    }

    Record find(long id) {
        try (Cursor c = getReadableDatabase().query("records", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    /** 由新到舊。 */
    List<Record> list(int limit) {
        List<Record> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("records", null, null, null, null, null,
                "ts DESC, id DESC", limit > 0 ? String.valueOf(limit) : null)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /** 指定時間後相同 dedup key 的最新紀錄（避免同一筆付款被錢包和銀行 App 各跳一次）。 */
    Record findRecent(String dedupKey, long sinceMillis) {
        try (Cursor c = getReadableDatabase().query("records", null, "dedup=? AND ts>=?",
                new String[]{dedupKey, String.valueOf(sinceMillis)}, null, null, "ts DESC", "1")) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    /** 區間內已填寫紀錄依支付卡加總；key "" 代表總計。 */
    Map<String, Double> totals(long fromMillis, long toMillis) {
        Map<String, Double> out = new LinkedHashMap<>();
        double sum = 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT card, SUM(amount) FROM records WHERE pending=0 AND ts>=? AND ts<? "
                        + "GROUP BY card ORDER BY SUM(amount) DESC",
                new String[]{String.valueOf(fromMillis), String.valueOf(toMillis)})) {
            while (c.moveToNext()) {
                double v = c.getDouble(1);
                out.put(c.getString(0), v);
                sum += v;
            }
        }
        out.put("", sum);
        return out;
    }

    /** 區間內的紀錄（含待填寫），由新到舊。 */
    List<Record> listRange(long fromMillis, long toMillis) {
        List<Record> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("records", null, "ts>=? AND ts<?",
                new String[]{String.valueOf(fromMillis), String.valueOf(toMillis)},
                null, null, "ts DESC, id DESC")) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /** 欄位中曾輸入過的字詞（備註 / 商家 / 地點），依使用次數、最近使用排序，供輸入時聯想。 */
    List<String> pastWords(String column, int limit) {
        if (!"note".equals(column) && !"merchant".equals(column) && !"address".equals(column)) {
            throw new IllegalArgumentException(column);
        }
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT TRIM(" + column + ") AS w, COUNT(*) AS n, MAX(ts) AS t FROM records "
                        + "WHERE TRIM(" + column + ")<>'' GROUP BY w ORDER BY n DESC, t DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM records WHERE pending=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
