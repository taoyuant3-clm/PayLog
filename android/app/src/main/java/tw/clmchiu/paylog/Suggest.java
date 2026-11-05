package tw.clmchiu.paylog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 輸入聯想：備註、商家、地點欄位顯示曾輸入過的字詞。
 * 中文沒有空格分詞，所以用「包含」比對（輸入「午」也會找到「公司午餐」），
 * 開頭相同的排前面，其次依使用次數。點一下空白欄位會列出最常用的字詞。
 */
final class Suggest extends BaseAdapter implements Filterable {

    private static final int MAX_SHOWN = 8;

    private final Context ctx;
    private final List<String> all;
    private List<String> shown;

    private Suggest(Context c, List<String> words) {
        ctx = c;
        all = words;
        shown = top(words);
    }

    /** 讓輸入框套用聯想；column 為 note / merchant / address。 */
    static void attach(final AutoCompleteTextView et, String column) {
        List<String> words = Db.get(et.getContext()).pastWords(column, 300);
        if (words.isEmpty()) return;
        et.setAdapter(new Suggest(et.getContext(), words));
        et.setThreshold(1);
        // 欄位空白時點一下，列出最常用的字詞
        et.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (et.getText().length() == 0 && et.getAdapter() != null && et.getAdapter().getCount() > 0) {
                    et.showDropDown();
                }
            }
        });
    }

    private static List<String> top(List<String> words) {
        return new ArrayList<>(words.subList(0, Math.min(MAX_SHOWN, words.size())));
    }

    @Override
    public int getCount() {
        return shown.size();
    }

    @Override
    public String getItem(int position) {
        return shown.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv = convertView instanceof TextView ? (TextView) convertView
                : Ui.text(ctx, "", 16, false);
        int h = Ui.dp(ctx, 16);
        tv.setPadding(h, Ui.dp(ctx, 12), h, Ui.dp(ctx, 12));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setText(shown.get(position));
        return tv;
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                List<String> out;
                String q = constraint == null ? "" : constraint.toString().trim().toLowerCase(Locale.ROOT);
                if (q.isEmpty()) {
                    out = top(all);
                } else {
                    List<String> starts = new ArrayList<>(), contains = new ArrayList<>();
                    for (String w : all) {
                        String lw = w.toLowerCase(Locale.ROOT);
                        if (lw.equals(q)) continue;            // 已完整輸入，不必再提示
                        if (lw.startsWith(q)) starts.add(w);
                        else if (lw.contains(q)) contains.add(w);
                    }
                    starts.addAll(contains);
                    out = starts.size() > MAX_SHOWN ? new ArrayList<>(starts.subList(0, MAX_SHOWN)) : starts;
                }
                FilterResults r = new FilterResults();
                r.values = out;
                r.count = out.size();
                return r;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                shown = results.values == null ? new ArrayList<String>() : (List<String>) results.values;
                if (results.count > 0) notifyDataSetChanged();
                else notifyDataSetInvalidated();
            }
        };
    }
}
