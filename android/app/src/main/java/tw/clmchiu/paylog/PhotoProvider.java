package tw.clmchiu.paylog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * 極簡檔案提供者：只讓相機 App 把照片寫進 files/photos/ 的指定檔案。
 * 不對外公開（exported=false），只有收到臨時授權的 App 能存取單一檔案。
 */
public class PhotoProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 1) throw new FileNotFoundException(uri.toString());
        String name = seg.get(0);
        if (name.isEmpty() || name.contains("/") || name.contains("..") || !name.endsWith(".jpg")) {
            throw new FileNotFoundException(uri.toString());
        }
        return Photos.file(getContext(), name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        int m;
        if (mode.contains("w")) {
            m = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
            if (mode.contains("r")) m = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        } else {
            m = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
