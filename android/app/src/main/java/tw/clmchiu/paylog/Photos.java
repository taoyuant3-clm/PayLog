package tw.clmchiu.paylog;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 發票照片的存放與壓縮。
 * 照片只存在 App 私有目錄 files/photos/，其他 App 讀不到，解除安裝即刪除。
 */
final class Photos {

    static final String DIR = "photos";
    /** 長邊上限：發票文字仍清楚，單張約 200–500 KB。 */
    private static final int MAX_SIDE = 1800;
    private static final int JPEG_QUALITY = 85;

    private Photos() {
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File file(Context c, String name) {
        return new File(dir(c), name);
    }

    /** 新的空白檔名，例如 20261027_153012_123.jpg */
    static String newName() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + ".jpg";
    }

    static void deleteFile(Context c, String name) {
        if (name == null || name.isEmpty() || name.contains("/")) return;
        File f = file(c, name);
        if (f.exists()) f.delete();
    }

    /** 相機拍完後：依 EXIF 轉正、縮小、重新壓縮，原檔覆蓋。 */
    static boolean normalize(Context c, String name) {
        File f = file(c, name);
        if (!f.exists() || f.length() == 0) return false;
        try {
            Bitmap b = decodeScaled(f.getAbsolutePath());
            if (b == null) return false;
            b = rotate(b, exifRotation(f.getAbsolutePath()));
            try (OutputStream os = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os);
            }
            b.recycle();
            return true;
        } catch (Exception e) {
            return f.length() > 0;
        }
    }

    /** 從相簿選的照片：複製進 App 並壓縮，回傳新檔名；失敗回傳 null。 */
    static String importFrom(Context c, Uri uri) {
        String name = newName();
        File out = file(c, name);
        ContentResolver cr = c.getContentResolver();
        try (InputStream in = cr.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException | SecurityException e) {
            out.delete();
            return null;
        }
        if (!normalize(c, name)) {
            out.delete();
            return null;
        }
        return name;
    }

    /** 縮圖（清單、填寫畫面用）。 */
    static Bitmap thumbnail(Context c, String name, int sizePx) {
        File f = file(c, name);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, sizePx);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    /** 全螢幕檢視用。 */
    static Bitmap full(Context c, String name, int maxSidePx) {
        return thumbnail(c, name, maxSidePx);
    }

    private static Bitmap decodeScaled(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = sample(o.outWidth, o.outHeight, MAX_SIDE);
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;
        int longSide = Math.max(b.getWidth(), b.getHeight());
        if (longSide > MAX_SIDE) {
            float s = (float) MAX_SIDE / longSide;
            Bitmap r = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    private static int sample(int w, int h, int target) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= target) s *= 2;
        return s;
    }

    private static int exifRotation(String path) {
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    private static Bitmap rotate(Bitmap b, int deg) {
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** 給相機 App 寫入用的 content:// 網址（見 PhotoProvider）。 */
    static Uri providerUri(Context c, String name) {
        return new Uri.Builder().scheme("content")
                .authority(c.getPackageName() + ".photos")
                .appendPath(name).build();
    }
}
