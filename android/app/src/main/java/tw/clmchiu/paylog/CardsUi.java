package tw.clmchiu.paylog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 「我的卡片 / 付款方式」管理畫面（對話框）。 */
final class CardsUi {

    private CardsUi() {
    }

    /** 卡片清單：點一下編輯，下方新增。 */
    static void showList(final Activity a, final Runnable onChanged) {
        final List<Card> cards = Db.get(a).cards();
        String[] items = new String[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            Card k = cards.get(i);
            items[i] = k.label() + "\n" + k.detail();
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("我的卡片 / 付款方式")
                .setPositiveButton("＋ 新增", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        edit(a, null, onChanged);
                    }
                })
                .setNegativeButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onChanged != null) onChanged.run();
                    }
                });
        if (cards.isEmpty()) {
            b.setMessage("同一種卡（例如 Master）有好幾張、在不同地方發行時，在這裡分別加入，"
                    + "填上末四碼和結算幣別。\n\n"
                    + "付款通知裡有末四碼（例如 ••1234、尾號1234）就會自動認出是哪一張，"
                    + "香港卡的「$」會算港幣、大陸卡的「元 / ¥」會算人民幣，再換算成台幣計入總支出。\n\n"
                    + "微信支付、支付寶不用設定也會自動辨識（預設人民幣）。");
        } else {
            b.setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    edit(a, cards.get(which), onChanged);
                }
            });
        }
        b.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                if (onChanged != null) onChanged.run();   // 按返回鍵關閉也更新
            }
        });
        b.show();
    }

    /** 新增（card == null）或編輯一張卡。 */
    static void edit(final Activity a, Card card, final Runnable onChanged) {
        final boolean isNew = card == null;
        final Card k = isNew ? new Card() : card;
        if (isNew) {
            k.brand = PaymentParser.MASTER;
            k.currency = "TWD";
        }
        final String[] brand = {k.brand};
        final String[] cur = {TextUtils.isEmpty(k.currency) ? "TWD" : k.currency};

        LinearLayout box = Ui.vbox(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 4), p, 0);

        box.addView(Ui.label(a, "卡別 / 付款方式"));
        final Button brandBtn = Ui.ghostButton(a, brand[0]);
        box.addView(brandBtn, Ui.matchWrap());

        box.addView(Ui.label(a, "末四碼（用來自動認卡，選填）"));
        final EditText last4 = new EditText(a);
        last4.setInputType(InputType.TYPE_CLASS_NUMBER);
        last4.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        last4.setHint("例如 1234");
        last4.setText(k.last4);
        box.addView(last4, Ui.matchWrap());

        box.addView(Ui.label(a, "發卡銀行（選填）"));
        final EditText bank = new EditText(a);
        bank.setSingleLine(true);
        bank.setHint("例如 滙豐香港、招商銀行、國泰世華");
        bank.setText(k.bank);
        box.addView(bank, Ui.matchWrap());

        box.addView(Ui.label(a, "結算幣別（通知只寫 $、元、¥ 時算這個）"));
        final Button curBtn = Ui.ghostButton(a, "");
        box.addView(curBtn, Ui.matchWrap());

        box.addView(Ui.label(a, "顯示名稱（選填，留空自動產生）"));
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setText(k.name);
        box.addView(name, Ui.matchWrap());

        final TextView preview = Ui.text(a, "", 13, false);
        preview.setTextColor(Ui.muted(a));
        preview.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(preview);

        final Runnable sync = new Runnable() {
            @Override
            public void run() {
                brandBtn.setText(brand[0]);
                curBtn.setText(Card.currencyName(cur[0]) + "（" + cur[0] + "）");
                if (TextUtils.isEmpty(name.getText().toString().trim())) {
                    Card t = new Card();
                    t.brand = brand[0];
                    t.last4 = last4.getText().toString().trim();
                    String bk = bank.getText().toString().trim();
                    name.setHint((bk.isEmpty() ? "" : bk + " ") + t.label());
                }
                preview.setText("記帳時顯示為：" + previewLabel(name, bank, brand[0], last4));
            }
        };
        sync.run();
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                sync.run();
            }
        };
        last4.addTextChangedListener(tw);
        bank.addTextChangedListener(tw);
        name.addTextChangedListener(tw);

        brandBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("卡別 / 付款方式")
                        .setItems(Card.BRANDS, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                brand[0] = Card.BRANDS[w];
                                // 常見預設幣別
                                if (Card.WECHAT.equals(brand[0]) || Card.ALIPAY.equals(brand[0])
                                        || Card.UNIONPAY.equals(brand[0])) {
                                    if ("TWD".equals(cur[0])) cur[0] = "CNY";
                                } else if (Card.OCTOPUS.equals(brand[0]) || Card.PAYME.equals(brand[0])) {
                                    cur[0] = "HKD";
                                } else if (PaymentParser.EASYCARD.equals(brand[0])) {
                                    cur[0] = "TWD";
                                }
                                sync.run();
                            }
                        }).show();
            }
        });
        curBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] names = new String[Card.CURRENCIES.length];
                for (int i = 0; i < names.length; i++) names[i] = Card.CURRENCY_NAMES[i] + "（" + Card.CURRENCIES[i] + "）";
                new AlertDialog.Builder(a).setTitle("結算幣別")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                cur[0] = Card.CURRENCIES[w];
                                sync.run();
                            }
                        }).show();
            }
        });

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(isNew ? "新增卡片" : "編輯卡片")
                .setView(sv)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showList(a, onChanged);
                    }
                })
                .setPositiveButton("儲存", null);
        if (!isNew) {
            b.setNeutralButton("刪除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    Db.get(a).deleteCard(k.id);
                    Toast.makeText(a, "已刪除；已記錄的消費不受影響", Toast.LENGTH_SHORT).show();
                    showList(a, onChanged);
                }
            });
        }
        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String l4 = last4.getText().toString().trim();
                        if (!l4.isEmpty() && !l4.matches("[0-9]{4}")) {
                            last4.setError("請輸入 4 位數字");
                            return;
                        }
                        // 同末四碼不能重複，否則無法分辨
                        if (!l4.isEmpty()) {
                            for (Card o : Db.get(a).cards()) {
                                if (o.id != k.id && l4.equals(o.last4)) {
                                    last4.setError("「" + o.label() + "」已用這個末四碼");
                                    return;
                                }
                            }
                        }
                        k.brand = brand[0];
                        k.last4 = l4;
                        k.bank = bank.getText().toString().trim();
                        k.currency = cur[0];
                        k.name = previewLabel(name, bank, brand[0], last4);
                        Db.get(a).saveCard(k);
                        dlg.dismiss();
                        showList(a, onChanged);
                    }
                });
            }
        });
        dlg.show();
    }

    /** 名稱留空時用「銀行 卡別 ••末四碼」。 */
    private static String previewLabel(EditText name, EditText bank, String brand, EditText last4) {
        String n = name.getText().toString().replace('\n', ' ').trim();
        if (!n.isEmpty()) return n.length() > 30 ? n.substring(0, 30) : n;
        Card t = new Card();
        t.brand = brand;
        t.last4 = last4.getText().toString().trim();
        String bk = bank.getText().toString().trim();
        return (bk.isEmpty() ? "" : bk + " ") + t.label();
    }
}
