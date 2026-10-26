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
    private static final int VERSION = 2;
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

    int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM records WHERE pending=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
