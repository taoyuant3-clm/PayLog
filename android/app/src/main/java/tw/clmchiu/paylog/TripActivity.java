package tw.clmchiu.paylog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 出差頁：以暖沙色背景與平常的主畫面區分，只列出這趟出差期間的消費與小計。
 * 這些消費同時仍在主畫面與每月合計中，總消費不受影響。
 */
public class TripActivity extends Activity {

    static final String EXTRA_TRIP = "trip_id";
    private static final int MAX_ROWS = 500;

    private long tripId;
    private LinearLayout col;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        tripId = getIntent().getLongExtra(EXTRA_TRIP, 0);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        int pad = Ui.dp(this, 16);
        scroll.setPadding(pad, pad, pad, Ui.dp(this, 24));
        col = Ui.vbox(this);
        scroll.addView(col, Ui.matchWrap());
        Ui.tripWindow(this, scroll);
        Ui.applyInsets(this, scroll);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // 從記帳 / 編輯回來時更新
    }

    private void refresh() {
        final Trip t = Db.get(this).findTrip(tripId);
        if (t == null) {
            finish();
            return;
        }
        col.removeAllViews();
        Db db = Db.get(this);
        int accent = Ui.tripAccent(this);

        // ── 標題 ──
        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "✈ " + t.displayName(), 24, true);
        title.setTextColor(accent);
        top.addView(title, Ui.weight(1));
        TextView edit = Ui.text(this, "編輯", 15, true);
        edit.setTextColor(accent);
        edit.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTrip(TripActivity.this, t, new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                });
            }
        });
        top.addView(edit);
        col.addView(top, Ui.matchWrap());

        LocalDate today = LocalDate.now();
        int dayIdx = t.dayIndex(today);
        String status = dayIdx > 0 ? "出差中 · 第 " + dayIdx + " / " + t.days() + " 天"
                : today.isBefore(t.start()) ? "尚未出發" : "已結束";
        TextView sub = Ui.text(this, t.rangeLabel() + "  ·  " + status, 13, false);
        sub.setTextColor(Ui.muted(this));
        col.addView(sub);

        // ── 小計 ──
        List<Record> rows = db.listRange(t.startMillis(), t.endMillis());
        Map<String, Double> byCard = db.totals(t.startMillis(), t.endMillis());
        Map<String, Double> byCat = db.categoryTotals(t.startMillis(), t.endMillis());
        Double totalObj = byCard.get("");
        double total = totalObj == null ? 0 : totalObj;
        int done = 0, pending = 0;
        for (Record r : rows) {
            if (r.pending) pending++;
            else done++;
        }

        LinearLayout sum = Ui.vbox(this);
        Ui.tripCard(sum);
        TextView k = Ui.text(this, "出差期間支出", 13, false);
        k.setTextColor(Ui.muted(this));
        sum.addView(k);
        sum.addView(Ui.text(this, Fmt.money(total), 34, true));
        // 日均：已結束以全程天數計；進行中以已過天數計
        int spanDays = dayIdx > 0 ? dayIdx : t.days();
        TextView avg = Ui.text(this, done + " 筆  ·  日均 " + Fmt.money(Math.round(total / Math.max(1, spanDays))), 13, false);
        avg.setTextColor(Ui.muted(this));
        sum.addView(avg);

        LinearLayout cards = Ui.hbox(this);
        for (String card : PaymentParser.CARDS) {
            Double v = byCard.get(card);
            LinearLayout cell = Ui.vbox(this);
            TextView ck = Ui.text(this, card, 12, false);
            ck.setTextColor(Ui.muted(this));
            cell.addView(ck);
            cell.addView(Ui.text(this, Fmt.money(v == null ? 0 : v), 15, true));
            cards.addView(cell, Ui.weight(1));
        }
        sum.addView(cards, Ui.topMargin(this, 10));

        if (!byCat.isEmpty()) {
            TextView h = Ui.text(this, "用途", 13, false);
            h.setTextColor(Ui.muted(this));
            h.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
            sum.addView(h);
            for (Map.Entry<String, Double> e : byCat.entrySet()) {
                LinearLayout r = Ui.hbox(this);
                r.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
                r.addView(Ui.text(this, TextUtils.isEmpty(e.getKey()) ? "未分類" : e.getKey(), 14, false), Ui.weight(1));
                r.addView(Ui.text(this, Fmt.money(e.getValue()), 14, true));
                sum.addView(r, Ui.matchWrap());
            }
        }
        if (pending > 0) {
            TextView p = Ui.text(this, "⚠ 有 " + pending + " 筆付款尚未填寫，未計入", 13, true);
            p.setTextColor(Ui.warn(this));
            p.setPadding(0, Ui.dp(this, 12), 0, 0);
            sum.addView(p);
        }
        TextView note = Ui.text(this, "這些消費同時列在主畫面，每月總支出照常計入。", 12, false);
        note.setTextColor(Ui.muted(this));
        note.setPadding(0, Ui.dp(this, 10), 0, 0);
        sum.addView(note);
        col.addView(sum, Ui.topMargin(this, 12));

        // ── 動作 ──
        Button add = Ui.tripButton(this, "＋ 記一筆（出差）");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(TripActivity.this, RecordActivity.class);
                // 不在出差期間時（事後補記），預設帶入出發當天中午，可在填寫頁改日期
                if (!t.contains(System.currentTimeMillis())) {
                    i.putExtra(RecordActivity.EXTRA_TIME, t.start().atTime(LocalTime.NOON)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                }
                startActivity(i);
            }
        });
        col.addView(add, Ui.topMargin(this, 12));

        // ── 明細 ──
        TextView lh = Ui.label(this, "出差消費明細（" + rows.size() + " 筆）");
        col.addView(lh);
        if (rows.isEmpty()) {
            TextView e = Ui.text(this, "這段期間還沒有消費紀錄。\n出差期間付款時自動記下的消費，會出現在這裡。", 14, false);
            e.setTextColor(Ui.muted(this));
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            col.addView(e, Ui.matchWrap());
            return;
        }
        Map<Long, Integer> pc = db.photoCounts();
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            Integer n = pc.get(rows.get(i).id);
            col.addView(row(rows.get(i), n == null ? 0 : n), Ui.topMargin(this, 8));
        }
        TextView hint = Ui.text(this, "點一下可編輯，長按可刪除", 12, false);
        hint.setTextColor(Ui.muted(this));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 16), 0, 0);
        col.addView(hint, Ui.matchWrap());
    }

    private View row(final Record r, int photos) {
        LinearLayout box = Ui.vbox(this);
        Ui.tripCard(box);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        LinearLayout line1 = Ui.hbox(this);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView when = Ui.text(this, Fmt.format(r.timeMillis, Fmt.SHORT)
                + (TextUtils.isEmpty(r.card) ? "" : "  ·  " + r.card)
                + (TextUtils.isEmpty(r.currency) ? "" : "  ·  " + PaymentParser.foreignLabel(r.currency, r.origAmount))
                + (photos > 0 ? "  ·  📎" + photos : ""), 13, false);
        when.setTextColor(Ui.muted(this));
        line1.addView(when, Ui.weight(1));
        line1.addView(Ui.text(this, r.amount > 0 ? Fmt.money(r.amount) : "NT$ —", 18, true));
        box.addView(line1, Ui.matchWrap());

        TextView detail = Ui.text(this, "", 14, false);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setPadding(0, Ui.dp(this, 4), 0, 0);
        if (r.pending) {
            detail.setText("⚠ 待填寫 — 點此補填");
            detail.setTextColor(Ui.warn(this));
        } else {
            StringBuilder sb = new StringBuilder();
            for (String s : new String[]{r.category, r.note, r.merchant, r.address}) {
                if (TextUtils.isEmpty(s)) continue;
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(s);
            }
            detail.setText(sb);
            detail.setTextColor(Ui.muted(this));
        }
        box.addView(detail, Ui.matchWrap());

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(TripActivity.this, RecordActivity.class)
                        .putExtra(RecordActivity.EXTRA_ID, r.id));
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(TripActivity.this)
                        .setTitle(Fmt.money(r.amount) + " · " + Fmt.format(r.timeMillis, Fmt.SHORT))
                        .setMessage("刪除這筆紀錄？（主畫面也會一併刪除）")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("刪除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Db.get(TripActivity.this).delete(r.id);
                                Notifier.cancel(TripActivity.this, r.id);
                                refresh();
                            }
                        })
                        .show();
                return true;
            }
        });
        return box;
    }

    // ───────────────────────── 新增 / 編輯出差 ─────────────────────────

    static void open(Context c, long tripId) {
        c.startActivity(new Intent(c, TripActivity.class).putExtra(EXTRA_TRIP, tripId));
    }

    /**
     * 新增（trip == null）或編輯出差：名稱、出發日、回程日。
     * @param onSaved 儲存或刪除後執行，可為 null
     */
    static void editTrip(final Activity a, Trip trip, final Runnable onSaved) {
        final boolean isNew = trip == null;
        final Trip t = isNew ? new Trip() : trip;
        if (isNew) {
            LocalDate today = LocalDate.now();
            t.startDay = today.toEpochDay();
            t.endDay = today.plusDays(9).toEpochDay();
        }
        final long[] days = {t.startDay, t.endDay};

        LinearLayout box = Ui.vbox(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);

        TextView nl = Ui.label(a, "名稱");
        nl.setPadding(0, 0, 0, Ui.dp(a, 4));
        box.addView(nl);
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setHint("例如：馬來西亞出差");
        name.setText(t.name);
        box.addView(name, Ui.matchWrap());

        box.addView(Ui.label(a, "出發日"));
        final Button startBtn = Ui.tripGhostButton(a, LocalDate.ofEpochDay(days[0]).format(Fmt.DATE));
        box.addView(startBtn, Ui.matchWrap());
        box.addView(Ui.label(a, "回程日（含當天）"));
        final Button endBtn = Ui.tripGhostButton(a, LocalDate.ofEpochDay(days[1]).format(Fmt.DATE));
        box.addView(endBtn, Ui.matchWrap());
        final TextView len = Ui.text(a, "", 13, false);
        len.setTextColor(Ui.muted(a));
        len.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(len);

        final Runnable sync = new Runnable() {
            @Override
            public void run() {
                if (days[0] > days[1]) {   // 出發日晚於回程日時自動對調
                    long x = days[0];
                    days[0] = days[1];
                    days[1] = x;
                }
                startBtn.setText(LocalDate.ofEpochDay(days[0]).format(Fmt.DATE));
                endBtn.setText(LocalDate.ofEpochDay(days[1]).format(Fmt.DATE));
                len.setText("共 " + (days[1] - days[0] + 1) + " 天；這段期間的消費會歸入這趟出差");
            }
        };
        sync.run();
        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                final int which = v == startBtn ? 0 : 1;
                LocalDate cur = LocalDate.ofEpochDay(days[which]);
                new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker dp, int y, int m, int d) {
                        days[which] = LocalDate.of(y, m + 1, d).toEpochDay();
                        sync.run();
                    }
                }, cur.getYear(), cur.getMonthValue() - 1, cur.getDayOfMonth()).show();
            }
        };
        startBtn.setOnClickListener(pick);
        endBtn.setOnClickListener(pick);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(isNew ? "新增出差" : "編輯出差")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", null);
        if (!isNew) {
            b.setNeutralButton("刪除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    new AlertDialog.Builder(a)
                            .setMessage("刪除出差「" + t.displayName() + "」？\n只刪除出差設定，期間的消費紀錄仍保留在主畫面。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("刪除", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w2) {
                                    Db.get(a).deleteTrip(t.id);
                                    if (onSaved != null) onSaved.run();
                                }
                            })
                            .show();
                }
            });
        }
        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                // 自行處理「儲存」，驗證失敗時不關閉對話框
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Trip clash = Db.get(a).overlappingTrip(days[0], days[1], t.id);
                        if (clash != null) {
                            Toast.makeText(a, "日期和「" + clash.displayName() + "」（" + clash.rangeLabel()
                                    + "）重疊，請調整", Toast.LENGTH_LONG).show();
                            return;
                        }
                        String n = name.getText().toString().replace('\n', ' ').trim();
                        if (n.length() > 30) n = n.substring(0, 30);
                        t.name = n;
                        t.startDay = days[0];
                        t.endDay = days[1];
                        Db.get(a).saveTrip(t);
                        dlg.dismiss();
                        if (onSaved != null) onSaved.run();
                        if (isNew) open(a, t.id);
                    }
                });
            }
        });
        dlg.show();
    }

    /** 出差清單：點一下開啟出差頁，下方可新增。 */
    static void showList(final Activity a, final Runnable onChanged) {
        final List<Trip> trips = Db.get(a).trips();
        if (trips.isEmpty()) {
            new AlertDialog.Builder(a)
                    .setTitle("✈ 出差")
                    .setMessage("設定一段出差日期（例如去馬來西亞 10 天），那段期間的消費會另外列在一個出差頁，"
                            + "用不同底色區分；主畫面和每月總支出照常計入。")
                    .setNegativeButton("關閉", null)
                    .setPositiveButton("＋ 新增出差", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            editTrip(a, null, onChanged);
                        }
                    })
                    .show();
            return;
        }
        Db db = Db.get(a);
        LocalDate today = LocalDate.now();
        String[] items = new String[trips.size()];
        for (int i = 0; i < trips.size(); i++) {
            Trip t = trips.get(i);
            Double sum = db.totals(t.startMillis(), t.endMillis()).get("");
            String tag = t.containsDay(today) ? "（出差中）" : today.isBefore(t.start()) ? "（未出發）" : "";
            items[i] = "✈ " + t.displayName() + tag + "\n" + t.rangeLabel() + "  ·  " + Fmt.money(sum == null ? 0 : sum);
        }
        new AlertDialog.Builder(a)
                .setTitle("出差")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        open(a, trips.get(which).id);
                    }
                })
                .setPositiveButton("＋ 新增出差", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        editTrip(a, null, onChanged);
                    }
                })
                .setNegativeButton("關閉", null)
                .show();
    }
}
