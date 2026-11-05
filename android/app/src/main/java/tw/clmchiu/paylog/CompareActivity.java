package tw.clmchiu.paylog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兩段期間比較：自訂兩個日期區間，並排顯示合計、筆數、各支付卡與各用途金額，
 * 下方可切換查看任一期間的每筆消費明細（點一下可編輯）。
 */
public class CompareActivity extends Activity {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final int MAX_ROWS = 500;

    /** [期間][0=起,1=迄]，迄日含當天。 */
    private final LocalDate[][] range = new LocalDate[2][2];
    private int detailOf = 0;

    private final Button[][] dateBtn = new Button[2][2];
    private LinearLayout summary, details;
    private Button tab0, tab1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        loadRanges();
        if (saved != null) detailOf = saved.getInt("detail", 0);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // 從明細編輯回來時更新
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("detail", detailOf);
    }

    // ───────────────────────── 日期 ─────────────────────────

    private void loadRanges() {
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate[] def = {
                thisMonth.minusMonths(1), thisMonth.minusDays(1),          // 期間一：上月
                thisMonth, thisMonth.plusMonths(1).minusDays(1)};         // 期間二：本月
        for (int i = 0; i < 4; i++) {
            long d = Prefs.compareDay(this, i);
            range[i / 2][i % 2] = d >= 0 ? LocalDate.ofEpochDay(d) : def[i];
        }
    }

    private void saveRanges() {
        Prefs.setCompareDays(this, new long[]{
                range[0][0].toEpochDay(), range[0][1].toEpochDay(),
                range[1][0].toEpochDay(), range[1][1].toEpochDay()});
    }

    private long startMillis(int p) {
        return range[p][0].atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private long endMillis(int p) {
        return range[p][1].plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void pickDate(final int p, final int which) {
        LocalDate cur = range[p][which];
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker v, int y, int m, int d) {
                range[p][which] = LocalDate.of(y, m + 1, d);
                // 起日晚於迄日時自動對調
                if (range[p][0].isAfter(range[p][1])) {
                    LocalDate t = range[p][0];
                    range[p][0] = range[p][1];
                    range[p][1] = t;
                }
                saveRanges();
                refresh();
            }
        }, cur.getYear(), cur.getMonthValue() - 1, cur.getDayOfMonth()).show();
    }

    private void quickPick(final int p) {
        final LocalDate today = LocalDate.now();
        final LocalDate month = today.withDayOfMonth(1);
        final LocalDate week = today.with(DayOfWeek.MONDAY);
        final String[] names = {"本月", "上月", "本週", "上週", "今年", "去年", "近 30 天"};
        new AlertDialog.Builder(this)
                .setTitle("期間" + (p == 0 ? "一" : "二") + " 快速選擇")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        LocalDate a, b;
                        switch (w) {
                            case 0: a = month; b = month.plusMonths(1).minusDays(1); break;
                            case 1: a = month.minusMonths(1); b = month.minusDays(1); break;
                            case 2: a = week; b = week.plusDays(6); break;
                            case 3: a = week.minusWeeks(1); b = week.minusDays(1); break;
                            case 4: a = today.withDayOfYear(1); b = a.plusYears(1).minusDays(1); break;
                            case 5: a = today.withDayOfYear(1).minusYears(1); b = today.withDayOfYear(1).minusDays(1); break;
                            default: a = today.minusDays(29); b = today; break;
                        }
                        range[p][0] = a;
                        range[p][1] = b;
                        saveRanges();
                        refresh();
                    }
                })
                .show();
    }

    // ───────────────────────── 畫面 ─────────────────────────

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        int pad = Ui.dp(this, 16);
        scroll.setPadding(pad, pad, pad, Ui.dp(this, 24));

        LinearLayout col = Ui.vbox(this);
        scroll.addView(col, Ui.matchWrap());

        col.addView(Ui.text(this, "兩段期間比較", 24, true));
        TextView sub = Ui.text(this, "選兩段日期，比較支出並查看每筆明細（只計已填寫的紀錄）", 13, false);
        sub.setTextColor(Ui.muted(this));
        col.addView(sub);

        for (int p = 0; p < 2; p++) col.addView(rangeCard(p), Ui.topMargin(this, 12));

        summary = Ui.vbox(this);
        Ui.card(summary);
        col.addView(summary, Ui.topMargin(this, 12));

        col.addView(Ui.label(this, "消費明細"));
        LinearLayout tabs = Ui.hbox(this);
        tab0 = Ui.ghostButton(this, "期間一");
        tab1 = Ui.ghostButton(this, "期間二");
        tab0.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detailOf = 0;
                refresh();
            }
        });
        tab1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detailOf = 1;
                refresh();
            }
        });
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.rightMargin = Ui.dp(this, 8);
        tabs.addView(tab0, lp);
        tabs.addView(tab1, Ui.weight(1));
        col.addView(tabs, Ui.matchWrap());

        details = Ui.vbox(this);
        col.addView(details, Ui.topMargin(this, 8));

        Ui.applyInsets(this, scroll);
        return scroll;
    }

    private View rangeCard(final int p) {
        LinearLayout c = Ui.vbox(this);
        Ui.card(c);
        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, "期間" + (p == 0 ? "一" : "二"), 15, true);
        t.setTextColor(Ui.brand(this));
        head.addView(t, Ui.weight(1));
        TextView quick = Ui.text(this, "快速選擇", 13, true);
        quick.setTextColor(Ui.brand(this));
        quick.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        quick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                quickPick(p);
            }
        });
        head.addView(quick);
        c.addView(head, Ui.matchWrap());

        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int w = 0; w < 2; w++) {
            final int which = w;
            Button b = Ui.ghostButton(this, "");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickDate(p, which);
                }
            });
            dateBtn[p][w] = b;
            row.addView(b, Ui.weight(1));
            if (w == 0) {
                TextView to = Ui.text(this, "至", 14, false);
                to.setTextColor(Ui.muted(this));
                to.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
                row.addView(to);
            }
        }
        c.addView(row, Ui.topMargin(this, 8));
        return c;
    }

    private static final class Stats {
        double total;
        int count, pending;
        final Map<String, Double> byCard = new LinkedHashMap<>();
        final Map<String, Double> byCat = new LinkedHashMap<>();
        List<Record> rows;
    }

    private Stats stats(int p) {
        Stats s = new Stats();
        s.rows = Db.get(this).listRange(startMillis(p), endMillis(p));
        for (Record r : s.rows) {
            if (r.pending) {
                s.pending++;
                continue;
            }
            s.total += r.amount;
            s.count++;
            add(s.byCard, TextUtils.isEmpty(r.card) ? "未指定" : r.card, r.amount);
            add(s.byCat, TextUtils.isEmpty(r.category) ? "未分類" : r.category, r.amount);
        }
        return s;
    }

    private static void add(Map<String, Double> m, String k, double v) {
        Double o = m.get(k);
        m.put(k, (o == null ? 0 : o) + v);
    }

    private void refresh() {
        if (summary == null) return;
        for (int p = 0; p < 2; p++) {
            dateBtn[p][0].setText(range[p][0].format(D));
            dateBtn[p][1].setText(range[p][1].format(D));
        }
        Stats[] s = {stats(0), stats(1)};

        // ── 比較表 ──
        summary.removeAllViews();
        summary.addView(row3("", "期間一", "期間二", true, true));
        summary.addView(row3("天數", days(0) + " 天", days(1) + " 天", false, false));
        summary.addView(row3("筆數", s[0].count + " 筆", s[1].count + " 筆", false, false));
        summary.addView(row3("合計", Fmt.money(s[0].total), Fmt.money(s[1].total), true, false));
        summary.addView(row3("日均", Fmt.money(round(s[0].total / days(0))),
                Fmt.money(round(s[1].total / days(1))), false, false));

        double diff = s[1].total - s[0].total;
        String pct = s[0].total > 0 ? String.format(java.util.Locale.ROOT, "（%+.1f%%）", diff / s[0].total * 100) : "";
        TextView d = Ui.text(this, "期間二比期間一" + (diff >= 0 ? "多 " : "少 ")
                + Fmt.money(Math.abs(round(diff))) + pct, 14, true);
        d.setTextColor(diff > 0 ? Ui.warn(this) : Ui.brand(this));
        d.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        summary.addView(d);

        section("支付卡", s[0].byCard, s[1].byCard);
        section("用途", s[0].byCat, s[1].byCat);

        if (s[0].pending + s[1].pending > 0) {
            TextView w = Ui.text(this, "⚠ 期間一有 " + s[0].pending + " 筆、期間二有 " + s[1].pending
                    + " 筆尚未填寫，未計入合計", 12, false);
            w.setTextColor(Ui.warn(this));
            w.setPadding(0, Ui.dp(this, 10), 0, 0);
            summary.addView(w);
        }

        // ── 明細 ──
        tab0.setText("期間一（" + s[0].rows.size() + "）");
        tab1.setText("期間二（" + s[1].rows.size() + "）");
        styleTab(tab0, detailOf == 0);
        styleTab(tab1, detailOf == 1);
        details.removeAllViews();
        List<Record> rows = s[detailOf].rows;
        if (rows.isEmpty()) {
            TextView e = Ui.text(this, "這段期間沒有紀錄", 14, false);
            e.setTextColor(Ui.muted(this));
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            details.addView(e, Ui.matchWrap());
            return;
        }
        Map<Long, Integer> pc = Db.get(this).photoCounts();
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            Integer n = pc.get(rows.get(i).id);
            details.addView(detailRow(rows.get(i), n == null ? 0 : n), Ui.topMargin(this, 8));
        }
        if (rows.size() > MAX_ROWS) {
            TextView more = Ui.text(this, "只顯示最新 " + MAX_ROWS + " 筆；完整資料請用「匯出 CSV」", 12, false);
            more.setTextColor(Ui.muted(this));
            more.setPadding(0, Ui.dp(this, 10), 0, 0);
            details.addView(more);
        }
    }

    private long days(int p) {
        return ChronoUnit.DAYS.between(range[p][0], range[p][1]) + 1;
    }

    private static double round(double v) {
        return Math.round(v);
    }

    private void styleTab(Button b, boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 14));
        g.setStroke(Ui.dp(this, 1), Ui.brand(this));
        g.setColor(on ? Ui.brand(this) : 0x00000000);
        b.setBackground(g);
        b.setTextColor(on ? (Ui.dark(this) ? 0xFF0B1F1C : 0xFFFFFFFF) : Ui.brand(this));
    }

    private void section(String title, Map<String, Double> a, Map<String, Double> b) {
        List<String> keys = new ArrayList<>(a.keySet());
        for (String k : b.keySet()) if (!keys.contains(k)) keys.add(k);
        if (keys.isEmpty()) return;
        // 依兩期合計由大到小
        final Map<String, Double> sum = new LinkedHashMap<>();
        for (String k : keys) {
            sum.put(k, (a.containsKey(k) ? a.get(k) : 0) + (b.containsKey(k) ? b.get(k) : 0));
        }
        java.util.Collections.sort(keys, new java.util.Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                return Double.compare(sum.get(y), sum.get(x));
            }
        });
        TextView h = Ui.label(this, title);
        summary.addView(h);
        for (String k : keys) {
            Double va = a.get(k), vb = b.get(k);
            summary.addView(row3(k, Fmt.money(va == null ? 0 : va), Fmt.money(vb == null ? 0 : vb), false, false));
        }
    }

    private View row3(String k, String a, String b, boolean bold, boolean head) {
        LinearLayout r = Ui.hbox(this);
        r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        TextView tk = Ui.text(this, k, 14, false);
        tk.setTextColor(Ui.muted(this));
        tk.setSingleLine(true);
        tk.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(tk, Ui.weight(1.1f));
        for (String v : new String[]{a, b}) {
            TextView tv = Ui.text(this, v, head ? 13 : 15, bold);
            tv.setGravity(Gravity.END);
            if (head) tv.setTextColor(Ui.brand(this));
            r.addView(tv, Ui.weight(1.3f));
        }
        return r;
    }

    private View detailRow(final Record r, int photos) {
        LinearLayout box = Ui.vbox(this);
        Ui.card(box);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        LinearLayout line1 = Ui.hbox(this);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView when = Ui.text(this, Fmt.format(r.timeMillis, Fmt.FULL)
                + (TextUtils.isEmpty(r.card) ? "" : "  ·  " + r.card)
                + (photos > 0 ? "  ·  📎" + photos : ""), 13, false);
        when.setTextColor(Ui.muted(this));
        line1.addView(when, Ui.weight(1));
        line1.addView(Ui.text(this, r.amount > 0 ? Fmt.money(r.amount) : "NT$ —", 18, true));
        box.addView(line1, Ui.matchWrap());

        if (r.pending) {
            TextView t = Ui.text(this, "⚠ 待填寫 — 點此補填", 14, false);
            t.setTextColor(Ui.warn(this));
            t.setPadding(0, Ui.dp(this, 4), 0, 0);
            box.addView(t);
        } else {
            detailLine(box, "用途", r.category);
            detailLine(box, "備註", r.note);
            detailLine(box, "商家", r.merchant);
            detailLine(box, "地點", r.address);
            detailLine(box, "來源", TextUtils.isEmpty(r.sourceLabel) ? "手動" : r.sourceLabel);
        }
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(CompareActivity.this, RecordActivity.class)
                        .putExtra(RecordActivity.EXTRA_ID, r.id));
            }
        });
        return box;
    }

    private void detailLine(LinearLayout box, String k, String v) {
        if (TextUtils.isEmpty(v)) return;
        TextView t = Ui.text(this, k + "：" + v, 14, false);
        t.setPadding(0, Ui.dp(this, 3), 0, 0);
        box.addView(t);
    }
}
