package tw.clmchiu.paylog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * 填寫消費內容：
 * 使用者填 — 消費總值、支付卡（VISA / MASTER / 悠遊卡）、用途；
 * 自動帶入 — 日期、時間、地點（GPS 反查地址）、商家（由付款通知解析）。
 */
public class RecordActivity extends Activity {

    static final String EXTRA_ID = "record_id";
    private static final int REQ_LOCATION = 11;
    private static final long LOCATE_TIMEOUT_MS = 20_000L;

    private Record record;
    private boolean isNew;

    private EditText amountEt, noteEt, merchantEt, addressEt;
    private RadioGroup cardGroup;
    private Spinner categorySp;
    private Button dateBtn, timeBtn;
    private TextView locStatus;
    private String autoAddress = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private CancellationSignal locateCancel;
    private LocationListener legacyListener;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        long id = getIntent().getLongExtra(EXTRA_ID, 0);
        record = id > 0 ? Db.get(this).find(id) : null;
        if (record == null) {
            record = new Record();
            record.timeMillis = System.currentTimeMillis();
            record.pending = true;
            isNew = true;
        }
        if (saved != null) record.timeMillis = saved.getLong("ts", record.timeMillis);

        setContentView(buildUi());
        bind();

        // 只對新紀錄 / 待填寫紀錄自動定位；編輯舊紀錄時不覆蓋原地點
        if ((isNew || record.pending) && !record.hasLocation() && TextUtils.isEmpty(record.address)) {
            startLocate();
        } else if (TextUtils.isEmpty(record.address)) {
            addressEt.setHint("地點（選填）");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("ts", record.timeMillis);
    }

    @Override
    protected void onDestroy() {
        stopLocate();
        super.onDestroy();
    }

    // ───────────────────────── UI ─────────────────────────

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        int pad = Ui.dp(this, 20);
        scroll.setPadding(pad, pad, pad, pad);

        LinearLayout col = Ui.vbox(this);
        scroll.addView(col, Ui.matchWrap());

        TextView title = Ui.text(this, record.pending ? "記錄這筆消費" : "編輯消費", 24, true);
        col.addView(title);
        String sub = TextUtils.isEmpty(record.sourceLabel)
                ? "日期、時間、地點會自動帶入"
                : "偵測自 " + record.sourceLabel + " · 日期、時間、地點已自動帶入";
        TextView subTv = Ui.text(this, sub, 13, false);
        subTv.setTextColor(Ui.muted(this));
        col.addView(subTv);

        // ── 使用者填寫 ──
        LinearLayout fill = Ui.vbox(this);
        Ui.card(fill);
        col.addView(fill, Ui.topMargin(this, 16));

        fill.addView(Ui.label(this, "消費總值（NT$）"));
        amountEt = new EditText(this);
        amountEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountEt.setTextSize(28);
        amountEt.setHint("0");
        amountEt.setSelectAllOnFocus(true);
        fill.addView(amountEt, Ui.matchWrap());

        fill.addView(Ui.label(this, "支付卡"));
        cardGroup = new RadioGroup(this);
        cardGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < PaymentParser.CARDS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(PaymentParser.CARDS[i]);
            rb.setTag(PaymentParser.CARDS[i]);
            rb.setTextSize(16);
            cardGroup.addView(rb, new RadioGroup.LayoutParams(0,
                    RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        fill.addView(cardGroup, Ui.matchWrap());

        fill.addView(Ui.label(this, "用途"));
        categorySp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, Prefs.CATEGORIES);
        categorySp.setAdapter(ad);
        fill.addView(categorySp, Ui.matchWrap());

        noteEt = new EditText(this);
        noteEt.setHint("備註（選填），例如：午餐、出差計程車");
        noteEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        noteEt.setSingleLine(true);
        fill.addView(noteEt, Ui.topMargin(this, 4));

        // ── 自動帶入 ──
        LinearLayout auto = Ui.vbox(this);
        Ui.card(auto);
        col.addView(auto, Ui.topMargin(this, 12));

        TextView autoTitle = Ui.text(this, "自動帶入（可修改）", 13, true);
        autoTitle.setTextColor(Ui.brand(this));
        auto.addView(autoTitle);

        auto.addView(Ui.label(this, "日期 / 時間"));
        LinearLayout dt = Ui.hbox(this);
        dateBtn = Ui.ghostButton(this, "");
        timeBtn = Ui.ghostButton(this, "");
        LinearLayout.LayoutParams lpL = Ui.weight(3);
        lpL.rightMargin = Ui.dp(this, 8);
        dt.addView(dateBtn, lpL);
        dt.addView(timeBtn, Ui.weight(2));
        auto.addView(dt, Ui.matchWrap());
        dateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });
        timeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime();
            }
        });

        auto.addView(Ui.label(this, "商家"));
        merchantEt = new EditText(this);
        merchantEt.setHint("例如：7-ELEVEN");
        merchantEt.setSingleLine(true);
        auto.addView(merchantEt, Ui.matchWrap());

        LinearLayout locHead = Ui.hbox(this);
        locHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView locLabel = Ui.label(this, "地點");
        locHead.addView(locLabel, Ui.weight(1));
        locStatus = Ui.text(this, "", 12, false);
        locStatus.setTextColor(Ui.muted(this));
        locStatus.setPadding(0, Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 6));
        locHead.addView(locStatus);
        TextView relocate = Ui.text(this, "重新定位", 13, true);
        relocate.setTextColor(Ui.brand(this));
        relocate.setPadding(Ui.dp(this, 8), Ui.dp(this, 16), 0, Ui.dp(this, 6));
        relocate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoAddress = "";
                addressEt.setText("");
                startLocate();
            }
        });
        locHead.addView(relocate);
        auto.addView(locHead, Ui.matchWrap());

        addressEt = new EditText(this);
        addressEt.setHint("定位中…");
        addressEt.setMinLines(1);
        addressEt.setMaxLines(3);
        auto.addView(addressEt, Ui.matchWrap());

        // ── 按鈕 ──
        Button save = Ui.primaryButton(this, "儲存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        col.addView(save, Ui.topMargin(this, 20));

        if (!isNew) {
            Button del = Ui.ghostButton(this, record.pending ? "略過這筆" : "刪除");
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete();
                }
            });
            col.addView(del, Ui.topMargin(this, 10));
        }

        Ui.applyInsets(this, scroll);
        return scroll;
    }

    private void bind() {
        if (record.amount > 0) amountEt.setText(Fmt.plainNumber(record.amount));

        String card = !TextUtils.isEmpty(record.card) ? record.card
                : (isNew || record.pending ? Prefs.lastCard(this) : "");
        for (int i = 0; i < cardGroup.getChildCount(); i++) {
            View v = cardGroup.getChildAt(i);
            if (card.equals(v.getTag())) cardGroup.check(v.getId());
        }

        String cat = !TextUtils.isEmpty(record.category) ? record.category : Prefs.lastCategory(this);
        for (int i = 0; i < Prefs.CATEGORIES.length; i++) {
            if (Prefs.CATEGORIES[i].equals(cat)) categorySp.setSelection(i);
        }

        noteEt.setText(record.note);
        merchantEt.setText(record.merchant);
        addressEt.setText(record.address);
        if (!TextUtils.isEmpty(record.address)) addressEt.setHint("地址");
        refreshDateTime();

        if (record.amount <= 0) {
            amountEt.requestFocus();
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    InputMethodManager imm = getSystemService(InputMethodManager.class);
                    if (imm != null) imm.showSoftInput(amountEt, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 250);
        }
    }

    private void refreshDateTime() {
        dateBtn.setText(Fmt.format(record.timeMillis, Fmt.DATE));
        timeBtn.setText(Fmt.format(record.timeMillis, Fmt.TIME));
    }

    private LocalDateTime current() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timeMillis), ZoneId.systemDefault());
    }

    private void setCurrent(LocalDateTime t) {
        record.timeMillis = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        refreshDateTime();
    }

    private void pickDate() {
        final LocalDateTime t = current();
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker v, int y, int m, int d) {
                setCurrent(t.withYear(y).withMonth(m + 1).withDayOfMonth(d));
            }
        },
                t.getYear(), t.getMonthValue() - 1, t.getDayOfMonth()).show();
    }

    private void pickTime() {
        final LocalDateTime t = current();
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker v, int h, int m) {
                setCurrent(t.withHour(h).withMinute(m));
            }
        },
                t.getHour(), t.getMinute(), true).show();
    }

    // ───────────────────────── 儲存 ─────────────────────────

    private void save() {
        double amount;
        try {
            amount = Double.parseDouble(amountEt.getText().toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount <= 0) {
            amountEt.setError("請輸入消費總值");
            amountEt.requestFocus();
            return;
        }
        View checked = cardGroup.findViewById(cardGroup.getCheckedRadioButtonId());
        if (checked == null) {
            Toast.makeText(this, "請選擇支付卡", Toast.LENGTH_SHORT).show();
            return;
        }

        record.amount = Math.round(amount * 100) / 100.0;
        record.card = String.valueOf(checked.getTag());
        record.category = String.valueOf(categorySp.getSelectedItem());
        record.note = noteEt.getText().toString().trim();
        record.merchant = merchantEt.getText().toString().trim();
        record.address = addressEt.getText().toString().trim();
        record.pending = false;

        Db db = Db.get(this);
        if (record.id > 0) db.update(record);
        else db.insert(record);

        Prefs.remember(this, record.card, record.category);
        Notifier.cancel(this, record.id);
        Toast.makeText(this, "已記錄 " + Fmt.money(record.amount), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(record.pending ? "略過這筆付款，不記錄？" : "刪除這筆紀錄？")
                .setNegativeButton("取消", null)
                .setPositiveButton(record.pending ? "略過" : "刪除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Db.get(RecordActivity.this).delete(record.id);
                        Notifier.cancel(RecordActivity.this, record.id);
                        finish();
                    }
                })
                .show();
    }

    // ───────────────────────── 定位 ─────────────────────────

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocate() {
        if (!hasLocationPermission()) {
            locStatus.setText("需要定位權限");
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null) return;

        String provider = null;
        if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            provider = LocationManager.FUSED_PROVIDER;
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        }
        if (provider == null) {
            locStatus.setText("定位未開啟");
            addressEt.setHint("請手動輸入地點");
            return;
        }

        locStatus.setText("定位中…");
        addressEt.setHint("定位中…");

        // 先用最近一次位置（若在 2 分鐘內）立即顯示
        final Location last = freshestLastKnown(lm);
        if (last != null && System.currentTimeMillis() - last.getTime() < 2 * 60 * 1000L) {
            onLocation(last, false);
        }

        stopLocate();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locateCancel = new CancellationSignal();
                lm.getCurrentLocation(provider, locateCancel, getMainExecutor(), new java.util.function.Consumer<Location>() {
                    @Override
                    public void accept(Location loc) {
                        if (loc != null) onLocation(loc, true);
                        else if (last != null) onLocation(last, true);
                        else locStatus.setText("無法取得位置");
                    }
                });
            } else {
                legacyListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location loc) {
                        onLocation(loc, true);
                    }

                    @Override
                    public void onStatusChanged(String p, int s, Bundle e) {
                    }

                    @Override
                    public void onProviderEnabled(String p) {
                    }

                    @Override
                    public void onProviderDisabled(String p) {
                    }
                };
                requestSingleLegacy(lm, provider, legacyListener);
            }
        } catch (SecurityException e) {
            locStatus.setText("需要定位權限");
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if ("定位中…".contentEquals(locStatus.getText())) {
                    stopLocate();
                    locStatus.setText(record.hasLocation() ? "" : "定位逾時");
                    if (!record.hasLocation()) addressEt.setHint("請手動輸入地點");
                }
            }
        }, LOCATE_TIMEOUT_MS);
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private static void requestSingleLegacy(LocationManager lm, String provider, LocationListener l) {
        lm.requestSingleUpdate(provider, l, Looper.getMainLooper());
    }

    @SuppressWarnings("MissingPermission")
    private static Location freshestLastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    private void stopLocate() {
        if (locateCancel != null) {
            locateCancel.cancel();
            locateCancel = null;
        }
        if (legacyListener != null) {
            LocationManager lm = getSystemService(LocationManager.class);
            if (lm != null) lm.removeUpdates(legacyListener);
            legacyListener = null;
        }
    }

    private void onLocation(Location loc, boolean done) {
        if (isFinishing() || isDestroyed()) return;
        record.lat = loc.getLatitude();
        record.lng = loc.getLongitude();
        if (done) {
            stopLocate();
            locStatus.setText(String.format(Locale.ROOT, "±%.0fm", loc.getAccuracy()));
        }
        final double lat = loc.getLatitude(), lng = loc.getLongitude();
        if (!Geocoder.isPresent()) {
            setAutoAddress(String.format(Locale.ROOT, "%.5f, %.5f", lat, lng));
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = reverseGeocode(lat, lng);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        setAutoAddress(text);
                    }
                });
            }
        }, "paylog-geocode").start();
    }

    @SuppressWarnings("deprecation")
    private String reverseGeocode(double lat, double lng) {
        try {
            Geocoder g = new Geocoder(this, Locale.TRADITIONAL_CHINESE);
            List<Address> list = g.getFromLocation(lat, lng, 1);
            if (list != null && !list.isEmpty()) {
                Address a = list.get(0);
                String line = a.getAddressLine(0);
                if (!TextUtils.isEmpty(line)) return line;
            }
        } catch (Exception ignored) {
        }
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lng);
    }

    /** 只有在使用者沒有自行修改地址時才覆蓋。 */
    private void setAutoAddress(String text) {
        if (isFinishing() || isDestroyed()) return;
        String cur = addressEt.getText().toString().trim();
        if (cur.isEmpty() || cur.equals(autoAddress)) {
            addressEt.setText(text);
            autoAddress = text;
        }
        addressEt.setHint("地址");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            startLocate();
        } else {
            locStatus.setText("未授權定位");
            addressEt.setHint("請手動輸入地點");
        }
    }
}
