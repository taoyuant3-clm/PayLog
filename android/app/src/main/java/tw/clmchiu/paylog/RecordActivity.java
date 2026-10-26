package tw.clmchiu.paylog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import java.util.ArrayList;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.AdapterView;
import android.provider.MediaStore;
import android.net.Uri;
import android.content.Intent;
import android.content.ActivityNotFoundException;
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

    private static final int REQ_CAMERA = 31;
    private static final int REQ_GALLERY = 32;
    private static final String ADD_CATEGORY = "＋ 新增用途…";

    /** 照片：畫面上的全部、本次新增、本次移除（按「儲存」才生效）。 */
    private final ArrayList<String> photos = new ArrayList<>();
    private final ArrayList<String> addedPhotos = new ArrayList<>();
    private final ArrayList<String> removedPhotos = new ArrayList<>();
    private String captureName;
    private boolean didSave;
    private LinearLayout photoStrip;
    private TextView photoHint;

    private List<String> categoryItems = new ArrayList<>();
    private int categoryIndex;

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
        if (record.id > 0) photos.addAll(Db.get(this).photos(record.id));
        if (saved != null) {
            record.timeMillis = saved.getLong("ts", record.timeMillis);
            captureName = saved.getString("capture");
            restoreList(saved, "photos", photos);
            restoreList(saved, "added", addedPhotos);
            restoreList(saved, "removed", removedPhotos);
        }

        setContentView(buildUi());
        bind();
        refreshPhotos();

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
        out.putString("capture", captureName);
        out.putStringArrayList("photos", photos);
        out.putStringArrayList("added", addedPhotos);
        out.putStringArrayList("removed", removedPhotos);
    }

    private static void restoreList(Bundle b, String key, ArrayList<String> into) {
        ArrayList<String> v = b.getStringArrayList(key);
        if (v != null) {
            into.clear();
            into.addAll(v);
        }
    }

    @Override
    protected void onDestroy() {
        stopLocate();
        // 沒按儲存就離開：丟棄本次新拍的照片
        if (isFinishing() && !didSave) {
            for (String f : addedPhotos) Photos.deleteFile(this, f);
            if (captureName != null) Photos.deleteFile(this, captureName);
        }
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
        fill.addView(categorySp, Ui.matchWrap());
        categorySp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (ADD_CATEGORY.equals(categoryItems.get(pos))) {
                    categorySp.setSelection(categoryIndex);
                    askNewCategory();
                } else {
                    categoryIndex = pos;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        noteEt = new EditText(this);
        noteEt.setHint("備註（選填），例如：午餐、出差計程車");
        noteEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        noteEt.setSingleLine(true);
        fill.addView(noteEt, Ui.topMargin(this, 4));

        // ── 發票 / 收據照片 ──
        LinearLayout pic = Ui.vbox(this);
        Ui.card(pic);
        col.addView(pic, Ui.topMargin(this, 12));
        TextView picTitle = Ui.text(this, "發票 / 收據照片（選填）", 13, true);
        picTitle.setTextColor(Ui.brand(this));
        pic.addView(picTitle);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        photoStrip = Ui.hbox(this);
        hs.addView(photoStrip);
        pic.addView(hs, Ui.topMargin(this, 8));
        photoHint = Ui.text(this, "拍下發票，報帳、對帳時一目了然", 13, false);
        photoHint.setTextColor(Ui.muted(this));
        pic.addView(photoHint);

        LinearLayout picBtns = Ui.hbox(this);
        Button cam = Ui.ghostButton(this, "拍照");
        cam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePhoto();
            }
        });
        LinearLayout.LayoutParams lpc = Ui.weight(1);
        lpc.rightMargin = Ui.dp(this, 8);
        picBtns.addView(cam, lpc);
        Button gal = Ui.ghostButton(this, "從相簿選");
        gal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPhoto();
            }
        });
        picBtns.addView(gal, Ui.weight(1));
        pic.addView(picBtns, Ui.topMargin(this, 10));

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
        loadCategories(cat);

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
        record.category = selectedCategory();
        record.note = noteEt.getText().toString().trim();
        record.merchant = merchantEt.getText().toString().trim();
        record.address = addressEt.getText().toString().trim();
        record.pending = false;

        Db db = Db.get(this);
        if (record.id > 0) db.update(record);
        else db.insert(record);

        for (String f : removedPhotos) {
            db.removePhoto(record.id, f);
            Photos.deleteFile(this, f);
        }
        for (String f : addedPhotos) {
            if (photos.contains(f)) db.addPhoto(record.id, f);
        }
        didSave = true;

        Prefs.remember(this, record.card, record.category);
        Notifier.cancel(this, record.id);
        Toast.makeText(this, "已記錄 " + Fmt.money(record.amount), Toast.LENGTH_SHORT).show();
        finish();
    }

    // ───────────────────────── 用途 ─────────────────────────

    /** 重建用途選單：使用者清單 +（舊紀錄已刪除的用途）+「＋ 新增用途…」。 */
    private void loadCategories(String select) {
        categoryItems = new ArrayList<>(Prefs.categories(this));
        if (!TextUtils.isEmpty(select) && !categoryItems.contains(select)) categoryItems.add(0, select);
        if (categoryItems.isEmpty()) categoryItems.add("其他");
        categoryItems.add(ADD_CATEGORY);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categoryItems);
        categorySp.setAdapter(ad);
        int idx = select == null ? -1 : categoryItems.indexOf(select);
        categoryIndex = Math.max(0, idx);
        categorySp.setSelection(categoryIndex);
    }

    private String selectedCategory() {
        String v = categoryItems.get(categoryIndex);
        return ADD_CATEGORY.equals(v) ? "" : v;
    }

    private void askNewCategory() {
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
                        String n = Prefs.addCategory(RecordActivity.this, et.getText().toString());
                        if (!n.isEmpty()) loadCategories(n);
                    }
                })
                .show();
        et.requestFocus();
    }

    // ───────────────────────── 照片 ─────────────────────────

    @SuppressWarnings("deprecation")
    private void takePhoto() {
        captureName = Photos.newName();
        Photos.dir(this);
        Uri out = Photos.providerUri(this, captureName);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, out)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(android.content.ClipData.newRawUri("photo", out));
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            captureName = null;
            Toast.makeText(this, "找不到相機 App", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, "選擇發票照片"), REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "找不到相簿 App", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            final String name = captureName;
            captureName = null;
            if (name == null) return;
            if (resultCode != RESULT_OK) {
                Photos.deleteFile(this, name);
                return;
            }
            runPhotoJob(new PhotoJob() {
                @Override
                public List<String> run() {
                    List<String> out = new ArrayList<>();
                    if (Photos.normalize(RecordActivity.this, name)) out.add(name);
                    else Photos.deleteFile(RecordActivity.this, name);
                    return out;
                }
            });
        } else if (requestCode == REQ_GALLERY && resultCode == RESULT_OK && data != null) {
            final List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                    Uri u = data.getClipData().getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            runPhotoJob(new PhotoJob() {
                @Override
                public List<String> run() {
                    List<String> out = new ArrayList<>();
                    for (Uri u : uris) {
                        String n = Photos.importFrom(RecordActivity.this, u);
                        if (n != null) out.add(n);
                    }
                    return out;
                }
            });
        }
    }

    private interface PhotoJob {
        List<String> run();
    }

    /** 壓縮照片在背景執行，完成後加入畫面。 */
    private void runPhotoJob(final PhotoJob job) {
        photoHint.setText("處理照片中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> got = job.run();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            for (String f : got) Photos.deleteFile(RecordActivity.this, f);
                            return;
                        }
                        photos.addAll(got);
                        addedPhotos.addAll(got);
                        refreshPhotos();
                        if (got.isEmpty()) Toast.makeText(RecordActivity.this, "照片讀取失敗", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "paylog-photo").start();
    }

    private void refreshPhotos() {
        photoStrip.removeAllViews();
        int size = Ui.dp(this, 88);
        for (final String f : photos) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(Photos.thumbnail(this, f, size));
            iv.setContentDescription("發票照片");
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 10));
            g.setColor(Ui.line(this));
            iv.setBackground(g);
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = Ui.dp(this, 8);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    viewPhoto(f);
                }
            });
            photoStrip.addView(iv, lp);
        }
        photoHint.setText(photos.isEmpty()
                ? "拍下發票，報帳、對帳時一目了然"
                : "已附 " + photos.size() + " 張 · 點照片可放大或刪除");
    }

    private void viewPhoto(final String f) {
        ImageView big = new ImageView(this);
        big.setAdjustViewBounds(true);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        big.setImageBitmap(Photos.full(this, f, max));
        ScrollView sv = new ScrollView(this);
        sv.addView(big);
        new AlertDialog.Builder(this)
                .setView(sv)
                .setNegativeButton("刪除這張", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        photos.remove(f);
                        if (addedPhotos.remove(f)) Photos.deleteFile(RecordActivity.this, f);
                        else removedPhotos.add(f);
                        refreshPhotos();
                    }
                })
                .setPositiveButton("關閉", null)
                .show();
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
