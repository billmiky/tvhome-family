package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * The Screen time panel the Report pill opens: 24 hours or 7 days (two tabs, switched by focus), the
 * total, a bar per hour or day, and the apps watched most with a bar each. Focus on an app row turns the
 * total and the chart into that app's own.
 */
final class ReportPanel {

    interface Tabs {
        void chose(boolean week);
    }

    final LinearLayout view;
    final TextView tab24, tab7;
    private final Context c;
    private final float d;
    private final TextView total, sub;
    private final Bars bars;
    private final LinearLayout apps;
    private ScreenTime.Report report;
    private boolean week;

    ReportPanel(Context c, Tabs tabs) {
        this.c = c;
        this.d = c.getResources().getDisplayMetrics().density;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundResource(R.drawable.glass_panel);
        int pad = px(26);
        view.setPadding(pad, px(16), pad, px(16));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(22, 0xFFFFFFFF, 400);
        title.setText(R.string.report_title);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tab24 = tab(R.string.report_24h);
        tab7 = tab(R.string.report_7d);
        head.addView(tab24, tabParams());
        head.addView(tab7, tabParams());
        tab24.setId(View.generateViewId());
        tab7.setId(View.generateViewId());
        tab24.setNextFocusRightId(tab7.getId());
        tab7.setNextFocusLeftId(tab24.getId());
        tab24.setOnFocusChangeListener((v, f) -> {
            if (f) tabs.chose(false);
        });
        tab7.setOnFocusChangeListener((v, f) -> {
            if (f) tabs.chose(true);
        });
        view.addView(head);

        total = text(34, 0xFFFFFFFF, 300);
        total.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = px(8);
        view.addView(total, tl);
        sub = text(13, 0x99FFFFFF, 400);
        view.addView(sub);

        bars = new Bars(c, d);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(110));
        bl.topMargin = px(10);
        view.addView(bars, bl);

        apps = new LinearLayout(c);
        apps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = px(8);
        view.addView(apps, al);
    }

    void loading(boolean week) {
        this.week = week;
        sub.setText(week ? R.string.report_sub_7d : R.string.report_sub_24h);
    }

    /** Fills the panel; a null report means no usage access. */
    void bind(ScreenTime.Report r, boolean week) {
        this.report = r;
        this.week = week;
        apps.removeAllViews();
        sub.setText(week ? R.string.report_sub_7d : R.string.report_sub_24h);
        if (r == null) {
            total.setText("—");
            sub.setText(R.string.report_no_access);
            bars.set(null, null, week, 0xFFFFFFFF);
            return;
        }
        showAll();
        List<Map.Entry<String, Long>> ranked = r.ranked();
        long top = ranked.isEmpty() ? 1 : Math.max(1, ranked.get(0).getValue());
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            Map.Entry<String, Long> e = ranked.get(i);
            if (e.getValue() < 60_000L) break;   // under a minute: not worth a row
            View row = appRow(e.getKey(), e.getValue(), (float) e.getValue() / top);
            // Up from the first row goes back to the tab that is showing, not always the first one.
            if (apps.getChildCount() == 0) row.setNextFocusUpId(week ? tab7.getId() : tab24.getId());
            apps.addView(row);
        }
        // Left and right stay on the rows; down from the last one goes nowhere.
        for (int i = 0; i < apps.getChildCount(); i++) {
            View row = apps.getChildAt(i);
            row.setId(View.generateViewId());
            row.setNextFocusLeftId(row.getId());
            row.setNextFocusRightId(row.getId());
            if (i == apps.getChildCount() - 1) row.setNextFocusDownId(row.getId());
        }
    }

    /** The whole TV: total and chart for every app. */
    private void showAll() {
        if (report == null) return;
        total.setText(ScreenTime.format(report.total));
        sub.setText(week ? R.string.report_sub_7d : R.string.report_sub_24h);
        bars.set(report.buckets, report.labels, week, 0xFFFFFFFF);
    }

    /** One app: its time and its own chart, in the row's green. */
    private void showApp(String pkg, String name) {
        if (report == null) return;
        Long ms = report.apps.get(pkg);
        total.setText(ScreenTime.format(ms == null ? 0 : ms));
        sub.setText(c.getString(week ? R.string.report_app_7d : R.string.report_app_24h, name));
        bars.set(report.appBuckets.get(pkg), report.labels, week, 0xFF4CD787);
    }

    private View appRow(String pkg, long ms, float share) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(3), px(8), px(3));
        row.setFocusable(true);
        android.graphics.drawable.GradientDrawable lit = new android.graphics.drawable.GradientDrawable();
        lit.setColor(0x24FFFFFF);
        lit.setCornerRadius(px(10));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, lit);
        row.setBackground(bg);
        final String appName = ScreenTime.label(c, pkg);
        row.setOnFocusChangeListener((v, f) -> {
            if (f) showApp(pkg, appName);
            else if (!apps.hasFocus()) showAll();
        });
        ImageView icon = new ImageView(c);
        try {
            Drawable dr = c.getPackageManager().getApplicationIcon(pkg);
            icon.setImageDrawable(dr);
        } catch (Exception ignored) {
            // gone since: no icon
        }
        row.addView(icon, new LinearLayout.LayoutParams(px(26), px(26)));
        TextView name = text(15, 0xFFFFFFFF, 400);
        name.setSingleLine(true);
        name.setText(appName);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(px(190), ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.leftMargin = px(12);
        row.addView(name, nl);
        View fill = new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                float h = getHeight(), r = h / 2f;
                p.setColor(0x26FFFFFF);
                rect.set(0, 0, getWidth(), h);
                canvas.drawRoundRect(rect, r, r, p);
                p.setColor(0xFF4CD787);
                rect.set(0, 0, Math.max(h, getWidth() * share), h);
                canvas.drawRoundRect(rect, r, r, p);
            }
        };
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, px(6), 1f);
        fl.leftMargin = px(12);
        fl.rightMargin = px(14);
        row.addView(fill, fl);
        TextView len = text(15, 0xB3FFFFFF, 400);
        len.setText(ScreenTime.format(ms));
        len.setGravity(Gravity.END);
        row.addView(len, new LinearLayout.LayoutParams(px(70), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView tab(int text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setBackgroundResource(R.drawable.pill_bg);
        t.setTextColor(c.getColorStateList(R.drawable.pill_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(UiFont.get(c));
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(16), 0, px(16), 0);
        t.setFocusable(true);
        return t;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
        lp.leftMargin = px(8);
        return lp;
    }

    private TextView text(float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(c));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }

    /** One bar per hour or day, with a few labels underneath. */
    private static final class Bars extends View {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float d;
        private long[] values;
        private String[] labels;
        private boolean week;
        private int color = 0xFFFFFFFF;

        Bars(Context c, float d) {
            super(c);
            this.d = d;
            label.setColor(0x80FFFFFF);
            label.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(UiFont.get(c));
            top.setColor(0xCCFFFFFF);
            top.setTextAlign(Paint.Align.CENTER);
            top.setTypeface(UiFont.get(c));
        }

        void set(long[] values, String[] labels, boolean week, int color) {
            this.color = color;
            this.values = values;
            this.labels = labels;
            this.week = week;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (values == null || values.length == 0) return;
            long max = 1;
            for (long v : values) max = Math.max(max, v);
            // Room under the bars for the hour or day, and over them for each bar's time.
            float labelH = 18 * d, topH = 14 * d, h = getHeight() - labelH, w = getWidth();
            float room = h - topH;
            float slot = w / values.length, gap = week ? slot * 0.3f : slot * 0.22f, r = Math.min(4 * d, (slot - gap) / 2f);
            for (int i = 0; i < values.length; i++) {
                float x = i * slot + gap / 2f, bh = Math.max(2 * d, room * values[i] / max);
                rect.set(x, h - bh, x + slot - gap, h);
                // The longest bar full strength, the rest a little dimmer.
                bar.setColor(values[i] == max && values[i] > 0 ? color : (color & 0xFFFFFF) | 0xB3000000);
                canvas.drawRoundRect(rect, r, r, bar);
                // Its time on top, shrunk to the bar's slot (the 24 hour chart's are narrow).
                String t = ScreenTime.formatShort(values[i]);
                if (!t.isEmpty()) {
                    top.setTextSize((week ? 11 : 8.5f) * getResources().getDisplayMetrics().scaledDensity);
                    float tw = top.measureText(t);
                    if (tw > slot - d) top.setTextSize(top.getTextSize() * (slot - d) / tw);
                    canvas.drawText(t, i * slot + slot / 2f, h - bh - 4 * d, top);
                }
                if (week || i % 3 == 0) canvas.drawText(labels[i], i * slot + slot / 2f, getHeight() - 3 * d, label);
            }
        }
    }
}
