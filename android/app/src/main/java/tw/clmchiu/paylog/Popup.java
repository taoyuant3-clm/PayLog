package tw.clmchiu.paylog;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 從背景直接打開填寫畫面。
 *
 * Android 10 起禁止背景 App 開啟畫面，例外之一是使用者授予
 * 「顯示在其他應用程式上層」權限。螢幕鎖定時改由全螢幕通知喚起（見 Notifier）。
 */
final class Popup {

    private Popup() {
    }

    static boolean canPopup(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** @return true 表示已直接彈出畫面 */
    static boolean show(Context c, long recordId) {
        if (!canPopup(c)) return false;
        PowerManager pm = c.getSystemService(PowerManager.class);
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        // 螢幕關閉或鎖定時交給全螢幕通知處理，避免畫面開在鎖定畫面後面
        if (pm != null && !pm.isInteractive()) return false;
        if (km != null && km.isKeyguardLocked()) return false;
        try {
            Intent i = new Intent(c, RecordActivity.class)
                    .putExtra(RecordActivity.EXTRA_ID, recordId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w("PayLog", "popup failed", e);
            return false;
        }
    }
}
