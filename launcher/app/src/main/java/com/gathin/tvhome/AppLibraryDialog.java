package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The App library (Settings menu): every installed app with a launcher entry, TV or phone, on a shelf of icon cards
 * (upstream a6ac8872, 277634a9, a793048f). OK puts the focused app on Home or takes it off, with the same choices as
 * the tile menu's Hide from Home (HiddenApps); hold OK for Open, the toggle and App info. The order is alphabetical
 * and stays put while the library is open, so a toggle never moves the card under focus. The catalogue, labels and
 * icons load off the UI thread; the tiles are rebuilt once, when the library closes.
 */
final class AppLibraryDialog {

    /** Whether an app is on Home under the owner's choices; tv: it has a TV (leanback) entry. */
    interface OnHome {
        boolean test(String pkg, boolean tv);
    }

    /** Puts an app on Home or takes it off. */
    interface Change {
        void set(String pkg, boolean onHome);
    }

    /** Runs open, after the App lock PIN when the app is locked. */
    interface Gate {
        void open(String pkg, Runnable open);
    }

    /** One thread for the catalogue and the icons: each label and icon opens the app's resources. */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private static final class App {
        final ResolveInfo info;
        final String pkg;
        final String label;
        final boolean tv;

        App(ResolveInfo info, String label, boolean tv) {
            this.info = info;
            this.pkg = info.activityInfo.packageName;
            this.label = label;
            this.tv = tv;
        }
    }

    private final Activity activity;
    private final OnHome onHome;
    private final Change change;
    private final Gate gate;
    private final Runnable dismissed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final LinearLayout panel;
    private final TextView count;
    private final TextView hint;
    private final RecyclerView shelf;
    private final PopupWindow popup;
    private final List<App> apps = new ArrayList<>();
    /** Icons loaded for this opening, dropped with it. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private PopupWindow actions;
    private boolean closed;

    AppLibraryDialog(Activity activity, OnHome onHome, Change change, Gate gate, Runnable dismissed) {
        this.activity = activity;
        this.onHome = onHome;
        this.change = change;
        this.gate = gate;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(4), dp(10), 0);
        TextView title = text(activity.getString(R.string.app_library), 22, Color.WHITE);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        count = text("", 13, 0xA6FFFFFF);
        count.setSingleLine(true);
        header.addView(count, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        hint = text(activity.getString(R.string.library_loading), 13, 0xBBFFFFFF);
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setPadding(dp(10), 0, dp(10), dp(6));
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        shelf = new RecyclerView(activity);
        shelf.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        shelf.setItemAnimator(null);
        shelf.setOverScrollMode(View.OVER_SCROLL_NEVER);
        shelf.setClipToPadding(false);
        shelf.setClipChildren(false);
        shelf.setPadding(dp(8), dp(10), dp(8), dp(10));
        shelf.setAdapter(new Catalog());
        panel.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // Until the catalogue is in, the panel holds focus so Back still closes it.
        panel.setFocusable(true);

        View decor = activity.getWindow().getDecorView();
        int width = Math.min(dp(860), Math.max(dp(300), decor.getWidth() - dp(64)));
        popup = new PopupWindow(panel, width, dp(300), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissActions();
            icons.clear();
            dismissed.run();
        });
    }

    /** Shows the panel at once (with "Loading…") and fills it when the catalogue is in. */
    void show() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(180).start();
        panel.requestFocus();
        load();
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) popup.dismiss();
    }

    /** Reads the catalogue on LOADER (TV entries first, so an app with both keeps its TV one), then fills the shelf. */
    private void load() {
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                List<ResolveInfo> found;
                try {
                    found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
                } catch (RuntimeException e) {
                    continue;
                }
                for (ResolveInfo info : found) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg.equals(self)) continue;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            List<App> loaded = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> e : unique.entrySet()) {
                String label;
                try {
                    label = Cards.cleanLabel(String.valueOf(e.getValue().loadLabel(pm)), e.getKey());
                } catch (RuntimeException unavailable) {
                    label = e.getKey();
                }
                loaded.add(new App(e.getValue(), label, tv.contains(e.getKey())));
            }
            Collator collator = Collator.getInstance();
            loaded.sort((a, b) -> {
                int byLabel = collator.compare(a.label, b.label);
                return byLabel != 0 ? byLabel : a.pkg.compareTo(b.pkg);
            });
            main.post(() -> {
                if (closed) return;
                apps.clear();
                apps.addAll(loaded);
                count.setText(activity.getResources().getQuantityString(R.plurals.library_app_count, apps.size(), apps.size()));
                hint.setText(apps.isEmpty() ? R.string.library_empty : R.string.library_hint);
                shelf.getAdapter().notifyDataSetChanged();
                if (apps.isEmpty()) return;
                shelf.post(() -> {
                    RecyclerView.ViewHolder h = shelf.findViewHolderForAdapterPosition(0);
                    if (h != null && !closed) h.itemView.requestFocus();
                });
            });
        });
    }

    /** The app's icon from this opening's cache, or loaded on LOADER and set once it arrives. */
    private void bindIcon(ImageView icon, App app) {
        icon.setTag(app.pkg);
        Drawable cached = icons.get(app.pkg);
        icon.setImageDrawable(cached);
        if (cached != null || !iconsLoading.add(app.pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            try {
                loaded = app.info.loadIcon(pm);
            } catch (RuntimeException unavailable) {
                loaded = null;
            }
            Drawable result = loaded;
            main.post(() -> {
                iconsLoading.remove(app.pkg);
                if (closed || result == null) return;
                icons.put(app.pkg, result);
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (app.pkg.equals(icon.getTag())) icon.setImageDrawable(result);
            });
        });
    }

    /** OK on a card: on Home or off; the card changes in place. */
    private void toggle(App app, int position) {
        change.set(app.pkg, !onHome.test(app.pkg, app.tv));
        shelf.getAdapter().notifyItemChanged(position);
    }

    private void open(App app) {
        gate.open(app.pkg, () -> {
            close();
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                    new ComponentName(app.pkg, app.info.activityInfo.name));
            // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
            if (app.tv) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void appInfo(App app) {
        gate.open(app.pkg, () -> {
            close();
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Hold OK on a card: Open, the Home toggle and App info, in a small glass menu over the card. */
    private void showActions(App app, int position, View anchor) {
        dismissActions();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(menuPanel, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actions = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setOnDismissListener(() -> {
            if (actions == menu) actions = null;
            if (!closed && anchor.isAttachedToWindow()) anchor.requestFocus();
        });
        addAction(rows, menu, activity.getString(R.string.library_open), () -> open(app));
        boolean home = onHome.test(app.pkg, app.tv);
        addAction(rows, menu, activity.getString(home ? R.string.tile_hide : R.string.library_show),
                () -> toggle(app, position));
        addAction(rows, menu, activity.getString(R.string.tile_app_info), () -> appInfo(app));
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int[] box = new int[2];
        panel.getLocationOnScreen(box);
        int x = Math.max(box[0] + dp(12), Math.min(at[0] + anchor.getWidth() / 2 - dp(120), box[0] + panel.getWidth() - dp(252)));
        int y = Math.max(box[1] + dp(12), at[1] + dp(24));
        menuPanel.setAlpha(0f);
        menuPanel.setTranslationY(-dp(8));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        menuPanel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, PopupWindow menu, String title, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_menu_row, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            menu.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    private void dismissActions() {
        PopupWindow menu = actions;
        actions = null;
        if (menu != null) menu.dismiss();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity));
        view.setIncludeFontPadding(false);
        UiFont.watch(view);
        return view;
    }

    private GradientDrawable surface(int fill, int strokeDp, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(strokeDp), stroke);
        return bg;
    }

    /** A card's background: brighter with a white edge when focused; a green tint when the app is on Home. */
    private StateListDrawable cardBackground(boolean home) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, surface(home ? 0x5575BBA4 : 0x40FFFFFF, 2, 0xCCFFFFFF));
        bg.addState(new int[]{}, surface(home ? 0x1A75BBA4 : 0x0CFFFFFF, 1, 0x18FFFFFF));
        return bg;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private final class Catalog extends RecyclerView.Adapter<Card> {
        @NonNull
        @Override
        public Card onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT);
            params.setMargins(dp(6), 0, dp(6), 0);
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(14), dp(10), dp(10));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView name = text("", 15, Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0xBBFFFFFF);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView check = new ImageView(activity);
            check.setImageResource(R.drawable.library_check);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
            badge.setMargins(0, -dp(6), -dp(2), 0);
            row.addView(check, badge);
            return new Card(row, icon, name, status, check);
        }

        @Override
        public void onBindViewHolder(@NonNull Card h, int position) {
            App app = apps.get(position);
            bindIcon(h.icon, app);
            h.name.setText(app.label);
            boolean home = onHome.test(app.pkg, app.tv);
            String state = activity.getString(home ? R.string.library_on_home : R.string.library_off_home);
            h.status.setText(app.tv ? state : state + " · " + activity.getString(R.string.library_phone_app));
            h.check.setVisibility(home ? View.VISIBLE : View.GONE);
            h.itemView.setBackground(cardBackground(home));
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) toggle(apps.get(pos), pos);
            });
            h.itemView.setOnLongClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) showActions(apps.get(pos), pos, v);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    private final class Card extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView status;
        final ImageView check;

        Card(View view, ImageView icon, TextView name, TextView status, ImageView check) {
            super(view);
            this.icon = icon;
            this.name = name;
            this.status = status;
            this.check = check;
            // A small lift on focus, like the tiles; Up and Down have nowhere to go, nor Left and Right at the ends.
            view.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.08f : 1f)
                    .scaleY(focused ? 1.08f : 1f).setDuration(150).start());
            view.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || (key == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0)
                        || (key == KeyEvent.KEYCODE_DPAD_RIGHT && pos == apps.size() - 1);
            });
        }
    }
}
