package tw.clmchiu.paylog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 主畫面：本月合計、權限狀態、消費清單、匯出。 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 21;
    private static final int REQ_SMS = 23;
    private static final int REQ_EXPORT = 22;

    private ListView list;
    private LinearLayout header;
    private TextView empty;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Notifier.ensureChannel(this);

        list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        int pad = Ui.dp(this, 16);
        list.setPadding(pad, pad, pad, Ui.dp(this, 24));

        header = Ui.vbox(this);
        list.addHeaderView(header, null, false);
        empty = Ui.text(this, "", 14, false);
        empty.setTextColor(Ui.muted(this));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pad, Ui.dp(this, 32), pad, Ui.dp(this, 32));
        list.addFooterView(empty, null, false);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Record r = itemAt(pos);
                if (r != null) openRecord(r.id);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                final Record r = itemAt(pos);
                if (r == null) return false;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(Fmt.money(r.amount) + " · " + Fmt.format(r.timeMillis, Fmt.SHORT))
                        .setMessage("刪除這筆紀錄？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("刪除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Db.get(MainActivity.this).delete(r.id);
                                Notifier.cancel(MainActivity.this, r.id);
                                refresh();
                            }
                        })
                        .show();
                return true;
            }
        });

        Ui.applyInsets(this, list);
        setContentView(list);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Record itemAt(int listPosition) {
        int i = listPosition - list.getHeaderViewsCount();
        return i >= 0 && i < adapter.items.size() ? adapter.items.get(i) : null;
    }

    private void openRecord(long id) {
        Intent i = new Intent(this, RecordActivity.class);
        if (id > 0) i.putExtra(RecordActivity.EXTRA_ID, id);
        startActivity(i);
    }

    // ───────────────────────── 畫面 ─────────────────────────

    private void refresh() {
        Db db = Db.get(this);
        adapter.items = db.list(500);
        adapter.photoCounts = db.photoCounts();
        adapter.notifyDataSetChanged();
        empty.setText(adapter.items.isEmpty()
                ? "還沒有紀錄。\n開啟付款偵測後，用手機感應付款或網購收到驗證碼簡訊，就會自動彈出填寫畫面。"
                : "長按紀錄可刪除");
        buildHeader(db);
    }

    private void buildHeader(Db db) {
        header.removeAllViews();

        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, getString(R.string.app_name), 26, true);
        top.addView(title, Ui.weight(1));
        TextView settings = Ui.text(this, "設定", 15, true);
        settings.setTextColor(Ui.brand(this));
        settings.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        top.addView(settings);
        header.addView(top, Ui.matchWrap());

        // 權限提示
        if (!listenerEnabled(this)) {
            header.addView(banner("付款偵測尚未開啟",
                    "開啟後，手機感應付款（Google 錢包、Samsung Wallet、銀行 App 通知）會自動跳出記帳提醒。",
                    "開啟付款偵測", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            askListenerAccess();
                        }
                    }), Ui.topMargin(this, 12));
        } else if (!notificationsAllowed()) {
            header.addView(banner("通知被關閉",
                    "偵測到付款後需要通知才能提醒你填寫。",
                    "允許通知", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openNotificationSettings();
                        }
                    }), Ui.topMargin(this, 12));
        } else if (Prefs.autoPopup(this) && !Popup.canPopup(this)) {
            header.addView(banner("尚未允許自動彈出",
                    "付款後要直接彈出填寫畫面，請允許嗶記帳「顯示在其他應用程式上層」。",
                    "允許自動彈出", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openOverlaySettings();
                        }
                    }), Ui.topMargin(this, 12));
        } else if (!Notifier.canFullScreen(this)) {
            header.addView(banner("螢幕鎖定時無法彈出",
                    "請允許「全螢幕通知」，手機鎖定時付款也能直接亮屏顯示填寫畫面。",
                    "允許全螢幕通知", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openFullScreenSettings();
                        }
                    }), Ui.topMargin(this, 12));
        } else if (Prefs.smsEnabled(this) && !hasSmsPermission(this)) {
            header.addView(banner("網購驗證簡訊偵測",
                    "允許讀取「收到的簡訊」，網購時收到銀行驗證碼簡訊就會自動記一筆（只辨識驗證碼簡訊，不儲存簡訊內容）。",
                    "允許簡訊偵測", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, REQ_SMS);
                        }
                    }), Ui.topMargin(this, 12));
        }

        // 本月合計
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        ZoneId z = ZoneId.systemDefault();
        long from = first.atStartOfDay(z).toInstant().toEpochMilli();
        long to = first.plusMonths(1).atStartOfDay(z).toInstant().toEpochMilli();
        Map<String, Double> totals = db.totals(from, to);

        LinearLayout sum = Ui.vbox(this);
        Ui.card(sum);
        TextView m = Ui.text(this, first.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")) + " 支出", 13, false);
        m.setTextColor(Ui.muted(this));
        sum.addView(m);
        Double total = totals.get("");
        sum.addView(Ui.text(this, Fmt.money(total == null ? 0 : total), 34, true));

        LinearLayout cards = Ui.hbox(this);
        for (String card : PaymentParser.CARDS) {
            Double v = totals.get(card);
            LinearLayout cell = Ui.vbox(this);
            TextView k = Ui.text(this, card, 12, false);
            k.setTextColor(Ui.muted(this));
            cell.addView(k);
            cell.addView(Ui.text(this, Fmt.money(v == null ? 0 : v), 15, true));
            cards.addView(cell, Ui.weight(1));
        }
        sum.addView(cards, Ui.topMargin(this, 10));

        int pending = db.pendingCount();
        if (pending > 0) {
            TextView p = Ui.text(this, "⚠ 有 " + pending + " 筆付款尚未填寫", 13, true);
            p.setTextColor(Ui.warn(this));
            p.setPadding(0, Ui.dp(this, 12), 0, 0);
            sum.addView(p);
        }
        header.addView(sum, Ui.topMargin(this, 12));

        // 動作
        LinearLayout actions = Ui.hbox(this);
        Button add = Ui.primaryButton(this, "＋ 記一筆");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRecord(0);
            }
        });
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.rightMargin = Ui.dp(this, 10);
        actions.addView(add, lp);
        Button export = Ui.ghostButton(this, "匯出 CSV");
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
        actions.addView(export, Ui.weight(1));
        header.addView(actions, Ui.topMargin(this, 12));

        if (!adapter.items.isEmpty()) {
            header.addView(Ui.label(this, "最近紀錄"));
        }
    }

    private View banner(String title, String body, String action, View.OnClickListener l) {
        LinearLayout b = Ui.vbox(this);
        Ui.card(b);
        TextView t = Ui.text(this, title, 16, true);
        t.setTextColor(Ui.warn(this));
        b.addView(t);
        TextView d = Ui.text(this, body, 14, false);
        d.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        b.addView(d);
        Button btn = Ui.primaryButton(this, action);
        btn.setOnClickListener(l);
        b.addView(btn, Ui.matchWrap());
        return b;
    }

    private final class Adapter extends BaseAdapter {
        List<Record> items = new ArrayList<>();
        Map<Long, Integer> photoCounts = new java.util.HashMap<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context c = MainActivity.this;
            Row row;
            if (convertView != null && convertView.getTag() instanceof Row) {
                row = (Row) convertView.getTag();
            } else {
                row = new Row(c);
            }
            Record rec = items.get(position);
            Integer pc = photoCounts.get(rec.id);
            row.bind(rec, pc == null ? 0 : pc);
            return row.root;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final TextView when, amount, detail;

        Row(Context c) {
            LinearLayout outer = Ui.vbox(c);
            outer.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            LinearLayout box = Ui.vbox(c);
            Ui.card(box);
            box.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
            LinearLayout line1 = Ui.hbox(c);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            when = Ui.text(c, "", 13, false);
            when.setTextColor(Ui.muted(c));
            line1.addView(when, Ui.weight(1));
            amount = Ui.text(c, "", 18, true);
            line1.addView(amount);
            box.addView(line1, Ui.matchWrap());
            detail = Ui.text(c, "", 14, false);
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setPadding(0, Ui.dp(c, 4), 0, 0);
            box.addView(detail, Ui.matchWrap());
            outer.addView(box, Ui.matchWrap());
            root = outer;
            root.setTag(this);
        }

        void bind(Record r, int photoCount) {
            Context c = root.getContext();
            String card = TextUtils.isEmpty(r.card) ? "" : "  ·  " + r.card;
            String pics = photoCount > 0 ? "  ·  📎" + photoCount : "";
            when.setText(Fmt.format(r.timeMillis, Fmt.SHORT) + card + pics);
            amount.setText(r.amount > 0 ? Fmt.money(r.amount) : "NT$ —");
            if (r.pending) {
                detail.setText("⚠ 待填寫 — 點此補填" + (TextUtils.isEmpty(r.sourceLabel) ? "" : "（" + r.sourceLabel + "）"));
                detail.setTextColor(Ui.warn(c));
            } else {
                List<String> parts = new ArrayList<>();
                if (!TextUtils.isEmpty(r.category)) parts.add(r.category);
                if (!TextUtils.isEmpty(r.note)) parts.add(r.note);
                if (!TextUtils.isEmpty(r.merchant)) parts.add(r.merchant);
                if (!TextUtils.isEmpty(r.address)) parts.add(r.address);
                detail.setText(TextUtils.join("  ·  ", parts));
                detail.setTextColor(Ui.muted(c));
            }
        }
    }

    // ───────────────────────── 權限 ─────────────────────────

    static boolean listenerEnabled(Context c) {
        ComponentName cn = new ComponentName(c, PaymentListenerService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) return nm.isNotificationListenerAccessGranted(cn);
        }
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private boolean notificationsAllowed() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    /** Google Play 要求的「顯著揭露」：說明讀取通知的用途後才導向系統設定。 */
    private void askListenerAccess() {
        new AlertDialog.Builder(this)
                .setTitle("需要「通知存取權」")
                .setMessage("嗶記帳會讀取 Google 錢包、Samsung Wallet、銀行及電子支付 App 的通知，"
                        + "只用來判斷你是否剛完成一筆付款，並擷取金額、卡別與商家，以便跳出記帳提醒。\n\n"
                        + "• 不會讀取聊天、Email 等 App 的通知；簡訊只辨識網購驗證碼\n"
                        + "• 所有資料只存在你的手機，不會上傳或分享\n"
                        + "• 可隨時在系統設定中關閉\n\n"
                        + "下一步請在清單中找到「嗶記帳」並開啟。")
                .setNegativeButton("暫不", null)
                .setPositiveButton("同意並前往設定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setDisclosed(MainActivity.this);
                        openListenerSettings();
                    }
                })
                .show();
    }

    private void openListenerSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(this, PaymentListenerService.class).flattenToString());
                startActivity(i);
                return;
            }
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "請至 設定 → 通知 → 裝置與應用程式通知 開啟嗶記帳", Toast.LENGTH_LONG).show();
        }
    }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    static boolean hasSmsPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
        Toast.makeText(this, "請開啟「允許顯示在其他應用程式上層」", Toast.LENGTH_LONG).show();
    }

    private void openFullScreenSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
        } catch (ActivityNotFoundException ignored) {
        }
        openNotificationSettings();
    }

    // ───────────────────────── 設定 ─────────────────────────

    private void showSettings() {
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        Switch all = new Switch(this);
        all.setText("也偵測銀行、電子支付等其他 App 的付款通知");
        all.setChecked(Prefs.watchAllApps(this));
        all.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setWatchAllApps(MainActivity.this, on);
            }
        });
        all.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(all, Ui.matchWrap());
        TextView hint = Ui.text(this, "Google 錢包與 Samsung Wallet 一律偵測；聊天、Email 一律不偵測。", 12, false);
        hint.setTextColor(Ui.muted(this));
        box.addView(hint);

        Switch pop = new Switch(this);
        pop.setText("付款後直接彈出填寫畫面");
        pop.setChecked(Prefs.autoPopup(this));
        pop.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setAutoPopup(MainActivity.this, on);
                if (on && !Popup.canPopup(MainActivity.this)) openOverlaySettings();
            }
        });
        pop.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        box.addView(pop, Ui.matchWrap());

        Switch sms = new Switch(this);
        sms.setText("網購驗證碼簡訊也觸發記帳");
        sms.setChecked(Prefs.smsEnabled(this));
        sms.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setSmsEnabled(MainActivity.this, on);
                if (on && !hasSmsPermission(MainActivity.this)) {
                    requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, REQ_SMS);
                }
            }
        });
        sms.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(sms, Ui.matchWrap());

        Switch otpAll = new Switch(this);
        otpAll.setText("任何驗證碼簡訊都算一筆（含登入、註冊）");
        otpAll.setChecked(Prefs.otpAll(this));
        otpAll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setOtpAll(MainActivity.this, on);
            }
        });
        otpAll.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(otpAll, Ui.matchWrap());
        TextView otpHint = Ui.text(this, "預設只算含金額或「交易／消費／網路」字樣的驗證碼；登入、註冊、綁定類不算。", 12, false);
        otpHint.setTextColor(Ui.muted(this));
        box.addView(otpHint);

        final int[] choices = Prefs.OTP_DELAY_CHOICES;
        final Button delay = Ui.ghostButton(this, delayLabel(Prefs.otpDelaySeconds(this)));
        delay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = Prefs.otpDelaySeconds(MainActivity.this);
                int idx = 0;
                for (int i = 0; i < choices.length; i++) if (choices[i] == cur) idx = i;
                int next = choices[(idx + 1) % choices.length];
                Prefs.setOtpDelaySeconds(MainActivity.this, next);
                delay.setText(delayLabel(next));
            }
        });
        box.addView(delay, Ui.topMargin(this, 10));

        int blocked = Prefs.blocked(this).size();
        final Button unblock = Ui.ghostButton(this, "重設已略過的 App（" + blocked + "）");
        unblock.setEnabled(blocked > 0);
        unblock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.clearBlocked(MainActivity.this);
                unblock.setText("重設已略過的 App（0）");
                unblock.setEnabled(false);
            }
        });
        box.addView(unblock, Ui.topMargin(this, 16));

        Button test = Ui.ghostButton(this, "測試：模擬感應付款（8 秒後彈出）");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                simulate(false);
            }
        });
        box.addView(test, Ui.topMargin(this, 10));

        Button testOtp = Ui.ghostButton(this, "測試：模擬網購驗證簡訊（8 秒後彈出）");
        testOtp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                simulate(true);
            }
        });
        box.addView(testOtp, Ui.topMargin(this, 10));

        Button cats = Ui.ghostButton(this, "管理用途（新增 / 刪除）");
        cats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manageCategories();
            }
        });
        box.addView(cats, Ui.topMargin(this, 10));

        Button access = Ui.ghostButton(this, "通知存取權設定");
        access.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openListenerSettings();
            }
        });
        box.addView(access, Ui.topMargin(this, 10));

        TextView about = Ui.text(this, "所有紀錄僅儲存在本機。版本 " + versionName(), 12, false);
        about.setTextColor(Ui.muted(this));
        about.setPadding(0, Ui.dp(this, 16), 0, 0);
        box.addView(about);

        new AlertDialog.Builder(this)
                .setTitle("設定")
                .setView(scrollOf(box))
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        refresh();
                    }
                })
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private android.widget.ScrollView scrollOf(View v) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(v);
        return sv;
    }

    /** 用途清單：點一下刪除，下方可新增或恢復預設。 */
    private void manageCategories() {
        final List<String> list = Prefs.categories(this);
        final String[] items = list.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("用途（點一下可刪除）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, final int which) {
                        final String name = items[which];
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage("刪除用途「" + name + "」？\n已記錄的消費不受影響。")
                                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w) {
                                        manageCategories();
                                    }
                                })
                                .setPositiveButton("刪除", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w) {
                                        Prefs.removeCategory(MainActivity.this, name);
                                        manageCategories();
                                    }
                                })
                                .show();
                    }
                })
                .setPositiveButton("＋ 新增", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        addCategoryDialog();
                    }
                })
                .setNeutralButton("恢復預設", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.resetCategories(MainActivity.this);
                        Toast.makeText(MainActivity.this, "已恢復預設用途", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("完成", null)
                .show();
    }

    private void addCategoryDialog() {
        final EditText et = new EditText(this);
        et.setHint("例如：停車費、孩子學費、公司報帳");
        et.setSingleLine(true);
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(et, Ui.matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("新增用途")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("新增", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = Prefs.addCategory(MainActivity.this, et.getText().toString());
                        if (!n.isEmpty()) Toast.makeText(MainActivity.this, "已新增「" + n + "」", Toast.LENGTH_SHORT).show();
                        manageCategories();
                    }
                })
                .show();
    }

    private static String delayLabel(int sec) {
        return "驗證簡訊彈出時間：" + (sec <= 0 ? "立即" : "收到後 " + sec + " 秒");
    }

    /** 模擬付款，8 秒後彈出；請先回到桌面或其他 App 確認會自動跳出。 */
    private void simulate(boolean otp) {
        String fake = otp
                ? "【測試銀行】您的信用卡於蝦皮購物網路交易金額NT$1,280，OTP驗證碼：123456，請勿告知他人。"
                : "Google 錢包\n已在 7-ELEVEN 付款 NT$85 · Visa ••1234";
        PaymentParser.Result p = PaymentParser.parse(fake);
        Record r = new Record();
        r.timeMillis = System.currentTimeMillis();
        r.amount = p.amount == null ? 0 : p.amount;
        r.card = p.card == null ? "" : p.card;
        r.merchant = p.merchant == null ? "" : p.merchant;
        r.sourceLabel = otp ? "測試：網購驗證簡訊" : "測試：感應付款";
        if (otp) {
            r.category = "購物";
            r.note = "網購";
        }
        r.pending = true;
        r.dedupKey = "test|" + r.timeMillis;
        Db.get(this).insert(r);
        Detector.schedulePopup(this, r.id, 8);
        Toast.makeText(this, "請在 8 秒內回到桌面，確認填寫畫面會自動彈出", Toast.LENGTH_LONG).show();
        refresh();
    }

    // ───────────────────────── 匯出 ─────────────────────────

    private static final int REQ_EXPORT_ZIP = 24;

    private void startExport() {
        new AlertDialog.Builder(this)
                .setTitle("匯出")
                .setItems(new String[]{"只匯出表格（CSV）", "表格 + 發票照片（ZIP）"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        createExportFile(which == 1);
                    }
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private void createExportFile(boolean zip) {
        String base = "嗶記帳_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(zip ? "application/zip" : "text/csv")
                .putExtra(Intent.EXTRA_TITLE, base + (zip ? ".zip" : ".csv"));
        try {
            startActivityForResult(i, zip ? REQ_EXPORT_ZIP : REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "找不到檔案管理員", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQ_EXPORT && requestCode != REQ_EXPORT_ZIP)
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final boolean zip = requestCode == REQ_EXPORT_ZIP;
        final Uri uri = data.getData();
        final Db db = Db.get(this);
        final List<Record> all = db.list(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new java.io.IOException("無法寫入");
                    if (zip) {
                        int n = Export.zip(MainActivity.this, db, all, os);
                        msg = "已匯出 " + all.size() + " 筆、" + n + " 張照片";
                    } else {
                        os.write(Csv.build(all, Export.photoCountsAsNames(db, all)).getBytes(StandardCharsets.UTF_8));
                        msg = "已匯出 " + all.size() + " 筆";
                    }
                } catch (Exception e) {
                    msg = "匯出失敗：" + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "paylog-export").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    /** CSV（含 BOM，Excel 開啟中文不亂碼）。 */
    static final class Csv {
        private Csv() {
        }

        /** @param photoNames 每筆紀錄寫進「照片」欄的文字（ZIP 內檔名或張數），可為 null */
        static String build(List<Record> rows, Map<Long, String> photoNames) {
            StringBuilder sb = new StringBuilder("\uFEFF");
            sb.append("日期,時間,消費總值,支付卡,用途,備註,商家,地點,緯度,經度,來源,狀態,照片\r\n");
            for (Record r : rows) {
                sb.append(Fmt.format(r.timeMillis, Fmt.DATE)).append(',')
                        .append(Fmt.format(r.timeMillis, Fmt.TIME)).append(',')
                        .append(Fmt.plainNumber(r.amount)).append(',')
                        .append(esc(r.card)).append(',')
                        .append(esc(r.category)).append(',')
                        .append(esc(r.note)).append(',')
                        .append(esc(r.merchant)).append(',')
                        .append(esc(r.address)).append(',')
                        .append(r.hasLocation() ? String.format(Locale.ROOT, "%.6f", r.lat) : "").append(',')
                        .append(r.hasLocation() ? String.format(Locale.ROOT, "%.6f", r.lng) : "").append(',')
                        .append(esc(TextUtils.isEmpty(r.sourceLabel) ? "手動" : r.sourceLabel)).append(',')
                        .append(r.pending ? "待填寫" : "已填寫").append(',')
                        .append(esc(photoNames == null || photoNames.get(r.id) == null ? "" : photoNames.get(r.id)))
                        .append("\r\n");
            }
            return sb.toString();
        }

        private static String esc(String s) {
            if (s == null) return "";
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                return "\"" + s.replace("\"", "\"\"") + "\"";
            }
            return s;
        }
    }
}
