package tw.clmchiu.paylog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 付款後跳出的「請填寫這筆消費」通知。 */
final class Notifier {

    static final String CHANNEL = "pay_prompt";
    /** 畫面已彈出時用的安靜頻道：只留在通知欄，不響不跳。 */
    static final String CHANNEL_QUIET = "pay_prompt_quiet";

    private Notifier() {
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null && nm.getNotificationChannel(CHANNEL_QUIET) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "付款後填寫提醒",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("偵測到手機感應付款後，提醒你填寫這筆消費");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
        NotificationChannel q = new NotificationChannel(CHANNEL_QUIET, "待填寫（已彈出畫面）",
                NotificationManager.IMPORTANCE_LOW);
        q.setDescription("填寫畫面已自動彈出時，留在通知欄備用");
        nm.createNotificationChannel(q);
    }

    static int notificationId(long recordId) {
        return (int) (recordId % Integer.MAX_VALUE);
    }

    static void prompt(Context c, Record r) {
        prompt(c, r, false);
    }

    /**
     * @param quiet true = 畫面已直接彈出，通知只留在通知欄備用，不響不跳
     */
    static void prompt(Context c, Record r, boolean quiet) {
        ensureChannel(c);

        Intent open = new Intent(c, RecordActivity.class)
                .putExtra(RecordActivity.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent piOpen = PendingIntent.getActivity(c, notificationId(r.id), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent skip = new Intent(c, SkipReceiver.class)
                .setAction(SkipReceiver.ACTION_SKIP)
                .putExtra(RecordActivity.EXTRA_ID, r.id);
        PendingIntent piSkip = PendingIntent.getBroadcast(c, notificationId(r.id), skip,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = r.amount > 0
                ? "剛剛付款 " + Fmt.money(r.amount) + "，記一下？"
                : "剛剛有一筆感應付款，記一下？";
        StringBuilder text = new StringBuilder("點此填寫用途");
        if (!r.card.isEmpty()) text.append(" · ").append(r.card);
        if (!r.merchant.isEmpty()) text.append(" · ").append(r.merchant);

        Notification.Builder b = new Notification.Builder(c, quiet ? CHANNEL_QUIET : CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pay)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(piOpen)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(r.timeMillis)
                .setShowWhen(true)
                .setColor(c.getColor(R.color.brand))
                .addAction(new Notification.Action.Builder(null, "填寫", piOpen).build())
                .addAction(new Notification.Action.Builder(null, "略過", piSkip).build());

        if (!quiet && canFullScreen(c)) {
            // 螢幕鎖定 / 關閉時直接亮屏顯示填寫畫面
            b.setFullScreenIntent(piOpen, true);
        }

        if (!r.source.isEmpty() && !Prefs.isWallet(r.source)) {
            Intent block = new Intent(c, SkipReceiver.class)
                    .setAction(SkipReceiver.ACTION_BLOCK)
                    .putExtra(RecordActivity.EXTRA_ID, r.id)
                    .putExtra(SkipReceiver.EXTRA_PKG, r.source);
            PendingIntent piBlock = PendingIntent.getBroadcast(c, notificationId(r.id) ^ 0x40000000, block,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "不再偵測此App", piBlock).build());
        }

        try {
            c.getSystemService(NotificationManager.class).notify(notificationId(r.id), b.build());
        } catch (SecurityException ignored) {
            // 使用者未允許通知；紀錄仍會以「待填寫」顯示在主畫面
        }
    }

    static boolean canFullScreen(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    static void cancel(Context c, long recordId) {
        c.getSystemService(NotificationManager.class).cancel(notificationId(recordId));
    }
}
