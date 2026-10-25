package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** How far the Now playing card lifts when focused; less than a tile, since it is much wider. */
    private static final float NOW_PLAYING_SCALE = 1.04f;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnInputs;
    private TextView btnReport;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    /** Between onResume and onPause; package broadcasts while paused only mark the tiles stale. */
    private boolean resumed;
    /**
     * The tiles no longer match the TV: never built, a package changed while Home was away, or the
     * drawables were dropped under memory pressure. Otherwise a return to Home keeps the tiles as they are.
     */
    private boolean tilesStale = true;
    /** The grid's entry animation has played; it plays once per build from scratch, not on every return. */
    private boolean tilesShown;
    /** PackageManager's change counter at the last check (getChangedPackages). */
    private int packageSeq;
    private int backgroundColor;
    private File wallpaperFile;
    private android.widget.ImageView wallView;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs, Settings or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    private TextView btnSettings;
    /** Now playing card (NowPlaying): the media session of an app playing in the background. */
    private boolean nowPlayingOn;
    private View nowPlaying;
    private ImageView nowPlayingArt;
    private ImageView nowPlayingState;
    private TextView nowPlayingTitle;
    private TextView nowPlayingSubtitle;
    private MediaSessionManager sessions;
    private ComponentName sessionListener;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController shownSession;
    /** A session to show; the card is on screen when there is one, room for it, and the grid is at its top. */
    private boolean nowPlayingHasSession;
    private boolean nowPlayingVisible;
    /** Up on the top bar slid the dock (the whole grid) off the bottom, to see the background. */
    private boolean dockHidden;
    private Bitmap nowPlayingSource;
    private String nowPlayingPkg;
    /** On an occasion day the card plays its song instead of showing Now playing; null on other days. */
    private Occasions.Play todayPlay;
    private final Runnable nowPlayingRefresh = () -> NowPlaying.run(() -> {
        refreshNowPlaying();
        return null;
    }, null);
    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            scheduleNowPlaying();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scheduleNowPlaying();
        }

        @Override
        public void onSessionDestroyed() {
            scheduleNowPlaying();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> NowPlaying.run(() -> {
        watch(list);
        return null;
    }, null);
    /** self_update (config.xml or a theme's config.json): off, manual or weekly. */
    private String selfUpdate;
    private Dialog updatePrompt;
    // Static so they survive the activity being recreated: the newer version the last check found,
    // and whether the owner still has to be asked about it in this process.
    private static Updater.Feed offered;
    private static boolean updatePromptDue;
    /** Bitcoin line (Ticker): on from btc_ticker, or from the Settings menu, which wins once used. */
    private TextView btcTicker;
    private boolean btcOn;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    static final String PREFS = "home";
    static final String PREF_BTC = "btc_ticker";
    /** Settings menu: the phone links (relay QR code and the home-network page) in the network menu. */
    static final String PREF_REMOTE_QR = "remote_qr";
    /** Plus the input id: the owner's name for that input (renameInput). */
    static final String PREF_INPUT_NAME = "input_name:";
    /** A message from the phone remote (RemoteRelay) for Home to show when it starts or comes back. */
    static final String EXTRA_BANNER = "com.gathin.tvhome.BANNER";
    /** Overrides set from the phone page (WebControl); the config value is the default. */
    static final String PREF_NOW_PLAYING = "now_playing";
    static final String PREF_GREETING = "greeting_name";
    static final String PREF_WEATHER_LOCATION = "weather_location";
    /** The owner's occasions as a JSON array (replaces the config's list once set) and the built-in holidays. */
    static final String PREF_OCCASIONS = "occasions";
    static final String PREF_HOLIDAYS = "holidays";
    /** Occasions for the greeting line (Occasions); parsed when the settings are read. */
    private List<Occasions.Entry> occasions = Collections.emptyList();
    private boolean holidaysOn = true;
    /** config.xml with the theme's config.json over it, for the defaults behind those overrides. */
    private Config config;
    /** Weather next to the date: on from the weather key, or from the Settings menu, which wins once used. */
    static final String PREF_WEATHER = "weather";
    private TextView weatherView;
    private boolean weatherOn;
    private String weatherLocation = "";
    private String weatherUnit = Weather.CELSIUS;
    static final String PREF_GALLERY = "gallery";
    private static final int REQUEST_PHOTOS = 7;
    /** Gallery (Gallery.java): on from gallery, or from the Settings menu, which wins once used. */
    private boolean galleryOn;
    /** Two photos for the crossfade and the scrim over them, under everything; null until a photo shows. */
    private FrameLayout galleryLayer;
    private ImageView galleryFront;
    private ImageView galleryBack;
    private long galleryShownAt;
    private boolean galleryLoading;
    // Static so a recreated activity doesn't list the folder again or start a new round of photos.
    private static volatile List<String> galleryPhotos = Collections.emptyList();
    private static volatile long galleryListedAt;
    private static final Gallery.ShuffleBag galleryBag = new Gallery.ShuffleBag(new java.util.Random());
    private final Runnable galleryTick = new Runnable() {
        @Override
        public void run() {
            nextPhoto();
            handler.postDelayed(this, Gallery.INTERVAL_MS);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (!resumed) {
                tilesStale = true;   // rebuilt when Home comes back
                return;
            }
            reloadApps(false, true);
        }
    };
    /**
     * Tidy: a while after the greeting intro, the top bar folds to its icons (HDMI and the VPN tag go, the
     * Bitcoin change goes) and the Now playing card to its artwork. A focused pill or card unfolds while it
     * has focus. Each fold is 1 for shown, 0 for folded away.
     */
    private boolean tidy = true;
    private static final long TIDY_DELAY_MS = 5000;
    private static final long FOLD_MS = 450;
    private Tween inputsFold, reportFold, wifiFold, tickerFold, cardFold;
    private final FoldSpan inputsSpan = new FoldSpan(null, 0), reportSpan = new FoldSpan(null, 0);
    private FoldSpan wifiBadgeFold, tickerChangeFold;
    private CharSequence wifiText, tickerText;
    private View nowPlayingText;
    private final Runnable tidyUp = () -> setTidy(true, true);

    /** The date's turn on the line: "FRI 23 OCT   Next PH: 8 NOV (SG)" (Singapore and Vietnam holidays). */
    private String dateWithHoliday() {
        String date = new java.text.SimpleDateFormat(getString(R.string.date_format), Locale.getDefault()).format(new java.util.Date());
        String next = PublicHolidays.next(java.time.LocalDate.now());
        return next == null ? date : date + "   Next PH: " + next;
    }

    /** The greeting line's turns: greeting, date, Bitcoin price (TextCycle). */
    private TextCycle cycle;
    private CharSequence greetingLine = "", btcLine;

    /** The big greeting that types itself out when the TV comes on (GreetingIntro). */
    private GreetingIntro intro;
    /** When the TV last came on (elapsedRealtime), until Home shows the intro for it; 0 when shown. */
    private long introDueAt;
    private static final long INTRO_WINDOW_MS = 2 * 60_000L;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The TV comes on at the first dock tile: from a lower row the grid has scrolled up, and the
            // greeting intro, as it grows, would cover the tiles. Done while the screen is off too, so
            // the scroll back is over by the time it comes on.
            backToDock();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                LastUse.record(context);
                intro.finish();
                return;
            }
            introDueAt = android.os.SystemClock.elapsedRealtime();
            if (resumed) playIntroWhenReady();
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            fetchWeather();
            handler.postDelayed(this, Weather.REFRESH_MS);
        }
    };
    private final Runnable btcTick = new Runnable() {
        @Override
        public void run() {
            fetchTicker();
            handler.postDelayed(this, Ticker.REFRESH_MS);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            // The network pill follows the network callbacks and each return to Home; refreshing it here cost
            // about 7 ms of binder calls on the UI thread every minute (upstream c9798026).
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // The root (or the wallpaper) paints every pixel; a window background under it is a wasted full-screen fill.
        getWindow().setBackgroundDrawable(null);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        config = cfg;
        // restore_key_services: after a force stop, put back the accessibility helpers (and with them the remote
        // keys and the phone remote) that Android took off the list. Needs WRITE_SECURE_SETTINGS.
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        // The phone page lives as long as the process; a second onCreate finds it running.
        WebControl.start(this, cfg.webControl, cfg.webPort);
        // The phone remote through the relay, likewise.
        RemoteRelay.start(this, cfg.remoteRelay);
        TvLock.start(this);
        pendingBanner = getIntent() != null ? getIntent().getStringExtra(EXTRA_BANNER) : null;
        COLUMNS = cfg.columns;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        backgroundColor = cfg.backgroundColor;
        root.setBackgroundColor(backgroundColor);
        wallpaperFile = cfg.wallpaper;
        if (wallpaperFile != null) showWallpaper(wallpaperFile);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        UiFont.watch(heroName);
        // The glow is a small bitmap stretched over the screen (setAmbient); its alpha is applied while drawing it
        // rather than through a full-screen offscreen buffer on every frame of a fade.
        ambient.forceHasOverlappingRendering(false);
        greeting = findViewById(R.id.greeting);
        UiFont.watch(greeting);
        intro = new GreetingIntro(root, greeting, handler);
        intro.onDone = () -> {
            if (resumed) {
                handler.postDelayed(tidyUp, TIDY_DELAY_MS);
                cycle.start();
            } else {
                setTidy(true, false);
            }
        };
        // The greeting line takes turns with the date and the Bitcoin price, which have no lines of their own.
        cycle = new TextCycle(greeting, handler, () -> java.util.Arrays.asList(greetingLine,
                dateWithHoliday(),
                btcOn ? btcLine : null));
        introDueAt = android.os.SystemClock.elapsedRealtime();   // a cold start is the TV coming on too
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            /**
             * Every "bring this tile on screen" scroll lands here. The stock version treats the grid's top padding as
             * the visible top, but that padding only pushes the dock down the screen: Up into a row above it dragged
             * the row down to the padding line, cut it off and could scroll the focused tile out, dropping focus into
             * the top bar. Grid tiles now scroll just enough to show between the grid's top and bottom edges; dock
             * tiles are left to onTileFocused (upstream 72e4cfa7, 564dcb22).
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                if (getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                int limitBottom = getHeight();
                int dy = top < 0 ? top : bottom > limitBottom ? Math.min(top, bottom - limitBottom) : 0;
                if (dy == 0) return false;
                if (immediate) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        applyOwnerChoices();
        adapter.setSavedOrder(Order.ranks(Order.load(getSharedPreferences(PREFS, MODE_PRIVATE)), cfg.dockApps));
        grid.setAdapter(adapter);
        // Move mode dims every tile but the carried one; a recycled view must not keep an old alpha.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + DockDecoration.DOCK_BOTTOM_DP) * density);   // plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Leave the top of the next row peeking out at the bottom, so it's clear there are more apps.
            int peek = rowsBelowDock == 0 ? (int) ((8 + 26) * density) : (int) (8 * density);
            int want = Math.max(0, h - dockRow - gridRows - peek);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnReport = findViewById(R.id.btnReport);
        btnReport.setOnClickListener(v -> showReport());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            showNetworkMenu();
            return true;
        });
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        selfUpdate = cfg.selfUpdate;
        greetingName = cfg.greetingName;
        btcTicker = findViewById(R.id.btcTicker);
        UiFont.watch(btcTicker);
        btcOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_BTC, cfg.btcTicker);
        weatherView = findViewById(R.id.weather);
        weatherOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_WEATHER, cfg.weather);
        weatherLocation = cfg.weatherLocation;
        weatherUnit = cfg.weatherUnit;
        galleryOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_GALLERY, cfg.gallery);
        // The first start after a self-update says so (and puts accessibility services back if it can).
        int started = UpdateFeature.run(() -> Updater.afterStart(this), Updater.STARTED_PLAIN);
        if (started != Updater.STARTED_PLAIN) {
            Toast.makeText(this, getString(started == Updater.STARTED_UPDATED ? R.string.updated_to : R.string.updated_services_off,
                    BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show();
        }

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            // Up from the top bar, which has nothing above it, slides the dock away to show the background;
            // Down brings it back (the next Down then moves into it as usual).
            // It also folds the bar back to the chevron and Settings, which keeps the focus.
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (!dockHidden) setDockHidden(true);
                if (v != btnSettings) btnSettings.requestFocus();
                setBarOpen(false);
                return true;
            }
            // Left from Settings on the folded bar slides the pills out again, onto Wi-Fi.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == btnSettings && !barExpanded) {
                setBarOpen(true);
                // Once it has some width: a pill folded to nothing can't take focus.
                handler.postDelayed(() -> {
                    if (btnSettings.isFocused()) btnWifi.requestFocus();
                }, 120);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && dockHidden) {
                setDockHidden(false);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is Inputs, or Wi-Fi on a box without inputs; there Left steps back a photo.
                boolean end = v.getId() == R.id.btnInputs
                        || (v.getId() == R.id.btnReport && btnInputs.getVisibility() != View.VISIBLE);
                if (end) stepPhoto(true);
                return end;
            }
            boolean end = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
            if (end) stepPhoto(false);
            return end;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnReport.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        // Focused, a pill is dark text on white and needs no shadow; unfocused it keeps the style's soft one.
        View.OnFocusChangeListener pillShadow = (v, focused) -> {
            TextView t = (TextView) v;
            if (focused) t.setShadowLayer(0f, 0f, 0f, 0);
            else t.setShadowLayer(4f, 0f, 1f, 0x80000000);
            if (focused) setBarOpen(true);
            // A tidy pill unfolds while it has focus.
            Tween fold = v == btnInputs ? inputsFold : v == btnReport ? reportFold : v == btnWifi ? wifiFold : null;
            if (fold != null) fold.to(focused || !tidy ? 1f : 0f, FOLD_MS * 2 / 3);
        };
        inputsFold = new Tween(0f, v -> foldPill(btnInputs, R.string.inputs, inputsSpan, v));
        reportFold = new Tween(0f, v -> foldPill(btnReport, R.string.report, reportSpan, v));
        wifiFold = new Tween(0f, v -> {
            if (wifiBadgeFold == null) return;
            wifiBadgeFold.fraction = v;
            btnWifi.setText(wifiText);
        });
        tickerFold = new Tween(0f, v -> {
            if (tickerChangeFold == null) return;
            tickerChangeFold.fraction = v;
            btcTicker.setText(tickerText);
        });
        topChevron = findViewById(R.id.topChevron);
        barOpen = new Tween(0f, this::openBar);
        for (View pill : barPills()) pill.setFocusable(false);
        openBar(0f);
        foldPill(btnInputs, R.string.inputs, inputsSpan, 0f);
        foldPill(btnReport, R.string.report, reportSpan, 0f);
        btnInputs.setOnFocusChangeListener(pillShadow);
        btnReport.setOnFocusChangeListener(pillShadow);
        btnWifi.setOnFocusChangeListener(pillShadow);
        btnSettings.setOnFocusChangeListener(pillShadow);
        setUpNowPlaying(cfg.nowPlaying);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                magnify(-1);
            }
            if (newF != btnInputs && newF != btnReport && newF != btnWifi && newF != btnSettings) {
                setBarOpen(false);   // focus left the top bar: back to the chevron and Settings
            }
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        // Registered for the activity's life, not just while resumed: a package that changes while
        // another app is in front marks the tiles stale, so returning Home rebuilds only when needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wallView = wall;
            updateBackdrop();
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        readOverrides();
        tilesReady = false;
        promptsPaused = false;
        if (wallpaperFile != null && wallView == null) showWallpaper(wallpaperFile);   // dropped by onTrimMemory
        // Both checks run every time: packagesChanged() also moves the counter on.
        boolean changed = packagesChanged();
        reloadApps(true, changed || tilesStale);
        updateGreeting();
        cycle.reset();
        cycle.start();
        poke();
        playIntroWhenReady();
        // The network and Inputs pills keep their state while Home is away, and callbacks update them, so this only
        // catches anything missed. Its binder calls (about 7 ms) would hold up the first frame back on Home, so
        // after the first resume they run once that frame is drawn (upstream 0f73adcf).
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        handler.postDelayed(minuteTick, MINUTE_MS);
        startTicker();
        startWeather();
        startNowPlaying();
        startGallery();
        Updater.installListener = () -> handler.post(() -> {
            if (updatePrompt != null) updatePrompt.dismiss();
        });
        RemoteRelay.screen = new RemoteRelay.Screen() {
            @Override
            public void showBanner(String text) {
                MainActivity.this.showBanner(text);
            }

            @Override
            public void clearBanner() {
                hideBanner();
            }
        };
        TvLock.screen = new TvLock.Screen() {
            @Override
            public void locked() {
                showLock();
            }

            @Override
            public void unlocked() {
                hideLock();
            }
        };
        if (TvLock.locked(this)) showLock();
        else hideLock();
        if (pendingBanner != null) {
            String text = pendingBanner;
            pendingBanner = null;
            showBanner(text);
        }
        WebControl.remote = new WebControl.Remote() {
            @Override
            public void settingsChanged() {
                handler.post(MainActivity.this::applyRemoteChange);
            }

            @Override
            public void step(boolean back) {
                handler.post(() -> {
                    if (resumed) stepPhoto(back);
                });
            }

            @Override
            public void photosChanged() {
                handler.post(() -> {
                    galleryListedAt = 0;   // list the folder again before the next photo
                    if (resumed && galleryShownAt == 0) startGallery();
                });
            }
        };
        updateUpdateDot();
        checkWeekly();
    }

    /**
     * True when a package was added, removed or updated since the last call (or the platform can't say).
     * One cheap call; when it says nothing changed, coming back Home skips the app and card scan.
     */
    private boolean packagesChanged() {
        try {
            ChangedPackages cp = getPackageManager().getChangedPackages(packageSeq);
            if (cp == null) return false;
            packageSeq = cp.getSequenceNumber();
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Puts focus back where it was, after rebuilding the tiles (and the module cards) when reload is set.
     * resume: Home is coming to the front, so the glass and the hero fade back in on the returning tile.
     */
    private void reloadApps(boolean resume, boolean reload) {
        final boolean animate = resume && reload && !tilesShown;
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = resume || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        if (reload) reloadTiles();
        if (animate) grid.scheduleLayoutAnimation();
        tilesShown = true;

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        endTileModes();   // an app installed or removed while moving: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback,
                addedEntries(leanback));
        tilesStale = false;
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        endTileModes();   // Home or another app while moving keeps the order so far
        stopVideo();   // the decoder goes back to the app being opened
        intro.finish();
        handler.removeCallbacks(goIdle);
        setIdle(false, 0);   // back from an app, Home is all there
        cycle.reset();
        handler.removeCallbacks(tidyUp);
        if (!tidy) setTidy(true, false);   // back from an app, Home is tidy
        // Back from an app, Home looks as usual.
        if (dockHidden) {
            dockHidden = false;
            grid.animate().cancel();
            grid.setTranslationY(0f);
        }
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(btcTick);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(galleryTick);
        stopNowPlaying();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (updatePrompt != null) updatePrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
        if (library != null) library.close();
        Updater.installListener = null;
        WebControl.remote = null;
        // From another app a message starts Home with it (onNewIntent), so it shows on top.
        RemoteRelay.screen = null;
        TvLock.screen = null;
        // Locked: whatever took the screen (an app button, voice search) gives it back to the lock.
        if (TvLock.locked(this)) {
            handler.postDelayed(() -> {
                if (!resumed && TvLock.locked(this)) TvLock.bringHome(this);
            }, 400);
        }
    }

    /** The switches and texts the Settings menu or the phone page may have changed since the config was read. */
    private void readOverrides() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        btcOn = p.getBoolean(PREF_BTC, config.btcTicker);
        weatherOn = p.getBoolean(PREF_WEATHER, config.weather);
        galleryOn = p.getBoolean(PREF_GALLERY, config.gallery);
        nowPlayingOn = p.getBoolean(PREF_NOW_PLAYING, config.nowPlaying);
        greetingName = p.getString(PREF_GREETING, config.greetingName);
        weatherLocation = p.getString(PREF_WEATHER_LOCATION, config.weatherLocation);
        holidaysOn = p.getBoolean(PREF_HOLIDAYS, config.holidays);
        List<String> skipped = new ArrayList<>();
        occasions = Occasions.parse(p.getString(PREF_OCCASIONS, config.occasions), skipped);
        for (String why : skipped) Log.w("TvHomeOccasions", why);
    }

    /** A change from the phone page while Home is in front: stop what depends on it and start it again. */
    private void applyRemoteChange() {
        if (!resumed) return;   // onResume reads the settings anyway
        handler.removeCallbacks(btcTick);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(galleryTick);
        stopNowPlaying();
        boolean galleryWas = galleryOn;
        readOverrides();
        updateGreeting();
        startTicker();
        startWeather();
        if (nowPlayingOn) {
            startNowPlaying();
        } else {
            nowPlayingHasSession = false;
            placeNowPlaying();
        }
        if (galleryOn != galleryWas && heroShown) ambient.animate().alpha(ambientAlpha()).setDuration(300).start();
        startGallery();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork and
     * the wallpaper, and rebuild them when Home comes back. Not at UI_HIDDEN, the ordinary trip to an
     * app, where keeping them is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is hidden when an accessibility service keeps
        // the process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || resumed) return;
        adapter.clear();
        tilesStale = true;
        tilesShown = false;
        if (wallView != null) {
            wallView.setImageDrawable(null);
            root.removeView(wallView);
            wallView = null;
        }
        dropGallery();   // the next return to Home shows a new photo
        updateBackdrop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere, except to bring a hidden dock back.
        if (dockHidden) setDockHidden(false);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    // ---- magnification ---------------------------------------------------------------------------

    private Tween magnifier;
    private final java.util.ArrayList<View> magCards = new java.util.ArrayList<>();
    private float[] magFrom = new float[0], magTo = new float[0];

    /**
     * Grows the focused tile and, less, its neighbours in the row; every other tile on screen settles back
     * to its resting size. focusedPos -1: none (focus left the grid). Frame by frame, like the folds.
     */
    private void magnify(int focusedPos) {
        magCards.clear();
        java.util.ArrayList<Float> from = new java.util.ArrayList<>(), to = new java.util.ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(child);
            View card = child.findViewById(R.id.card);
            if (pos == RecyclerView.NO_POSITION || card == null) continue;
            float target = 1f;
            if (focusedPos >= 0 && pos / COLUMNS == focusedPos / COLUMNS) {
                int d = Math.abs(pos - focusedPos);
                target = d == 0 ? AppAdapter.FOCUS_SCALE : d == 1 ? AppAdapter.NEAR_SCALE : 1f;
            }
            if (card.getScaleX() == target) continue;
            magCards.add(card);
            from.add(card.getScaleX());
            to.add(target);
        }
        if (magCards.isEmpty()) return;
        magFrom = new float[from.size()];
        magTo = new float[to.size()];
        for (int i = 0; i < magFrom.length; i++) {
            magFrom[i] = from.get(i);
            magTo[i] = to.get(i);
        }
        if (magnifier == null) {
            magnifier = new Tween(1f, v -> {
                for (int i = 0; i < magCards.size() && i < magFrom.length; i++) {
                    float s = magFrom[i] + (magTo[i] - magFrom[i]) * v;
                    magCards.get(i).setScaleX(s);
                    magCards.get(i).setScaleY(s);
                }
            });
        }
        magnifier.cancel();
        magnifier.to(0f, 0);
        magnifier.to(1f, 220);
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        magnify(position);
        lastFocusedKey = adapter.keyAt(position);
        Rect r = glassTo(card, AppAdapter.FOCUS_SCALE, AppAdapter.cornerRadius(card.getWidth(), density));
        float pad = 6f * density;        // matches GlassView's glow inset
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            // The hero name keeps its gap above the tile as magnified.
            showHero(position, r.top - dy - card.getHeight() * (AppAdapter.FOCUS_SCALE - 1.1f) / 2f);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    /**
     * Springs the glass highlight onto {@code target} as it looks scaled by {@code scale}, with the
     * target's unscaled corner {@code radius}. Returns the target's rect in root coordinates, from
     * layout positions (ignores the scale animation).
     */
    private Rect glassTo(View target, float scale, float radius) {
        Rect r = new Rect(0, 0, target.getWidth(), target.getHeight());
        root.offsetDescendantRectToMyCoords(target, r);
        // The Now playing card is placed with setY (placeNowPlaying), which the layout rect leaves out.
        if (target == nowPlaying) r.offset(Math.round(target.getTranslationX()), Math.round(target.getTranslationY()));
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = target.getWidth() * scale + 2 * pad;
        float h = target.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
        }
        glass.setRadius(radius * scale + pad);
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            // The spring works in grid content coordinates; a target outside the grid simply doesn't scroll.
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        return r;
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 18 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(ambientAlpha()).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f * ambientAlpha()).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(ambientAlpha()).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /** The app-coloured glow stays off over Gallery photos: tinting family photos red or blue looks wrong. */
    private float ambientAlpha() {
        return galleryOn ? 0f : 1f;
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable, drawn on the CPU: fading it redrew 1920x1080 every
     * frame. A small bitmap stretched over the screen is one texture, uploaded once per app (upstream da15a448).
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        // Painted once in white and tinted with the app's colour: changing apps neither repaints it on the CPU
        // (about 11 ms) nor uploads it again (upstream 4cc83ce1).
        android.graphics.drawable.BitmapDrawable glow = ambient.getBackground() instanceof android.graphics.drawable.BitmapDrawable
                ? (android.graphics.drawable.BitmapDrawable) ambient.getBackground() : null;
        if (glow == null) {
            int w = 480, h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            new Canvas(bmp).drawRect(0, 0, w, h, paint);
            glow = new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        // At a row's ends, with Gallery on, the key steps through the photos instead.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position % COLUMNS != 0) return false;
            stepPhoto(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (position % COLUMNS != COLUMNS - 1 && position != count - 1) return false;
            stepPhoto(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                if (!nowPlayingVisible) return false;      // dock row: let focus move to the top bar
                // With the Now playing card up, only the last dock tile goes to it; the others go to
                // the top button above them, as without the card.
                if (position == COLUMNS - 1) nowPlaying.requestFocus();
                else topButtonAbove(position).requestFocus();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    /** The visible top button nearest horizontally to the dock tile at {@code position}. */
    private View topButtonAbove(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        float x = 0;
        if (vh != null) {
            Rect r = new Rect(0, 0, vh.itemView.getWidth(), vh.itemView.getHeight());
            root.offsetDescendantRectToMyCoords(vh.itemView, r);
            x = r.exactCenterX();
        }
        View best = btnSettings;
        float bestD = Float.MAX_VALUE;
        for (View b : new View[]{btnInputs, btnReport, btnWifi, btnSettings}) {
            if (b.getVisibility() != View.VISIBLE || !b.isFocusable()) continue;   // folded away: not a target
            Rect r = new Rect(0, 0, b.getWidth(), b.getHeight());
            root.offsetDescendantRectToMyCoords(b, r);
            float d = Math.abs(r.exactCenterX() - x);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /** Focus goes to the first dock tile, now if Home is in front, else when it next resumes. */
    private void backToDock() {
        lastFocused = 0;
        lastFocusedKey = adapter.keyAt(0);
        if (!resumed) return;
        if (dockHidden) setDockHidden(false);
        focusTile(0);
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- tile menu and move mode ------------------------------------------------------------------

    /** Key of the tile being moved (tile menu, Move), or null. */
    private String movingKey;
    /** Tile order when the move started, to save only when something changed. */
    private List<String> orderAtStart;
    /** Tile menu, Add to dock: the grid app waiting for the dock place it takes, or null. */
    private String dockPickKey;
    /** Tile menu, Replace with another app: the dock app waiting for the app that takes its place, or null. */
    private String dockReplaceKey;
    /** The tiles the modes above leave aside are dimmed to this. */
    private static final float DIMMED = 0.4f;

    private boolean tileModeOn() {
        return movingKey != null || dockPickKey != null || dockReplaceKey != null;
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        int pos = grid.getChildAdapterPosition((View) f.getParent());
        return pos == RecyclerView.NO_POSITION ? -1 : pos;
    }

    /**
     * Hold OK (or the Menu key) on a tile: a small glass menu by it (upstream d4ec1da3, 7dbc6917). Move, Add to dock
     * or Replace with another app and Remove from dock change the order, which is kept (Order); Hide from Home adds
     * the app to the owner's hidden set (HiddenApps); Lock with PIN toggles App lock; App info is what holding OK
     * used to do.
     */
    @Override
    public void onTileLongPress(int position, View card) {
        if (tileModeOn() || openPopup != null || card == null) return;
        final String key = adapter.keyAt(position);
        final String pkg = adapter.packageAt(position);
        // The "Default home" tile carries the launcher's own package: no menu for it.
        if (key == null || pkg == null || pkg.equals(getPackageName())) return;
        final boolean movable = position < adapter.movableCount();
        final boolean inDock = position < COLUMNS;
        final String label = adapter.labelAt(position);

        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(label);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (280 * density));
        if (movable) addTileMenuRow(rows, popup, getString(R.string.tile_move), null, () -> startMove(key));
        if (movable && adapter.movableCount() > COLUMNS) {
            if (inDock) {
                addTileMenuRow(rows, popup, getString(R.string.tile_replace_in_dock), null, () -> startDockReplace(key));
                addTileMenuRow(rows, popup, getString(R.string.tile_remove_from_dock), null, () -> removeFromDock(key));
            } else {
                addTileMenuRow(rows, popup, getString(R.string.tile_add_to_dock), null, () -> startDockPick(key));
            }
        }
        addTileMenuRow(rows, popup, getString(R.string.tile_hide), null, () -> hideFromHome(pkg, label, key));
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean locked = AppLock.locked(p, pkg);
        addTileMenuRow(rows, popup, getString(locked ? R.string.tile_unlock : R.string.tile_lock),
                getString(locked ? R.string.app_lock_locked : R.string.app_lock_open), () -> toggleAppLock(pkg, label));
        // The app's info page has an Open button, so a locked app asks first.
        addTileMenuRow(rows, popup, getString(R.string.tile_app_info), null, () -> onTileOpen(pkg, () -> open(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)))));

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max((int) (280 * density), panel.getMeasuredWidth());
        int height = panel.getMeasuredHeight();
        popup.setWidth(width);
        popup.setHeight(height);
        // Next to the tile as magnified; dock tiles sit near the bottom, so their menu opens above them.
        int[] at = new int[2];
        card.getLocationInWindow(at);
        float growX = card.getWidth() * (AppAdapter.FOCUS_SCALE - 1f) / 2f;
        float growY = card.getHeight() * (AppAdapter.FOCUS_SCALE - 1f) / 2f;
        int gap = (int) (12 * density);
        int x = Math.round(at[0] - growX);
        x = Math.max(gap, Math.min(x, root.getWidth() - width - gap));
        int below = Math.round(at[1] + card.getHeight() + growY) + gap;
        boolean fitsBelow = below + height <= root.getHeight() - gap;
        int y = fitsBelow ? below : Math.max(gap, Math.round(at[1] - growY) - gap - height);
        panel.setAlpha(0f);
        panel.setPivotY(fitsBelow ? 0f : height);
        panel.setScaleY(0.92f);
        panel.setTranslationY((fitsBelow ? -8 : 8) * density);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /** A row of the tile menu: the popup closes, then the action runs. status null: a title alone. */
    private void addTileMenuRow(LinearLayout rows, PopupWindow popup, String title, String status, Runnable action) {
        View row = addMenuRow(rows, title, status == null ? "" : status);
        if (status == null) row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
    }

    /**
     * Keys while a tile mode is on, before the views see them.
     * Move: arrows move the tile, OK or Back puts it down. Add to dock: Left and Right pick the dock place, OK takes
     * it. Replace: arrows pick the app in the grid, OK swaps it in. Back cancels either. Other keys (volume, Home)
     * pass through.
     */
    private boolean dispatchTileModes(KeyEvent event) {
        if (!tileModeOn()) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (code == KeyEvent.KEYCODE_MENU) return true;
        if (movingKey != null) {
            if (arrow) {
                if (down) moveBy(code);
                return true;
            }
            if (ok || back) {
                if (!down) finishMove();
                return true;
            }
            return false;
        }
        if (back) {
            if (!down) cancelDockChoice();
            return true;
        }
        int pos = focusedTilePosition();
        if (pos < 0) {
            // Focus slipped off the tiles (it should not): put it back on one rather than act elsewhere.
            if (down && (ok || arrow)) focusTile(dockPickKey != null ? 0 : COLUMNS);
            return ok || arrow;
        }
        int n = adapter.movableCount();
        if (arrow) {
            // Stay on the tiles that can be chosen: the dock row for a place, the grid for an app. No top bar,
            // and no photo stepping at a row's ends.
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return pos % COLUMNS == 0;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return pos % COLUMNS == COLUMNS - 1 || pos >= n - 1;
            if (dockPickKey != null) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP) return pos < 2 * COLUMNS;
            return false;   // Down in the grid: onTileVerticalKey as usual
        }
        if (ok) {
            if (!down) {
                if (dockPickKey != null) {
                    int from = adapter.positionOf(dockPickKey);
                    if (from >= 0 && pos < Math.min(COLUMNS, n)) swapIntoDock(pos, from);
                    else if (from < 0) cancelDockChoice();
                } else {
                    int slot = adapter.positionOf(dockReplaceKey);
                    if (slot >= 0 && pos >= COLUMNS && pos < n) swapIntoDock(slot, pos);
                    else if (slot < 0) cancelDockChoice();
                }
            }
            return true;   // never opens the app while choosing
        }
        return false;
    }

    /** Move: the tile is picked up; arrows move it until OK or Back. */
    private void startMove(String key) {
        int pos = adapter.positionOf(key);
        if (pos < 0 || adapter.movableCount() < 2) return;
        movingKey = key;
        orderAtStart = adapter.movableKeys();
        dimOthers();
        focusTile(pos);
        Toast.makeText(this, getString(R.string.tile_move_hint, adapter.labelAt(pos)), Toast.LENGTH_LONG).show();
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            finishMove();
            return;
        }
        int to;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        final int target = to;
        keepScroll(() -> adapter.move(from, target));
        refocusAfterLayout(target);
    }

    /** Puts the carried tile down where it is and keeps the order if it changed. */
    private void finishMove() {
        if (movingKey == null) return;
        movingKey = null;
        if (!adapter.movableKeys().equals(orderAtStart)) saveOrder();
        orderAtStart = null;
        dimOthers();
    }

    /** Keeps the tiles' current order as the owner's (Order), which the next reload follows. */
    private void saveOrder() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        Order order = new Order(Order.dockKey(config.dockApps), adapter.movableKeys());
        Order.save(p, order);
        adapter.setSavedOrder(Order.ranks(order, config.dockApps));
    }

    /** Add to dock: focus goes to the dock, where OK chooses the place the app takes. */
    private void startDockPick(String key) {
        if (adapter.positionOf(key) < 0) return;
        dockPickKey = key;
        dimOthers();
        focusTile(0);
        Toast.makeText(this, getString(R.string.dock_pick_place, adapter.labelAt(adapter.positionOf(key))),
                Toast.LENGTH_LONG).show();
    }

    /** Replace with another app: focus goes to the grid, where OK chooses the app that takes the dock place. */
    private void startDockReplace(String key) {
        int slot = adapter.positionOf(key);
        if (slot < 0) return;
        dockReplaceKey = key;
        dimOthers();
        focusTile(COLUMNS);
        Toast.makeText(this, getString(R.string.dock_pick_app, adapter.labelAt(slot)), Toast.LENGTH_LONG).show();
    }

    /** Back while choosing: nothing changes and focus goes back to the app the menu was for. */
    private void cancelDockChoice() {
        String key = dockPickKey != null ? dockPickKey : dockReplaceKey;
        dockPickKey = null;
        dockReplaceKey = null;
        dimOthers();
        int pos = adapter.positionOf(key);
        if (pos >= 0) focusTile(pos);
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        dockPickKey = null;
        dockReplaceKey = null;
        String slotKey = adapter.keyAt(slot);
        if (pos != slot && slotKey != null) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            saveOrder();
        }
        dimOthers();
        refocusAfterLayout(slot);
    }

    /** Remove from dock: the app goes to the start of the grid and the first grid app takes the dock's last place. */
    private void removeFromDock(String key) {
        int pos = adapter.positionOf(key);
        if (pos < 0 || pos >= COLUMNS || adapter.movableCount() <= COLUMNS) return;
        keepScroll(() -> adapter.move(pos, COLUMNS));
        saveOrder();
        refocusAfterLayout(pos);
    }

    /** Hide from Home: the app joins the owner's hidden set; Settings menu, Hidden apps, or the App library bring it back. */
    private void hideFromHome(String pkg, String label, String key) {
        int pos = Math.max(0, adapter.positionOf(key));
        setOnHome(pkg, false, true);
        Toast.makeText(this, getString(R.string.tile_hidden_toast, label), Toast.LENGTH_LONG).show();
        grid.post(() -> focusTile(Math.min(pos, adapter.getItemCount() - 1)));
    }

    /**
     * Puts an app on Home or takes it off (HiddenApps). reload: rebuild the tiles now; otherwise the caller does it
     * once it is done (a list of toggles reloads when it closes, not on every press). Focus is left to the caller.
     */
    private void setOnHome(String pkg, boolean onHome, boolean reload) {
        HiddenApps.setOnHome(getSharedPreferences(PREFS, MODE_PRIVATE), pkg, onHome, isTvApp(pkg),
                config.hiddenApps.contains(pkg));
        applyOwnerChoices();
        if (reload) reloadTiles();
    }

    /** The owner's choices (HiddenApps) into the tiles and the card check, from the next reload. */
    private void applyOwnerChoices() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> hidden = HiddenApps.get(p), added = HiddenApps.added(p);
        // config.xml's hidden_apps plus the apps the owner hid, less those added back: no tile, no card.
        hiddenApps = HiddenApps.effective(config.hiddenApps, hidden, added);
        adapter.setOwnerChoices(hidden, added);
    }

    /** Whether the app has a TV (leanback) entry, which puts it on Home by itself. */
    private boolean isTvApp(String pkg) {
        return !getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg), 0).isEmpty();
    }

    /**
     * The phone entry of each app the owner added from the App library that has no TV entry (one query per such
     * app, usually none), for the tiles.
     */
    private List<ResolveInfo> addedEntries(List<ResolveInfo> leanback) {
        Set<String> added = HiddenApps.added(getSharedPreferences(PREFS, MODE_PRIVATE));
        if (added.isEmpty()) return Collections.emptyList();
        for (ResolveInfo ri : leanback) if (ri.activityInfo != null) added.remove(ri.activityInfo.packageName);
        List<ResolveInfo> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String pkg : added) {
            List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0);
            for (ResolveInfo ri : found) {
                if (ri.activityInfo != null && ri.activityInfo.exported) {
                    out.add(ri);
                    break;
                }
            }
        }
        return out;
    }

    /** Lock with PIN / Unlock from the tile menu: the PIN first (a new one when none is set), as in Settings. */
    private void toggleAppLock(String pkg, String label) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean lock = !AppLock.locked(p, pkg);
        Runnable apply = () -> {
            AppLock.setLocked(p, pkg, lock);
            Toast.makeText(this, getString(lock ? R.string.tile_locked_toast : R.string.tile_unlocked_toast, label),
                    Toast.LENGTH_SHORT).show();
        };
        if (AppLock.hasPin(p)) verifyPin(getString(R.string.app_lock_enter), apply);
        else newPin(apply);
    }

    /** Ends whichever tile mode is on: a move keeps the order so far, a dock choice is cancelled. */
    private void endTileModes() {
        if (!tileModeOn()) return;
        finishMove();
        dockPickKey = null;
        dockReplaceKey = null;
        dimOthers();
    }

    /** Sets every tile on screen to its alpha for the current mode (tileAlpha). */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /**
     * Dimmed unless it is the carried tile, or a tile that can be chosen: a dock place for Add to dock, a grid app
     * for Replace (with the dock app being replaced kept bright).
     */
    private float tileAlpha(int pos) {
        if (pos == RecyclerView.NO_POSITION || !tileModeOn()) return 1f;
        String key = adapter.keyAt(pos);
        if (movingKey != null) return movingKey.equals(key) ? 1f : DIMMED;
        if (dockPickKey != null) return pos < COLUMNS || dockPickKey.equals(key) ? 1f : DIMMED;
        return pos >= COLUMNS || dockReplaceKey.equals(key) ? 1f : DIMMED;
    }

    /**
     * Applies an adapter change without the grid jumping (upstream 33ca9770). After a move the layout manager
     * anchors on the focused tile, shifting every other row; pinning the first visible row at its current offset
     * keeps the scroll where it was. onTileFocused still scrolls afterwards if the moved tile ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, focus (and the glass and magnification) on the tile at pos. The grid
     * has a fixed size, so it applies adapter changes on the next animation frame; wait for that, at most 10 frames.
     * No focus change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    /** Settings menu, Hidden apps: every app the owner hid, OK shows it on Home again (or hides it back). */
    private void showHiddenApps() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> owner = HiddenApps.get(p);
        if (owner.isEmpty()) {
            Toast.makeText(this, R.string.hidden_apps_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        View anchor = btnSettings;
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(R.string.settings_menu_hidden_apps);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<String[]> apps = new ArrayList<>();   // {label, package}
        for (String pkg : owner) apps.add(new String[] {appLabel(pkg), pkg});
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        for (String[] app : apps) {
            final String pkg = app[1];
            View row = addMenuRow(rows, app[0], getString(R.string.hidden_state_hidden));
            final TextView status = row.findViewById(R.id.status);
            row.setOnClickListener(v -> {
                boolean hide = !HiddenApps.get(getSharedPreferences(PREFS, MODE_PRIVATE)).contains(pkg);
                setOnHome(pkg, !hide, false);
                status.setText(hide ? R.string.hidden_state_hidden : R.string.hidden_state_shown);
                tilesOutOfDate = true;
            });
        }
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            reloadIfOutOfDate();
        });
        fitBelow(popup, panel, anchor);
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** A list of Home toggles changed something; the tiles are rebuilt once it closes. */
    private boolean tilesOutOfDate;

    private void reloadIfOutOfDate() {
        if (!tilesOutOfDate) return;
        tilesOutOfDate = false;
        reloadTiles();
    }

    /** Settings menu, App library: every installed app, TV or not; OK puts it on Home or takes it off. */
    private AppLibraryDialog library;

    private void showAppLibrary() {
        if (library != null) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        library = new AppLibraryDialog(this,
                (pkg, tv) -> HiddenApps.onHome(tv, config.hiddenApps.contains(pkg),
                        HiddenApps.get(p).contains(pkg), HiddenApps.added(p).contains(pkg)),
                (pkg, onHome) -> {
                    setOnHome(pkg, onHome, false);
                    tilesOutOfDate = true;
                },
                (pkg, open) -> onTileOpen(pkg, open),
                () -> {
                    library = null;
                    reloadIfOutOfDate();
                });
        library.show();
    }

    /** The Reset order row's status: whether the owner's order is in force. */
    private String orderState() {
        return getString(Order.applies(getSharedPreferences(PREFS, MODE_PRIVATE), config.dockApps)
                ? R.string.reset_order_custom : R.string.reset_order_default);
    }

    /** Settings menu, Reset order: forgets the owner's order, so dock_apps and the alphabet decide again. */
    private void resetOrder() {
        Order.clear(getSharedPreferences(PREFS, MODE_PRIVATE));
        adapter.setSavedOrder(Collections.emptyMap());
        reloadTiles();
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /**
     * Plays the greeting intro once per TV start, when Home is in front within a couple of minutes of it;
     * a TV that wakes into an app and comes Home later gets the plain line.
     */
    private void playIntroWhenReady() {
        if (introDueAt == 0) return;
        if (android.os.SystemClock.elapsedRealtime() - introDueAt > INTRO_WINDOW_MS) {
            introDueAt = 0;
            return;
        }
        handler.removeCallbacks(introStart);
        handler.postDelayed(introStart, 150);
    }

    /** Waits for the first layout (a cold start resumes before it), then plays. */
    private final Runnable introStart = new Runnable() {
        @Override
        public void run() {
            if (!resumed || introDueAt == 0) return;
            if (greeting.getWidth() == 0 || root.getWidth() == 0) {
                handler.postDelayed(this, 150);
                return;
            }
            introDueAt = 0;
            handler.removeCallbacks(tidyUp);
            cycle.reset();   // the intro types the greeting, so the line shows it
            intro.play(LastUse.line(MainActivity.this));
        }
    };

    // ---- idle ------------------------------------------------------------------------------------

    /**
     * Left alone for IDLE_MS, Home fades to the photo and the clock corner: the dock, the rows, the top
     * bar, the Now playing card and the app name go. Any key brings them back.
     */
    private static final long IDLE_MS = 5 * 60_000L, IDLE_FADE_MS = 1500, IDLE_WAKE_MS = 300;
    private boolean idle;
    private Tween idleFade;
    private View[] idleViews;
    private float[] idleAlpha;
    private final Runnable goIdle = () -> setIdle(true, IDLE_FADE_MS);

    /** Starts the idle countdown again (a key, Home coming back). */
    private void poke() {
        handler.removeCallbacks(goIdle);
        if (resumed) handler.postDelayed(goIdle, IDLE_MS);
    }

    private void setIdle(boolean on, long ms) {
        if (on == idle) return;
        idle = on;
        if (idleViews == null) {
            idleViews = new View[] {grid, findViewById(R.id.topButtons), nowPlaying, heroName, glass, ambient};
        }
        final int n = idleViews.length;
        final float[] from = new float[n], to = new float[n];
        if (on) idleAlpha = new float[n];
        for (int i = 0; i < n; i++) {
            from[i] = idleViews[i].getAlpha();
            if (on) idleAlpha[i] = from[i];
            to[i] = on ? 0f : idleAlpha[i];
        }
        if (idleFade != null) idleFade.cancel();
        idleFade = new Tween(0f, t -> {
            for (int i = 0; i < n; i++) idleViews[i].setAlpha(from[i] + (to[i] - from[i]) * t);
        });
        idleFade.to(1f, ms);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String text = intent.getStringExtra(EXTRA_BANNER);
        if (text != null) pendingBanner = text;   // onResume shows it
    }

    // ---- banner (a message from the phone remote) -------------------------------------------------

    private String pendingBanner;
    private View banner;

    /** The message large in the middle of the screen until someone presses OK or Back (or the phone clears it). */
    private void showBanner(String text) {
        if (text == null || text.trim().isEmpty()) return;
        hideBanner();
        if (openPopup != null) openPopup.dismiss();
        handler.removeCallbacks(goIdle);
        setIdle(false, 0);

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (48 * density);
        card.setPadding(pad, (int) (36 * density), pad, (int) (32 * density));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF01C1C22);
        bg.setCornerRadius(32 * density);
        bg.setStroke((int) Math.max(1, density), 0x33FFFFFF);
        card.setBackground(bg);
        card.setElevation(24 * density);

        TextView label = new TextView(this);
        label.setText(R.string.banner_title);
        label.setTextColor(0xFFFF9F0A);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        label.setLetterSpacing(0.12f);
        label.setAllCaps(true);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(label);

        TextView body = new TextView(this);
        body.setText(text.trim());
        body.setTextColor(Color.WHITE);
        // Large enough to read across the room; long messages step down so they still fit.
        int len = text.trim().length();
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, len <= 40 ? 56 : len <= 120 ? 42 : len <= 250 ? 32 : 26);
        body.setLineSpacing(0, 1.12f);
        body.setGravity(Gravity.CENTER);
        body.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (18 * density);
        bp.bottomMargin = (int) (26 * density);
        card.addView(body, bp);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.banner_hint,
                android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date())));
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        card.addView(hint);

        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        card.setMinimumWidth((int) (420 * density));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, cp);
        body.setMaxWidth(maxWidth - 2 * pad);

        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setElevation(100 * density);
        scrim.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        scrim.animate().alpha(1f).setDuration(220).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
        banner = scrim;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void hideBanner() {
        final View b = banner;
        if (b == null) return;
        banner = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        b.animate().alpha(0f).setDuration(180).withEndAction(() -> root.removeView(b)).start();
        poke();
    }

    // ---- TV lock (from the phone remote) ------------------------------------------------------------

    private View lockView;

    /** "This TV is locked" over everything, with the PIN pad; OK opens the pad again after Back. */
    private void showLock() {
        if (lockView == null) {
            if (openPopup != null) openPopup.dismiss();
            handler.removeCallbacks(goIdle);
            setIdle(false, 0);
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setGravity(Gravity.CENTER);
            v.setBackgroundColor(0xFA0B0B0F);
            v.setClickable(true);
            v.setOnClickListener(x -> askUnlockPin());

            TextView icon = new TextView(this);
            icon.setText("🔒");
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
            v.addView(icon);

            TextView title = new TextView(this);
            title.setText(R.string.lock_title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = (int) (16 * density);
            v.addView(title, tp);

            TextView sub = new TextView(this);
            sub.setText(R.string.lock_subtitle);
            sub.setTextColor(0xB3FFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = (int) (10 * density);
            v.addView(sub, sp);

            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            v.setElevation(90 * density);   // under a message from the phone (banner), over everything else
            lockView = v;
        }
        askUnlockPin();
    }

    private boolean unlockPadOpen;
    private Dialog unlockPad;

    private void askUnlockPin() {
        if (unlockPadOpen || lockView == null || !resumed) return;
        unlockPadOpen = true;
        unlockPad = verifyPin(getString(R.string.lock_pin_title), () -> {
            unlockPadOpen = false;
            TvLock.unlock(this);
            hideLock();
        }, () -> unlockPadOpen = false);
    }

    private void hideLock() {
        // Unlocked from the phone: the PIN pad goes too.
        if (unlockPad != null) {
            if (unlockPad.isShowing()) unlockPad.dismiss();
            unlockPad = null;
        }
        unlockPadOpen = false;
        final View v = lockView;
        if (v == null) return;
        lockView = null;
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(v)).start();
        poke();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A message on screen takes every key; OK or Back closes it.
        if (banner != null) {
            int k = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE)) {
                hideBanner();
            }
            return true;
        }
        // Locked: every key stays here; OK brings the PIN pad back.
        if (lockView != null) {
            int k = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER || (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9))) {
                askUnlockPin();
            }
            return true;
        }
        // Idle: the first key only brings everything back (its key-up is ignored by the views too).
        if (idle) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) setIdle(false, IDLE_WAKE_MS);
            poke();
            return true;
        }
        poke();
        // The first key during the intro sends the greeting to its corner instead of moving focus.
        if (intro != null && intro.running()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) intro.skip();
            return true;
        }
        if (dispatchTileModes(event)) return true;
        // The remote's Menu key on a tile opens the same menu as holding OK (upstream cce45dc5).
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && openPopup == null) {
            int pos = focusedTilePosition();
            if (pos >= 0) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onTileLongPress(pos, getCurrentFocus());
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        // An occasion (birthday, Tết…) takes the line for the day; the minute tick moves on at midnight.
        java.time.LocalDate today = java.time.LocalDate.now();
        String occasion = Occasions.on(today, occasions, holidaysOn);
        updateOccasionCard(config == null ? null : Occasions.play(today, occasions, holidaysOn,
                config.christmasVideo, config.newYearVideo, config.tetVideo));
        if (!occasion.isEmpty()) greetingLine = occasion;
        else {
            String line = greetingName.isEmpty() ? getString(res) : getString(res) + ", " + greetingName;
            String emoji = "👋";
            greetingLine = greeting.getPaint().hasGlyph(emoji) ? line + " " + emoji : line;
        }
        if (cycle != null) cycle.refresh();
        else greeting.setText(greetingLine);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    // ---- folded top bar ----------------------------------------------------------------------------

    private View topChevron;
    private Tween barOpen;
    private boolean barExpanded;

    /** The pills that fold away behind the chevron; Settings always shows. */
    private View[] barPills() {
        return new View[] {btnInputs, btnReport, btnWifi};
    }

    /** Slides the pills out (focus came to the top bar) or back behind the chevron. */
    private void setBarOpen(boolean open) {
        if (open == barExpanded) return;
        barExpanded = open;
        for (View pill : barPills()) pill.setFocusable(open);
        barOpen.to(open ? 1f : 0f, FOLD_MS);
    }

    /** The bar at an opening: 0 the chevron and Settings, 1 every pill; widths and margins grow from nothing. */
    private void openBar(float e) {
        int h = View.MeasureSpec.makeMeasureSpec((int) (42 * density), View.MeasureSpec.EXACTLY);
        int free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        for (View pill : barPills()) {
            if (pill.getVisibility() == View.GONE) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
            if (e >= 1f) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                pill.measure(free, h);
                lp.width = Math.round(pill.getMeasuredWidth() * e);
            }
            lp.setMarginStart(Math.round(10 * density * e));
            pill.setLayoutParams(lp);
            pill.setAlpha(e);
        }
        ViewGroup.LayoutParams cl = topChevron.getLayoutParams();
        if (e <= 0f) {
            cl.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            topChevron.measure(free, h);
            cl.width = Math.round(topChevron.getMeasuredWidth() * (1f - e));
        }
        topChevron.setLayoutParams(cl);
        topChevron.setAlpha(0.8f * (1f - e));
    }

    /** Folds the top bar and the Now playing card to their icons (true) or opens them out; focused ones stay open. */
    private void setTidy(boolean on, boolean animate) {
        tidy = on;
        long ms = animate ? FOLD_MS : 0;
        float v = on ? 0f : 1f;
        if (!btnInputs.isFocused()) inputsFold.to(v, ms);
        if (!btnReport.isFocused()) reportFold.to(v, ms);
        if (!btnWifi.isFocused()) wifiFold.to(v, ms);
        tickerFold.to(v, ms);
        if (!nowPlaying.isFocused()) cardFold.to(v, ms);
    }

    /** A labelled pill (HDMI, Report) at a fold: its word narrows and fades, and the paddings close to the icon's. */
    private void foldPill(TextView pill, int labelRes, FoldSpan span, float v) {
        String label = getString(labelRes);
        if (v >= 1f) {
            pill.setText(label);
        } else if (v <= 0f) {
            pill.setText("");
        } else {
            SpannableString s = new SpannableString(label);
            span.fraction = v;
            s.setSpan(span, 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pill.setText(s);
        }
        pill.setCompoundDrawablePadding(Math.round(9 * density * v));
        pill.setPaddingRelative(Math.round((14 + 2 * v) * density), pill.getPaddingTop(),
                Math.round((14 + 6 * v) * density), pill.getPaddingBottom());
    }

    /** The Screen time panel, centred; the tabs switch between 24 hours and 7 days as they take focus. */
    private void showReport() {
        final ReportPanel[] panel = new ReportPanel[1];
        final boolean[] want = new boolean[1];
        panel[0] = new ReportPanel(this, week -> {
            want[0] = week;
            panel[0].loading(week);
            final Context app = getApplicationContext();
            ScreenTime.EXECUTOR.execute(() -> {
                ScreenTime.Report r = ScreenTime.load(app, week);
                handler.post(() -> {
                    if (want[0] == week) panel[0].bind(r, week);
                });
            });
        });
        PopupWindow popup = glassPopup(panel[0].view, (int) (720 * density));
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel[0].tab24.requestFocus();
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // Compact pill: the icon says Wi-Fi or Ethernet; only "Offline" is spelled out.
        if (online) text = "";
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A replacement span alone on a line gets no font metrics and isn't drawn: lead with a zero-width space.
            String lead = text.isEmpty() ? "\u200B" : text + " ";
            SpannableString label = new SpannableString(lead + badge);
            // The green tag folds away when tidy; a red "VPN off" is a warning and stays.
            Object shown = span;
            wifiBadgeFold = null;
            if (vpn) {
                wifiBadgeFold = new FoldSpan(span, 0);
                wifiBadgeFold.fraction = wifiFold.value();
                shown = wifiBadgeFold;
            }
            label.setSpan(shown, lead.length(), label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            wifiText = label;
            btnWifi.setText(label);
        } else {
            wifiBadgeFold = null;
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        // Icon alone: no gap after it and even padding, like the Settings button.
        boolean iconOnly = btnWifi.getText().length() == 0;
        // No name: the VPN tag's own margin is gap enough after the icon.
        btnWifi.setCompoundDrawablePadding(iconOnly || text.isEmpty() ? 0 : (int) (9 * density));
        int padEnd = (int) ((iconOnly || vpn || vpnDown ? 14 : 20) * density);
        btnWifi.setPaddingRelative((int) (14 * density), btnWifi.getPaddingTop(), padEnd, btnWifi.getPaddingBottom());
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        // A CEC device is a child input: tune its parent HDMI port, which is what the TV can open (upstream 7fa2aa84).
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) inputId = info.getParentId();
            } catch (Throwable ignored) {
                // Keep the id as given.
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                // A name the owner gave this port (hold OK on it): shown instead, with the port's own name beside it.
                String custom = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_INPUT_NAME + id, "");
                final String portName = label;
                View row = addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av,
                        custom.isEmpty() ? label : custom, state, () -> openInput(id));
                if (!custom.isEmpty()) {
                    TextView status = row.findViewById(R.id.status);
                    CharSequence was = status.getVisibility() == View.VISIBLE ? status.getText() : null;
                    status.setText(was == null ? portName : portName + " · " + was);
                    status.setVisibility(View.VISIBLE);
                }
                row.setOnLongClickListener(v -> {
                    popup.dismiss();
                    renameInput(id, portName);
                    return true;
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn; a post straight
                // from onResume would run first, as the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    /**
     * Hold OK on a port in Inputs: a name of the owner's for it ("PlayStation" for HDMI 4), typed on the TV's
     * keyboard. Empty, or "Use HDMI 4", goes back to the port's own name. Kept per input id in the preferences.
     */
    private void renameInput(String id, String portName) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (24 * density);
        panel.setPadding(pad, pad, pad, (int) (18 * density));

        TextView title = new TextView(this);
        title.setText(getString(R.string.input_rename_title, portName));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(UiFont.get(this));
        panel.addView(title);

        final android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setText(p.getString(PREF_INPUT_NAME + id, ""));
        name.setHint(portName);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0x66FFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        name.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = (int) (14 * density);
        panel.addView(name, np);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (16 * density);
        panel.addView(buttons, bp);

        final Dialog dialog = glassDialog(panel);
        Runnable save = () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty() || n.equals(portName)) p.edit().remove(PREF_INPUT_NAME + id).apply();
            else p.edit().putString(PREF_INPUT_NAME + id, n).apply();
            dialog.dismiss();
            showInputs();
        };
        name.setOnEditorActionListener((v, actionId, event) -> {
            save.run();
            return true;
        });
        buttons.addView(dialogButton(getString(R.string.input_rename_save), save));
        buttons.addView(dialogButton(getString(R.string.input_rename_reset, portName), () -> {
            name.setText("");
            save.run();
        }));
        dialog.show();
        name.requestFocus();
    }

    /** A pill-shaped button for the small dialogs built in code. */
    private TextView dialogButton(String text, Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setTypeface(UiFont.get(this));
        b.setFocusable(true);
        b.setClickable(true);
        b.setGravity(Gravity.CENTER);
        int h = (int) (18 * density), v = (int) (9 * density);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (10 * density));
        b.setLayoutParams(lp);
        return b;
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        showCardPromptIfDue();
        showUpdatePrompt();   // waits for every card prompt to be answered
    }

    private void showCardPromptIfDue() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = glassDialog(panel);
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** A centred glass panel over the dimmed home screen: the card and update prompts. */
    private Dialog glassDialog(View panel) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        return dialog;
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        fitBelow(popup, panel, anchor);
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }

    // ---- App lock -------------------------------------------------------------------------------

    /** Wrong PINs in a row, and until when the pad refuses after too many (AppLock.MAX_TRIES). */
    private int pinFails;
    private long pinWaitUntil;

    @Override
    public void onTileOpen(String pkg, Runnable open) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!AppLock.locked(p, pkg) || AppLock.inGrace(AppLock.unlockedAt, SystemClock.elapsedRealtime(), AppLock.grace(p))) {
            open.run();
            return;
        }
        verifyPin(getString(R.string.app_lock_enter_for, appLabel(pkg)), () -> {
            AppLock.unlockedAt = SystemClock.elapsedRealtime();
            open.run();
        });
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private String appLockState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!AppLock.hasPin(p)) return getString(R.string.app_lock_no_pin);
        int n = AppLock.apps(p).size();
        return n == 0 ? getString(R.string.settings_menu_off) : getString(R.string.app_lock_count, n);
    }

    /** Settings, App lock: the PIN first (a new one when none is set), then the list of apps. */
    private void openAppLock() {
        if (AppLock.hasPin(getSharedPreferences(PREFS, MODE_PRIVATE))) {
            verifyPin(getString(R.string.app_lock_enter), this::showAppLockList);
        } else {
            newPin(this::showAppLockList);
        }
    }

    private void verifyPin(String title, Runnable then) {
        verifyPin(title, then, null);
    }

    /** onCancel: the pad was closed without the right PIN (Back). Returns the pad's dialog. */
    private Dialog verifyPin(String title, Runnable then, Runnable onCancel) {
        View panel = getLayoutInflater().inflate(R.layout.dialog_pin, root, false);
        Dialog dialog = glassDialog(panel);
        if (onCancel != null) dialog.setOnCancelListener(d -> onCancel.run());
        PinPad.show(dialog, panel, title, (pad, pin) -> {
            long now = SystemClock.elapsedRealtime();
            if (now < pinWaitUntil) {
                pad.fail(getString(R.string.app_lock_wait, (int) ((pinWaitUntil - now + 999) / 1000)));
                return;
            }
            if (AppLock.checkPin(getSharedPreferences(PREFS, MODE_PRIVATE), pin)) {
                pinFails = 0;
                pad.dismiss();
                then.run();
                return;
            }
            if (++pinFails >= AppLock.MAX_TRIES) {
                pinFails = 0;
                pinWaitUntil = now + AppLock.WAIT_MS;
                pad.fail(getString(R.string.app_lock_wait, (int) (AppLock.WAIT_MS / 1000)));
            } else {
                pad.fail(getString(R.string.app_lock_wrong));
            }
        });
        return dialog;
    }

    /** A new PIN, typed twice. */
    private void newPin(Runnable then) {
        final String[] first = new String[1];
        View panel = getLayoutInflater().inflate(R.layout.dialog_pin, root, false);
        PinPad.show(glassDialog(panel), panel, getString(R.string.app_lock_new), (pad, pin) -> {
            if (first[0] == null) {
                first[0] = pin;
                pad.next(getString(R.string.app_lock_confirm));
                return;
            }
            if (!first[0].equals(pin)) {
                first[0] = null;
                pad.next(getString(R.string.app_lock_new));
                pad.fail(getString(R.string.app_lock_mismatch));
                return;
            }
            AppLock.setPin(getSharedPreferences(PREFS, MODE_PRIVATE), pin);
            pad.dismiss();
            Toast.makeText(this, R.string.app_lock_saved, Toast.LENGTH_SHORT).show();
            then.run();
        });
    }

    /** Dropdown under the Settings pill: Change PIN, then every app with a TV entry; OK toggles its lock. */
    private void showAppLockList() {
        View anchor = btnSettings;
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(R.string.settings_menu_app_lock);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        View pinRow = addMenuRow(rows, getString(R.string.app_lock_change_pin), "");
        pinRow.setOnClickListener(v -> {
            popup.dismiss();
            newPin(this::showAppLockList);
        });
        View graceRow = addMenuRow(rows, getString(R.string.app_lock_grace), graceLabel(AppLock.grace(prefs)));
        final TextView graceStatus = graceRow.findViewById(R.id.status);
        graceRow.setOnClickListener(v -> {
            long g = AppLock.nextGrace(AppLock.grace(prefs));
            AppLock.setGrace(prefs, g);
            graceStatus.setText(graceLabel(g));
        });

        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        final List<String[]> apps = new ArrayList<>();   // {label, package}
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[] {String.valueOf(ri.loadLabel(pm)), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        Set<String> locked = AppLock.apps(prefs);
        for (String[] app : apps) {
            final String pkg = app[1];
            View row = addMenuRow(rows, app[0], getString(locked.contains(pkg) ? R.string.app_lock_locked : R.string.app_lock_open));
            final TextView status = row.findViewById(R.id.status);
            row.setOnClickListener(v -> {
                boolean on = !AppLock.apps(prefs).contains(pkg);
                AppLock.setLocked(prefs, pkg, on);
                status.setText(on ? R.string.app_lock_locked : R.string.app_lock_open);
            });
        }
        fitBelow(popup, panel, anchor);
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private String graceLabel(long ms) {
        if (ms == 0) return getString(R.string.app_lock_grace_always);
        long min = ms / 60_000;
        return min < 60 ? getString(R.string.app_lock_grace_min, (int) min) : getString(R.string.app_lock_grace_hours, (int) (min / 60));
    }

    /** Scroll inside the panel instead of running off the bottom of a 720p screen. */
    private void fitBelow(PopupWindow popup, View panel, View anchor) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
    }

    // ---- Now playing ----------------------------------------------------------------------------

    private void setUpNowPlaying(boolean on) {
        nowPlayingOn = on;
        nowPlaying = findViewById(R.id.nowPlaying);
        nowPlayingArt = findViewById(R.id.nowPlayingArt);
        nowPlayingState = findViewById(R.id.nowPlayingState);
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle);
        nowPlayingSubtitle = findViewById(R.id.nowPlayingSubtitle);
        UiFont.watch(nowPlayingTitle);
        UiFont.watch(nowPlayingSubtitle);
        sessionListener = new ComponentName(this, NowPlayingListener.class);
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        nowPlaying.setContentDescription(getString(R.string.now_playing_description));
        // Tidy, the card is its artwork and state icon; focused, it opens out on solid black to be read,
        // and lifts a little.
        nowPlayingText = findViewById(R.id.nowPlayingText);
        final android.graphics.drawable.Drawable cardGlass = nowPlaying.getBackground();
        final GradientDrawable cardSolid = new GradientDrawable();
        cardSolid.setColor(0xFF000000);
        cardSolid.setCornerRadius(16 * density);
        cardSolid.setStroke(Math.max(1, Math.round(density)), 0x59FFFFFF);
        cardFold = new Tween(0f, this::foldCard);
        foldCard(0f);
        // Progress along the bottom edge, inside the card's rounded corners.
        progressStrip = new ProgressStrip(density);
        nowPlaying.setForeground(progressStrip);
        nowPlaying.setClipToOutline(true);
        nowPlaying.setOnFocusChangeListener((v, focused) -> {
            progressStrip.setFocused(focused);
            float s = focused ? NOW_PLAYING_SCALE : 1.0f;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setBackground(focused ? cardSolid : cardGlass);
            cardFold.to(focused || !tidy ? 1f : 0f, FOLD_MS * 2 / 3);
        });
        final float artRadius = 9 * density;
        nowPlayingArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), artRadius);
            }
        });
        nowPlayingArt.setClipToOutline(true);
        nowPlaying.setOnClickListener(v -> {
            if (todayPlay != null) {
                playOccasion(todayPlay);
                return;
            }
            NowPlaying.run(() -> {
            MediaController c = shownSession;
            if (c == null) return null;
            PlaybackState st = c.getPlaybackState();
            if (st != null && NowPlaying.isActive(st.getState())) c.getTransportControls().pause();
            else c.getTransportControls().play();
            return null;
            }, null);
        });
        nowPlaying.setOnLongClickListener(v -> {
            if (todayPlay != null) return true;   // the occasion card has nothing to open
            NowPlaying.run(() -> {
                openNowPlayingApp();
                return null;
            }, null);
            return true;
        });
        nowPlaying.setOnKeyListener((v, keyCode, event) -> {
            boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
            if (!left && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(COLUMNS - 1);                      // back to the tile it is entered from
                return true;
            }
            if (todayPlay != null) return true;            // nothing to skip on the occasion card
            if (event.getRepeatCount() > 0) return true;   // one skip per press, not a run of them
            NowPlaying.run(() -> {
                MediaController c = shownSession;
                if (c == null) return null;
                if (left) c.getTransportControls().skipToPrevious();
                else c.getTransportControls().skipToNext();
                return null;
            }, null);
            nowPlayingArt.animate().translationX((left ? -6 : 6) * density).setDuration(90)
                    .withEndAction(() -> nowPlayingArt.animate().translationX(0f).setDuration(160).start()).start();
            return true;
        });
        // The card sits in the space above the dock: keep it there as the layout settles, and out of the
        // way while the grid is scrolled up over that space.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> grid.post(this::placeNowPlaying));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (nowPlayingHasSession || todayPlay != null) placeNowPlaying();
            }
        });
    }

    private void startNowPlaying() {
        if (!nowPlayingOn || !NowPlaying.working()) return;
        NowPlaying.run(() -> {
            if (!listenerGranted()) {
                nowPlayingHasSession = false;
                placeNowPlaying();
                return null;
            }
            if (sessions == null) sessions = getSystemService(MediaSessionManager.class);
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, sessionListener, handler);
            watch(sessions.getActiveSessions(sessionListener));
            return null;
        }, null);
        if (!NowPlaying.working()) {
            nowPlayingHasSession = false;
            placeNowPlaying();
        }
    }

    private void stopNowPlaying() {
        handler.removeCallbacks(nowPlayingRefresh);
        handler.removeCallbacks(progressTick);
        NowPlaying.run(() -> {
            if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            unwatch();
            return null;
        }, null);
    }

    /** Whether the notification listener is enabled, without which Android lists no sessions. */
    private boolean listenerGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(sessionListener);
        }
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && Arrays.asList(enabled.split(":")).contains(sessionListener.flattenToString());
    }

    /** Follows every active session, so whichever one starts playing can take the card. */
    private void watch(List<MediaController> list) {
        unwatch();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(sessionCallback, handler);
                watched.add(c);
            }
        }
        refreshNowPlaying();
    }

    private void unwatch() {
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(sessionCallback);
            } catch (Exception ignored) {
                // A session that died already has nothing to unregister.
            }
        }
        watched.clear();
    }

    /** Callbacks can come in bursts (state, then metadata); draw once after them. */
    private void scheduleNowPlaying() {
        handler.removeCallbacks(nowPlayingRefresh);
        handler.postDelayed(nowPlayingRefresh, 120);
    }

    private void refreshNowPlaying() {
        int n = watched.size();
        int[] states = new int[n];
        String[] packages = new String[n];
        for (int i = 0; i < n; i++) {
            PlaybackState st = watched.get(i).getPlaybackState();
            states[i] = st != null ? st.getState() : NowPlaying.STATE_NONE;
            packages[i] = watched.get(i).getPackageName();
        }
        int pick = NowPlaying.choose(states, packages, getPackageName());
        shownSession = pick >= 0 ? watched.get(pick) : null;
        nowPlayingHasSession = shownSession != null;
        if (nowPlayingHasSession && todayPlay == null) bindNowPlaying(shownSession, states[pick]);
        placeNowPlaying();
        updateProgress();
    }

    private ProgressStrip progressStrip;
    private final Runnable progressTick = () -> NowPlaying.run(() -> {
        updateProgress();
        return null;
    }, null);

    /**
     * Moves the strip to where the shown session is, and again each second while it plays and the card
     * is up. Hidden on the occasion card and for streams with no duration.
     */
    private void updateProgress() {
        handler.removeCallbacks(progressTick);
        if (progressStrip == null) return;
        MediaController c = shownSession;
        PlaybackState st = c != null && todayPlay == null ? c.getPlaybackState() : null;
        if (st == null) {
            progressStrip.set(-1f);
            return;
        }
        MediaMetadata md = c.getMetadata();
        long duration = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        boolean playing = NowPlaying.isActive(st.getState());
        progressStrip.set(NowPlaying.progress(st.getPosition(), st.getLastPositionUpdateTime(),
                st.getPlaybackSpeed(), playing, duration, android.os.SystemClock.elapsedRealtime()));
        if (playing && resumed && nowPlayingVisible) handler.postDelayed(progressTick, 1000);
    }

    private void bindNowPlaying(MediaController c, int state) {
        String pkg = c.getPackageName();
        String appLabel = pkg;
        try {
            appLabel = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
            // A package we can't see keeps its package name.
        }
        MediaMetadata md = c.getMetadata();
        String title = NowPlaying.title(text(md, MediaMetadata.METADATA_KEY_TITLE),
                text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE), appLabel);
        nowPlayingTitle.setText(title);
        String sub = NowPlaying.subtitle(text(md, MediaMetadata.METADATA_KEY_ARTIST),
                text(md, MediaMetadata.METADATA_KEY_ALBUM), appLabel, title);
        nowPlayingSubtitle.setText(sub);
        nowPlayingSubtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        nowPlayingState.setImageResource(NowPlaying.isActive(state) ? R.drawable.ic_pause : R.drawable.ic_play);

        Bitmap art = null;
        if (md != null) {
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                art = md.getBitmap(key);
                if (art != null) break;
            }
        }
        // Metadata comes again with every state change; decode a new picture only when it changed.
        if (art != null && (art != nowPlayingSource || !pkg.equals(nowPlayingPkg))) {
            nowPlayingArt.setImageBitmap(squareThumb(art, (int) (44 * density)));
        } else if (art == null && (nowPlayingSource != null || !pkg.equals(nowPlayingPkg))) {
            Drawable icon = null;
            try {
                // The square icon: a wide banner cropped to a square cuts the app's name off.
                icon = getPackageManager().getApplicationIcon(pkg);
            } catch (Exception ignored) {
                // No picture: the card shows the title alone.
            }
            nowPlayingArt.setImageDrawable(icon);
        }
        nowPlayingSource = art;
        nowPlayingPkg = pkg;
    }

    private static String text(MediaMetadata md, String key) {
        if (md == null) return null;
        CharSequence t = md.getText(key);
        return t != null ? t.toString() : null;
    }

    /** A centre square of the artwork at the card's size, so a 1000 px cover isn't drawn at 72 dp. */
    private static Bitmap squareThumb(Bitmap src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        if (side <= 0) return src;
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap square = side == src.getWidth() && side == src.getHeight() ? src : Bitmap.createBitmap(src, x, y, side, side);
        return side > size ? Bitmap.createScaledBitmap(square, size, size, true) : square;
    }

    /**
     * Shows the card right-aligned just above the dock when there is a session, room under the top
     * bar, and the grid sits at its top; otherwise fades it out. The top shelf name is shortened so
     * it never runs under the card.
     */
    /** Slides the grid below the screen's bottom edge (or back), with the top shelf and Now playing card. */
    private void setDockHidden(boolean hide) {
        dockHidden = hide;
        float dy = 0;
        if (hide) {
            Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
            root.offsetDescendantRectToMyCoords(grid, g);
            dy = root.getHeight() - (g.top + grid.getPaddingTop()) + 24 * density;
            hideHero();
        }
        grid.animate().translationY(dy).setDuration(hide ? 340 : 280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction(this::placeNowPlaying).start();
        placeNowPlaying();
    }

    /** The card's title and artist at a fold: 1 their full width, 0 none (the artwork and state icon stay). */
    private void foldCard(float v) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) nowPlayingText.getLayoutParams();
        if (v >= 1f) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            nowPlayingText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            lp.width = Math.round(nowPlayingText.getMeasuredWidth() * v);
        }
        int margin = Math.round(10 * density * v);
        lp.setMarginStart(margin);
        lp.setMarginEnd(margin);
        nowPlayingText.setLayoutParams(lp);
        nowPlayingText.setAlpha(v * v);
    }

    private void placeNowPlaying() {
        boolean want = (todayPlay != null || (nowPlayingOn && nowPlayingHasSession && NowPlaying.working())) && !dockHidden
                && !grid.canScrollVertically(-1) && grid.getHeight() > 0;
        float top = 0;
        if (want) {
            if (nowPlaying.getVisibility() != View.VISIBLE) {
                nowPlaying.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.AT_MOST));
            }
            int h = nowPlaying.getVisibility() == View.VISIBLE && nowPlaying.getHeight() > 0
                    ? nowPlaying.getHeight() : nowPlaying.getMeasuredHeight();
            Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
            root.offsetDescendantRectToMyCoords(grid, g);
            Rect bar = new Rect(0, 0, btnSettings.getWidth(), btnSettings.getHeight());
            root.offsetDescendantRectToMyCoords(btnSettings, bar);
            float dockTop = g.top + grid.getPaddingTop();
            top = dockTop - 22 * density - h;
            want = top >= bar.bottom + 14 * density;   // no room with rows under the dock: leave it out
        }
        if (want) {
            nowPlaying.setY(top);
            if (!nowPlayingVisible) {
                nowPlayingVisible = true;
                nowPlaying.setVisibility(View.VISIBLE);
                nowPlaying.animate().alpha(1f).setDuration(240).start();
            }
            nowPlaying.post(() -> {
                int cardLeft = root.getWidth() - (int) (56 * density) - nowPlaying.getWidth();
                heroName.setMaxWidth(Math.max(0, cardLeft - heroName.getLeft() - (int) (24 * density)));
            });
        } else if (nowPlayingVisible) {
            nowPlayingVisible = false;
            if (nowPlaying.hasFocus()) focusTile(lastFocused < COLUMNS ? lastFocused : 0);
            nowPlaying.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (!nowPlayingVisible) nowPlaying.setVisibility(View.GONE);
            }).start();
            heroName.setMaxWidth(Integer.MAX_VALUE);
        }
        // The card is reached only from Settings (Down) and the last dock tile (Up, onTileVerticalKey).
        btnWifi.setNextFocusDownId(R.id.grid);
        btnSettings.setNextFocusDownId(nowPlayingVisible ? R.id.nowPlaying : R.id.grid);
    }

    // ---- occasion Play card --------------------------------------------------------------------

    /**
     * On an occasion day the Now playing card becomes a Play card for the day's song; back to Now playing
     * the day after. Same place, look and navigation, so nothing else changes.
     */
    private void updateOccasionCard(Occasions.Play play) {
        boolean was = todayPlay != null;
        String before = was ? todayPlay.song + "|" + todayPlay.videoId + "|" + todayPlay.search : "";
        String after = play != null ? play.song + "|" + play.videoId + "|" + play.search : "";
        todayPlay = play;
        if (before.equals(after)) return;
        if (play != null) {
            GradientDrawable festive = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {0xFFE53935, 0xFFFFB300});
            festive.setCornerRadius(9 * density);
            nowPlayingArt.setBackground(festive);
            nowPlayingArt.setImageResource(R.drawable.ic_play);
            nowPlayingArt.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            nowPlayingArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int pad = (int) (11 * density);
            nowPlayingArt.setPadding(pad, pad, pad, pad);
            nowPlayingTitle.setText(R.string.occasion_play);
            nowPlayingSubtitle.setText(play.song);
            nowPlayingSubtitle.setVisibility(View.VISIBLE);
            nowPlayingState.setVisibility(View.GONE);
            nowPlaying.setContentDescription(getString(R.string.occasion_play_description, play.song));
        } else {
            // Back to Now playing: its artwork and state icon return with the next session update.
            nowPlayingArt.setBackground(null);
            nowPlayingArt.setImageTintList(null);
            nowPlayingArt.setImageDrawable(null);
            nowPlayingArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            nowPlayingArt.setPadding(0, 0, 0, 0);
            nowPlayingState.setVisibility(View.VISIBLE);
            nowPlaying.setContentDescription(getString(R.string.now_playing_description));
            nowPlayingSource = null;
            nowPlayingPkg = null;
            if (nowPlayingHasSession && shownSession != null) {
                PlaybackState st = shownSession.getPlaybackState();
                bindNowPlaying(shownSession, st != null ? st.getState() : NowPlaying.STATE_NONE);
            }
        }
        placeNowPlaying();
    }

    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";
    private static final String[] SMART_TUBE = {"com.teamsmart.videomanager.tv", "com.liskovsoft.videomanager"};

    /**
     * Plays the day's song: a video in YouTube for Android TV (else SmartTube, else whatever handles
     * YouTube links), or a search there when the occasion has no video.
     */
    private void playOccasion(Occasions.Play p) {
        java.util.List<Intent> tries = new java.util.ArrayList<>();
        if (!p.videoId.isEmpty()) {
            Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + p.videoId);
            tries.add(new Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_TV));
            for (String pkg : SMART_TUBE) tries.add(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
            tries.add(new Intent(Intent.ACTION_VIEW, uri));
        } else {
            // YouTube for Android TV answers the system search intent (the remote's voice search uses it).
            tries.add(new Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE_TV).putExtra(android.app.SearchManager.QUERY, p.search));
            Uri results = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(p.search));
            tries.add(new Intent(Intent.ACTION_VIEW, results).setPackage(YOUTUBE_TV));
            for (String pkg : SMART_TUBE) tries.add(new Intent(Intent.ACTION_VIEW, results).setPackage(pkg));
            tries.add(new Intent(Intent.ACTION_VIEW, results));
        }
        for (Intent i : tries) {
            if (i.resolveActivity(getPackageManager()) == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // try the next app
            }
        }
        Toast.makeText(this, R.string.occasion_play_failed, Toast.LENGTH_SHORT).show();
    }

    /** Hold OK on the card: the app's own player screen, or failing that its launcher entry. */
    private void openNowPlayingApp() {
        MediaController c = shownSession;
        if (c == null) return;
        PendingIntent pi = c.getSessionActivity();
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Android 14 lets a foreground app start another app's PendingIntent only when it says so.
                    ActivityOptions o = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(this, 0, null, null, null, null, o.toBundle());
                } else {
                    pi.send();
                }
                return;
            } catch (PendingIntent.CanceledException ignored) {
                // Fall back to the app's launcher entry.
            }
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i != null) open(i);
    }

    // ---- Gallery -----------------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PHOTOS) {
            galleryListedAt = 0;
            startGallery();
        }
    }

    private boolean hasPhotoPermission() {
        return checkSelfPermission(Gallery.permission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** The Gallery row's status: Off, On, or On with what's missing. */
    private String galleryState() {
        if (!galleryOn) return getString(R.string.settings_menu_off);
        if (!hasPhotoPermission()) return getString(R.string.gallery_no_permission);
        if (galleryListedAt > 0 && galleryPhotos.isEmpty()) return getString(R.string.gallery_empty);
        return getString(R.string.settings_menu_on);
    }

    /** Shows a photo now if none is up or the current one is five minutes old, then one every five minutes. */
    private void startGallery() {
        handler.removeCallbacks(galleryTick);
        if (!galleryOn || !Gallery.working() || !hasPhotoPermission()) {
            dropGallery();
            updateBackdrop();
            return;
        }
        handler.postDelayed(galleryTick, Gallery.delayUntilNext(galleryShownAt, System.currentTimeMillis()));
    }

    /** Lists the folder when due, picks the next photo and decodes it on the gallery thread. */
    private void nextPhoto() {
        nextPhoto(false);
    }

    /**
     * Left on the leftmost tile or button, Right on the rightmost: the photo before or after this one. Only with
     * Gallery showing; the five-minute timer starts again from this photo. False when there is no gallery.
     */
    private boolean stepPhoto(boolean back) {
        if (!galleryOn || galleryLayer == null || !Gallery.working()) return false;
        handler.removeCallbacks(galleryTick);
        nextPhoto(back);
        handler.postDelayed(galleryTick, Gallery.INTERVAL_MS);
        return true;
    }

    private void nextPhoto(final boolean back) {
        if (galleryLoading || !Gallery.working()) return;
        galleryLoading = true;
        final Context app = getApplicationContext();
        final int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        final int h = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        Gallery.EXECUTOR.execute(() -> {
            Bitmap bmp = Gallery.run(() -> {
                long now = System.currentTimeMillis();
                if (Gallery.relistDue(galleryListedAt, now)) {
                    galleryPhotos = Gallery.list(app, true);
                    galleryListedAt = now;
                }
                // A photo that can't be read is skipped; give up after a few so a folder of bad files can't spin.
                for (int tries = 0; tries < 5; tries++) {
                    String item = back ? galleryBag.previous(galleryPhotos) : galleryBag.next(galleryPhotos);
                    if (item == null) return null;
                    Log.d(Gallery.TAG, (back ? "previous " : "next ") + item.substring(item.lastIndexOf('/') + 1)
                            + " (" + galleryPhotos.size() + " items)");
                    if (Gallery.isVideo(item)) {
                        handler.post(() -> playVideo(item));   // played on the main thread, not decoded
                        return null;
                    }
                    Bitmap b = Gallery.decode(app, item, w, h);
                    if (b != null) return b;
                }
                return null;
            }, null);
            handler.post(() -> {
                galleryLoading = false;
                if (isDestroyed() || !galleryOn) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp != null) Gallery.run(() -> {
                    showPhoto(bmp);
                    return null;
                }, null);
            });
        });
    }

    /** Crossfades to a new photo over about a second; the one it replaces is let go once covered. */
    private void showPhoto(Bitmap bmp) {
        ensureGalleryLayer();
        final ImageView incoming = galleryBack;
        final ImageView outgoing = galleryFront;
        if (galleryFade != null) galleryFade.cancel();
        incoming.setImageBitmap(bmp);
        incoming.setAlpha(0f);
        galleryLayer.removeView(incoming);
        galleryLayer.addView(incoming, galleryLayer.getChildCount() - 1);   // above the rest, under the scrim
        galleryFront = incoming;
        galleryBack = outgoing;
        galleryShownAt = System.currentTimeMillis();
        // A photo stays INTERVAL_MS; a video before it was playing on, untimed.
        handler.removeCallbacks(galleryTick);
        handler.postDelayed(galleryTick, Gallery.INTERVAL_MS);
        // The wallpaper (or colour) stays under the first photo until it has faded in; a video under it stops
        // once it is covered. Frame by frame, so the crossfade shows on TVs with system animations off.
        galleryFade = new Tween(0f, v -> {
            incoming.setAlpha(v);
            if (v >= 1f) {
                if (galleryBack == outgoing) release(outgoing);
                outgoing.setAlpha(1f);
                stopVideo();
                updateBackdrop();
            }
        });
        galleryFade.to(1f, 1200);
    }

    // ---- gallery videos ----------------------------------------------------------------------------

    /** The video playing under the photos (a SurfaceView, so the TV's video plane does the work), or null. */
    private android.media.MediaPlayer videoPlayer;
    private android.view.SurfaceView videoSurface;
    private String videoPending;

    /**
     * Plays a gallery video muted, to its end, then moves on. It plays on a surface under the photos; the
     * photo on top fades away once the first frame is up, so nothing flashes black.
     */
    private void playVideo(String item) {
        if (isDestroyed() || !galleryOn || !resumed) return;
        ensureGalleryLayer();
        handler.removeCallbacks(galleryTick);   // a video is not timed
        galleryShownAt = System.currentTimeMillis();
        if (videoSurface == null) {
            videoSurface = new android.view.SurfaceView(this);
            videoSurface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull android.view.SurfaceHolder h) {
                    if (videoPending != null) {
                        String p = videoPending;
                        videoPending = null;
                        startPlayer(p);
                    }
                }

                @Override
                public void surfaceChanged(@NonNull android.view.SurfaceHolder h, int f, int w, int hh) {
                }

                @Override
                public void surfaceDestroyed(@NonNull android.view.SurfaceHolder h) {
                    stopVideo();
                }
            });
            galleryLayer.addView(videoSurface, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        if (videoSurface.getHolder().getSurface().isValid()) startPlayer(item);
        else videoPending = item;
    }

    private void startPlayer(String item) {
        releasePlayer();
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        videoPlayer = mp;
        try {
            if (item.startsWith("content:")) mp.setDataSource(this, android.net.Uri.parse(item));
            else mp.setDataSource(item);
            mp.setSurface(videoSurface.getHolder().getSurface());
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((p, vw, vh) -> coverWith(vw, vh));
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && p == videoPlayer) {
                    revealVideo();
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (p == videoPlayer) nextPhoto();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(Gallery.TAG, "video failed " + what + "/" + extra + ": " + item);
                if (p == videoPlayer) {
                    stopVideo();
                    nextPhoto();
                }
                return true;
            });
            mp.setOnPreparedListener(android.media.MediaPlayer::start);
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(Gallery.TAG, "video skipped " + item + ": " + e);
            stopVideo();
            handler.post(this::nextPhoto);
        }
    }

    /** Sizes the surface to cover the screen at the video's own shape, centred; the overflow is cut off. */
    private void coverWith(int vw, int vh) {
        if (videoSurface == null || vw <= 0 || vh <= 0 || root.getWidth() == 0) return;
        float sw = root.getWidth(), sh = root.getHeight();
        float scale = Math.max(sw / vw, sh / vh);
        ViewGroup.LayoutParams lp = videoSurface.getLayoutParams();
        lp.width = Math.round(vw * scale);
        lp.height = Math.round(vh * scale);
        videoSurface.setLayoutParams(lp);
    }

    /** The first frame is up: fade the photo above away so the video shows. */
    private void revealVideo() {
        if (galleryFront == null) return;
        final ImageView top = galleryFront;
        if (galleryFade != null) galleryFade.cancel();
        galleryFade = new Tween(top.getAlpha(), v -> top.setAlpha(v));
        galleryFade.to(0f, 1200);
    }

    private void releasePlayer() {
        if (videoPlayer == null) return;
        try {
            videoPlayer.release();
        } catch (Exception ignored) {
            // already gone
        }
        videoPlayer = null;
    }

    /**
     * Stops the video and gives the decoder back (leaving Home, a photo covering it). The photo it was
     * hiding shows again, and leaving mid-video means a new item on the way back.
     */
    private void stopVideo() {
        videoPending = null;
        if (videoPlayer == null) return;
        releasePlayer();
        if (galleryFront != null) galleryFront.setAlpha(1f);
        if (!resumed) galleryShownAt = 0;
    }

    private void ensureGalleryLayer() {
        if (galleryLayer == null) {
            galleryLayer = new FrameLayout(this);
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            galleryFront = new ImageView(this);
            galleryBack = new ImageView(this);
            for (ImageView v : new ImageView[] {galleryFront, galleryBack}) {
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
                galleryLayer.addView(v, new FrameLayout.LayoutParams(full));
            }
            // Darker at the top and bottom so the clock, the header and the dock stay readable on bright photos.
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x8C000000, 0x1A000000, 0x26000000, 0xB3000000}));
            galleryLayer.addView(scrim, new FrameLayout.LayoutParams(full));
            root.addView(galleryLayer, wallView != null ? 1 : 0, full);
        }
    }

    private Tween galleryFade;

    /** Lets go of a photo. Not recycle(): a frame already recorded may still draw it; the collector frees it. */
    private static void release(ImageView v) {
        v.setImageDrawable(null);
    }

    /** Removes the photos (Gallery off, or memory trimmed); the theme wallpaper or colour shows again. */
    private void dropGallery() {
        stopVideo();
        videoSurface = null;
        if (galleryLayer == null) return;
        galleryFront.animate().cancel();
        galleryBack.animate().cancel();
        release(galleryFront);
        release(galleryBack);
        root.removeView(galleryLayer);
        galleryLayer = null;
        galleryFront = null;
        galleryBack = null;
        galleryShownAt = 0;
    }

    /**
     * Exactly one full-screen layer paints the background: the gallery, else the theme wallpaper, else the
     * root's colour. The ones underneath would be overdraw.
     */
    private void updateBackdrop() {
        boolean photos = galleryLayer != null;
        if (wallView != null) wallView.setVisibility(photos ? View.GONE : View.VISIBLE);
        if (photos || wallView != null) root.setBackground(null);
        else root.setBackgroundColor(backgroundColor);
    }

    // ---- Bitcoin line ----------------------------------------------------------------------------

    /** Shows the last reading at once, then refreshes now if it's over a minute old and every five minutes after. */
    private void startTicker() {
        if (!btcOn) {
            btcTicker.setVisibility(View.GONE);
            return;
        }
        showTicker(Ticker.last);
        Ticker.Quote q = Ticker.last;
        long age = q == null ? Long.MAX_VALUE : System.currentTimeMillis() - q.at;
        handler.postDelayed(btcTick, age >= Ticker.STALE_MS ? 0 : Math.max(0, Ticker.REFRESH_MS - age));
    }

    /** One refresh on the ticker thread; the result is shown if the line is still on. */
    private void fetchTicker() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) return;
        if (!Ticker.busy.compareAndSet(false, true)) return;
        Ticker.EXECUTOR.execute(() -> {
            try {
                Ticker.last = Ticker.fetch(Ticker.last);
            } catch (Exception e) {
                // A failed refresh keeps the last reading on screen.
            } finally {
                Ticker.busy.set(false);
            }
            handler.post(() -> {
                if (!isDestroyed() && btcOn) showTicker(Ticker.last);
            });
        });
    }

    /** "₿ $81,002  ▼ 0.3%"; hidden until there is a price. */
    private void showTicker(Ticker.Quote q) {
        if (q == null || !q.hasPrice()) {
            btcTicker.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // Older TVs' fonts may lack U+20BF; spell it out there.
        String sign = btcTicker.getPaint().hasGlyph("\u20BF") ? "\u20BF" : "BTC";
        append(sb, sign, 0xFFF7931A);
        sb.append("  ").append(Ticker.formatPrice(q.price));
        tickerChangeFold = null;
        if (!Double.isNaN(q.changePct)) {
            // The change folds away when tidy, the price stays.
            int at = sb.length();
            sb.append("   ").append(Ticker.formatChange(q.changePct));
            tickerChangeFold = new FoldSpan(null, q.changePct < 0 ? 0xFFFF6B6B : 0xFF4CD787);
            tickerChangeFold.fraction = tickerFold.value();
            sb.setSpan(tickerChangeFold, at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tickerText = sb;
        btcTicker.setText(sb);
        // Shown in turn on the greeting line instead of a line of its own.
        SpannableStringBuilder line = new SpannableStringBuilder();
        append(line, sign, 0xFFF7931A);
        line.append("  ").append(Ticker.formatPrice(q.price));
        if (!Double.isNaN(q.changePct)) {
            line.append("   ");
            append(line, Ticker.formatChange(q.changePct), q.changePct < 0 ? 0xFFFF6B6B : 0xFF4CD787);
        }
        // USDT in VND (Remitano) shares the turn, after Bitcoin, in the line's own colour.
        if (!Double.isNaN(q.usdtVnd)) line.append("     USDT ").append(Ticker.formatVnd(q.usdtVnd));
        btcLine = line;
        if (cycle != null) cycle.refresh();
    }

    // ---- weather ---------------------------------------------------------------------------------

    /** Shows the cached reading at once, then refreshes now if it's due and every 30 minutes after. */
    private void startWeather() {
        if (!weatherOn || weatherLocation.isEmpty()) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        Weather.Reading r = Weather.cached(this, weatherLocation, weatherUnit);
        showWeather(r);
        long age = r == null ? Long.MAX_VALUE : System.currentTimeMillis() - r.at;
        handler.postDelayed(weatherTick, age >= Weather.REFRESH_MS ? 0 : Weather.REFRESH_MS - age);
    }

    /** One refresh on the ticker thread; the result is shown if weather is still on. */
    private void fetchWeather() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) return;
        if (!Weather.busy.compareAndSet(false, true)) return;
        final Context app = getApplicationContext();
        final String location = weatherLocation, unit = weatherUnit;
        Ticker.EXECUTOR.execute(() -> {
            Weather.Reading r = null;
            try {
                r = Weather.fetch(app, location, unit);
            } catch (Exception e) {
                // A failed refresh keeps the last reading on screen.
            } finally {
                Weather.busy.set(false);
            }
            final Weather.Reading shown = r;
            handler.post(() -> {
                if (!isDestroyed() && weatherOn) showWeather(shown);
            });
        });
    }

    /** "☀ 31°  Rain: 40% 1h 30% 2h"; the icon is left out where the TV's fonts don't have it. Hidden until there is a reading. */
    private void showWeather(Weather.Reading r) {
        if (r == null) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        String icon = Weather.icon(r.code, r.day);
        if (!icon.isEmpty() && !weatherView.getPaint().hasGlyph(icon)) icon = "";
        weatherView.setText(Weather.line(r, icon));
        weatherView.setVisibility(View.VISIBLE);
    }

    /** The Weather row's status: Off, On, or On without a place to look up. */
    private String weatherState() {
        if (!weatherOn) return getString(R.string.settings_menu_off);
        if (weatherLocation.isEmpty()) return getString(R.string.weather_no_location);
        return getString(R.string.settings_menu_on);
    }

    private static void append(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---- self-update -----------------------------------------------------------------------------

    /** Hold OK on Settings: Module cards, and Check for updates with the installed version. */
    private void showSettingsMenu() {
        View anchor = btnSettings;
        // The scrolling panel: with the Home rows (Hidden apps, Reset order) the menu no longer fits on every screen.
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        panel.findViewById(R.id.heading).setVisibility(View.GONE);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (340 * density));

        String cardsState = !moduleCards || !CardFeature.working() ? getString(R.string.settings_menu_off)
                : cards.isEmpty() ? getString(R.string.no_module_cards)
                : getString(R.string.settings_menu_cards_count, cards.size());
        View cardsRow = addMenuRow(rows, getString(R.string.settings_menu_cards), cardsState);
        cardsRow.setOnClickListener(v -> {
            popup.dismiss();
            showModuleCards();
        });

        View btcRow = addMenuRow(rows, getString(R.string.settings_menu_btc),
                getString(btcOn ? R.string.settings_menu_on : R.string.settings_menu_off));
        final TextView btcStatus = btcRow.findViewById(R.id.status);
        btcRow.setOnClickListener(v -> {
            btcOn = !btcOn;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_BTC, btcOn).apply();
            btcStatus.setText(btcOn ? R.string.settings_menu_on : R.string.settings_menu_off);
            handler.removeCallbacks(btcTick);
            startTicker();
        });

        View weatherRow = addMenuRow(rows, getString(R.string.settings_menu_weather), weatherState());
        final TextView weatherStatus = weatherRow.findViewById(R.id.status);
        weatherRow.setOnClickListener(v -> {
            weatherOn = !weatherOn;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_WEATHER, weatherOn).apply();
            weatherStatus.setText(weatherState());
            handler.removeCallbacks(weatherTick);
            startWeather();
        });

        View galleryRow = addMenuRow(rows, getString(R.string.settings_menu_gallery), galleryState());
        final TextView galleryStatus = galleryRow.findViewById(R.id.status);
        galleryRow.setOnClickListener(v -> {
            galleryOn = !galleryOn;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_GALLERY, galleryOn).apply();
            if (heroShown) ambient.animate().alpha(ambientAlpha()).setDuration(300).start();
            galleryStatus.setText(galleryState());
            if (galleryOn && !hasPhotoPermission()) {
                popup.dismiss();
                requestPermissions(new String[] {Gallery.permission()}, REQUEST_PHOTOS);
                return;
            }
            galleryListedAt = 0;   // look at the folder again: the owner may just have added photos
            startGallery();
        });

        View qrRow = addMenuRow(rows, getString(R.string.settings_menu_remote_qr), remoteQrState());
        final TextView qrStatus = qrRow.findViewById(R.id.status);
        qrRow.setOnClickListener(v -> {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean show = !p.getBoolean(PREF_REMOTE_QR, true);
            Runnable apply = () -> {
                p.edit().putBoolean(PREF_REMOTE_QR, show).apply();
                qrStatus.setText(remoteQrState());
            };
            // Showing the QR code lets a phone pair, and a paired phone controls the TV: the PIN first, if any.
            if (show && AppLock.hasPin(p)) verifyPin(getString(R.string.remote_qr_pin), apply);
            else apply.run();
        });

        if (RemoteRelay.enabled()) {
            int agentCount = RemoteRelay.agents(this).list().size();
            View agentsRow = addMenuRow(rows, getString(R.string.agents_menu),
                    agentCount == 0 ? getString(R.string.agents_none) : String.valueOf(agentCount));
            agentsRow.setOnClickListener(v -> {
                popup.dismiss();
                showAgents();
            });
        }

        View lockRow = addMenuRow(rows, getString(R.string.settings_menu_app_lock), appLockState());
        lockRow.setOnClickListener(v -> {
            popup.dismiss();
            openAppLock();
        });

        View remapRow = addMenuRow(rows, getString(R.string.remap_menu), remapState());
        remapRow.setOnClickListener(v -> {
            popup.dismiss();
            showRemoteButtons();
        });

        int hiddenCount = HiddenApps.get(getSharedPreferences(PREFS, MODE_PRIVATE)).size();
        View hiddenRow = addMenuRow(rows, getString(R.string.settings_menu_hidden_apps), hiddenCount == 0
                ? getString(R.string.hidden_apps_none) : getString(R.string.hidden_apps_count, hiddenCount));
        hiddenRow.setOnClickListener(v -> {
            popup.dismiss();
            showHiddenApps();
        });

        View libraryRow = addMenuRow(rows, getString(R.string.app_library), getString(R.string.app_library_status));
        libraryRow.setOnClickListener(v -> {
            popup.dismiss();
            showAppLibrary();
        });

        View orderRow = addMenuRow(rows, getString(R.string.settings_menu_reset_order), orderState());
        final TextView orderStatus = orderRow.findViewById(R.id.status);
        orderRow.setOnClickListener(v -> {
            resetOrder();
            orderStatus.setText(orderState());
        });

        View checkRow = addMenuRow(rows, getString(R.string.update_menu_check, BuildConfig.VERSION_NAME), updateState());
        final TextView status = checkRow.findViewById(R.id.status);
        checkRow.setOnClickListener(v -> {
            String state = updateState();
            if (!updatesOn()) {
                status.setText(state);
                return;
            }
            status.setText(R.string.update_checking);
            runCheck(status, popup);
        });

        fitBelow(popup, panel, anchor);
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    // ---- AI agents (Agents) ------------------------------------------------------------------------

    /** Settings menu, AI agents: Create an agent, every agent (OK on one changes or revokes it), the log. */
    private void showAgents() {
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(R.string.agents_menu);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (420 * density));
        final Agents agents = RemoteRelay.agents(this);
        addMenuRow(rows, getString(R.string.agents_create), getString(R.string.agents_create_hint)).setOnClickListener(v -> {
            if (agents.list().size() >= Agents.MAX) {
                Toast.makeText(this, R.string.agents_full, Toast.LENGTH_LONG).show();
                return;
            }
            popup.dismiss();
            // A key lets someone else's program drive the TV: the PIN first, if any, as for the Remote QR.
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (AppLock.hasPin(p)) verifyPin(getString(R.string.agents_pin), this::nameAgent);
            else nameAgent();
        });
        for (Agents.Agent a : agents.list()) {
            addMenuRow(rows, a.name, agentState(a)).setOnClickListener(v -> {
                popup.dismiss();
                showAgent(a.id);
            });
        }
        addMenuRow(rows, getString(R.string.agents_log), "").setOnClickListener(v -> {
            popup.dismiss();
            showAgentLog();
        });
        fitBelow(popup, panel, btnSettings);
        dropIn(popup, panel, btnSettings, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** "Read + control · used 5 minutes ago". */
    private String agentState(Agents.Agent a) {
        String perms = getString(a.read && a.control ? R.string.agents_perm_read_control
                : a.control ? R.string.agents_control : a.read ? R.string.agents_read : R.string.agents_perm_none);
        String used = a.lastUsed == 0 ? getString(R.string.agents_never)
                : getString(R.string.agents_used, android.text.format.DateUtils.getRelativeTimeSpanString(a.lastUsed,
                System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        return perms + " · " + used;
    }

    /** One agent: Read and Control switch, Revoke removes it (the relay is told at once). */
    private void showAgent(String id) {
        final Agents agents = RemoteRelay.agents(this);
        final Agents.Agent a = agents.byId(id);
        if (a == null) {
            showAgents();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(a.name);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (420 * density));
        View readRow = addMenuRow(rows, getString(R.string.agents_read) + " · " + getString(R.string.agents_read_hint),
                getString(a.read ? R.string.settings_menu_on : R.string.settings_menu_off));
        final TextView readStatus = readRow.findViewById(R.id.status);
        readRow.setOnClickListener(v -> {
            agents.set(id, !a.read, null);
            RemoteRelay.agentsChanged();
            readStatus.setText(a.read ? R.string.settings_menu_on : R.string.settings_menu_off);
        });
        View controlRow = addMenuRow(rows, getString(R.string.agents_control) + " · " + getString(R.string.agents_control_hint),
                getString(a.control ? R.string.settings_menu_on : R.string.settings_menu_off));
        final TextView controlStatus = controlRow.findViewById(R.id.status);
        controlRow.setOnClickListener(v -> {
            Runnable flip = () -> {
                agents.set(id, null, !a.control);
                RemoteRelay.agentsChanged();
                controlStatus.setText(a.control ? R.string.settings_menu_on : R.string.settings_menu_off);
            };
            // Granting control asks for the PIN, as making a key does; taking it away never does.
            if (!a.control && AppLock.hasPin(getSharedPreferences(PREFS, MODE_PRIVATE))) verifyPin(getString(R.string.agents_pin), flip);
            else flip.run();
        });
        addMenuRow(rows, getString(R.string.agents_revoke), agentState(a)).setOnClickListener(v -> {
            popup.dismiss();
            agents.revoke(id);
            RemoteRelay.agentsChanged();
            showAgents();
        });
        fitBelow(popup, panel, btnSettings);
        dropIn(popup, panel, btnSettings, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** The new agent's name (the phone remote's keyboard can type it), then its key. */
    private void nameAgent() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (24 * density);
        panel.setPadding(pad, pad, pad, (int) (18 * density));
        TextView title = new TextView(this);
        title.setText(R.string.agents_name_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(UiFont.get(this));
        panel.addView(title);
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setHint("Claude");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0x66FFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Agents.MAX_NAME)});
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = (int) (14 * density);
        panel.addView(name, np);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (16 * density);
        panel.addView(buttons, bp);
        final Dialog dialog = glassDialog(panel);
        Runnable create = () -> {
            dialog.dismiss();
            try {
                Agents.Created c = RemoteRelay.agents(this).create(name.getText().toString(), false, System.currentTimeMillis());
                RemoteRelay.agentsChanged();
                showAgentKey(c);
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
        name.setOnEditorActionListener((v, actionId, event) -> {
            create.run();
            return true;
        });
        buttons.addView(dialogButton(getString(R.string.agents_create_button), create));
        dialog.show();
        name.requestFocus();
    }

    /** The key, once: as text and as a QR code, with the relay's address. Nothing keeps it after Done. */
    private void showAgentKey(Agents.Created c) {
        String token = RemoteRelay.agentToken(this, c.key);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (24 * density);
        panel.setPadding(pad, pad, pad, (int) (18 * density));
        TextView title = new TextView(this);
        title.setText(getString(R.string.agents_key_title, c.agent.name));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(UiFont.get(this));
        panel.addView(title);
        TextView body = new TextView(this);
        body.setText(getString(R.string.agents_key_body, String.valueOf(RemoteRelay.relayBase())));
        body.setTextColor(0xB3FFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTypeface(UiFont.get(this));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (8 * density);
        panel.addView(body, hp);
        TextView key = new TextView(this);
        key.setText(token);
        key.setTextColor(Color.WHITE);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextIsSelectable(true);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.topMargin = (int) (12 * density);
        panel.addView(key, kp);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(token));
        int side = (int) (168 * density);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(side, side);
        qp.topMargin = (int) (12 * density);
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(qr, qp);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (16 * density);
        panel.addView(buttons, bp);
        final Dialog dialog = glassDialog(panel);
        TextView done = dialogButton(getString(R.string.agents_done), () -> {
            dialog.dismiss();
            showAgents();
        });
        buttons.addView(done);
        dialog.show();
        done.requestFocus();
    }

    /** The agent log, newest first: when, who, what, and the result. */
    private void showAgentLog() {
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(R.string.agents_log);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (480 * density));
        org.json.JSONArray log = RemoteRelay.agents(this).log(Agents.LOG_MAX);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(this);
        java.text.DateFormat day = android.text.format.DateFormat.getMediumDateFormat(this);
        for (int i = 0; i < log.length(); i++) {
            org.json.JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            java.util.Date at = new java.util.Date(e.optLong("t"));
            String what = e.optString("cmd") + (e.optString("what").isEmpty() ? "" : " " + e.optString("what"));
            String result = getString(e.optBoolean("ok") ? R.string.agents_ok : R.string.agents_failed)
                    + (e.optString("msg").isEmpty() ? "" : " · " + e.optString("msg"));
            addMenuRow(rows, e.optString("name") + " · " + what, day.format(at) + " " + time.format(at) + " · " + result);
        }
        if (rows.getChildCount() == 0) addMenuRow(rows, getString(R.string.agents_log_empty), "");
        fitBelow(popup, panel, btnSettings);
        dropIn(popup, panel, btnSettings, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    // ---- remote buttons (KeyRemap) ---------------------------------------------------------------

    /** The Remote buttons row: how many buttons are set, or that the key helper is off. */
    private String remapState() {
        if (!KeyRemap.helperOn()) return getString(R.string.remap_helper_off);
        int n = KeyRemap.entries(this).size();
        return n == 0 ? getString(R.string.remap_none) : getString(R.string.remap_count, n);
    }

    /** Settings menu, Remote buttons: Add a button, then every button set; OK on one changes or removes it. */
    private void showRemoteButtons() {
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(R.string.remap_menu);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (420 * density));
        final boolean on = KeyRemap.helperOn();
        addMenuRow(rows, getString(R.string.remap_add), getString(on ? R.string.remap_add_hint : R.string.remap_helper_off))
                .setOnClickListener(v -> {
                    if (!KeyRemap.helperOn()) {
                        Toast.makeText(this, R.string.remap_helper_needed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    popup.dismiss();
                    recordButton();
                });
        List<KeyRemap.Entry> list = KeyRemap.entries(this);
        for (KeyRemap.Entry e : list) {
            addMenuRow(rows, buttonName(list, e.code, e.scan, e.hold), actionName(e)).setOnClickListener(v -> {
                popup.dismiss();
                showButtonActions(e.code, e.scan, e.hold);
            });
        }
        fitBelow(popup, panel, btnSettings);
        dropIn(popup, panel, btnSettings, Gravity.END);
        rows.getChildAt(rows.getChildCount() > 1 ? 1 : 0).requestFocus();
    }

    /** "1", "PROG_RED", "MEDIA_PLAY_PAUSE, held"; the scancode too when another set button shares the keycode. */
    private String buttonName(List<KeyRemap.Entry> list, int code, int scan, boolean hold) {
        String name = KeyEvent.keyCodeToString(code);
        if (name.startsWith("KEYCODE_")) name = name.substring(8);
        for (KeyRemap.Entry x : list) {
            if (x.code == code && x.scan != scan && scan > 0) {
                name += " #" + scan;
                break;
            }
        }
        return hold ? getString(R.string.remap_hold, name) : name;
    }

    private String actionName(KeyRemap.Entry e) {
        switch (e.action) {
            case KeyRemap.APP: {
                String input = RemoteKeys.inputId(e.arg);
                return input != null ? input : appLabel(e.arg);
            }
            case KeyRemap.WEBHOOK:
                try {
                    return getString(R.string.remap_action_webhook) + " · " + new java.net.URL(e.arg).getHost();
                } catch (Exception x) {
                    return getString(R.string.remap_action_webhook);
                }
            default:
                return getString(actionTitle(e.action));
        }
    }

    private static int actionTitle(String action) {
        switch (action) {
            case KeyRemap.APP: return R.string.remap_action_app;
            case KeyRemap.PICTURE: return R.string.remap_action_picture;
            case KeyRemap.ADB_WIFI: return R.string.remap_action_adb;
            case KeyRemap.MUTE: return R.string.remap_action_mute;
            case KeyRemap.INPUTS: return R.string.remap_action_inputs;
            case KeyRemap.HOME: return R.string.remap_action_home;
            case KeyRemap.BACK: return R.string.remap_action_back;
            case KeyRemap.RECENTS: return R.string.remap_action_recents;
            case KeyRemap.SETTINGS: return R.string.remap_action_settings;
            default: return R.string.remap_action_webhook;
        }
    }

    /** Waits for one button from the key helper (KeyRemap.recorder); a held button sets what holding it does. */
    private void recordButton() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (24 * density);
        panel.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.remap_press_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(UiFont.get(this));
        panel.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.remap_press_hint);
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setTypeface(UiFont.get(this));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (10 * density);
        panel.addView(hint, hp);
        final Dialog dialog = glassDialog(panel);
        final KeyRemap.Recorder r = (code, scan, hold) -> {
            dialog.dismiss();
            if (!isDestroyed()) showButtonActions(code, scan, hold);
        };
        dialog.setOnDismissListener(d -> {
            if (KeyRemap.recorder == r) KeyRemap.recorder = null;
        });
        KeyRemap.recorder = r;
        dialog.show();
    }

    /** What a button does: every action, the current one marked; OK sets it. Remove when it is set. */
    private void showButtonActions(int code, int scan, boolean hold) {
        List<KeyRemap.Entry> list = KeyRemap.entries(this);
        KeyRemap.Entry now = null;
        for (KeyRemap.Entry e : list) if (e.sameButton(code, scan, hold)) now = e;
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(buttonName(list, code, scan, hold));
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (420 * density));
        View focus = null;
        for (String action : KeyRemap.ACTIONS) {
            boolean current = now != null && now.action.equals(action);
            String status = current ? actionName(now) : "";
            View row = addMenuRow(rows, getString(actionTitle(action)), status);
            if (current) focus = row;
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (KeyRemap.APP.equals(action)) {
                    pickButtonApp(code, scan, hold);
                } else if (KeyRemap.WEBHOOK.equals(action)) {
                    editWebhook(code, scan, hold);
                } else {
                    setButton(code, scan, hold, action, "");
                }
            });
        }
        if (now != null) {
            addMenuRow(rows, getString(R.string.remap_remove), "").setOnClickListener(v -> {
                popup.dismiss();
                KeyRemap.save(this, KeyRemap.with(KeyRemap.entries(this), code, scan, hold, null));
                showRemoteButtons();
            });
        }
        fitBelow(popup, panel, btnSettings);
        dropIn(popup, panel, btnSettings, Gravity.END);
        (focus != null ? focus : rows.getChildAt(0)).requestFocus();
    }

    private void setButton(int code, int scan, boolean hold, String action, String arg) {
        KeyRemap.save(this, KeyRemap.with(KeyRemap.entries(this), code, scan, hold, new KeyRemap.Entry(code, scan, hold, action, arg)));
        showRemoteButtons();
    }

    /** Every app with a launcher entry, TV or phone, by name; OK sets it. */
    private void pickButtonApp(int code, int scan, boolean hold) {
        PackageManager pm = getPackageManager();
        Map<String, String> apps = new HashMap<>();   // package -> label
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !apps.containsKey(pkg)) apps.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        Collections.sort(sorted, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(R.string.remap_action_app);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (420 * density));
        for (Map.Entry<String, String> app : sorted) {
            addMenuRow(rows, app.getValue(), "").setOnClickListener(v -> {
                popup.dismiss();
                setButton(code, scan, hold, KeyRemap.APP, app.getKey());
            });
        }
        fitBelow(popup, panel, btnSettings);
        dropIn(popup, panel, btnSettings, Gravity.END);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    /** The webhook's URL (the phone remote's keyboard can type it); it gets a JSON POST on every press. */
    private void editWebhook(int code, int scan, boolean hold) {
        KeyRemap.Entry now = KeyRemap.find(KeyRemap.entries(this), code, scan, hold);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (24 * density);
        panel.setPadding(pad, pad, pad, (int) (18 * density));
        TextView title = new TextView(this);
        title.setText(R.string.remap_webhook_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(UiFont.get(this));
        panel.addView(title);
        final android.widget.EditText url = new android.widget.EditText(this);
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(now != null && KeyRemap.WEBHOOK.equals(now.action) ? now.arg : "");
        url.setHint("http://192.168.1.10:8123/api/webhook/tv");
        url.setTextColor(Color.WHITE);
        url.setHintTextColor(0x66FFFFFF);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        url.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        url.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = (int) (14 * density);
        panel.addView(url, np);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (16 * density);
        panel.addView(buttons, bp);
        final Dialog dialog = glassDialog(panel);
        Runnable save = () -> {
            String u = url.getText().toString().trim();
            if (!KeyRemap.webhookUrl(u)) {
                Toast.makeText(this, R.string.remap_webhook_bad, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            setButton(code, scan, hold, KeyRemap.WEBHOOK, u);
        };
        url.setOnEditorActionListener((v, actionId, event) -> {
            save.run();
            return true;
        });
        buttons.addView(dialogButton(getString(R.string.remap_save), save));
        dialog.show();
        url.requestFocus();
    }

    // ---- network menu (hold OK on the network pill) ---------------------------------------------

    /**
     * The TV's address and its ADB endpoints, so a computer can connect without digging through
     * Developer options: classic ADB on 5555 when it is open, and the Wireless debugging port, which
     * changes every time and is found the way `adb mdns services` finds it. The pairing code itself
     * is only ever shown by the system's own pairing screen; "Pair new device" opens Developer options.
     */
    private void showNetworkMenu() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final String ip = ipv4Address();
        // Settings › Remote QR: Hide leaves out the QR code and the phone page's link (a guest is watching).
        final boolean showPhone = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_REMOTE_QR, true);
        // The QR code pairs the phone remote through the relay; without a relay it is the home-network page.
        final boolean viaRelay = RemoteRelay.enabled();
        final boolean withQr = showPhone && (viaRelay || (WebControl.port() > 0 && ip != null));
        // With the phone page on, its QR code sits in a column beside the rows (the menu doesn't scroll).
        final PopupWindow popup = glassPopup(panel, (int) ((withQr ? 380 + 186 : 380) * density));

        addMenuRow(rows, getString(R.string.net_menu_address), ip != null ? ip : getString(R.string.net_menu_offline));
        final TextView adbStatus = addMenuRow(rows, getString(R.string.net_menu_adb), getString(R.string.net_menu_checking))
                .findViewById(R.id.status);
        final TextView wirelessStatus = addMenuRow(rows, getString(R.string.net_menu_wireless_port), "")
                .findViewById(R.id.status);
        View toggleRow = addMenuRow(rows, getString(R.string.net_menu_wireless), "");
        final TextView toggleStatus = toggleRow.findViewById(R.id.status);
        // The phone remote (relay) and the phone page: the page's address with its code, and a way to change both.
        if (showPhone && viaRelay) {
            addMenuRow(rows, getString(R.string.net_menu_remote),
                    getString(RemoteRelay.online() ? R.string.net_menu_remote_online : R.string.net_menu_remote_connecting));
        }
        final TextView webStatus = showPhone
                ? addMenuRow(rows, getString(R.string.net_menu_phone), webControlUrl(ip)).findViewById(R.id.status) : null;
        final ImageView qr = withQr ? new ImageView(this) : null;
        if (withQr) {
            qr.setImageBitmap(qrBitmap(viaRelay ? RemoteRelay.phoneUrl(this) : webControlUrl(ip)));
            addMenuRow(rows, getString(R.string.net_menu_new_code), getString(R.string.net_menu_new_code_hint))
                    .setOnClickListener(v -> {
                        WebControl.newCode(this);
                        if (viaRelay) RemoteRelay.newPhoneKey(this);
                        webStatus.setText(webControlUrl(ip));
                        qr.setImageBitmap(qrBitmap(viaRelay ? RemoteRelay.phoneUrl(this) : webControlUrl(ip)));
                    });
        }
        addMenuRow(rows, getString(R.string.net_menu_pair), getString(R.string.net_menu_pair_hint))
                .setOnClickListener(v -> {
                    popup.dismiss();
                    open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                });
        addMenuRow(rows, getString(R.string.net_menu_wifi_settings), "").setOnClickListener(v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        });

        final NsdManager nsd = getSystemService(NsdManager.class);
        final NsdManager.DiscoveryListener[] discovery = new NsdManager.DiscoveryListener[1];
        final Runnable refreshWireless = () -> {
            stopAdbDiscovery(nsd, discovery);
            boolean on = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
            toggleStatus.setText(on ? R.string.settings_menu_on : R.string.settings_menu_off);
            if (!on || ip == null) {
                wirelessStatus.setText(R.string.settings_menu_off);
                return;
            }
            wirelessStatus.setText(R.string.net_menu_checking);
            discovery[0] = findAdbPort(nsd, ip, port -> {
                if (popup.isShowing()) wirelessStatus.setText(ip + ":" + port);
            });
            // Nothing found within a few seconds: say so instead of "Checking…" forever.
            handler.postDelayed(() -> {
                if (popup.isShowing() && getString(R.string.net_menu_checking).contentEquals(wirelessStatus.getText())) {
                    wirelessStatus.setText(R.string.net_menu_not_found);
                }
            }, 6000);
        };
        refreshWireless.run();
        toggleRow.setOnClickListener(v -> {
            boolean on = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
            try {
                // Needs WRITE_SECURE_SETTINGS, which the install script grants over adb.
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", on ? 0 : 1);
            } catch (SecurityException e) {
                toggleStatus.setText(R.string.net_menu_no_permission);
                return;
            }
            // adbd needs a moment to start listening before it advertises the new port.
            toggleStatus.setText(R.string.net_menu_checking);
            handler.postDelayed(() -> {
                if (popup.isShowing()) refreshWireless.run();
            }, 1500);
        });
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            stopAdbDiscovery(nsd, discovery);
        });

        // Classic ADB (adb tcpip 5555) is open when something accepts a connection on that port.
        Ticker.EXECUTOR.execute(() -> {
            boolean open;
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", 5555), 400);
                open = true;
            } catch (Exception e) {
                open = false;
            }
            final boolean tcp = open;
            handler.post(() -> {
                if (popup.isShowing()) adbStatus.setText(tcp && ip != null ? ip + ":5555" : getString(R.string.settings_menu_off));
            });
        });

        if (withQr) {
            // rows | the QR code of the phone page's link, for the phone's camera.
            LinearLayout outer = (LinearLayout) panel;
            outer.removeView(rows);
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            columns.addView(rows, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int side = (int) (160 * density);
            // The code, with a small dim credit line under it.
            LinearLayout qrColumn = new LinearLayout(this);
            qrColumn.setOrientation(LinearLayout.VERTICAL);
            qrColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            qrColumn.addView(qr, new LinearLayout.LayoutParams(side, side));
            TextView credit = new TextView(this);
            credit.setText(R.string.remote_credit);
            credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            credit.setTextColor(0x4DFFFFFF);
            credit.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = (int) (8 * density);
            qrColumn.addView(credit, cp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMargins((int) (12 * density), 0, (int) (8 * density), 0);
            columns.addView(qrColumn, lp);
            outer.addView(columns);
        }
        dropIn(popup, panel, btnWifi, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** A QR code of {@code text}, black on white with the four-module quiet zone, one pixel per module. */
    private static Bitmap qrBitmap(String text) {
        boolean[][] m = Qr.encode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (m == null) return null;
        int n = m.length + 8;
        int[] px = new int[n * n];
        java.util.Arrays.fill(px, Color.WHITE);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) if (m[y][x]) px[(y + 4) * n + x + 4] = Color.BLACK;
        }
        Bitmap small = Bitmap.createBitmap(px, n, n, Bitmap.Config.RGB_565);
        // Scale up without smoothing so the modules stay sharp.
        return Bitmap.createScaledBitmap(small, n * 8, n * 8, false);
    }

    /** http://ip:port/?k=code for the phone page, or Off / Offline. */
    private String webControlUrl(String ip) {
        int port = WebControl.port();
        if (port <= 0) return getString(R.string.settings_menu_off);
        if (ip == null) return getString(R.string.net_menu_offline);
        return WebControl.url(ip, port, WebControl.code(this));
    }

    /** The active network's IPv4 address, or null when offline. */
    private String ipv4Address() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof java.net.Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }

    interface PortFound {
        void found(int port);
    }

    /** Browses _adb-tls-connect._tcp and reports the port of the service on this TV's own address. */
    @SuppressWarnings("deprecation")   // resolveService: the replacement needs API 34, minSdk is 26
    private NsdManager.DiscoveryListener findAdbPort(NsdManager nsd, String ip, PortFound callback) {
        if (nsd == null) return null;
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo r) {
                        if (r.getHost() != null && ip.equals(r.getHost().getHostAddress())) {
                            handler.post(() -> callback.found(r.getPort()));
                        }
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo r, int error) {
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, l);
        } catch (Exception e) {
            return null;
        }
        return l;
    }

    private static void stopAdbDiscovery(NsdManager nsd, NsdManager.DiscoveryListener[] holder) {
        if (nsd == null || holder[0] == null) return;
        try {
            nsd.stopServiceDiscovery(holder[0]);
        } catch (Exception ignored) {
            // Already stopped.
        }
        holder[0] = null;
    }

    private String remoteQrState() {
        return getString(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_REMOTE_QR, true)
                ? R.string.settings_menu_show : R.string.settings_menu_hide);
    }

    private View addMenuRow(LinearLayout rows, String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_menu_row, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView s = row.findViewById(R.id.status);
        s.setText(status);
        rows.addView(row);
        return row;
    }

    /** The Check for updates row before a check: why there are no updates, or the version already found. */
    private String updateState() {
        if (Updater.feedUrl().isEmpty()) return getString(R.string.update_self_built);
        if (!updatesOn()) return getString(R.string.update_off);
        Updater.Feed f = offered;
        return f != null ? getString(R.string.update_available, f.versionName) : "";
    }

    /** A build with a feed, self_update not off, and the updater hasn't failed in this process. */
    private boolean updatesOn() {
        return !Updater.feedUrl().isEmpty() && !Updater.MODE_OFF.equals(selfUpdate) && UpdateFeature.working();
    }

    /** Weekly mode: check when the last check is a week old (a day after a failure) and the TV is online. */
    private void checkWeekly() {
        if (!updatesOn() || !Updater.MODE_WEEKLY.equals(selfUpdate)) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) return;
        boolean due = UpdateFeature.run(() -> Updater.checkDue(Updater.load(Updater.stateFile(this)), System.currentTimeMillis()), false);
        if (due) runCheck(null, null);
    }

    /** One check on the update thread. status and popup are the Settings menu's, for a check the owner asked for. */
    private void runCheck(final TextView status, final PopupWindow popup) {
        if (!Updater.busy.compareAndSet(false, true)) return;   // a check or a download is already running
        final Context app = getApplicationContext();
        Updater.EXECUTOR.execute(() -> {
            Updater.Outcome o;
            try {
                o = UpdateFeature.run(() -> Updater.check(app), null);
            } finally {
                Updater.busy.set(false);
            }
            final Updater.Outcome outcome = o;
            handler.post(() -> {
                if (!isDestroyed()) UpdateFeature.run(() -> {
                    checked(outcome, status, popup);
                    return null;
                }, null);
            });
        });
    }

    private void checked(Updater.Outcome o, TextView status, PopupWindow popup) {
        boolean manual = status != null;
        if (o == null) {
            if (manual) status.setText(R.string.update_off);
            return;
        }
        switch (o.kind) {
            case Updater.AVAILABLE:
                offered = o.feed;
                // The weekly check doesn't ask again about a skipped version; the owner's own check does.
                if (manual || o.feed.versionCode != Updater.load(Updater.stateFile(this)).skipped) updatePromptDue = true;
                if (manual) status.setText(getString(R.string.update_available, o.feed.versionName));
                break;
            case Updater.UP_TO_DATE:
                offered = null;
                if (manual) status.setText(R.string.update_up_to_date);
                break;
            case Updater.TOO_OLD:
                offered = null;
                if (manual) status.setText(getString(R.string.update_too_old, o.feed.versionName));
                break;
            case Updater.Outcome.INVALID:
                if (manual) status.setText(R.string.update_invalid);
                break;
            default:
                if (manual) status.setText(R.string.update_unreachable);
                break;
        }
        // The prompt follows straight away; the menu makes room for it.
        if (manual && updatePromptDue && popup != null && popup.isShowing()) popup.dismiss();
        updateUpdateDot();
        showUpdatePrompt();
    }

    /** A dot on the Settings pill while a newer version is known and not skipped. */
    private void updateUpdateDot() {
        boolean waiting = updatesOn() && UpdateFeature.run(() -> {
            Updater.State st = Updater.load(Updater.stateFile(this));
            return st.availableCode > BuildConfig.VERSION_CODE && st.availableCode != st.skipped;
        }, false);
        // drawableTint covers the dot too, so it follows the pill's text colour as the icon does.
        btnSettings.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_settings), null,
                waiting ? getDrawable(R.drawable.update_dot) : null, null);
    }

    /**
     * Asks about the version the last check found: once its check is done, over a laid out grid with
     * window focus, after every module card prompt has been answered. Nothing installs without Update.
     */
    private void showUpdatePrompt() {
        if (!updatePromptDue || offered == null || updatePrompt != null || cardPrompt != null || openPopup != null) return;
        if (!tilesReady || !hasWindowFocus() || isFinishing() || !updatesOn()) return;
        if (moduleCards && !promptsPaused && !cards.isEmpty() && CardFeature.working()
                && CardFeature.run(() -> Cards.firstPending(cards), null) != null) return;
        UpdateFeature.run(() -> {
            showUpdatePromptNow();
            return null;
        }, null);
    }

    private void showUpdatePromptNow() {
        final Updater.Feed feed = offered;
        View panel = getLayoutInflater().inflate(R.layout.dialog_update_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.update_prompt_title, feed.versionName));
        ((TextView) panel.findViewById(R.id.body)).setText(getString(R.string.update_prompt_body, BuildConfig.VERSION_NAME));
        TextView notes = panel.findViewById(R.id.notes);
        if (feed.notes.isEmpty()) notes.setVisibility(View.GONE);
        else notes.setText(feed.notes);
        final TextView status = panel.findViewById(R.id.status);
        final TextView later = panel.findViewById(R.id.btnLater);
        final TextView update = panel.findViewById(R.id.btnUpdate);
        final TextView skip = panel.findViewById(R.id.btnSkip);

        final Dialog dialog = glassDialog(panel);
        final AtomicBoolean cancel = new AtomicBoolean();
        later.setOnClickListener(v -> {
            updatePromptDue = false;   // the dot stays; the next weekly check asks again
            dialog.dismiss();
        });
        skip.setOnClickListener(v -> {
            updatePromptDue = false;
            UpdateFeature.run(() -> {
                File file = Updater.stateFile(this);
                Updater.State st = Updater.load(file);
                st.skipped = feed.versionCode;
                return Updater.save(file, st);
            }, false);
            dialog.dismiss();
            updateUpdateDot();
        });
        update.setOnClickListener(v -> UpdateFeature.run(() -> {
            startUpdate(feed, dialog, cancel, status, later, update, skip);
            return null;
        }, null));
        dialog.setOnCancelListener(d -> later.performClick());   // Back means Later, and cancels a download
        dialog.setOnDismissListener(d -> {
            cancel.set(true);
            if (updatePrompt == dialog) updatePrompt = null;
        });
        // Left and Right move between the buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (update.getVisibility() != View.VISIBLE) return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnLater;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSkip;
        };
        later.setOnKeyListener(keys);
        update.setOnKeyListener(keys);
        skip.setOnKeyListener(keys);
        updatePrompt = dialog;
        dialog.show();
        later.requestFocus();
    }

    /** Update pressed: the install permission first, then download, verify and install on the update thread. */
    private void startUpdate(Updater.Feed feed, Dialog dialog, AtomicBoolean cancel, TextView status,
                             View later, View update, View skip) {
        status.setVisibility(View.VISIBLE);
        if (!getPackageManager().canRequestPackageInstalls()) {
            // The prompt comes back when Home does, and the owner presses Update again.
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, R.string.update_permission, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException | SecurityException e) {
                status.setText(R.string.update_permission_adb);
            }
            return;
        }
        if (!Updater.busy.compareAndSet(false, true)) {
            status.setText(R.string.update_checking);   // a weekly check is still running; try again in a moment
            return;
        }
        update.setVisibility(View.GONE);
        skip.setVisibility(View.GONE);
        later.requestFocus();   // Later now cancels the download
        status.setText(getString(R.string.update_downloading, 0));
        final Context app = getApplicationContext();
        Updater.EXECUTOR.execute(() -> {
            int result;
            try {
                result = UpdateFeature.run(() -> Updater.update(app, feed, cancel, (stage, percent) -> handler.post(() -> {
                    if (cancel.get()) return;
                    status.setText(stage == Updater.Progress.DOWNLOADING ? getString(R.string.update_downloading, percent)
                            : getString(stage == Updater.Progress.VERIFYING ? R.string.update_verifying : R.string.update_installing));
                })), Updater.FAILED_INSTALL);
            } finally {
                Updater.busy.set(false);
            }
            final int done = result;
            handler.post(() -> {
                if (done == Updater.DONE) {
                    // Android takes it from here; declining its confirmation counts as Later.
                    updatePromptDue = false;
                    return;
                }
                if (done == Updater.CANCELLED || isDestroyed() || !dialog.isShowing()) return;
                status.setText(done == Updater.FAILED_DOWNLOAD ? R.string.update_download_failed
                        : done == Updater.REJECTED ? R.string.update_rejected : R.string.update_install_failed);
            });
        });
    }
}
