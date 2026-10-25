package com.streamvault.plugin.hap;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jopsis.httpaceserveproxy.ChannelTestBatch;
import com.jopsis.httpaceserveproxy.HapBridge;
import com.jopsis.httpaceserveproxy.StopWipe;
import com.jopsis.httpaceserveproxy.UpdateClient;
import com.jopsis.httpaceserveproxy.UpdateDownloader;
import com.jopsis.httpaceserveproxy.UpdateInstaller;
import com.jopsis.httpaceserveproxy.UpdateSettings;
import com.jopsis.httpaceserveproxy.UpdateVerifier;
import com.jopsis.httpaceserveproxy.update.AppVersion;
import com.jopsis.httpaceserveproxy.update.ReleaseAsset;
import com.jopsis.httpaceserveproxy.update.ReleaseInfo;
import com.jopsis.httpaceserveproxy.update.UpdateConfig;
import com.jopsis.httpaceserveproxy.update.UpdateDecision;

import java.io.File;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class HapConfigActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 1000L;
    /** Peer budget of one channel inside a list test. */
    private static final int CHANNEL_TEST_MAX_WAIT_SECONDS = 8;
    /** Peer budget of a channel the user asked for explicitly; it owns the engine, so it gets more. */
    private static final int CHANNEL_TEST_SINGLE_MAX_WAIT_SECONDS = 12;
    /** How long a playback probe is reused before asking the proxy again. */
    private static final long PLAYBACK_PROBE_TTL_MS = 1000L;
    /**
     * Palette of the approved mockup, mirroring res/values/colors.xml. These constants exist
     * because the panels below build their views in code; keep both lists identical.
     */
    private static final int COLOR_PANEL_SOFT = 0x8016243F;
    private static final int COLOR_SURFACE = 0xFF16243F;
    private static final int COLOR_SURFACE_FOCUSED = 0xFF1E3057;
    private static final int COLOR_STROKE = 0xFF22314F;
    private static final int COLOR_BRAND = 0xFF2F6BFF;
    private static final int COLOR_BRAND_MUTED = 0x332F6BFF;
    private static final int COLOR_SUCCESS = 0xFF3DDC84;
    private static final int COLOR_SUCCESS_MUTED = 0x333DDC84;
    private static final int COLOR_WARNING = 0xFFF4C430;
    private static final int COLOR_WARNING_MUTED = 0x33F4C430;
    private static final int COLOR_ERROR = 0xFFFF6B6B;
    private static final int COLOR_ERROR_MUTED = 0x33FF6B6B;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFF9FB1CC;
    private static final int COLOR_TEXT_TERTIARY = 0xFF7E90AC;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Runs state refreshes, source/playlist changes and other user-triggered work. */
    private final ExecutorService workExecutor = newSingleThread("hap-config-work");
    /**
     * Channel tests get their own thread: a list test can spend up to ~8s per channel on a
     * blocking peer check, and on the shared executor that blocked every other background action
     * (refresh, source add/remove, playlist load) for the whole test, which read as a frozen UI.
     * It runs a single explicit check, or the batch driver that dispatches the parallel checks
     * below. Keeping the driver here also keeps explicit single checks ordered behind one another.
     */
    private final ExecutorService channelTestExecutor = newSingleThread("hap-channel-test");
    /**
     * Concurrency of a list test, taken from the CPU count instead of guessed: see
     * {@link ChannelTestBatch#parallelLimit}. 4 cores on the reference device (Amlogic S905X5M)
     * give 2 checks at once, which leaves the rest of the box to playback.
     */
    private final int channelTestParallelism =
            ChannelTestBatch.parallelLimit(Runtime.getRuntime().availableProcessors());
    /** Pool for the parallel checks. Created on demand: most sessions never run a list test. */
    private volatile ExecutorService channelTestPool;
    /** The list test that is running, so a stop can abort every check it has in flight. */
    private volatile ChannelTestBatch<HapBridge.PeerResultInfo> activeChannelTestBatch;
    /**
     * Run token. Starting a test bumps it and every callback checks it, so a superseded run can
     * never overwrite a newer result. A shared boolean cannot do that: a new run would clear the
     * flag before the previous task had actually stopped.
     */
    private final AtomicInteger channelTestGeneration = new AtomicInteger();
    private volatile boolean playbackProbeActive;
    private volatile long playbackProbeAt;
    /** Reads {@link HapBridge#status} off the main thread for the 1s runtime poll. */
    private final ExecutorService statusExecutor = newSingleThread("hap-runtime-status");
    private final AtomicBoolean statusPollInFlight = new AtomicBoolean(false);
    private final Map<String, HapBridge.PeerResultInfo> peerResults = new HashMap<>();
    private final List<View> busyControls = new ArrayList<>();

    /** One pre-built view per section, keyed by {@link HapSections.Section#ordinal()}. */
    private final SparseArray<View> sectionViews = new SparseArray<>();
    /** The five rail entries, in {@link HapSections.Section} order. */
    private final List<TextView> navItems = new ArrayList<>();
    private LinearLayout navRail;
    private FrameLayout sectionContainer;
    /**
     * True when the inflated shell is the phone one.
     *
     * <p>Decided from the layout that {@code setContentView} just inflated — the phone bottom bar
     * always carries the "Más" entry and the television rail never does — and never from the
     * screen width, which is itself what chose the layout (M3). One activity therefore drives
     * both shells, and the section list stays {@link HapSections}' (S5).
     */
    private boolean phoneShell;
    /** The phone's "Más" menu, or {@code null} on the television shell. */
    private LinearLayout navMenu;
    /** The phone's fourth bottom-bar entry, or {@code null} on the television shell. */
    private TextView navMoreItem;
    private HapSections.Section currentSection = HapSections.Section.INICIO;
    /**
     * Resolves every D-pad press and owns where focus goes: movement inside the rail and the
     * current section, the remembered control of each section, and the repair after a rebuild.
     */
    private FocusNavigator focusNavigator;
    private HomeScreen homeScreen;
    /**
     * Whether the first-run assistant was opened from here, so coming back re-reads the sources it
     * may have added. Without it the Fuentes list would stay on the model it had at launch.
     */
    private boolean firstRunOpened;

    private Pill statePill;
    private Pill phasePill;
    private Pill aceServePill;
    private Pill proxyPill;
    private TextView runtimeView;
    private LinearLayout runtimePillsContainer;
    private TextView errorView;
    private TextView messageView;
    private LinearLayout endpointsContainer;
    private LinearLayout sourcesListContainer;
    private TextView sourcesHintView;
    private LinearLayout customListContainer;
    private TextView customHintView;
    private LinearLayout clientsContainer;
    private TextView clientsStatusView;
    private LinearLayout pluginTabsContainer;
    private TextView channelsHintView;
    private TextView channelFeedbackView;
    /**
     * The six row lists of the screen. Each one keeps its children and rebinds them, so a refresh
     * never loses a row — and never loses the focus that was on it (S7).
     */
    private InPlaceList<HapRows.Endpoint> endpointList;
    private InPlaceList<HapRows.Source> sourceList;
    private InPlaceList<HapRows.CustomChannel> customChannelList;
    private InPlaceList<HapRows.Client> clientList;
    private InPlaceList<HapRows.PluginTab> pluginTabList;
    /**
     * The channel list is the one list that is a recycler (D3): it is the only one long enough that
     * holding a view hierarchy per row costs real memory. It is created once, with the adapter, and
     * only ever receives a new model through {@code submitList}.
     */
    private RecyclerView channelRecycler;
    private EditText channelSearchEdit;
    /** What the search field holds; empty means the whole list. */
    private String channelQuery = "";
    private ChannelAdapter channelAdapter;
    private Button stopChannelTestButton;
    private TextView logView;
    private Switch enabledSwitch;
    private Switch externalServerSwitch;
    private Switch lanSwitch;
    private Switch ipfsSwitch;
    private Pill ipfsStatePill;
    private TextView ipfsErrorView;
    private LinearLayout ipfsGatewayContainer;
    private View ipfsGatewayRow;
    private TextView ipfsGatewayValueView;
    private String ipfsGatewayValue = "";
    private Switch ipfsExternalSwitch;
    private LinearLayout ipfsExternalFieldsContainer;
    private EditText ipfsExternalHostEdit;
    private EditText ipfsExternalPortEdit;
    private EditText ipfsGatewayPortEdit;
    private EditText ipfsStorageMaxEdit;
    private TextView ipfsAdvancedFeedbackView;
    private EditText sourceNameEdit;
    private EditText sourceUrlEdit;
    private TextView sourceFeedbackView;
    private Button sourceSaveButton;
    private Button sourceCancelButton;
    private EditText customNameEdit;
    private EditText customContentIdEdit;

    private static final int REQUEST_PICK_DEVICE_LIST = 4201;

    private List<HapBridge.SourceInfo> currentSources = new ArrayList<>();
    private LinearLayout deviceListsContainer;
    private TextView deviceListsEmptyView;
    private Button deviceImportButton;
    private InPlaceList<DeviceListModel> deviceList;
    /** True once another screen took the window, which is how a real picker announces itself. */
    private boolean pickerTookFocus;
    private List<HapBridge.CustomChannelInfo> currentCustomChannels = new ArrayList<>();
    private List<HapBridge.PluginChannelsInfo> channelPlugins = new ArrayList<>();
    private String editingM3uSourceId = "";
    private String selectedPluginId;
    private String selectedChannelContentId;
    private int customEditingIndex = -1;
    private volatile HapBridge.PeerRequest inFlightPeerRequest;
    private volatile boolean destroyed;
    private String renderedLogText;
    private boolean suppressSwitchCallbacks;

    /** Updater — own thread so a slow GitHub request never queues behind a channel test. */
    private final ExecutorService updateExecutor = newSingleThread("hap-update");
    private TextView updateInstalledView;
    private Pill updateStatePill;
    private Button updateCheckButton;
    private Button updateAllowInstallsButton;
    private Button updateDownloadButton;
    private Button updateLaterButton;
    private Button updateCancelButton;
    private Button updateInstallButton;
    private TextView updateDetailView;
    private TextView updateNotesHeaderView;
    private TextView updateNotesView;
    private TextView updateProgressView;
    private TextView updateFeedbackView;
    private LinearLayout updateConfirmContainer;
    private Switch updateAutomaticSwitch;
    private Switch updatePrereleaseSwitch;
    /** P3: persisted opt-in wipe of the streaming cache and the engine log on an explicit stop. */
    private Switch wipeOnStopSwitch;
    private EditText updateIntervalEdit;
    private EditText updateRepositoryEdit;
    private UpdateDecision currentUpdate;
    private volatile File downloadedApk;
    private volatile boolean updateCheckInFlight;
    private volatile boolean updateDownloadInProgress;
    private volatile AtomicBoolean updateCancelFlag = new AtomicBoolean(false);
    private boolean suppressUpdateCallbacks;
    private int lastUpdateProgressPercent = -1;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            refreshRuntimeState();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private static ExecutorService newSingleThread(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hap);
        buildShell();
        refreshConfigState();
        refreshRuntimeStateNow();
        populateIpfsAdvancedFields();
        // The update settings block is its own panel now, so its fields are filled here instead
        // of at the end of the update panel builder.
        populateUpdateSettingsFields();
        // P3's switch shows what was stored; the read itself belongs off the main thread (S7).
        loadWipeOnStopSetting();
        maybeRunAutomaticUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        refreshRunnable.run();
        // The system installer owns the screen during an update, so its result is picked up here.
        showPendingInstallResult();
        // Coming back from the installer, the unknown-sources settings or the "Cómo ver mis
        // canales" screen puts the user back on the control that opened it, but only when nothing
        // else kept the focus.
        if (focusNavigator != null) focusNavigator.restoreFocus();
        // Coming back from the first-run assistant can have changed its answer, so it is read
        // again instead of being remembered from launch.
        resolveFirstRunOffer();
        // The assistant can also have added a list, and only the sources list knows that: re-read
        // it through the existing refresh, once, on the way back.
        if (firstRunOpened) {
            firstRunOpened = false;
            refreshConfigState();
        }
    }

    /**
     * A dialog of this activity (or any window opened over it) takes the window focus away and gives
     * it back. Remembering on the way out and restoring on the way in is what makes a dismissed
     * dialog put the user back on the control that opened it. Restoring never steals focus: it only
     * acts when nothing in the rail or the section holds it.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Any other screen taking over is the only proof a picker really opened.
        if (!hasFocus) pickerTookFocus = true;
        if (destroyed || focusNavigator == null) return;
        if (hasFocus) focusNavigator.restoreFocus();
        else focusNavigator.rememberFocus();
    }

    /**
     * Back cancels an in-flight update download instead of leaving it running behind a closing
     * activity. From any section but Inicio it returns to Inicio; on Inicio it leaves the
     * activity, which is the default behaviour.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (updateDownloadInProgress) {
            cancelUpdateDownload();
            return;
        }
        // On a phone an open "Más" menu is the innermost thing on screen, so BACK closes it rather
        // than leaving the section behind it (M3). On television there is no menu to close.
        if (closeMoreMenu()) return;
        HapSections.Section target = HapSections.backTarget(currentSection);
        if (target != null) {
            showSection(target, false);
            if (focusNavigator != null) focusNavigator.focusRailItem(target);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        // Where the user was, for when this window comes back.
        if (focusNavigator != null) focusNavigator.rememberFocus();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // A stale posted callback must never resurrect the poll loop after teardown.
        destroyed = true;
        if (focusNavigator != null) focusNavigator.detach();
        handler.removeCallbacksAndMessages(null);
        abortChannelTest();
        releaseUpdateResources();
        statusExecutor.shutdownNow();
        ExecutorService pool = channelTestPool;
        if (pool != null) pool.shutdownNow();
        channelTestExecutor.shutdownNow();
        workExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * Binds the shell that {@code setContentView(R.layout.activity_hap)} just inflated and fills
     * its content area with the five sections. Every section is built exactly once, here, and
     * afterwards navigation only toggles visibility.
     */
    private void buildShell() {
        navRail = findViewById(R.id.hap_nav_rail);
        sectionContainer = findViewById(R.id.hap_section_container);
        phoneShell = findViewById(R.id.hap_nav_more) != null;
        bindNavigation();
        buildSections();
        // One owner of the D-pad for the whole screen: it reads the live view tree on every press,
        // so the sections below can be built, hidden and rebuilt without telling it anything.
        focusNavigator = new FocusNavigator(findViewById(R.id.hap_root), navRail, sectionContainer);
        focusNavigator.attach();
        showSection(HapSections.Section.INICIO, false);
        // A television screen has to open with visible focus. Without it the screen showed no
        // highlight at all and the first D-pad press was spent establishing one, which reads as a
        // dead remote. Posted so the rail is measured before the entry takes the focus.
        final FocusNavigator startNavigator = focusNavigator;
        navRail.post(() -> startNavigator.focusRailItem(HapSections.Section.INICIO));
    }

    /** Wires the five section entries to their section, in {@link HapSections.Section} order. */
    private void bindNavigation() {
        navItems.clear();
        for (HapSections.Section section : HapSections.Section.values()) {
            TextView item = findViewById(navItemId(section));
            if (item == null) continue;
            applyEntryIcon(item, section);
            item.setContentDescription(getString(R.string.nav_item_description, item.getText()));
            item.setOnClickListener(v -> showSection(section, false));
            navItems.add(item);
        }
        bindPhoneShell();
    }

    /**
     * The icon of one section entry. The television rail keeps the icon at the start of a wide row,
     * exactly as the box was verified (S1); a phone bottom-bar entry puts it above a centred label,
     * and the two rows inside the "Más" menu carry no icon, because they are a menu and not a bar
     * (M2).
     */
    private void applyEntryIcon(TextView item, HapSections.Section section) {
        if (!phoneShell) {
            item.setCompoundDrawablesWithIntrinsicBounds(navIconId(section), 0, 0, 0);
            item.setCompoundDrawablePadding(dp(18));
            return;
        }
        if (isBottomBarEntry(section)) {
            item.setCompoundDrawablesWithIntrinsicBounds(0, navIconId(section), 0, 0);
            item.setCompoundDrawablePadding(dp(4));
        }
    }

    /** True when a section has its own entry in the phone bottom bar; the other two are in Más. */
    private static boolean isBottomBarEntry(HapSections.Section section) {
        return HapSections.mobileSection(HapSections.mobileEntry(section)) == section;
    }

    /**
     * Wires the parts of the shell that only a phone has: the fourth bottom-bar entry that opens the
     * "Más" menu, and the two top-bar actions of the mockup. On the television shell every id here
     * is absent and nothing is wired, so the five direct rail entries are untouched (S1, M3).
     */
    private void bindPhoneShell() {
        navMenu = findViewById(R.id.hap_nav_menu);
        navMoreItem = findViewById(R.id.hap_nav_more);
        if (navMoreItem != null) {
            navMoreItem.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_nav_more, 0, 0);
            navMoreItem.setCompoundDrawablePadding(dp(4));
            navMoreItem.setOnClickListener(v -> setMoreMenuOpen(!isMoreMenuOpen()));
        }
        View settingsAction = findViewById(R.id.hap_top_settings);
        if (settingsAction != null) {
            // The gear is the shortcut the mockup draws; it goes straight to Ajustes, which is one
            // of the two sections behind Más and therefore has no bottom-bar entry of its own.
            settingsAction.setOnClickListener(v -> showSection(HapSections.Section.AJUSTES, false));
        }
        View overflowAction = findViewById(R.id.hap_top_overflow);
        if (overflowAction != null) {
            overflowAction.setOnClickListener(v -> setMoreMenuOpen(!isMoreMenuOpen()));
        }
    }

    private boolean isMoreMenuOpen() {
        return navMenu != null && navMenu.getVisibility() == View.VISIBLE;
    }

    /**
     * Opens or closes the phone's "Más" menu. The bottom entry and the top bar's overflow drive the
     * same method, so there is exactly one answer to "is the menu open".
     */
    private void setMoreMenuOpen(boolean open) {
        if (navMenu == null) return;
        int target = open ? View.VISIBLE : View.GONE;
        if (navMenu.getVisibility() != target) navMenu.setVisibility(target);
    }

    /**
     * Closes the menu if it is open.
     *
     * @return true when it was open, so BACK can consume the press instead of leaving the section
     */
    private boolean closeMoreMenu() {
        if (!isMoreMenuOpen()) return false;
        setMoreMenuOpen(false);
        return true;
    }

    private static int navIconId(HapSections.Section section) {
        switch (section) {
            case CANALES:
                return R.drawable.ic_nav_channels;
            case FUENTES:
                return R.drawable.ic_nav_sources;
            case CONEXIONES:
                return R.drawable.ic_nav_connections;
            case AJUSTES:
                return R.drawable.ic_nav_settings;
            case INICIO:
            default:
                return R.drawable.ic_nav_home;
        }
    }

    private static int navItemId(HapSections.Section section) {
        switch (section) {
            case CANALES:
                return R.id.hap_nav_channels;
            case FUENTES:
                return R.id.hap_nav_sources;
            case CONEXIONES:
                return R.id.hap_nav_connections;
            case AJUSTES:
                return R.id.hap_nav_settings;
            case INICIO:
            default:
                return R.id.hap_nav_home;
        }
    }

    /**
     * Builds every section once. The panel builders are unchanged: they are only called from here
     * now instead of being stacked in a single scroll, so nothing was rewritten or dropped.
     */
    private void buildSections() {
        // The header panel (start/stop, restart, prepare /aio) lives inside the home status card
        // and keeps its behaviour there.
        homeScreen = new HomeScreen(this, homeListener(), buildHeaderPanel(), phoneShell);
        addSection(HapSections.Section.INICIO, homeScreen.getView());
        for (HapSections.Section section : HapSections.Section.values()) {
            if (section == HapSections.Section.INICIO) continue;
            addSection(section, buildSectionView(section));
        }
    }

    private void addSection(HapSections.Section section, View view) {
        view.setVisibility(View.GONE);
        sectionContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        sectionViews.put(section.ordinal(), view);
    }

    /** One scrollable column per section, in the order {@link HapSections} declares. */
    /** The page title of a section, in the same size Inicio uses. */
    private View sectionHeading(HapSections.Section section) {
        TextView title = text(getString(sectionTitleRes(section)), 26, COLOR_TEXT_PRIMARY, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(4));
        return title;
    }

    private static int sectionTitleRes(HapSections.Section section) {
        switch (section) {
            case CANALES:
                return R.string.nav_channels;
            case FUENTES:
                return R.string.nav_sources;
            case CONEXIONES:
                return R.string.nav_connections;
            case AJUSTES:
                return R.string.nav_settings;
            case INICIO:
            default:
                return R.string.nav_home;
        }
    }

    private View buildSectionView(HapSections.Section section) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (HapSections.Panel panel : HapSections.panels(section)) {
            column.addView(buildPanel(panel),
                    column.getChildCount() == 0 ? matchWrapTop(20) : matchWrapTop(20));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // The title stays outside the scroll: focusing a card used to scroll it to the edge and cut
        // its glyphs in half, which looks like a fault rather than a scroll.
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(sectionHeading(section), matchWrap());
        wrapper.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return wrapper;
    }

    /** Maps a panel of {@link HapSections} to its existing builder; nothing is re-implemented. */
    private View buildPanel(HapSections.Panel panel) {
        switch (panel) {
            case DIAGNOSTICS:
                return buildDiagnosticsPanel();
            case CHANNEL_STATUS:
                return buildChannelStatusPanel();
            case SOURCES:
                return buildSourcesPanel();
            case CUSTOM_CHANNELS:
                return buildCustomSection();
            case AIO:
                return buildAioPanel();
            case EXTERNAL_PLAYERS:
                return buildExternalPlayersPanel();
            case CLIENTS:
                return buildClientsPanel();
            case IPFS:
                return buildIpfsPanel();
            case IPFS_ADVANCED:
                return buildIpfsAdvancedSection();
            case LOGS:
                return buildLogsPanel();
            case PRIVACY:
                return buildPrivacyPanel();
            case UPDATE:
                return buildUpdatePanel();
            case UPDATE_SETTINGS:
                return buildUpdateSettingsSection();
            case HEADER:
            default:
                // The status panel is built once, for the home card. Building it twice would
                // create a second set of pills and switches behind the 1s refresh.
                throw new IllegalStateException("Panel " + panel + " is not a section panel");
        }
    }

    private HomeScreen.Listener homeListener() {
        return new HomeScreen.Listener() {
            @Override
            public void onOpenHowToWatch() {
                // An explicit Intent: the screen is part of HaP, not an exported action.
                startActivity(new Intent(HapConfigActivity.this, ConnectPlayerActivity.class));
            }

            @Override
            public void onOpenSources() {
                showSection(HapSections.Section.FUENTES, true);
            }

            @Override
            public void onOpenChannels() {
                showSection(HapSections.Section.CANALES, true);
            }

            @Override
            public void onOpenFirstRun() {
                // An explicit Intent: the assistant is part of HaP, not an exported action, and it
                // never opens by itself on launch.
                firstRunOpened = true;
                startActivity(new Intent(HapConfigActivity.this, FirstRunActivity.class));
            }
        };
    }

    /**
     * Decides whether the first-run card is offered, off the main thread (T8).
     *
     * <p>Both inputs touch disk — the user's stored M3U lists and the two wizard decisions — so the
     * question runs on the work executor and only the answer reaches the main thread. Tasks queue
     * on one thread and their results are posted back in the same order, so the last answer applied
     * is always the newest one; a launch, a resume and a config change cannot race.
     */
    private void resolveFirstRunOffer() {
        if (destroyed) return;
        try {
            workExecutor.execute(() -> {
                boolean offer = FirstRunStore.shouldOfferNow(this);
                main(() -> {
                    if (!destroyed && homeScreen != null) homeScreen.applyFirstRunOffer(offer);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The activity is tearing down: the card keeps the state it already has.
        }
    }

    /**
     * Shows one section and hides the others. No view is ever removed: a section that is
     * re-selected keeps its children, its scroll position and the state of its controls.
     */
    private void showSection(HapSections.Section section, boolean focusContent) {
        if (section == null || sectionContainer == null) return;
        currentSection = section;

        for (int i = 0; i < sectionViews.size(); i++) {
            View view = sectionViews.valueAt(i);
            int visibility = sectionViews.keyAt(i) == section.ordinal() ? View.VISIBLE : View.GONE;
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
        }

        // Selected is not the same as focused: the rail shows both at once.
        for (HapSections.Section each : HapSections.Section.values()) {
            TextView item = navItemFor(each);
            if (item != null && item.isSelected() != (each == section)) item.setSelected(each == section);
        }

        // On a phone the two sections behind the menu light up "Más", which is the entry that owns
        // them; a navigation from anywhere also closes that menu, because it is a door, not a mode.
        if (navMoreItem != null) {
            boolean selected = HapSections.mobileEntry(section) == HapSections.MobileEntry.MAS;
            if (navMoreItem.isSelected() != selected) navMoreItem.setSelected(selected);
        }
        setMoreMenuOpen(false);

        if (focusNavigator != null) {
            // The navigator keeps one remembered control per section, so entering a section returns
            // to where the user left it and the first entry of a section gets its first control.
            focusNavigator.setSection(section);
            if (focusContent) focusNavigator.focusSection();
        }
    }

    private TextView navItemFor(HapSections.Section section) {
        int index = section.ordinal();
        return index >= 0 && index < navItems.size() ? navItems.get(index) : null;
    }

    /**
     * The one entry point of every D-pad press (S6).
     *
     * <p>{@link FocusNavigator} resolves the movement from the live view tree, so nothing here
     * depends on the platform's geometric focus search: UP/DOWN keep the column and never leave the
     * section, the rail wraps inside itself, LEFT returns to the rail from the first control of a
     * row, and RIGHT enters the section. A press the navigator does not recognise falls through
     * untouched, so OK, BACK, the text fields and the system keys keep their own behaviour.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!destroyed && focusNavigator != null && focusNavigator.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * The home card's action: the switch that starts and stops HaP, plus the feedback that has to
     * stay next to it. Everything diagnostic — the engine pills, the addresses, restart and
     * prepare — moved to {@link #buildDiagnosticsPanel()}, because inside the status card it
     * duplicated the plain-language state, truncated its own labels and pushed the primary action
     * off the first screen.
     */
    private View buildHeaderPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(null, false, true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(row, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(left, compact ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        left.addView(text(getString(R.string.label_enable_hap), 16, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());

        errorView = text("", 13, COLOR_ERROR, Typeface.NORMAL);
        errorView.setPadding(0, dp(6), 0, 0);
        errorView.setVisibility(View.GONE);
        left.addView(errorView, matchWrap());

        messageView = text("", 13, COLOR_BRAND, Typeface.NORMAL);
        messageView.setPadding(0, dp(6), 0, 0);
        messageView.setVisibility(View.GONE);
        left.addView(messageView, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        actions.setPadding(compact ? 0 : dp(16), compact ? dp(12) : 0, 0, 0);
        row.addView(actions, compact ? matchWrap() : wrapWrap());

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        actions.addView(switchRow, wrapWrap());

        enabledSwitch = new Switch(this);
        enabledSwitch.setPadding(dp(8), 0, 0, 0);
        // The label sits beside the switch, so the control itself says what it does.
        enabledSwitch.setContentDescription(getString(R.string.hap_enable_switch_description));
        enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setEnabled(checked);
        });
        busyControls.add(enabledSwitch);
        switchRow.addView(enabledSwitch, wrapWrap());

        return panel.container;
    }

    /**
     * The technical detail the home screen deliberately does not show: the engine and proxy pills,
     * the addresses, and the restart and prepare actions. It lives in Conexiones, next to the other
     * player-facing controls, so a normal user never meets it and an advanced one still finds it.
     */
    private View buildDiagnosticsPanel() {
        PanelViews panel = panel(getString(R.string.panel_diagnostics), false, true);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(titleRow, matchWrap());

        statePill = new Pill(pill(getString(R.string.status_stopped), COLOR_ERROR_MUTED, COLOR_ERROR));
        titleRow.addView(statePill.view, wrapWrap());
        phasePill = new Pill(pill(getString(R.string.status_idle), COLOR_SURFACE, COLOR_TEXT_SECONDARY));
        titleRow.addView(phasePill.view, wrapWrapLeft(8));

        runtimePillsContainer = new LinearLayout(this);
        runtimePillsContainer.setOrientation(LinearLayout.HORIZONTAL);
        runtimePillsContainer.setGravity(Gravity.CENTER_VERTICAL);
        runtimePillsContainer.setPadding(0, dp(10), 0, 0);
        panel.body.addView(runtimePillsContainer, matchWrap());

        // Built once here and only re-labelled/re-coloured afterwards: rebuilding these pills on
        // every refresh tick allocated a new TextView, layout and GradientDrawable per second.
        aceServePill = new Pill(pill(
                getString(R.string.label_aceserve) + " " + onlineLabel(false),
                COLOR_ERROR_MUTED,
                COLOR_ERROR
        ));
        runtimePillsContainer.addView(aceServePill.view, wrapWrap());
        proxyPill = new Pill(pill(
                getString(R.string.label_proxy) + " " + onlineLabel(false),
                COLOR_ERROR_MUTED,
                COLOR_ERROR
        ));
        runtimePillsContainer.addView(proxyPill.view, wrapWrapLeft(8));

        runtimeView = text(HapBridge.LOCAL_AIO_URL, 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        runtimeView.setSingleLine(true);
        runtimeView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        runtimeView.setPadding(0, dp(8), 0, 0);
        panel.body.addView(runtimeView, matchWrap());

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.START);
        buttonRow.setPadding(0, dp(10), 0, 0);
        panel.body.addView(buttonRow, wrapWrap());

        Button restart = button(getString(R.string.button_restart_proxy));
        restart.setOnClickListener(v -> restartProxy());
        buttonRow.addView(restart, wrapWrap());

        Button prepare = button(getString(R.string.button_prepare_aio));
        prepare.setOnClickListener(v -> prepareAio());
        buttonRow.addView(prepare, wrapWrapLeft(8));

        return panel.container;
    }

    private View buildExternalPlayersPanel() {
        PanelViews panel = panel(getString(R.string.section_external_players), false, true);

        TextView hint = text(getString(R.string.message_external_players_hint), 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        hint.setPadding(0, 0, 0, dp(10));
        panel.body.addView(hint, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(row, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        left.addView(text(getString(R.string.label_external_player_server), 14, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());
        TextView detail = text(getString(R.string.message_external_player_server_detail), 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        detail.setPadding(0, dp(3), 0, 0);
        left.addView(detail, matchWrap());

        externalServerSwitch = new Switch(this);
        externalServerSwitch.setPadding(dp(10), 0, 0, 0);
        externalServerSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setExternalPlayerServer(checked);
        });
        busyControls.add(externalServerSwitch);
        row.addView(externalServerSwitch, wrapWrap());

        return panel.container;
    }

    private View buildIpfsPanel() {
        PanelViews panel = panel(getString(R.string.section_ipfs), false, true);

        TextView hint = text(getString(R.string.message_ipfs_detail), 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        hint.setPadding(0, 0, 0, dp(10));
        panel.body.addView(hint, matchWrap());

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(switchRow, matchWrap());

        LinearLayout switchLeft = new LinearLayout(this);
        switchLeft.setOrientation(LinearLayout.VERTICAL);
        switchRow.addView(switchLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        switchLeft.addView(titleRow, matchWrap());
        titleRow.addView(text(getString(R.string.label_ipfs_enable), 14, COLOR_TEXT_PRIMARY, Typeface.BOLD));
        ipfsStatePill = new Pill(pill(getString(R.string.status_ipfs_offline), COLOR_ERROR_MUTED, COLOR_ERROR));
        titleRow.addView(ipfsStatePill.view, wrapWrapLeft(10));

        ipfsErrorView = text("", 12, COLOR_ERROR, Typeface.NORMAL);
        ipfsErrorView.setPadding(0, dp(4), 0, 0);
        ipfsErrorView.setVisibility(View.GONE);
        switchLeft.addView(ipfsErrorView, matchWrap());

        ipfsSwitch = new Switch(this);
        ipfsSwitch.setPadding(dp(10), 0, 0, 0);
        ipfsSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setIpfsEnabled(checked);
        });
        busyControls.add(ipfsSwitch);
        switchRow.addView(ipfsSwitch, wrapWrap());

        ipfsGatewayContainer = new LinearLayout(this);
        ipfsGatewayContainer.setOrientation(LinearLayout.VERTICAL);
        ipfsGatewayContainer.setPadding(0, dp(8), 0, 0);
        panel.body.addView(ipfsGatewayContainer, matchWrap());
        // Built once with the panel; the refresh only re-labels it and shows or hides it.
        ipfsGatewayRow = buildIpfsGatewayRow();
        ipfsGatewayRow.setVisibility(View.GONE);
        ipfsGatewayContainer.addView(ipfsGatewayRow, matchWrap());

        // The advanced block is its own panel in Ajustes, so it is not nested twice here.
        return panel.container;
    }

    /** The single gateway row of the IPFS panel, built once so the refresh only re-labels it. */
    private View buildIpfsGatewayRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(pill(getString(R.string.label_ipfs_gateway_url), COLOR_SURFACE, COLOR_TEXT_SECONDARY), wrapWrap());

        ipfsGatewayValueView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        ipfsGatewayValueView.setSingleLine(true);
        ipfsGatewayValueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        ipfsGatewayValueView.setPadding(dp(8), 0, dp(8), 0);
        row.addView(ipfsGatewayValueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = smallButton(getString(R.string.button_copy));
        copy.setOnClickListener(v -> copyToClipboard("HaP IPFS gateway", ipfsGatewayValue));
        row.addView(copy, wrapWrap());
        return row;
    }

    private View buildIpfsAdvancedSection() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        String title = getString(R.string.label_ipfs_advanced);
        TextView header = text("+ " + title, 16, COLOR_TEXT_SECONDARY, Typeface.BOLD);
        header.setFocusable(true);
        header.setBackground(focusBackground(0x00000000, COLOR_SURFACE_FOCUSED, dp(8), 0, 0));
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        container.addView(header, matchWrap());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.setVisibility(View.GONE);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(6);
        container.addView(body, bodyParams);

        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            header.setText((expanded ? "+ " : "- ") + title);
        });

        LinearLayout externalRow = new LinearLayout(this);
        externalRow.setOrientation(LinearLayout.HORIZONTAL);
        externalRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(externalRow, matchWrap());

        externalRow.addView(text(getString(R.string.label_ipfs_external_mode), 13, COLOR_TEXT_PRIMARY, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ipfsExternalSwitch = new Switch(this);
        ipfsExternalSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                ipfsExternalFieldsContainer.setVisibility(checked ? View.VISIBLE : View.GONE));
        busyControls.add(ipfsExternalSwitch);
        externalRow.addView(ipfsExternalSwitch, wrapWrap());

        ipfsExternalFieldsContainer = new LinearLayout(this);
        ipfsExternalFieldsContainer.setOrientation(LinearLayout.VERTICAL);
        ipfsExternalFieldsContainer.setVisibility(View.GONE);
        ipfsExternalFieldsContainer.setPadding(0, dp(8), 0, 0);
        body.addView(ipfsExternalFieldsContainer, matchWrap());

        ipfsExternalHostEdit = editText(getString(R.string.label_ipfs_external_host), false);
        ipfsExternalFieldsContainer.addView(ipfsExternalHostEdit, matchWrap());
        ipfsExternalPortEdit = editText(getString(R.string.label_ipfs_external_port), false);
        ipfsExternalPortEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        ipfsExternalFieldsContainer.addView(ipfsExternalPortEdit, matchWrapTop(8));

        LinearLayout embeddedRow = new LinearLayout(this);
        embeddedRow.setOrientation(LinearLayout.HORIZONTAL);
        embeddedRow.setPadding(0, dp(10), 0, 0);
        body.addView(embeddedRow, matchWrap());

        ipfsGatewayPortEdit = editText(getString(R.string.label_ipfs_gateway_port), false);
        ipfsGatewayPortEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        embeddedRow.addView(ipfsGatewayPortEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ipfsStorageMaxEdit = editText(getString(R.string.label_ipfs_storage_max), false);
        ipfsStorageMaxEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams storageParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        storageParams.leftMargin = dp(10);
        embeddedRow.addView(ipfsStorageMaxEdit, storageParams);

        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setGravity(Gravity.RIGHT);
        saveRow.setPadding(0, dp(10), 0, 0);
        body.addView(saveRow, matchWrap());

        Button save = button(getString(R.string.button_save));
        save.setOnClickListener(v -> saveIpfsAdvancedSettings());
        saveRow.addView(save, wrapWrap());

        ipfsAdvancedFeedbackView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        ipfsAdvancedFeedbackView.setPadding(0, dp(8), 0, 0);
        ipfsAdvancedFeedbackView.setVisibility(View.GONE);
        body.addView(ipfsAdvancedFeedbackView, matchWrap());

        return container;
    }

    private View buildAioPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_aio_lan), false, true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(row, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, compact ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        left.addView(text(getString(R.string.label_local_list), 12, COLOR_TEXT_TERTIARY, Typeface.BOLD), matchWrap());
        endpointsContainer = new LinearLayout(this);
        endpointsContainer.setOrientation(LinearLayout.VERTICAL);
        endpointsContainer.setPadding(0, dp(6), 0, 0);
        left.addView(endpointsContainer, matchWrap());
        endpointList = new InPlaceList<>(endpointsContainer, () -> new EndpointRow());

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL | (compact ? Gravity.START : Gravity.RIGHT));
        right.setPadding(compact ? 0 : dp(18), compact ? dp(12) : 0, 0, 0);
        row.addView(right, compact ? matchWrap() : wrapWrap());

        right.addView(text(getString(R.string.label_lan_server), 13, COLOR_TEXT_SECONDARY, Typeface.BOLD));
        lanSwitch = new Switch(this);
        lanSwitch.setPadding(dp(8), 0, 0, 0);
        lanSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setServerMode(checked);
        });
        busyControls.add(lanSwitch);
        right.addView(lanSwitch, wrapWrap());

        return panel.container;
    }

    private View buildSourcesPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_sources), true, false);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
        form.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.body.addView(form, matchWrap());

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(editRow, matchWrap());

        sourceNameEdit = editText(getString(R.string.label_source_name), false);
        editRow.addView(sourceNameEdit, compact
                ? matchWrap()
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));

        sourceUrlEdit = editText(getString(R.string.label_source_url), false);
        LinearLayout.LayoutParams urlParams = compact
                ? matchWrapTop(8)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f);
        if (!compact) urlParams.leftMargin = dp(10);
        editRow.addView(sourceUrlEdit, urlParams);

        LinearLayout sourceButtons = new LinearLayout(this);
        sourceButtons.setOrientation(LinearLayout.HORIZONTAL);
        sourceButtons.setGravity(Gravity.RIGHT);
        sourceButtons.setPadding(0, dp(10), 0, 0);
        form.addView(sourceButtons, matchWrap());

        sourceSaveButton = button(getString(R.string.button_add_m3u_source));
        sourceSaveButton.setOnClickListener(v -> saveM3uSource());
        sourceButtons.addView(sourceSaveButton, wrapWrap());

        sourceCancelButton = button(getString(R.string.button_cancel));
        sourceCancelButton.setVisibility(View.GONE);
        sourceCancelButton.setOnClickListener(v -> clearSourceDraft());
        sourceButtons.addView(sourceCancelButton, wrapWrapLeft(8));

        sourceFeedbackView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        sourceFeedbackView.setPadding(0, dp(8), 0, 0);
        sourceFeedbackView.setVisibility(View.GONE);
        form.addView(sourceFeedbackView, matchWrap());

        sourcesListContainer = new LinearLayout(this);
        sourcesListContainer.setOrientation(LinearLayout.VERTICAL);
        sourcesListContainer.setPadding(0, dp(12), 0, 0);
        panel.body.addView(sourcesListContainer, matchWrap());
        // The empty-state line lives beside the list and is only shown or hidden: it is not a row.
        sourcesHintView = text(getString(R.string.message_no_sources), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        sourcesHintView.setVisibility(View.GONE);
        panel.body.addView(sourcesHintView, matchWrapTop(12));
        sourceList = new InPlaceList<>(sourcesListContainer, () ->
                new SourceRow(sourcesListContainer.getChildCount() == 0 ? 0 : 8));

        // Lists held on the device. On a television this is the only path that always works: the box
        // ships no document picker, and the file managers that answer GET_CONTENT are optional
        // installs. A file reaches this folder with adb push.
        TextView deviceTitle = text(getString(R.string.section_device_lists), 16, COLOR_TEXT_PRIMARY, Typeface.BOLD);
        deviceTitle.setPadding(0, dp(18), 0, 0);
        panel.body.addView(deviceTitle, matchWrap());

        File listsFolder = LocalListFiles.folder(this);
        String folderPath = listsFolder == null
                ? getString(R.string.status_device_lists_unavailable)
                : listsFolder.getAbsolutePath();
        TextView deviceHint = text(getString(R.string.message_device_lists_folder, folderPath), 12,
                COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        deviceHint.setPadding(0, dp(6), 0, 0);
        panel.body.addView(deviceHint, matchWrap());

        deviceImportButton = button(getString(R.string.button_import_file));
        deviceImportButton.setOnClickListener(v -> pickLocalList());
        deviceImportButton.setVisibility(canPickFile() ? View.VISIBLE : View.GONE);
        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.setPadding(0, dp(10), 0, 0);
        importRow.addView(deviceImportButton, wrapWrap());
        panel.body.addView(importRow, matchWrap());

        deviceListsContainer = new LinearLayout(this);
        deviceListsContainer.setOrientation(LinearLayout.VERTICAL);
        deviceListsContainer.setPadding(0, dp(10), 0, 0);
        panel.body.addView(deviceListsContainer, matchWrap());
        deviceListsEmptyView = text(getString(R.string.message_no_device_lists), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        deviceListsEmptyView.setVisibility(View.GONE);
        panel.body.addView(deviceListsEmptyView, matchWrapTop(8));
        deviceList = new InPlaceList<>(deviceListsContainer, () ->
                new DeviceListRow(deviceListsContainer.getChildCount() == 0 ? 0 : 8));

        return panel.container;
    }

    private View buildCustomSection() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_custom_channels), true, false);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
        form.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.body.addView(form, matchWrap());

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(editRow, matchWrap());

        customNameEdit = editText(getString(R.string.label_channel_name), false);
        editRow.addView(customNameEdit, compact
                ? matchWrap()
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));

        customContentIdEdit = editText(getString(R.string.label_acestream_id), false);
        LinearLayout.LayoutParams contentParams = compact
                ? matchWrapTop(8)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f);
        if (!compact) contentParams.leftMargin = dp(10);
        editRow.addView(customContentIdEdit, contentParams);

        LinearLayout customButtons = new LinearLayout(this);
        customButtons.setOrientation(LinearLayout.HORIZONTAL);
        customButtons.setGravity(Gravity.RIGHT);
        customButtons.setPadding(0, dp(10), 0, 0);
        form.addView(customButtons, matchWrap());

        Button save = button(customEditingIndex >= 0 ? getString(R.string.button_update) : getString(R.string.button_save));
        save.setOnClickListener(v -> saveCustomChannel());
        customButtons.addView(save, wrapWrap());

        Button clear = button(getString(R.string.button_clear));
        clear.setOnClickListener(v -> clearCustomDraft());
        customButtons.addView(clear, wrapWrapLeft(8));

        customListContainer = new LinearLayout(this);
        customListContainer.setOrientation(LinearLayout.VERTICAL);
        customListContainer.setPadding(0, dp(10), 0, 0);
        panel.body.addView(customListContainer, matchWrap());
        customHintView = text(getString(R.string.message_no_custom_channels), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        customHintView.setVisibility(View.GONE);
        panel.body.addView(customHintView, matchWrapTop(10));
        customChannelList = new InPlaceList<>(customListContainer, () ->
                new CustomChannelRow(customListContainer.getChildCount() == 0 ? 0 : 8));

        return panel.container;
    }

    private View buildClientsPanel() {
        PanelViews panel = panel(getString(R.string.section_clients), false, true);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(controls, matchWrap());

        Button refresh = button(getString(R.string.button_refresh_clients));
        refresh.setOnClickListener(v -> loadClients());
        controls.addView(refresh, wrapWrap());

        clientsStatusView = text(getString(R.string.message_no_active_clients), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        clientsStatusView.setPadding(dp(10), 0, 0, 0);
        controls.addView(clientsStatusView, wrapWrap());

        clientsContainer = new LinearLayout(this);
        clientsContainer.setOrientation(LinearLayout.VERTICAL);
        clientsContainer.setPadding(0, dp(10), 0, 0);
        panel.body.addView(clientsContainer, matchWrap());
        clientList = new InPlaceList<>(clientsContainer, () ->
                new ClientRow(clientsContainer.getChildCount() == 0 ? 0 : 8));

        return panel.container;
    }

    private View buildChannelStatusPanel() {
        // Folded, this was the whole of Canales: one header on an otherwise empty screen. It is the
        // section's substance, so it starts open.
        PanelViews panel = panel(getString(R.string.section_channel_status), true, true);

        HorizontalScrollView controlsScroll = new HorizontalScrollView(this);
        controlsScroll.setHorizontalScrollBarEnabled(false);
        controlsScroll.setFocusable(false);
        controlsScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controlsScroll.addView(controls, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.body.addView(controlsScroll, matchWrap());

        Button load = button(getString(R.string.button_load_lists));
        load.setOnClickListener(v -> loadChannelPlugins());
        controls.addView(load, wrapWrap());

        Button testList = button(getString(R.string.button_test_list));
        testList.setOnClickListener(v -> checkSelectedList());
        controls.addView(testList, wrapWrapLeft(8));

        stopChannelTestButton = button(getString(R.string.button_stop));
        stopChannelTestButton.setEnabled(false);
        stopChannelTestButton.setOnClickListener(v -> stopChannelTest());
        controls.addView(stopChannelTestButton, wrapWrapLeft(8));

        channelFeedbackView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        channelFeedbackView.setPadding(0, dp(10), 0, 0);
        channelFeedbackView.setVisibility(View.GONE);
        panel.body.addView(channelFeedbackView, matchWrap());

        HorizontalScrollView pluginScroll = new HorizontalScrollView(this);
        pluginScroll.setHorizontalScrollBarEnabled(false);
        pluginScroll.setFocusable(false);
        pluginScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        pluginScroll.setPadding(0, dp(10), 0, 0);
        pluginTabsContainer = new LinearLayout(this);
        pluginTabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        pluginScroll.addView(pluginTabsContainer, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.body.addView(pluginScroll, matchWrap());
        pluginTabList = new InPlaceList<>(pluginTabsContainer, () ->
                new PluginTabRow(pluginTabsContainer.getChildCount() == 0 ? 0 : 8));

        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hintParams.topMargin = dp(10);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        listParams.topMargin = dp(10);
        channelsHintView = text(getString(R.string.message_load_channels_hint), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        channelsHintView.setVisibility(View.GONE);
        panel.body.addView(channelsHintView, hintParams);

        // The channel list is a recycler (D3). It owns a fixed height instead of growing with the
        // page: that bounded viewport is what keeps only the visible rows measured and attached,
        // and it is why the D-pad hand-off in FocusNavigator exists.
        listParams.height = getResources().getDimensionPixelSize(R.dimen.hap_channel_list_height);
        // The search field lives above the list: on a television the list can be hundreds of rows and
        // scrolling to a channel by hand is the slowest part of using it.
        channelSearchEdit = editText(getString(R.string.hint_search_channel), false);
        channelSearchEdit.setSingleLine(true);
        LinearLayout.LayoutParams searchParams = matchWrapTop(12);
        panel.body.addView(channelSearchEdit, searchParams);
        channelSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String next = s == null ? "" : s.toString();
                if (next.equals(channelQuery)) return;
                channelQuery = next;
                // Filtering repaints rows only: it never moves focus, which is what keeps the D-pad
                // steady while the user types.
                renderChannels();
            }
        });

        channelRecycler = new RecyclerView(this);
        channelRecycler.setLayoutManager(new LinearLayoutManager(this));
        // A peer result must repaint its row, not fade it out from under the user's focus.
        channelRecycler.setItemAnimator(null);
        // The list's own size never depends on its rows, so a change never re-measures it.
        channelRecycler.setHasFixedSize(true);
        channelRecycler.setFocusable(false);
        channelRecycler.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        channelRecycler.setVisibility(View.GONE);
        panel.body.addView(channelRecycler, listParams);
        channelAdapter = new ChannelAdapter(
                () -> new ChannelRow(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )),
                dp(8)
        );
        channelRecycler.setAdapter(channelAdapter);
        renderChannels();

        return panel.container;
    }

    private View buildLogsPanel() {
        PanelViews panel = panel(getString(R.string.section_log), true, false);
        logView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(roundRect(0xAA07111B, dp(10), 0, 0));
        panel.body.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
        ));
        return panel.container;
    }

    // ------------------------------------------------------------------ privacy

    /**
     * P3: the opt-in wipe of the streaming cache and the engine log on an explicit stop.
     *
     * <p>Same shape as the IPFS panel — a label, one line saying what the switch does, and the
     * switch — so the settings language stays the one the rest of Ajustes already speaks. The
     * switch itself is a persisted preference, not an action, so it is not added to
     * {@link #busyControls}: disabling a focused control mid-stop would drop the focus.
     */
    private View buildPrivacyPanel() {
        PanelViews panel = panel(getString(R.string.section_privacy), false, true);

        TextView hint = text(getString(R.string.message_wipe_on_stop_detail), 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        hint.setPadding(0, 0, 0, dp(10));
        panel.body.addView(hint, matchWrap());

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(switchRow, matchWrap());

        switchRow.addView(
                text(getString(R.string.label_wipe_on_stop), 14, COLOR_TEXT_PRIMARY, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        );

        wipeOnStopSwitch = new Switch(this);
        wipeOnStopSwitch.setPadding(dp(10), 0, 0, 0);
        wipeOnStopSwitch.setContentDescription(getString(R.string.label_wipe_on_stop));
        wipeOnStopSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            StopWipe.setEnabled(this, checked);
        });
        switchRow.addView(wipeOnStopSwitch, wrapWrap());

        return panel.container;
    }

    /**
     * Reads the persisted P3 setting off the main thread (S7) and shows it, without writing it
     * back: the same shape {@link #resolveFirstRunOffer()} uses for a disk-backed decision.
     */
    private void loadWipeOnStopSetting() {
        try {
            workExecutor.execute(() -> {
                boolean enabled = StopWipe.isEnabled(this);
                main(() -> {
                    if (destroyed || wipeOnStopSwitch == null) return;
                    if (wipeOnStopSwitch.isChecked() == enabled) return;
                    suppressSwitchCallbacks = true;
                    try {
                        wipeOnStopSwitch.setChecked(enabled);
                    } finally {
                        suppressSwitchCallbacks = false;
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The activity is tearing down: the switch keeps the state it was built with.
        }
    }

    // ------------------------------------------------------------------ updates

    /**
     * The update section.
     *
     * <p>Every view is created once and afterwards only re-labelled or made visible: an
     * asynchronous check or download must never rebuild the section, because removing the focused
     * view would drop D-pad focus back to the top of the screen mid-flow.
     */
    private View buildUpdatePanel() {
        PanelViews panel = panel(getString(R.string.section_updates), false, true);

        updateInstalledView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        panel.body.addView(updateInstalledView, matchWrap());

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(10), 0, 0);
        panel.body.addView(scrollableControlRow(statusRow), matchWrap());

        updateStatePill = new Pill(pill(getString(R.string.update_status_idle), COLOR_SURFACE, COLOR_TEXT_SECONDARY));
        statusRow.addView(updateStatePill.view, wrapWrap());

        updateCheckButton = button(getString(R.string.button_update_check));
        updateCheckButton.setOnClickListener(v -> startUpdateCheck(true));
        statusRow.addView(updateCheckButton, wrapWrapLeft(8));

        updateAllowInstallsButton = smallButton(getString(R.string.button_update_open_settings));
        updateAllowInstallsButton.setOnClickListener(v -> openUnknownSourcesSettings());
        updateAllowInstallsButton.setVisibility(View.GONE);
        statusRow.addView(updateAllowInstallsButton, wrapWrapLeft(8));

        updateDetailView = text("", 13, COLOR_TEXT_PRIMARY, Typeface.BOLD);
        updateDetailView.setPadding(0, dp(10), 0, 0);
        updateDetailView.setVisibility(View.GONE);
        panel.body.addView(updateDetailView, matchWrap());

        updateNotesHeaderView = text(getString(R.string.update_notes_header), 12, COLOR_TEXT_TERTIARY, Typeface.BOLD);
        updateNotesHeaderView.setPadding(0, dp(10), 0, 0);
        updateNotesHeaderView.setVisibility(View.GONE);
        panel.body.addView(updateNotesHeaderView, matchWrap());

        updateNotesView = text("", 12, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        updateNotesView.setPadding(0, dp(4), 0, 0);
        updateNotesView.setMaxLines(8);
        updateNotesView.setEllipsize(TextUtils.TruncateAt.END);
        updateNotesView.setVisibility(View.GONE);
        panel.body.addView(updateNotesView, matchWrap());

        updateProgressView = text("", 13, COLOR_BRAND, Typeface.NORMAL);
        updateProgressView.setPadding(0, dp(10), 0, 0);
        updateProgressView.setVisibility(View.GONE);
        panel.body.addView(updateProgressView, matchWrap());

        updateFeedbackView = text("", 13, COLOR_ERROR, Typeface.NORMAL);
        updateFeedbackView.setPadding(0, dp(8), 0, 0);
        updateFeedbackView.setVisibility(View.GONE);
        panel.body.addView(updateFeedbackView, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        actions.setPadding(0, dp(10), 0, 0);
        panel.body.addView(scrollableControlRow(actions), matchWrap());

        updateDownloadButton = button(getString(R.string.button_update_download));
        updateDownloadButton.setOnClickListener(v -> startUpdateDownload());
        updateDownloadButton.setVisibility(View.GONE);
        actions.addView(updateDownloadButton, wrapWrap());

        updateLaterButton = button(getString(R.string.button_update_later));
        updateLaterButton.setOnClickListener(v -> postponeOfferedUpdate());
        updateLaterButton.setVisibility(View.GONE);
        actions.addView(updateLaterButton, wrapWrapLeft(8));

        updateCancelButton = button(getString(R.string.button_update_cancel));
        updateCancelButton.setOnClickListener(v -> cancelUpdateDownload());
        updateCancelButton.setVisibility(View.GONE);
        actions.addView(updateCancelButton, wrapWrapLeft(8));

        updateConfirmContainer = new LinearLayout(this);
        updateConfirmContainer.setOrientation(LinearLayout.VERTICAL);
        updateConfirmContainer.setPadding(0, dp(10), 0, 0);
        updateConfirmContainer.setVisibility(View.GONE);
        panel.body.addView(updateConfirmContainer, matchWrap());

        TextView installWarning = text(getString(R.string.update_confirm_warning), 12, COLOR_WARNING, Typeface.NORMAL);
        installWarning.setPadding(dp(10), dp(10), dp(10), dp(10));
        installWarning.setBackground(roundRect(COLOR_WARNING_MUTED, dp(10), dp(1), COLOR_WARNING));
        updateConfirmContainer.addView(installWarning, matchWrap());

        LinearLayout confirmRow = new LinearLayout(this);
        confirmRow.setOrientation(LinearLayout.HORIZONTAL);
        confirmRow.setPadding(0, dp(10), 0, 0);
        updateConfirmContainer.addView(scrollableControlRow(confirmRow), matchWrap());

        updateInstallButton = button(getString(R.string.button_update_install));
        updateInstallButton.setOnClickListener(v -> confirmUpdateInstall());
        confirmRow.addView(updateInstallButton, wrapWrap());

        Button installLater = button(getString(R.string.button_update_later));
        installLater.setOnClickListener(v -> postponeOfferedUpdate());
        confirmRow.addView(installLater, wrapWrapLeft(8));

        // The update settings block is its own panel in Ajustes; onCreate fills its fields.
        refreshUpdateInstalledLabel();
        return panel.container;
    }

    /**
     * Wraps a horizontal row of controls so every control stays reachable with the D-pad on a
     * narrow screen: focus scrolls the row instead of clipping the last button.
     */
    private View scrollableControlRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scroll;
    }

    /** Collapsible block with the automatic-check switch, interval, pre-releases and repository. */
    private View buildUpdateSettingsSection() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        String title = getString(R.string.update_label_advanced);
        TextView header = text("+ " + title, 16, COLOR_TEXT_SECONDARY, Typeface.BOLD);
        header.setFocusable(true);
        header.setBackground(focusBackground(0x00000000, COLOR_SURFACE_FOCUSED, dp(8), 0, 0));
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        container.addView(header, matchWrap());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.setVisibility(View.GONE);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(6);
        container.addView(body, bodyParams);

        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            header.setText((expanded ? "+ " : "- ") + title);
        });

        LinearLayout automaticRow = new LinearLayout(this);
        automaticRow.setOrientation(LinearLayout.HORIZONTAL);
        automaticRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(automaticRow, matchWrap());

        automaticRow.addView(
                text(getString(R.string.update_label_automatic), 13, COLOR_TEXT_PRIMARY, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        );
        updateAutomaticSwitch = new Switch(this);
        updateAutomaticSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressUpdateCallbacks) return;
            UpdateSettings.setAutomaticCheckEnabled(this, checked);
        });
        busyControls.add(updateAutomaticSwitch);
        automaticRow.addView(updateAutomaticSwitch, wrapWrap());

        LinearLayout prereleaseRow = new LinearLayout(this);
        prereleaseRow.setOrientation(LinearLayout.HORIZONTAL);
        prereleaseRow.setGravity(Gravity.CENTER_VERTICAL);
        prereleaseRow.setPadding(0, dp(10), 0, 0);
        body.addView(prereleaseRow, matchWrap());

        prereleaseRow.addView(
                text(getString(R.string.update_label_prerelease), 13, COLOR_TEXT_PRIMARY, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        );
        updatePrereleaseSwitch = new Switch(this);
        updatePrereleaseSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressUpdateCallbacks) return;
            UpdateSettings.setIncludePrereleases(this, checked);
        });
        busyControls.add(updatePrereleaseSwitch);
        prereleaseRow.addView(updatePrereleaseSwitch, wrapWrap());

        updateIntervalEdit = editText(getString(R.string.update_label_interval), false);
        updateIntervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams intervalParams = matchWrapTop(10);
        body.addView(updateIntervalEdit, intervalParams);

        updateRepositoryEdit = editText(getString(R.string.update_label_repository), false);
        body.addView(updateRepositoryEdit, matchWrapTop(10));

        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setGravity(Gravity.RIGHT);
        saveRow.setPadding(0, dp(10), 0, 0);
        body.addView(saveRow, matchWrap());

        Button save = button(getString(R.string.button_update_save_settings));
        save.setOnClickListener(v -> saveUpdateSettings());
        saveRow.addView(save, wrapWrap());

        return container;
    }

    private void populateUpdateSettingsFields() {
        suppressUpdateCallbacks = true;
        try {
            updateAutomaticSwitch.setChecked(UpdateSettings.isAutomaticCheckEnabled(this));
            updatePrereleaseSwitch.setChecked(UpdateSettings.includePrereleases(this));
        } finally {
            suppressUpdateCallbacks = false;
        }
        updateIntervalEdit.setText(String.valueOf(UpdateConfig.hours(UpdateSettings.checkIntervalMillis(this))));
        updateRepositoryEdit.setText(UpdateSettings.repository(this));
    }

    private void saveUpdateSettings() {
        String repository = updateRepositoryEdit.getText().toString().trim();
        if (!repository.isEmpty() && !UpdateConfig.isValidRepository(repository)) {
            setUpdatesFeedback(getString(R.string.update_error_repository_invalid), COLOR_ERROR);
            return;
        }
        int hours;
        try {
            hours = Integer.parseInt(updateIntervalEdit.getText().toString().trim());
        } catch (NumberFormatException error) {
            hours = -1;
        }
        if (hours < 1 || hours > 168) {
            setUpdatesFeedback(getString(R.string.update_error_interval_invalid), COLOR_ERROR);
            return;
        }
        UpdateSettings.setRepository(this, repository);
        UpdateSettings.setCheckIntervalMillis(this, UpdateConfig.intervalMillis(hours));
        UpdateSettings.setIncludePrereleases(this, updatePrereleaseSwitch.isChecked());
        UpdateSettings.setAutomaticCheckEnabled(this, updateAutomaticSwitch.isChecked());
        populateUpdateSettingsFields();
        setUpdatesFeedback(getString(R.string.update_message_settings_saved), COLOR_SUCCESS);
    }

    /**
     * Lightweight automatic check: once per configured interval, never during a download, and only
     * while the automatic check is enabled (P4). The interval and the switch live in
     * SharedPreferences, so they are read on the work executor and only the decision comes back to
     * the main thread (S7). With the switch off nothing reaches this method's caller at all, so a
     * check then happens only through the explicit "check now" action.
     */
    private void maybeRunAutomaticUpdateCheck() {
        if (destroyed || updateCheckInFlight || updateDownloadInProgress) return;
        try {
            workExecutor.execute(() -> {
                boolean due = UpdateSettings.isAutomaticCheckDue(this, System.currentTimeMillis());
                if (due) main(() -> startUpdateCheck(false));
            });
        } catch (RejectedExecutionException ignored) {
            // The activity is tearing down; no automatic check is worth queueing.
        }
    }

    private void startUpdateCheck(boolean manual) {
        if (destroyed || updateCheckInFlight) return;
        if (updateDownloadInProgress) {
            setUpdatesFeedback(getString(R.string.update_error_download_running), COLOR_WARNING);
            return;
        }
        updateCheckInFlight = true;
        updateCheckButton.setEnabled(false);
        updateStatePill.apply(getString(R.string.update_status_checking), COLOR_BRAND_MUTED, COLOR_BRAND);
        setUpdatesFeedback(getString(R.string.update_message_checking), COLOR_TEXT_SECONDARY);
        try {
            updateExecutor.execute(() -> {
                UpdateClient.Result result = UpdateClient.check(this);
                UpdateSettings.setLastCheckMillis(this, System.currentTimeMillis());
                main(() -> applyUpdateCheckResult(result, manual));
            });
        } catch (RejectedExecutionException error) {
            updateCheckInFlight = false;
            updateCheckButton.setEnabled(true);
            setUpdatesFeedback(errorMessage(error), COLOR_ERROR);
        }
    }

    private void applyUpdateCheckResult(UpdateClient.Result result, boolean manual) {
        updateCheckInFlight = false;
        updateCheckButton.setEnabled(true);
        if (destroyed) return;

        if (result.outcome != UpdateClient.Outcome.OK) {
            currentUpdate = null;
            updateStatePill.apply(getString(R.string.update_status_error), COLOR_ERROR_MUTED, COLOR_ERROR);
            setUpdatesFeedback(result.message, COLOR_ERROR);
            hideUpdateDetails();
            return;
        }

        UpdateDecision decision = UpdateDecision.decide(
                result.releases,
                installedVersion(),
                deviceAbis(),
                UpdateSettings.includePrereleases(this)
        );
        currentUpdate = decision;
        if (!result.fromCache) setUpdatesFeedback("", COLOR_TEXT_SECONDARY);

        switch (decision.status()) {
            case UPDATE_AVAILABLE: {
                boolean postponed = !manual && UpdateSettings.isPostponed(
                        this,
                        decision.release().versionName(),
                        decision.release().announcedVersionCode()
                );
                showUpdateOffer(decision, postponed);
                if (result.fromCache) setUpdatesFeedback(result.message, COLOR_WARNING);
                break;
            }
            case NO_COMPATIBLE_ASSET:
                hideUpdateDetails();
                updateStatePill.apply(getString(R.string.update_status_no_asset), COLOR_WARNING_MUTED, COLOR_WARNING);
                setUpdatesFeedback(getString(R.string.update_error_no_asset, deviceAbisSummary()), COLOR_WARNING);
                break;
            case ONLY_PRERELEASES:
                hideUpdateDetails();
                updateStatePill.apply(getString(R.string.update_status_up_to_date), COLOR_SUCCESS_MUTED, COLOR_SUCCESS);
                setUpdatesFeedback(getString(R.string.update_error_only_prereleases), COLOR_TEXT_SECONDARY);
                break;
            case NO_RELEASES:
                hideUpdateDetails();
                updateStatePill.apply(getString(R.string.update_status_error), COLOR_WARNING_MUTED, COLOR_WARNING);
                setUpdatesFeedback(getString(R.string.update_error_no_releases), COLOR_WARNING);
                break;
            case DOWNGRADE_REFUSED:
            case UP_TO_DATE:
            default:
                hideUpdateDetails();
                updateStatePill.apply(getString(R.string.update_status_up_to_date), COLOR_SUCCESS_MUTED, COLOR_SUCCESS);
                break;
        }
    }

    private void showUpdateOffer(UpdateDecision decision, boolean postponed) {
        ReleaseInfo release = decision.release();
        ReleaseAsset asset = decision.asset();
        if (release == null || asset == null) return;

        boolean prerelease = release.isPrerelease();
        updateStatePill.apply(
                postponed
                        ? getString(R.string.update_status_postponed)
                        : prerelease
                        ? getString(R.string.update_status_prerelease)
                        : getString(R.string.update_status_available),
                postponed ? COLOR_SURFACE : COLOR_WARNING_MUTED,
                postponed ? COLOR_TEXT_SECONDARY : COLOR_WARNING
        );

        StringBuilder detail = new StringBuilder();
        detail.append(release.announcedVersionCode() > 0
                ? getString(R.string.update_detail_available, release.versionName(), release.announcedVersionCode())
                : getString(R.string.update_detail_available_no_code, release.versionName()));
        detail.append('\n').append(prerelease
                ? getString(R.string.update_detail_channel_prerelease)
                : getString(R.string.update_detail_channel_stable));
        detail.append('\n').append(getString(
                R.string.update_detail_size,
                UpdateDownloader.formatBytes(asset.sizeBytes()),
                asset.name()
        ));
        updateDetailView.setText(detail.toString());
        updateDetailView.setVisibility(View.VISIBLE);

        String notes = truncateNotes(release.notes());
        boolean hasNotes = !notes.isEmpty();
        updateNotesHeaderView.setVisibility(hasNotes ? View.VISIBLE : View.GONE);
        updateNotesView.setText(hasNotes ? notes : "");
        updateNotesView.setVisibility(hasNotes ? View.VISIBLE : View.GONE);

        updateDownloadButton.setVisibility(postponed ? View.GONE : View.VISIBLE);
        updateLaterButton.setVisibility(postponed ? View.GONE : View.VISIBLE);
        updateCancelButton.setVisibility(View.GONE);
        updateConfirmContainer.setVisibility(View.GONE);
        updateProgressView.setVisibility(View.GONE);
    }

    /** Long release bodies are cut so the D-pad does not have to scroll past a wall of text. */
    private String truncateNotes(String notes) {
        String value = notes == null ? "" : notes.trim();
        int limit = 1200;
        if (value.length() <= limit) return value;
        return value.substring(0, limit).trim() + "\n" + getString(R.string.update_notes_truncated);
    }

    /** Hides the offer and its details but keeps the status pill and the message area. */
    private void hideUpdateDetails() {
        hideControl(updateDownloadButton, null);
        hideControl(updateLaterButton, null);
        hideControl(updateCancelButton, updateCheckButton);
        updateConfirmContainer.setVisibility(View.GONE);
        updateProgressView.setVisibility(View.GONE);
        updateDetailView.setText("");
        updateDetailView.setVisibility(View.GONE);
        updateNotesView.setText("");
        updateNotesView.setVisibility(View.GONE);
        updateNotesHeaderView.setVisibility(View.GONE);
    }

    private void startUpdateDownload() {
        if (currentUpdate == null || !currentUpdate.hasUpdate() || updateDownloadInProgress) return;
        ReleaseAsset asset = currentUpdate.asset();
        if (asset == null) return;

        if (!UpdateInstaller.canInstallPackages(this)) {
            updateAllowInstallsButton.setVisibility(View.VISIBLE);
            setUpdatesFeedback(getString(R.string.update_error_install_permission), COLOR_WARNING);
            return;
        }
        updateAllowInstallsButton.setVisibility(View.GONE);

        updateDownloadInProgress = true;
        updateCancelFlag = new AtomicBoolean(false);
        lastUpdateProgressPercent = -1;
        downloadedApk = null;
        updateCheckButton.setEnabled(false);
        updateConfirmContainer.setVisibility(View.GONE);
        updateCancelButton.setVisibility(View.VISIBLE);
        hideControl(updateDownloadButton, updateCancelButton);
        hideControl(updateLaterButton, null);
        updateProgressView.setText(getString(R.string.update_message_downloading));
        updateProgressView.setVisibility(View.VISIBLE);
        setUpdatesFeedback("", COLOR_TEXT_SECONDARY);

        try {
            updateExecutor.execute(() -> {
                UpdateDownloader.Result result = UpdateDownloader.download(
                        this, asset, updateCancelFlag, this::onUpdateDownloadProgress);
                main(() -> applyUpdateDownloadResult(result));
            });
        } catch (RejectedExecutionException error) {
            updateDownloadInProgress = false;
            updateCheckButton.setEnabled(true);
            setUpdatesFeedback(errorMessage(error), COLOR_ERROR);
        }
    }

    private void onUpdateDownloadProgress(int percent, long receivedBytes, long totalBytes) {
        if (destroyed) return;
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped == lastUpdateProgressPercent) return;
        lastUpdateProgressPercent = clamped;
        main(() -> {
            if (destroyed || !updateDownloadInProgress) return;
            updateProgressView.setText(getString(
                    R.string.update_progress_format,
                    clamped,
                    UpdateDownloader.formatBytes(receivedBytes),
                    UpdateDownloader.formatBytes(totalBytes)
            ));
        });
    }

    private void applyUpdateDownloadResult(UpdateDownloader.Result result) {
        updateDownloadInProgress = false;
        updateCheckButton.setEnabled(true);
        updateCancelButton.setVisibility(View.GONE);
        if (destroyed) return;

        if (!result.success) {
            updateProgressView.setVisibility(View.GONE);
            if (!result.cancelled) {
                updateStatePill.apply(getString(R.string.update_status_error), COLOR_ERROR_MUTED, COLOR_ERROR);
            }
            setUpdatesFeedback(result.message, result.cancelled ? COLOR_WARNING : COLOR_ERROR);
            boolean offerStillValid = currentUpdate != null && currentUpdate.hasUpdate();
            updateDownloadButton.setEnabled(true);
            updateDownloadButton.setVisibility(offerStillValid ? View.VISIBLE : View.GONE);
            updateLaterButton.setVisibility(offerStillValid ? View.VISIBLE : View.GONE);
            return;
        }

        updateDownloadButton.setEnabled(true);
        updateDownloadButton.setVisibility(View.GONE);
        updateLaterButton.setVisibility(View.GONE);
        updateProgressView.setText(getString(R.string.update_verifying));
        updateProgressView.setVisibility(View.VISIBLE);
        downloadedApk = result.file;
        verifyDownloadedApk(result.file);
    }

    private void verifyDownloadedApk(File file) {
        int announced = currentUpdate != null && currentUpdate.release() != null
                ? currentUpdate.release().announcedVersionCode()
                : 0;
        int minimumVersionCode = Math.max(announced, installedVersionCode());
        try {
            updateExecutor.execute(() -> {
                UpdateVerifier.Result verification = UpdateVerifier.verify(this, file, minimumVersionCode);
                main(() -> applyUpdateVerification(file, verification));
            });
        } catch (RejectedExecutionException error) {
            updateProgressView.setVisibility(View.GONE);
            setUpdatesFeedback(errorMessage(error), COLOR_ERROR);
        }
    }

    private void applyUpdateVerification(File file, UpdateVerifier.Result verification) {
        updateProgressView.setVisibility(View.GONE);
        if (destroyed) return;

        if (!verification.ok) {
            UpdateDownloader.delete(file);
            downloadedApk = null;
            updateStatePill.apply(getString(R.string.update_status_error), COLOR_ERROR_MUTED, COLOR_ERROR);
            setUpdatesFeedback(verification.message, COLOR_ERROR);
            // A wrong signing key is final: re-downloading the same asset cannot fix it, and the
            // user must not be nudged towards uninstalling to force it through.
            boolean retryable = !verification.signatureMismatch;
            updateDownloadButton.setEnabled(retryable);
            updateDownloadButton.setVisibility(retryable ? View.VISIBLE : View.GONE);
            updateLaterButton.setVisibility(retryable ? View.VISIBLE : View.GONE);
            return;
        }

        updateStatePill.apply(getString(R.string.update_status_available), COLOR_WARNING_MUTED, COLOR_WARNING);
        setUpdatesFeedback(getString(R.string.update_verified), COLOR_SUCCESS);
        // The confirmation row appears under the action row; focus is deliberately left where the
        // user put it, so this result never moves or steals it (S6). DOWN reaches the new row.
        updateConfirmContainer.setVisibility(View.VISIBLE);
    }

    /** Stops HaP (only after the user confirmed) and hands the verified APK to the installer. */
    private void confirmUpdateInstall() {
        final File apk = downloadedApk;
        updateConfirmContainer.setVisibility(View.GONE);
        if (apk == null || !apk.isFile()) {
            downloadedApk = null;
            return;
        }

        updateInstallButton.setEnabled(false);
        setUpdatesFeedback(getString(R.string.update_message_stopping_hap), COLOR_TEXT_SECONDARY);
        try {
            updateExecutor.execute(() -> {
                HapBridge.HapStatus status = HapBridge.status(this);
                if (status.desiredRunning || status.proxyRunning) HapBridge.stop(this);
                UpdateInstaller.install(this, apk, new UpdateInstaller.Callback() {
                    @Override
                    public void onSessionOpened() {
                        main(() -> {
                            downloadedApk = null;
                            updateInstallButton.setEnabled(true);
                            setUpdatesFeedback(getString(R.string.update_message_install_sent), COLOR_BRAND);
                        });
                    }

                    @Override
                    public void onSessionFailed(String message) {
                        main(() -> {
                            updateInstallButton.setEnabled(true);
                            setUpdatesFeedback(message, COLOR_ERROR);
                        });
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            updateInstallButton.setEnabled(true);
            setUpdatesFeedback(errorMessage(error), COLOR_ERROR);
        }
    }

    private void postponeOfferedUpdate() {
        if (currentUpdate == null || currentUpdate.release() == null) return;
        if (updateDownloadInProgress) cancelUpdateDownload();
        ReleaseInfo release = currentUpdate.release();
        UpdateSettings.setPostponed(this, release.versionName(), release.announcedVersionCode());
        if (downloadedApk != null) {
            UpdateDownloader.delete(downloadedApk);
            downloadedApk = null;
        }
        updateConfirmContainer.setVisibility(View.GONE);
        hideControl(updateDownloadButton, null);
        hideControl(updateLaterButton, null);
        updateProgressView.setVisibility(View.GONE);
        updateStatePill.apply(getString(R.string.update_status_postponed), COLOR_SURFACE, COLOR_TEXT_SECONDARY);
        setUpdatesFeedback(getString(R.string.update_message_postponed), COLOR_TEXT_SECONDARY);
    }

    private void cancelUpdateDownload() {
        updateCancelFlag.set(true);
        updateCancelButton.setVisibility(View.GONE);
        setUpdatesFeedback(getString(R.string.update_error_download_cancelled), COLOR_WARNING);
    }

    /** Reports the installer outcome stored by {@code UpdateInstallReceiver}. */
    private void showPendingInstallResult() {
        if (updateAllowInstallsButton != null && UpdateInstaller.canInstallPackages(this)) {
            updateAllowInstallsButton.setVisibility(View.GONE);
        }

        int status = UpdateSettings.installStatus(this);
        if (status == UpdateSettings.NO_INSTALL_STATUS) return;
        UpdateSettings.clearInstallStatus(this);

        if (status == PackageInstaller.STATUS_SUCCESS) {
            refreshUpdateInstalledLabel();
            updateStatePill.apply(getString(R.string.update_status_up_to_date), COLOR_SUCCESS_MUTED, COLOR_SUCCESS);
            setUpdatesFeedback(getString(R.string.update_install_success), COLOR_SUCCESS);
            return;
        }
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            setUpdatesFeedback(getString(R.string.update_install_aborted), COLOR_WARNING);
            return;
        }
        if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) {
            updateAllowInstallsButton.setVisibility(View.VISIBLE);
            setUpdatesFeedback(getString(R.string.update_install_failed_blocked), COLOR_ERROR);
            return;
        }
        setUpdatesFeedback(getString(R.string.update_install_failed, status), COLOR_ERROR);
    }

    private void openUnknownSourcesSettings() {
        try {
            startActivity(UpdateInstaller.unknownSourcesSettingsIntent(this));
        } catch (RuntimeException error) {
            setUpdatesFeedback(errorMessage(error), COLOR_ERROR);
        }
    }

    private void releaseUpdateResources() {
        updateCancelFlag.set(true);
        updateExecutor.shutdownNow();
        if (downloadedApk != null) {
            UpdateDownloader.delete(downloadedApk);
            downloadedApk = null;
        }
    }

    private void setUpdatesFeedback(String message, int color) {
        if (updateFeedbackView == null) return;
        if (message == null || message.trim().isEmpty()) {
            updateFeedbackView.setText("");
            updateFeedbackView.setVisibility(View.GONE);
            return;
        }
        updateFeedbackView.setTextColor(color);
        updateFeedbackView.setText(message);
        updateFeedbackView.setVisibility(View.VISIBLE);
    }

    /** Hides a control, and moves focus to a neighbour when the hidden one held it. */
    private void hideControl(View control, View focusFallback) {
        if (control == null) return;
        boolean focused = control.hasFocus();
        control.setEnabled(true);
        control.setVisibility(View.GONE);
        if (focused && focusFallback != null && focusFallback.getVisibility() == View.VISIBLE) {
            focusFallback.requestFocus();
        }
    }

    private void refreshUpdateInstalledLabel() {
        if (updateInstalledView == null) return;
        updateInstalledView.setText(getString(
                R.string.update_installed_format,
                appVersionName(),
                installedVersionCode(),
                deviceAbisSummary()
        ));
    }

    private AppVersion installedVersion() {
        return new AppVersion(installedVersionCode(), appVersionName());
    }

    @SuppressWarnings("deprecation")
    private int installedVersionCode() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return (int) packageInfo.getLongVersionCode();
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0;
        }
    }

    /** The device's real ABI list, in Android's priority order. */
    private List<String> deviceAbis() {
        List<String> abis = new ArrayList<>();
        String[] supported = Build.SUPPORTED_ABIS;
        if (supported == null) return abis;
        for (String abi : supported) {
            if (abi == null) continue;
            String value = abi.trim();
            if (!value.isEmpty()) abis.add(value);
        }
        return abis;
    }

    private String deviceAbisSummary() {
        return TextUtils.join(", ", deviceAbis());
    }

    /**
     * First paint only: reads the status on the calling (main) thread, so the screen never shows a
     * placeholder state that is immediately replaced. Every later refresh runs off the main thread.
     */
    private void refreshRuntimeStateNow() {
        applyRuntimeSnapshot(buildRuntimeSnapshot(HapBridge.status(this)));
    }

    /**
     * Schedules a runtime state read. {@code HapBridge.status} copies the whole service log under
     * {@link com.jopsis.httpaceserveproxy.ServiceState}'s lock, reads SharedPreferences and (in LAN
     * mode) enumerates the network interfaces, so it runs on {@link #statusExecutor} and the result
     * is applied on the main thread. At most one read is in flight, so a slow read can never pile
     * up a queue of ticks.
     */
    private void refreshRuntimeState() {
        if (destroyed) return;
        if (!statusPollInFlight.compareAndSet(false, true)) return;
        try {
            statusExecutor.execute(this::readRuntimeState);
        } catch (RejectedExecutionException ignored) {
            statusPollInFlight.set(false);
        }
    }

    private void readRuntimeState() {
        RuntimeSnapshot snapshot = null;
        HapBridge.HapStatus status = null;
        try {
            status = HapBridge.status(this);
            snapshot = buildRuntimeSnapshot(status);
        } catch (Throwable ignored) {
            // A failed status read must not stop the 1s refresh loop.
        } finally {
            statusPollInFlight.set(false);
        }
        if (snapshot == null) return;
        // The client count is a second, independent reading: a loopback request that must never run
        // on the main thread, so it is asked here and not in buildRuntimeSnapshot, which the first
        // paint also calls (M4, S4). Only the phone shell shows that line, so the television path
        // keeps performing exactly the work it performed before this change (S1).
        snapshot.connectedClients = phoneShell ? readConnectedClients(status) : -1;
        RuntimeSnapshot ready = snapshot;
        main(() -> applyRuntimeSnapshot(ready));
    }

    /**
     * How many clients HaP is really serving, for the phone home card (S4).
     *
     * <p>{@link HapBridge#connectedClientCount()} is the only real source; nothing here derives a
     * number. A negative answer means "not known" — the proxy is not running or the read failed —
     * and the card then shows no line at all instead of a zero it cannot stand behind.
     */
    private static int readConnectedClients(HapBridge.HapStatus status) {
        if (status == null || !status.proxyRunning) return -1;
        try {
            return HapBridge.connectedClientCount();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private RuntimeSnapshot buildRuntimeSnapshot(HapBridge.HapStatus status) {
        RuntimeSnapshot snapshot = new RuntimeSnapshot();

        RuntimeBadge badge = runtimeBadge(status);
        snapshot.stateLabel = badge.label;
        snapshot.stateBackgroundColor = badge.containerColor;
        snapshot.stateTextColor = badge.contentColor;

        snapshot.phaseLabel = displayPhase(status.phase);
        snapshot.phaseBackgroundColor = status.proxyRunning ? COLOR_BRAND_MUTED : COLOR_SURFACE;
        snapshot.phaseTextColor = status.proxyRunning ? COLOR_BRAND : COLOR_TEXT_SECONDARY;

        snapshot.aceServeLabel = getString(R.string.label_aceserve) + " " + onlineLabel(status.aceRunning);
        snapshot.aceServeBackgroundColor = status.aceRunning ? COLOR_SUCCESS_MUTED : COLOR_ERROR_MUTED;
        snapshot.aceServeTextColor = status.aceRunning ? COLOR_SUCCESS : COLOR_ERROR;

        snapshot.proxyLabel = getString(R.string.label_proxy) + " " + onlineLabel(status.proxyRunning);
        snapshot.proxyBackgroundColor = status.proxyRunning ? COLOR_SUCCESS_MUTED : COLOR_ERROR_MUTED;
        snapshot.proxyTextColor = status.proxyRunning ? COLOR_SUCCESS : COLOR_ERROR;

        snapshot.errorText = status.lastError;

        snapshot.desiredRunning = status.desiredRunning;
        snapshot.externalPlayerServerEnabled = status.externalPlayerServerEnabled;
        snapshot.lanModeEnabled = status.serverModeEnabled;
        snapshot.ipfsEnabled = status.ipfsEnabled;

        RuntimeBadge ipfs = ipfsBadge(status);
        snapshot.ipfsLabel = ipfs.label;
        snapshot.ipfsBackgroundColor = ipfs.containerColor;
        snapshot.ipfsTextColor = ipfs.contentColor;
        snapshot.ipfsErrorText = status.ipfsError;
        snapshot.ipfsGatewayUrl = status.ipfsGatewayUrl;

        snapshot.endpoints = endpointRows(status);
        snapshot.logText = runtimeLogText(status.log);

        // The words the home screen shows, decided here so the refresh stays off the main thread.
        snapshot.services = HapServiceStates.of(
                status.desiredRunning,
                status.aceRunning,
                status.proxyRunning,
                !status.lastError.isEmpty(),
                status.ipfsEnabled,
                status.ipfsRunning,
                !status.ipfsError.isEmpty()
        );
        return snapshot;
    }

    /** Applies a snapshot, touching only the views whose value actually changed. */
    private void applyRuntimeSnapshot(RuntimeSnapshot snapshot) {
        if (destroyed) return;

        statePill.apply(snapshot.stateLabel, snapshot.stateBackgroundColor, snapshot.stateTextColor);
        phasePill.apply(snapshot.phaseLabel, snapshot.phaseBackgroundColor, snapshot.phaseTextColor);
        aceServePill.apply(
                snapshot.aceServeLabel,
                snapshot.aceServeBackgroundColor,
                snapshot.aceServeTextColor
        );
        proxyPill.apply(
                snapshot.proxyLabel,
                snapshot.proxyBackgroundColor,
                snapshot.proxyTextColor
        );

        applyCaption(errorView, snapshot.errorText);
        applySwitches(snapshot);

        ipfsStatePill.apply(snapshot.ipfsLabel, snapshot.ipfsBackgroundColor, snapshot.ipfsTextColor);
        applyCaption(ipfsErrorView, snapshot.ipfsErrorText);
        applyIpfsGateway(snapshot.ipfsGatewayUrl);

        // The endpoint list decides for itself whether the rows changed, so an unchanged tick
        // performs no view work at all.
        renderEndpoints(snapshot.endpoints);

        if (!TextUtils.equals(renderedLogText, snapshot.logText)) {
            renderedLogText = snapshot.logText;
            logView.setText(snapshot.logText);
        }

        // Only the words that changed are re-set, so a refresh never rebuilds or re-focuses.
        if (homeScreen != null) {
            homeScreen.applyServices(snapshot.services);
            homeScreen.applyConnectedClients(snapshot.connectedClients);
        }
    }

    private void applyCaption(TextView view, String text) {
        boolean visible = text != null && !text.isEmpty();
        if (visible && !TextUtils.equals(text, view.getText())) view.setText(text);
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    /**
     * Writes a label only when the view does not already say it. This is what makes an update that
     * produces the same model reach neither {@code setText} nor a re-layout (S7).
     */
    private void applyText(TextView view, String text) {
        String value = text == null ? "" : text;
        if (ListPatch.needsText(view.getText(), value)) view.setText(value);
    }

    /** Shows or hides a control only when its visibility actually differs. */
    private void applyVisibility(View view, boolean visible) {
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    private void applySwitches(RuntimeSnapshot snapshot) {
        if (enabledSwitch.isChecked() == snapshot.desiredRunning
                && externalServerSwitch.isChecked() == snapshot.externalPlayerServerEnabled
                && lanSwitch.isChecked() == snapshot.lanModeEnabled
                && ipfsSwitch.isChecked() == snapshot.ipfsEnabled) {
            return;
        }
        suppressSwitchCallbacks = true;
        try {
            enabledSwitch.setChecked(snapshot.desiredRunning);
            externalServerSwitch.setChecked(snapshot.externalPlayerServerEnabled);
            lanSwitch.setChecked(snapshot.lanModeEnabled);
            ipfsSwitch.setChecked(snapshot.ipfsEnabled);
        } finally {
            suppressSwitchCallbacks = false;
        }
    }

    private RuntimeBadge ipfsBadge(HapBridge.HapStatus status) {
        if (!status.ipfsError.isEmpty()) {
            return new RuntimeBadge(getString(R.string.status_ipfs_failed), COLOR_ERROR_MUTED, COLOR_ERROR);
        }
        if (status.ipfsRunning) {
            return new RuntimeBadge(getString(R.string.status_ipfs_online), COLOR_SUCCESS_MUTED, COLOR_SUCCESS);
        }
        if (status.ipfsEnabled) {
            return new RuntimeBadge(getString(R.string.status_ipfs_starting), COLOR_WARNING_MUTED, COLOR_WARNING);
        }
        return new RuntimeBadge(getString(R.string.status_ipfs_offline), COLOR_ERROR_MUTED, COLOR_ERROR);
    }

    private void refreshConfigState() {
        currentSources = HapBridge.sources(this);
        currentCustomChannels = HapBridge.customChannels(this);
        renderSources();
        // Adding or removing a list is what changes the first-run answer, so it is re-read here.
        resolveFirstRunOffer();
    }

    /** Computed off the main thread; the LAN URLs come from the same status read. */
    private List<HapRows.Endpoint> endpointRows(HapBridge.HapStatus status) {
        List<HapRows.Endpoint> rows = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        addEndpointRow(rows, shown, getString(R.string.label_local_m3u), HapBridge.LOCAL_AIO_URL);
        addEndpointRow(rows, shown, getString(R.string.label_local_epg), HapBridge.LOCAL_AIO_EPG_URL);
        String castUrl = HapBridge.castAioUrl(this);
        String castEpgUrl = HapBridge.castAioEpgUrl(this);
        if (!castUrl.isEmpty()) addEndpointRow(rows, shown, getString(R.string.label_lan_m3u), castUrl);
        if (!castEpgUrl.isEmpty()) addEndpointRow(rows, shown, getString(R.string.label_lan_epg), castEpgUrl);
        for (HapBridge.EndpointInfo endpoint : status.endpoints) {
            if ("127.0.0.1".equals(endpoint.host)) continue;
            addEndpointRow(rows, shown, endpoint.label, endpoint.baseUrl + "/aio");
        }
        return rows;
    }

    private void addEndpointRow(List<HapRows.Endpoint> rows, List<String> shown, String label, String url) {
        if (shown.contains(url)) return;
        shown.add(url);
        rows.add(new HapRows.Endpoint(label, url));
    }

    /**
     * Applies the endpoint rows. Nothing is cleared: a row that is still there is rebound, only the
     * missing tail is created and only the surplus tail is dropped, so a copy button keeps its
     * focus across a refresh that adds or removes one LAN address.
     */
    private void renderEndpoints(List<HapRows.Endpoint> rows) {
        endpointList.apply(rows);
    }

    /** One endpoint row: its label pill, the URL and the button that copies it. */
    private final class EndpointRow implements InPlaceList.Row<HapRows.Endpoint> {

        private final LinearLayout row;
        private final TextView labelView;
        private final TextView valueView;
        /** The URL the copy button reads at click time, so the listener is registered once. */
        private String url = "";

        EndpointRow() {
            row = new LinearLayout(HapConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            row.setLayoutParams(matchWrap());

            labelView = pill("", COLOR_SURFACE, COLOR_TEXT_SECONDARY);
            row.addView(labelView, wrapWrap());

            valueView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            valueView.setPadding(dp(8), 0, dp(8), 0);
            row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button copy = smallButton(getString(R.string.button_copy));
            copy.setOnClickListener(v -> copyToClipboard("HaP URL", url));
            row.addView(copy, wrapWrap());
        }

        @Override
        public View view() {
            return row;
        }

        @Override
        public void bind(HapRows.Endpoint item) {
            applyText(labelView, item.label);
            applyText(valueView, item.url);
            url = item.url;
        }
    }

    /**
     * Applies the M3U source rows. The set is effectively fixed-key: a source keeps its index while
     * it exists, so its checkbox, its edit/delete buttons and the focus inside the row are the same
     * objects on every update instead of eight fresh views per refresh.
     */
    /**
     * Lists the playlists this device already holds. A file whose source is registered shows the
     * action that removes the source; the file itself is never deleted from here, so nothing the
     * user pushed to the box disappears without them asking.
     */
    private void renderDeviceLists() {
        if (deviceList == null) return;
        List<DeviceListModel> rows = new ArrayList<>();
        for (File file : LocalListFiles.scan(LocalListFiles.folder(this))) {
            String value = LocalSourceFile.valueFor(file);
            String sourceId = sourceIdForLocalValue(value);
            String detail = LocalListFiles.describeSize(file.length());
            if (sourceId != null) detail = detail + " \u00b7 " + getString(R.string.status_device_list_added);
            rows.add(new DeviceListModel(file.getAbsolutePath(), file.getName(), detail, sourceId));
        }
        deviceList.apply(rows);
        applyVisibility(deviceListsEmptyView, rows.isEmpty());
        applyVisibility(deviceImportButton, canPickFile());
    }

    private String sourceIdForLocalValue(String value) {
        for (HapBridge.SourceInfo source : currentSources) {
            if (source.value != null && source.value.equals(value)) return source.id;
        }
        return null;
    }

    /**
     * True when a real document picker answers GET_CONTENT. Android TV ships a stub for documents
     * that only reports the feature is unsupported, so its package never counts as a picker.
     */
    private boolean canPickFile() {
        ResolveInfo info = getPackageManager().resolveActivity(pickListIntent(), 0);
        return info != null
                && info.activityInfo != null
                && !info.activityInfo.packageName.startsWith("com.android.tv.frameworkpackagestubs");
    }

    private Intent pickListIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Asking for */* on Android 13+ lets the photo picker answer, and a playlist is not a photo:
        // the box logged exactly that, then fell through to the television document stub. Naming the
        // playlist types keeps both out of the way.
        intent.setType("audio/x-mpegurl");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/x-mpegurl",
                "audio/mpegurl",
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "application/octet-stream"
        });
        return intent;
    }

    private void pickLocalList() {
        pickerTookFocus = false;
        try {
            startActivityForResult(pickListIntent(), REQUEST_PICK_DEVICE_LIST);
        } catch (ActivityNotFoundException e) {
            showSourceFeedback(getString(R.string.error_no_file_picker), COLOR_ERROR);
            return;
        }
        // resolveActivity() is not evidence that a picker opens: on this box it resolves to a
        // handler that never comes to the front. If the window never lost focus, the button did
        // nothing, which is worse than saying so.
        handler.postDelayed(() -> {
            if (pickerTookFocus || destroyed) return;
            showMessage(getString(R.string.message_use_folder_instead));
            showSourceFeedback(getString(R.string.message_use_folder_instead), COLOR_ERROR);
        }, 2500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_DEVICE_LIST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) copyPickedList(uri);
    }

    /** Copies the picked file into the app's folder, then registers it as a source. */
    private void copyPickedList(Uri uri) {
        File folder = LocalListFiles.folder(this);
        if (folder == null) {
            showSourceFeedback(getString(R.string.status_device_lists_unavailable), COLOR_ERROR);
            return;
        }
        showMessage(getString(R.string.message_importing_file));
        showSourceFeedback(getString(R.string.message_importing_file), COLOR_BRAND);
        workExecutor.execute(() -> {
            String copiedName = null;
            String failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("empty stream");
                File target = new File(folder, LocalListFiles.uniqueName(folder, queryDisplayName(uri)));
                copyPickedStream(input, target);
                copiedName = target.getName();
            } catch (Exception e) {
                failure = errorMessage(e);
            }
            final String name = copiedName;
            final String error = failure;
            main(() -> {
                if (error != null) {
                    showMessage(error);
                    showSourceFeedback(error, COLOR_ERROR);
                    return;
                }
                String value = LocalSourceFile.valueFor(new File(folder, name));
                HapBridge.SaveResult result =
                        HapBridge.saveM3uSource(this, "", LocalSourceFile.displayName(value), value);
                showMessage(result.message);
                showSourceFeedback(result.message, result.success ? COLOR_SUCCESS : COLOR_ERROR);
                if (result.success) refreshConfigState();
            });
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // A provider that cannot answer the query still has a path segment to fall back on.
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty() ? "lista.m3u" : fallback;
    }

    private void copyPickedStream(InputStream input, File target) throws IOException {
        boolean complete = false;
        try (OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > LocalSourceFile.MAX_BYTES) {
                    throw new IOException(getString(R.string.error_source_local_too_large,
                            LocalSourceFile.MAX_BYTES / (1024 * 1024)));
                }
                output.write(buffer, 0, read);
            }
            complete = true;
        } finally {
            // A refused copy must not leave half a playlist behind for the next scan to offer.
            if (!complete && target.exists() && !target.delete()) {
                showMessage(getString(R.string.error_device_list_cleanup, target.getName()));
            }
        }
    }

    private void renderSources() {
        boolean hasM3uSources = false;
        List<HapRows.Source> rows = new ArrayList<>(currentSources.size() + 1);
        for (HapBridge.SourceInfo source : currentSources) {
            if (!"M3U".equals(source.format)) continue;
            hasM3uSources = true;
            rows.add(sourceRowModel(source));
        }
        HapBridge.SourceInfo custom = customSource();
        if (custom != null) rows.add(sourceRowModel(custom));

        applyVisibility(sourcesHintView, !hasM3uSources);
        sourceList.apply(rows);
        renderDeviceLists();

        if (custom != null) {
            // The custom channel list has its own panel in Fuentes now, so this only refreshes
            // it instead of building a second copy inside the sources panel.
            renderCustomChannels();
        }
    }

    private HapRows.Source sourceRowModel(HapBridge.SourceInfo source) {
        String secondary = "CUSTOM".equals(source.format) ? source.description : source.value;
        return new HapRows.Source(
                source.id,
                source.label,
                source.value,
                secondary,
                source.selected,
                source.removable
        );
    }

    /** One source row: its select checkbox, its two lines and the edit/delete controls. */
    private final class SourceRow implements InPlaceList.Row<HapRows.Source> {

        private final LinearLayout row;
        private final CheckBox selected;
        private final TextView labelView;
        private final TextView valueView;
        private final Button edit;
        private final Button delete;
        /** The item the listeners act on; rebound in place, never re-registered. */
        private HapRows.Source item;
        /** -1 until the first bind, so the first check is not mistaken for a user action. */
        private int appliedSelected = -1;
        /** {@code setChecked} fires the listener; rebinding is not the user selecting a source. */
        private boolean binding;

        SourceRow(int topMargin) {
            row = new LinearLayout(HapConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
            row.setLayoutParams(matchWrapTop(topMargin));

            selected = new CheckBox(HapConfigActivity.this);
            selected.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_BRAND));
            selected.setOnCheckedChangeListener((buttonView, checked) -> {
                if (binding || item == null) return;
                setSourceSelected(item.id, checked);
            });
            row.addView(selected, wrapWrap());

            LinearLayout left = new LinearLayout(HapConfigActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setPadding(dp(4), 0, dp(8), 0);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            labelView = text("", 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            left.addView(labelView, matchWrap());

            valueView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(valueView, matchWrap());

            edit = smallButton(getString(R.string.button_edit));
            edit.setOnClickListener(v -> {
                if (item != null) editM3uSource(item);
            });
            row.addView(edit, wrapWrapLeft(8));

            delete = smallButton(getString(R.string.button_delete));
            delete.setOnClickListener(v -> {
                if (item != null) deleteM3uSource(item.id);
            });
            row.addView(delete, wrapWrapLeft(8));
        }

        @Override
        public View view() {
            return row;
        }

        @Override
        public void bind(HapRows.Source next) {
            item = next;
            int wanted = next.selected ? 1 : 0;
            if (appliedSelected != wanted) {
                appliedSelected = wanted;
                binding = true;
                try {
                    selected.setChecked(next.selected);
                } finally {
                    binding = false;
                }
            }
            applyText(labelView, next.label);
            applyText(valueView, next.secondary);
            // Only a removable source offers edit and delete; the controls stay, they are hidden.
            applyVisibility(edit, next.removable);
            applyVisibility(delete, next.removable);
        }
    }

    /** Applies the custom channel rows in place: a deletion only drops the surplus tail. */
    private void renderCustomChannels() {
        if (customListContainer == null) return;
        List<HapRows.CustomChannel> rows = new ArrayList<>(currentCustomChannels.size());
        for (HapBridge.CustomChannelInfo channel : currentCustomChannels) {
            rows.add(new HapRows.CustomChannel(channel.index, channel.name, channel.contentId));
        }
        applyVisibility(customHintView, rows.isEmpty());
        customChannelList.apply(rows);
    }

    /** One custom channel row: the name, the AceStream id and its edit/delete controls. */
    private final class CustomChannelRow implements InPlaceList.Row<HapRows.CustomChannel> {

        private final LinearLayout row;
        private final TextView nameView;
        private final TextView contentView;
        private HapRows.CustomChannel item;

        CustomChannelRow(int topMargin) {
            row = new LinearLayout(HapConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
            row.setLayoutParams(matchWrapTop(topMargin));

            LinearLayout left = new LinearLayout(HapConfigActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            nameView = text("", 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            left.addView(nameView, matchWrap());

            contentView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            contentView.setSingleLine(true);
            contentView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(contentView, matchWrap());

            Button edit = smallButton(getString(R.string.button_edit));
            edit.setOnClickListener(v -> {
                if (item != null) editCustomChannel(item);
            });
            row.addView(edit, wrapWrapLeft(8));

            Button delete = smallButton(getString(R.string.button_delete));
            delete.setOnClickListener(v -> {
                if (item != null) deleteCustomChannel(item.index);
            });
            row.addView(delete, wrapWrapLeft(8));
        }

        @Override
        public View view() {
            return row;
        }

        @Override
        public void bind(HapRows.CustomChannel next) {
            item = next;
            applyText(nameView, next.name);
            applyText(contentView, next.contentId);
        }
    }

    /** Applies the connected client rows in place; the status line only changes when it differs. */
    private void renderClients(List<HapBridge.ConnectedClientInfo> clients) {
        applyText(clientsStatusView, clients.isEmpty()
                ? getString(R.string.message_no_active_clients)
                : getString(R.string.message_client_count, clients.size()));

        List<HapRows.Client> rows = new ArrayList<>(clients.size());
        for (HapBridge.ConnectedClientInfo client : clients) {
            rows.add(new HapRows.Client(
                    client.ip,
                    client.device,
                    client.channel,
                    String.format(Locale.US, "%.1f MB", client.megabytes)
            ));
        }
        clientList.apply(rows);
    }

    /** One connected client row: address, player, channel and how much it has read. */
    private final class ClientRow implements InPlaceList.Row<HapRows.Client> {

        private final LinearLayout row;
        private final TextView ipView;
        private final TextView deviceView;
        private final TextView channelView;
        private final TextView megabytesView;

        ClientRow(int topMargin) {
            row = new LinearLayout(HapConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
            row.setLayoutParams(matchWrapTop(topMargin));

            ipView = weightedText("", 0.18f, COLOR_TEXT_PRIMARY, true);
            row.addView(ipView);
            deviceView = weightedText("", 0.22f, COLOR_TEXT_SECONDARY, false);
            row.addView(deviceView);
            channelView = weightedText("", 0.43f, COLOR_TEXT_SECONDARY, false);
            row.addView(channelView);
            megabytesView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            row.addView(megabytesView, wrapWrap());
        }

        @Override
        public View view() {
            return row;
        }

        @Override
        public void bind(HapRows.Client next) {
            applyText(ipView, next.ip);
            applyText(deviceView, next.device);
            applyText(channelView, next.channel);
            applyText(megabytesView, next.megabytes);
        }
    }

    /** Applies the plugin tabs in place; a new selection only re-colours the tabs that changed. */
    private void renderChannelPlugins() {
        List<HapRows.PluginTab> rows = new ArrayList<>(channelPlugins.size());
        for (HapBridge.PluginChannelsInfo plugin : channelPlugins) {
            rows.add(new HapRows.PluginTab(
                    plugin.id,
                    plugin.label,
                    plugin.channels.size(),
                    plugin.id.equals(selectedPluginId),
                    plugin.channels.isEmpty() ? null : plugin.channels.get(0).contentId
            ));
        }
        pluginTabList.apply(rows);
        renderChannels();
    }

    /** One plugin tab: the pill the user selects a list with. */
    private final class PluginTabRow implements InPlaceList.Row<HapRows.PluginTab> {

        private final TextView tab;
        private HapRows.PluginTab item;
        /** -1 until the first bind, so the first paint is not mistaken for a selection change. */
        private int appliedSelected = -1;

        PluginTabRow(int leftMargin) {
            tab = pill("", COLOR_SURFACE, COLOR_TEXT_SECONDARY);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setMinHeight(dp(36));
            tab.setLayoutParams(wrapWrapLeft(leftMargin));
            tab.setOnClickListener(v -> {
                if (item != null) selectPlugin(item);
            });
        }

        @Override
        public View view() {
            return tab;
        }

        @Override
        public void bind(HapRows.PluginTab next) {
            item = next;
            applyText(tab, next.label + " (" + next.channelCount + ")");
            int wanted = next.selected ? 1 : 0;
            if (appliedSelected == wanted) return;
            appliedSelected = wanted;
            tab.setTextColor(next.selected ? COLOR_BRAND : COLOR_TEXT_SECONDARY);
            tab.setBackground(focusBackground(
                    next.selected ? COLOR_BRAND_MUTED : COLOR_SURFACE,
                    COLOR_SURFACE_FOCUSED,
                    dp(999),
                    dp(1),
                    next.selected ? COLOR_BRAND : COLOR_STROKE
            ));
        }
    }

    /** Selecting a tab picks its list and its first channel, exactly as the rebuilt tabs did. */
    private void selectPlugin(HapRows.PluginTab tab) {
        selectedPluginId = tab.id;
        selectedChannelContentId = tab.firstChannelId;
        renderChannelPlugins();
        renderChannels();
        renderChannelFeedback(null);
    }

    /**
     * Hands the channel test rows to the list. The differ compares the value models off the main
     * thread and rebinds only the row whose model changed, so the row the user is standing on keeps
     * the focus while a list test is running, and a tick that changes nothing touches no view at all
     * (S7).
     */
    private void renderChannels() {
        HapBridge.PluginChannelsInfo selectedPlugin = selectedPlugin();
        applyVisibility(channelsHintView, selectedPlugin == null);
        if (selectedPlugin == null) {
            channelAdapter.submitList(Collections.<HapRows.Channel>emptyList());
            applyVisibility(channelRecycler, false);
            return;
        }

        List<HapRows.Channel> rows = new ArrayList<>(selectedPlugin.channels.size());
        // Working channels first, then by peers: a list of hundreds is only useful if the ones that
        // answer are at the top. Untested channels keep their alphabetical order below the ranked ones.
        List<HapBridge.ChannelInfo> ordered = ChannelRanking.order(
                selectedPlugin.channels,
                channel -> channel.contentId,
                channel -> channel.name,
                contentId -> {
                    HapBridge.PeerResultInfo result = peerResults.get(contentId);
                    if (result == null) return null;
                    return new ChannelRanking.Score() {
                        @Override
                        public boolean available() {
                            return result.available;
                        }

                        @Override
                        public int peers() {
                            return result.totalPeers;
                        }
                    };
                }
        );
        for (HapBridge.ChannelInfo channel : ordered) {
            if (!ChannelSearch.matches(channel.name, channelQuery)) continue;
            HapBridge.PeerResultInfo result = peerResults.get(channel.contentId);
            boolean selected = channel.contentId.equals(selectedChannelContentId);
            rows.add(new HapRows.Channel(
                    channel.contentId,
                    channel.name,
                    selected,
                    result == null ? null : peerDetailText(result),
                    result == null ? 0 : peerColor(result),
                    result == null ? null : peerShortStatus(result),
                    result == null ? 0 : (result.available ? COLOR_SUCCESS_MUTED : COLOR_SURFACE),
                    result == null ? 0 : (result.available ? COLOR_SUCCESS : COLOR_TEXT_TERTIARY)
            ));
        }
        // An empty result is the search's doing, not an empty source: say which, or the user is left
        // looking at a blank space with no explanation.
        if (rows.isEmpty() && !channelQuery.trim().isEmpty()) {
            channelsHintView.setText(getString(R.string.message_no_matching_channels, channelQuery.trim()));
            applyVisibility(channelsHintView, true);
        } else {
            channelsHintView.setText(getString(R.string.message_load_channels_hint));
            applyVisibility(channelsHintView, false);
        }
        // A fresh list every time: the differ keeps the one it was given, so nothing may mutate it.
        channelAdapter.submitList(rows);
        // An empty list would add nothing but a blank slab of its fixed height, so it is hidden.
        applyVisibility(channelRecycler, !rows.isEmpty());
    }

    /**
     * One channel test row. The detail line and the result pill are created with the row and only
     * shown or hidden afterwards, so a result never changes the shape of the row it belongs to.
     */
    private final class ChannelRow implements ChannelAdapter.Row {

        private final LinearLayout row;
        private final TextView nameView;
        private final TextView contentView;
        private final TextView detailView;
        private final TextView badgeView;
        private HapRows.Channel item;
        private int appliedSelected = -1;
        private int appliedDetailColor = Integer.MIN_VALUE;
        private int appliedBadgeBackground = Integer.MIN_VALUE;
        private int appliedBadgeForeground = Integer.MIN_VALUE;

        ChannelRow(RecyclerView.LayoutParams params) {
            row = new LinearLayout(HapConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            // The gap between rows is the list's business now: a recycled row learns it from its
            // position instead of from the container's child count.
            row.setLayoutParams(params);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                if (item != null) selectChannel(item);
            });

            LinearLayout left = new LinearLayout(HapConfigActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            nameView = text("", 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(nameView, matchWrap());

            contentView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            contentView.setSingleLine(true);
            contentView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(contentView, matchWrap());

            detailView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            detailView.setMaxLines(2);
            detailView.setEllipsize(TextUtils.TruncateAt.END);
            detailView.setVisibility(View.GONE);
            left.addView(detailView, matchWrap());

            badgeView = pill("", COLOR_SURFACE, COLOR_TEXT_TERTIARY);
            badgeView.setVisibility(View.GONE);
            row.addView(badgeView, wrapWrapLeft(8));
        }

        @Override
        public View view() {
            return row;
        }

        @Override
        public void bind(HapRows.Channel next) {
            item = next;

            int wanted = next.selected ? 1 : 0;
            if (appliedSelected != wanted) {
                appliedSelected = wanted;
                row.setBackground(focusBackground(
                        next.selected ? COLOR_BRAND_MUTED : COLOR_PANEL_SOFT,
                        COLOR_SURFACE_FOCUSED,
                        dp(10),
                        dp(1),
                        next.selected ? COLOR_BRAND : COLOR_STROKE
                ));
            }

            applyText(nameView, next.name);
            applyText(contentView, next.contentId);

            boolean hasResult = next.badge != null;
            if (next.detail != null && appliedDetailColor != next.detailColor) {
                appliedDetailColor = next.detailColor;
                detailView.setTextColor(next.detailColor);
            }
            applyText(detailView, next.detail);
            applyVisibility(detailView, next.detail != null);

            if (hasResult) {
                if (appliedBadgeBackground != next.badgeBackground) {
                    appliedBadgeBackground = next.badgeBackground;
                    badgeView.setBackground(roundRect(next.badgeBackground, dp(999), 0, 0));
                }
                if (appliedBadgeForeground != next.badgeForeground) {
                    appliedBadgeForeground = next.badgeForeground;
                    badgeView.setTextColor(next.badgeForeground);
                }
            }
            applyText(badgeView, next.badge);
            applyVisibility(badgeView, hasResult);
        }
    }

    /** Selecting a channel row marks it and checks its peers, exactly as the rebuilt rows did. */
    /**
     * One tap tests a channel, the next one watches it.
     *
     * <p>Before this, a tap only ever selected and checked peers: the channel never opened, which is
     * what "le doy al canal y no se me abre" described. A channel already checked and found working
     * now hands the stream to a player, and one that is untested or failed is checked again, because
     * that is what the user needs from it in that state.
     */
    private void selectChannel(HapRows.Channel channel) {
        selectedChannelContentId = channel.contentId;
        renderChannels();
        // OK on a channel plays it, always. It used to check first and only play when the check said
        // the channel was alive, which made the button dead: the peer check does not answer on this
        // engine, so a channel could never be watched from the list. Playback does not depend on that
        // check at all - the player's request is what starts the broadcast.
        watchChannel(channel);
    }

    /**
     * Hands the channel to a player.
     *
     * <p>The proxy serves the channel as an MPEG-TS stream and starts the broadcast on the first
     * request, so opening its URL is the whole of launching an AceStream channel. The player is
     * whichever app on the device answers {@code video/mp2t}; the television in the reference setup has
     * AceStream's own app for it.
     */
    private void watchChannel(HapRows.Channel channel) {
        if (channel.contentId == null || channel.contentId.trim().isEmpty()) return;
        // AceStream Pro is a player, but for acestream:// links: handed an http URL it treats it as its
        // own API query and answers "analyze failed". So the channel goes out on the protocol that app
        // speaks, the same way every other IPTV app launches it. That makes AceStream do the P2P itself,
        // which is also why this path does not depend on the embedded engine's broadcast.
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("acestream://" + channel.contentId));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            renderChannelFeedback(getString(R.string.message_watching_channel, channel.name));
        } catch (ActivityNotFoundException e) {
            renderChannelFeedback(getString(R.string.error_no_video_player));
        }
    }

    private void renderChannelFeedback(String message) {
        String feedback = message;
        HapBridge.PluginChannelsInfo selectedPlugin = selectedPlugin();
        HapBridge.PeerResultInfo selectedResult = selectedChannelContentId == null ? null : peerResults.get(selectedChannelContentId);

        if (feedback == null && selectedResult != null) {
            feedback = getString(R.string.message_selected_channel_result, peerShortStatus(selectedResult), peerDetailText(selectedResult));
        }

        if (feedback == null && selectedPlugin != null) {
            int tested = 0;
            int available = 0;
            int errors = 0;
            for (HapBridge.ChannelInfo channel : selectedPlugin.channels) {
                HapBridge.PeerResultInfo result = peerResults.get(channel.contentId);
                if (result == null) continue;
                tested++;
                if (result.available) available++;
                if (!result.success || !result.error.isEmpty()) errors++;
            }
            if (tested > 0) {
                int unavailable = Math.max(0, tested - available - errors);
                feedback = getString(
                        R.string.message_list_test_summary,
                        selectedPlugin.label,
                        tested,
                        selectedPlugin.channels.size(),
                        available,
                        unavailable,
                        errors
                );
            }
        }

        if (feedback == null || feedback.trim().isEmpty()) {
            applyVisibility(channelFeedbackView, false);
        } else {
            applyText(channelFeedbackView, feedback);
            applyVisibility(channelFeedbackView, true);
        }
    }

    /** Last 80 log lines, computed off the main thread so an unchanged tick touches no view. */
    private String runtimeLogText(String log) {
        String trimmed = log == null ? "" : log.trim();
        if (trimmed.isEmpty()) return getString(R.string.message_no_log);

        String[] lines = trimmed.split("\\r?\\n");
        int start = Math.max(0, lines.length - 80);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private void setEnabled(boolean enabled) {
        if (enabled) {
            runBusy(getString(R.string.message_starting_hap), () -> {
                HapBridge.start(this);
                boolean ready = HapBridge.waitForProxyReady(90_000);
                return ready ? getString(R.string.message_hap_ready) : getString(R.string.message_hap_start_timeout);
            }, this::refreshConfigState);
        } else {
            runBusy(getString(R.string.message_stopping_hap), () -> {
                HapBridge.stop(this);
                // P3: this is the only path that wipes. StopWipe waits for the engine to be down,
                // swallows and logs its own failures, and does nothing at all while the setting is
                // off (the default), so an unchanged install behaves exactly as before.
                StopWipe.wipeAfterExplicitStop(this);
                return getString(R.string.message_hap_stopped);
            }, this::refreshConfigState);
        }
    }

    private void restartProxy() {
        runBusy(getString(R.string.message_restarting_proxy), () -> {
            HapBridge.restartProxy(this);
            boolean ready = HapBridge.waitForProxyReady(45_000);
            return ready ? getString(R.string.message_proxy_ready) : getString(R.string.message_proxy_waiting);
        }, this::refreshConfigState);
    }

    private void prepareAio() {
        runBusy(getString(R.string.message_preparing_aio), () -> {
            HapBridge.start(this);
            boolean ready = HapBridge.waitForProxyReady(90_000);
            return ready ? getString(R.string.message_aio_ready, HapBridge.LOCAL_AIO_URL) : getString(R.string.message_aio_timeout);
        }, this::refreshConfigState);
    }

    private void setExternalPlayerServer(boolean enabled) {
        runBusy(
                enabled ? getString(R.string.message_enabling_external_server) : getString(R.string.message_disabling_external_server),
                () -> {
                    if (enabled) {
                        HapBridge.enableExternalPlayerServer(this);
                        boolean ready = HapBridge.waitForProxyReady(90_000);
                        String url = HapBridge.castAioUrl(this);
                        if (url.isEmpty()) url = HapBridge.LOCAL_AIO_URL;
                        return ready
                                ? getString(R.string.message_external_server_ready, url)
                                : getString(R.string.message_external_server_timeout);
                    }
                    HapBridge.disableExternalPlayerServer(this);
                    return getString(R.string.message_external_server_disabled);
                },
                this::refreshConfigState
        );
    }

    private void setServerMode(boolean enabled) {
        runBusy(enabled ? getString(R.string.message_enabling_lan) : getString(R.string.message_disabling_lan), () -> {
            if (!enabled && HapBridge.isExternalPlayerServerEnabled(this)) {
                HapBridge.setExternalPlayerServerEnabled(this, false);
            }
            HapBridge.setServerModeEnabled(this, enabled);
            return enabled ? getString(R.string.message_lan_enabled) : getString(R.string.message_lan_disabled);
        }, this::refreshConfigState);
    }

    private void setIpfsEnabled(boolean enabled) {
        runBusy(
                enabled ? getString(R.string.message_enabling_ipfs) : getString(R.string.message_disabling_ipfs),
                () -> {
                    HapBridge.setIpfsEnabled(this, enabled);
                    return enabled ? getString(R.string.message_ipfs_enabled) : getString(R.string.message_ipfs_disabled);
                },
                null
        );
    }

    private void saveIpfsAdvancedSettings() {
        boolean external = ipfsExternalSwitch.isChecked();
        String host = ipfsExternalHostEdit.getText().toString();
        int externalPort = parseIntOrDefault(ipfsExternalPortEdit.getText().toString(), 8080);
        int gatewayPort = parseIntOrDefault(ipfsGatewayPortEdit.getText().toString(), 8080);
        int storageMaxGb = parseIntOrDefault(ipfsStorageMaxEdit.getText().toString(), 2);
        showIpfsAdvancedFeedback(getString(R.string.message_saving_ipfs_settings), COLOR_BRAND);
        setBusy(true);
        workExecutor.execute(() -> {
            HapBridge.SaveResult result = HapBridge.saveIpfsAdvancedSettings(this, external, host, externalPort, gatewayPort, storageMaxGb);
            main(() -> {
                setBusy(false);
                showIpfsAdvancedFeedback(result.message, result.success ? COLOR_SUCCESS : COLOR_ERROR);
                refreshRuntimeState();
            });
        });
    }

    private void populateIpfsAdvancedFields() {
        boolean external = HapBridge.isIpfsExternalMode(this);
        ipfsExternalSwitch.setChecked(external);
        ipfsExternalFieldsContainer.setVisibility(external ? View.VISIBLE : View.GONE);
        ipfsExternalHostEdit.setText(HapBridge.ipfsExternalHost(this));
        ipfsExternalPortEdit.setText(String.valueOf(HapBridge.ipfsExternalPort(this)));
        ipfsGatewayPortEdit.setText(String.valueOf(HapBridge.ipfsGatewayPort(this)));
        ipfsStorageMaxEdit.setText(String.valueOf(HapBridge.ipfsStorageMaxGb(this)));
    }

    private void showIpfsAdvancedFeedback(String message, int color) {
        if (ipfsAdvancedFeedbackView == null) return;
        if (message == null || message.trim().isEmpty()) {
            ipfsAdvancedFeedbackView.setVisibility(View.GONE);
            return;
        }
        ipfsAdvancedFeedbackView.setTextColor(color);
        ipfsAdvancedFeedbackView.setText(message);
        ipfsAdvancedFeedbackView.setVisibility(View.VISIBLE);
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Applies the gateway URL. The row is built with the panel and only re-labelled here: hiding it
     * is a visibility change, never a removal, so the copy button keeps its identity (S7).
     */
    private void applyIpfsGateway(String url) {
        String value = url == null ? "" : url;
        ipfsGatewayValue = value;
        applyText(ipfsGatewayValueView, value);
        applyVisibility(ipfsGatewayRow, !value.isEmpty());
    }

    private void setSourceSelected(String sourceId, boolean selected) {
        runBusy(getString(R.string.message_updating_aio_selection), () -> {
            HapBridge.setSourceSelected(this, sourceId, selected);
            return getString(R.string.message_aio_selection_saved);
        }, this::refreshConfigState);
    }

    private void saveM3uSource() {
        String editId = editingM3uSourceId;
        String name = sourceNameEdit.getText().toString();
        String url = sourceUrlEdit.getText().toString();
        showMessage(getString(R.string.message_validating_source));
        showSourceFeedback(getString(R.string.message_validating_source), COLOR_BRAND);
        setBusy(true);
        workExecutor.execute(() -> {
            try {
                HapBridge.SaveResult result = HapBridge.saveM3uSource(this, editId, name, url);
                main(() -> {
                    setBusy(false);
                    showMessage(result.message);
                    showSourceFeedback(result.message, result.success ? COLOR_SUCCESS : COLOR_ERROR);
                    if (result.success) {
                        clearSourceDraft();
                        showSourceFeedback(result.message, COLOR_SUCCESS);
                        refreshConfigState();
                    }
                    refreshRuntimeState();
                });
            } catch (Throwable error) {
                String message = errorMessage(error);
                main(() -> {
                    setBusy(false);
                    showMessage(message);
                    showSourceFeedback(message, COLOR_ERROR);
                    refreshRuntimeState();
                });
            }
        });
    }

    private void editM3uSource(HapRows.Source source) {
        editingM3uSourceId = source.id;
        sourceNameEdit.setText(source.label);
        sourceUrlEdit.setText(source.value);
        sourceSaveButton.setText(getString(R.string.button_update_m3u_source));
        sourceCancelButton.setVisibility(View.VISIBLE);
    }

    private void clearSourceDraft() {
        editingM3uSourceId = "";
        sourceNameEdit.setText("");
        sourceUrlEdit.setText("");
        sourceSaveButton.setText(getString(R.string.button_add_m3u_source));
        sourceCancelButton.setVisibility(View.GONE);
        hideSourceFeedback();
    }

    private void deleteM3uSource(String sourceId) {
        runBusy(getString(R.string.message_deleting_source), () -> {
            HapBridge.SaveResult result = HapBridge.deleteM3uSource(this, sourceId);
            return result.message;
        }, this::refreshConfigState);
    }

    private void saveCustomChannel() {
        String name = customNameEdit == null ? "" : customNameEdit.getText().toString();
        String contentId = customContentIdEdit == null ? "" : customContentIdEdit.getText().toString();
        int editIndex = customEditingIndex;
        showMessage(getString(R.string.message_saving_custom_channel));
        setBusy(true);
        workExecutor.execute(() -> {
            try {
                HapBridge.SaveResult result = HapBridge.saveCustomChannel(this, editIndex, name, contentId);
                main(() -> {
                    setBusy(false);
                    showMessage(result.message);
                    if (result.success) {
                        clearCustomDraft();
                        refreshConfigState();
                    }
                    refreshRuntimeState();
                });
            } catch (Throwable error) {
                main(() -> {
                    setBusy(false);
                    showMessage(errorMessage(error));
                    refreshRuntimeState();
                });
            }
        });
    }

    private void editCustomChannel(HapRows.CustomChannel channel) {
        customEditingIndex = channel.index;
        if (customNameEdit != null) customNameEdit.setText(channel.name);
        if (customContentIdEdit != null) customContentIdEdit.setText(channel.contentId);
        showMessage(getString(R.string.message_editing_channel, channel.name));
    }

    private void clearCustomDraft() {
        customEditingIndex = -1;
        if (customNameEdit != null) customNameEdit.setText("");
        if (customContentIdEdit != null) customContentIdEdit.setText("");
    }

    private void deleteCustomChannel(int index) {
        runBusy(getString(R.string.message_deleting_custom_channel), () -> {
            HapBridge.SaveResult result = HapBridge.deleteCustomChannel(this, index);
            return result.message;
        }, this::refreshConfigState);
    }

    private void loadClients() {
        applyText(clientsStatusView, getString(R.string.message_loading_clients));
        workExecutor.execute(() -> {
            try {
                List<HapBridge.ConnectedClientInfo> clients = HapBridge.connectedClients();
                main(() -> renderClients(clients));
            } catch (Throwable error) {
                main(() -> {
                    applyText(clientsStatusView, errorMessage(error));
                    // The rows are stale, not reloading: drop the tail instead of rebuilding.
                    clientList.clear();
                });
            }
        });
    }

    private void loadChannelPlugins() {
        renderChannelFeedback(getString(R.string.message_loading_lists));
        workExecutor.execute(() -> {
            try {
                HapBridge.start(this);
                boolean ready = HapBridge.waitForProxyReady(90_000);
                if (!ready) throw new IllegalStateException(getString(R.string.message_aio_timeout));
                List<HapBridge.PluginChannelsInfo> plugins = HapBridge.channelPlugins(this);
                main(() -> {
                    channelPlugins = plugins;
                    selectedPluginId = plugins.isEmpty() ? null : plugins.get(0).id;
                    selectedChannelContentId = plugins.isEmpty() || plugins.get(0).channels.isEmpty()
                            ? null
                            : plugins.get(0).channels.get(0).contentId;
                    renderChannelPlugins();
                    renderChannelFeedback(plugins.isEmpty() ? getString(R.string.message_no_lists_available) : null);
                });
            } catch (Throwable error) {
                main(() -> renderChannelFeedback(errorMessage(error)));
            }
        });
    }

    private void checkChannel(String contentId, String channelName) {
        // An explicit single check supersedes anything already running: the same channel must not
        // be tested twice at once, and the remaining P2P sessions of a batch are wasted once the
        // user asks for one channel, so the batch is really stopped instead of left running.
        int generation = abortChannelTest();
        renderChannelFeedback(getString(R.string.message_testing_channel, channelName));

        HapBridge.PeerRequest request = HapBridge.newPeerRequest();
        inFlightPeerRequest = request;
        channelTestExecutor.execute(() -> {
            try {
                HapBridge.PeerResultInfo result =
                        HapBridge.checkPeers(contentId, CHANNEL_TEST_SINGLE_MAX_WAIT_SECONDS, request);
                main(() -> {
                    if (!isCurrentRun(generation)) return;
                    peerResults.put(contentId, result);
                    renderChannels();
                    renderChannelFeedback(null);
                });
            } catch (Throwable error) {
                // An aborted check is not a channel failure.
                if (request.isCancelled() || !isCurrentRun(generation)) return;
                main(() -> {
                    if (!isCurrentRun(generation)) return;
                    renderChannelFeedback(errorMessage(error));
                });
            } finally {
                endPeerRequest(request);
            }
        });
    }

    private void checkSelectedList() {
        HapBridge.PluginChannelsInfo plugin = selectedPlugin();
        if (plugin == null) {
            renderChannelFeedback(getString(R.string.message_select_list_first));
            return;
        }

        int generation = abortChannelTest();
        // A new batch starts from a fresh playback reading instead of the previous run's answer.
        playbackProbeActive = false;
        playbackProbeAt = 0L;

        List<String> contentIds = new ArrayList<>(plugin.channels.size());
        Map<String, String> names = new HashMap<>();
        for (HapBridge.ChannelInfo channel : plugin.channels) {
            contentIds.add(channel.contentId);
            names.put(channel.contentId, channel.name);
        }

        ChannelTestBatch<HapBridge.PeerResultInfo> batch = new ChannelTestBatch<>(
                contentIds,
                channelTestParallelism,
                HapBridge::newPeerRequest,
                // The batch creates its handles with HapBridge::newPeerRequest, so every handle it
                // hands to a check is a PeerRequest and can abort that check's socket.
                (contentId, cancellation) -> HapBridge.checkPeers(
                        contentId,
                        CHANNEL_TEST_MAX_WAIT_SECONDS,
                        (HapBridge.PeerRequest) cancellation),
                channelTestPool(),
                this::playbackActive,
                channelTestListener(generation, names));

        activeChannelTestBatch = batch;
        stopChannelTestButton.setEnabled(true);
        renderChannelFeedback(getString(R.string.message_testing_list_progress, plugin.label, 0, batch.total()));
        channelTestExecutor.execute(batch::run);
    }

    /**
     * Bridges the batch's per-check outcomes to the UI.
     *
     * <p>The callbacks arrive on the pool threads, so every one of them is posted to the main
     * thread and dropped when the run it belongs to was superseded.
     */
    private ChannelTestBatch.Listener<HapBridge.PeerResultInfo> channelTestListener(
            final int generation, final Map<String, String> names) {
        return new ChannelTestBatch.Listener<HapBridge.PeerResultInfo>() {
            @Override
            public void onResult(String contentId, HapBridge.PeerResultInfo result, int completed, int total) {
                main(() -> {
                    if (!isCurrentRun(generation)) return;
                    peerResults.put(contentId, result);
                    renderChannels();
                    renderChannelFeedback(progressMessage(names.get(contentId), completed, total));
                });
            }

            @Override
            public void onFailure(String contentId, Exception error, int completed, int total) {
                String errorText = errorMessage(error);
                main(() -> {
                    if (!isCurrentRun(generation)) return;
                    // A failing or timing out channel records its own result; the batch goes on.
                    peerResults.put(contentId, HapBridge.PeerResultInfo.errorResult(errorText));
                    renderChannels();
                    renderChannelFeedback(errorText);
                });
            }

            @Override
            public void onFinished(boolean cancelled) {
                main(() -> {
                    if (!isCurrentRun(generation)) return;
                    activeChannelTestBatch = null;
                    stopChannelTestButton.setEnabled(false);
                    renderChannelFeedback(cancelled
                            ? getString(R.string.message_test_stopped)
                            : getString(R.string.message_list_test_complete));
                });
            }
        };
    }

    private String progressMessage(String channelName, int completed, int total) {
        return getString(
                R.string.message_testing_list_progress,
                channelName == null ? "" : channelName,
                completed,
                total);
    }

    private void stopChannelTest() {
        abortChannelTest();
        stopChannelTestButton.setEnabled(false);
        renderChannelFeedback(getString(R.string.message_test_stopped));
    }

    private void endPeerRequest(HapBridge.PeerRequest request) {
        if (inFlightPeerRequest == request) inFlightPeerRequest = null;
    }

    /** True while {@code generation} is still the current run; stale callbacks are dropped. */
    private boolean isCurrentRun(int generation) {
        return channelTestGeneration.get() == generation && !destroyed;
    }

    /**
     * Cancels the whole current test run and returns the new run token.
     *
     * <p>Every check in flight is aborted through its own handle: closing the socket is the only
     * thing that unblocks a blocked read, an interrupt does not (S4). The native side then sees
     * the client disconnect and stops the P2P session instead of running it to completion.
     */
    private int abortChannelTest() {
        int generation = channelTestGeneration.incrementAndGet();
        ChannelTestBatch<HapBridge.PeerResultInfo> batch = activeChannelTestBatch;
        activeChannelTestBatch = null;
        if (batch != null) batch.cancel();
        HapBridge.PeerRequest request = inFlightPeerRequest;
        inFlightPeerRequest = null;
        if (request != null) request.cancel();
        return generation;
    }

    /**
     * True when something is actually being watched through HaP, so testing has to give way (S3).
     *
     * <p>A peer check is a control request and never registers as a streaming client, so any
     * connected client means real playback. The answer is reused for a second: the batch re-reads
     * it a few times per second and each reading costs a loopback round trip.
     */
    private boolean playbackActive() {
        long now = SystemClock.elapsedRealtime();
        if (now - playbackProbeAt < PLAYBACK_PROBE_TTL_MS) return playbackProbeActive;

        boolean active;
        if (destroyed || !HapBridge.status(this).proxyRunning) {
            // No proxy, no playback: nothing can be streaming through HaP.
            active = false;
        } else {
            active = playbackProbeActive;
            try {
                active = HapBridge.connectedClientCount() > 0;
            } catch (Throwable error) {
                // Keep the last known answer: widening the batch on an unreadable signal is the
                // wrong direction when playback has priority.
            }
        }
        playbackProbeActive = active;
        playbackProbeAt = now;
        return active;
    }

    /** The pool the batch dispatches its checks to; created on first use. */
    private ExecutorService channelTestPool() {
        ExecutorService pool = channelTestPool;
        if (pool != null) return pool;
        synchronized (this) {
            if (channelTestPool == null) {
                channelTestPool = Executors.newFixedThreadPool(
                        channelTestParallelism,
                        task -> {
                            Thread thread = new Thread(task, "hap-channel-check");
                            thread.setDaemon(true);
                            return thread;
                        });
            }
            return channelTestPool;
        }
    }

    private HapBridge.PluginChannelsInfo selectedPlugin() {
        if (selectedPluginId == null) return null;
        for (HapBridge.PluginChannelsInfo plugin : channelPlugins) {
            if (plugin.id.equals(selectedPluginId)) return plugin;
        }
        return null;
    }

    private HapBridge.SourceInfo customSource() {
        for (HapBridge.SourceInfo source : currentSources) {
            if ("CUSTOM".equals(source.format)) return source;
        }
        return null;
    }

    private void runBusy(String startMessage, Callable<String> work, Runnable after) {
        showMessage(startMessage);
        setBusy(true);
        workExecutor.execute(() -> {
            String message;
            try {
                message = work.call();
            } catch (Throwable error) {
                message = errorMessage(error);
            }
            String finalMessage = message;
            main(() -> {
                setBusy(false);
                showMessage(finalMessage);
                if (after != null) after.run();
                refreshRuntimeState();
            });
        });
    }

    private void setBusy(boolean busy) {
        for (View control : busyControls) {
            control.setEnabled(!busy);
        }
    }

    private void showMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            messageView.setVisibility(View.GONE);
            return;
        }
        messageView.setText(message);
        messageView.setVisibility(View.VISIBLE);
    }

    private void showSourceFeedback(String message, int color) {
        if (sourceFeedbackView == null) return;
        if (message == null || message.trim().isEmpty()) {
            hideSourceFeedback();
            return;
        }
        sourceFeedbackView.setTextColor(color);
        sourceFeedbackView.setText(message);
        sourceFeedbackView.setVisibility(View.VISIBLE);
    }

    private void hideSourceFeedback() {
        if (sourceFeedbackView == null) return;
        sourceFeedbackView.setVisibility(View.GONE);
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        showMessage(getString(R.string.message_copied, value));
    }

    private void main(Runnable runnable) {
        if (destroyed) return;
        handler.post(runnable);
    }

    private String onlineLabel(boolean value) {
        return value ? getString(R.string.status_online) : getString(R.string.status_offline);
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.toString() : message;
    }

    private String peerShortStatus(HapBridge.PeerResultInfo result) {
        if (result.available) return getString(R.string.peer_available, result.totalPeers);
        if (!result.success || !result.error.isEmpty()) return getString(R.string.peer_error);
        return getString(R.string.peer_no_seeds);
    }

    private String peerDetailText(HapBridge.PeerResultInfo result) {
        if (!result.error.isEmpty()) return result.error;
        if (result.available) {
            return getString(R.string.peer_detail_available, result.totalPeers, result.peers, result.httpPeers);
        }
        return result.statusText.isEmpty() ? getString(R.string.peer_detail_unavailable) : result.statusText;
    }

    private String appVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName == null ? "" : packageInfo.versionName.trim();
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private int peerColor(HapBridge.PeerResultInfo result) {
        if (result.available) return COLOR_SUCCESS;
        if (!result.success || !result.error.isEmpty()) return COLOR_ERROR;
        return COLOR_WARNING;
    }

    private String displayPhase(String rawPhase) {
        if (rawPhase == null || rawPhase.trim().isEmpty()) return getString(R.string.status_idle);
        String lower = rawPhase.toLowerCase(Locale.ROOT);
        if ("running".equals(lower)) return getString(R.string.status_running);
        if ("failed".equals(lower)) return getString(R.string.status_failed);
        if ("stopped".equals(lower)) return getString(R.string.status_stopped);
        if ("stopping".equals(lower)) return getString(R.string.phase_stopping);
        if ("preparing assets".equals(lower)) return getString(R.string.phase_preparing_assets);
        if ("starting acestream".equals(lower) || "starting aceserve".equals(lower)) return getString(R.string.phase_starting_aceserve);
        if ("starting httpaceproxy".equals(lower)) return getString(R.string.phase_starting_proxy);
        if ("restarting httpaceproxy".equals(lower)) return getString(R.string.phase_restarting_proxy);
        return rawPhase;
    }

    private RuntimeBadge runtimeBadge(HapBridge.HapStatus status) {
        if (!status.lastError.isEmpty()) {
            return new RuntimeBadge(getString(R.string.status_failed), COLOR_ERROR_MUTED, COLOR_ERROR);
        }
        if (status.aceRunning && status.proxyRunning) {
            return new RuntimeBadge(getString(R.string.status_running), COLOR_SUCCESS_MUTED, COLOR_SUCCESS);
        }
        if (status.desiredRunning) {
            return new RuntimeBadge(getString(R.string.status_starting), COLOR_WARNING_MUTED, COLOR_WARNING);
        }
        return new RuntimeBadge(getString(R.string.status_stopped), COLOR_ERROR_MUTED, COLOR_ERROR);
    }

    private TextView weightedText(String value, float weight, int color, boolean bold) {
        TextView text = text(value, 12, color, bold ? Typeface.BOLD : Typeface.NORMAL);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, 0, dp(8), 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return text;
    }

    private PanelViews panel(String title, boolean collapsible, boolean expandedByDefault) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int cardPadding = getResources().getDimensionPixelSize(R.dimen.hap_card_padding);
        // A collapsed panel is a header, not a card: keeping the full card padding around a single
        // folded line left a hole the size of a card, which is what made whole sections look empty.
        int tightPadding = Math.round(cardPadding / 2f);
        boolean folded = collapsible && !expandedByDefault;
        container.setPadding(cardPadding, folded ? tightPadding : cardPadding,
                cardPadding, folded ? tightPadding : cardPadding);
        // The shared state list: a panel that becomes focusable uses the same focus language.
        container.setBackgroundResource(R.drawable.bg_panel);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        if (title != null && !title.trim().isEmpty()) {
            TextView header = text(title, 18, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            if (collapsible) {
                header.setFocusable(true);
                header.setBackground(focusBackground(0x00000000, COLOR_SURFACE_FOCUSED, dp(10), 0, 0));
                header.setPadding(dp(8), dp(8), dp(8), dp(8));
                header.setText((expandedByDefault ? "- " : "+ ") + title);
                body.setVisibility(expandedByDefault ? View.VISIBLE : View.GONE);
                header.setOnClickListener(v -> {
                    boolean expanded = body.getVisibility() == View.VISIBLE;
                    body.setVisibility(expanded ? View.GONE : View.VISIBLE);
                    header.setText((expanded ? "+ " : "- ") + title);
                    int vertical = expanded ? tightPadding : cardPadding;
                    container.setPadding(cardPadding, vertical, cardPadding, vertical);
                });
            }
            container.addView(header, matchWrap());
            LinearLayout.LayoutParams bodyParams = matchWrap();
            bodyParams.topMargin = dp(10);
            container.addView(body, bodyParams);
        } else {
            container.addView(body, matchWrap());
        }

        return new PanelViews(container, body);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setIncludeFontPadding(true);
        return text;
    }

    private TextView pill(String label, int backgroundColor, int textColor) {
        TextView pill = text(label, 12, textColor, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(roundRect(backgroundColor, dp(999), 0, 0));
        return pill;
    }

    private EditText editText(String hint, boolean multiline) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(13);
        edit.setTextColor(COLOR_TEXT_PRIMARY);
        edit.setHintTextColor(COLOR_TEXT_TERTIARY);
        edit.setSingleLine(!multiline);
        edit.setMinLines(multiline ? 2 : 1);
        edit.setMaxLines(multiline ? 5 : 1);
        edit.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_VARIATION_URI));
        edit.setBackground(roundRect(0x33162435, dp(8), dp(1), COLOR_STROKE));
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        return edit;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(COLOR_TEXT_PRIMARY);
        button.setAllCaps(false);
        button.setMinHeight(dp(38));
        button.setMinimumHeight(dp(38));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(focusBackground(COLOR_SURFACE, COLOR_SURFACE_FOCUSED, dp(8), dp(1), COLOR_STROKE));
        busyControls.add(button);
        return button;
    }

    private Button smallButton(String label) {
        Button button = button(label);
        button.setTextSize(12);
        button.setMinHeight(dp(32));
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private StateListDrawable focusBackground(int normalColor, int focusedColor, int radius, int strokeWidth, int strokeColor) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused}, roundRect(focusedColor, radius, strokeWidth, COLOR_BRAND));
        drawable.addState(new int[]{android.R.attr.state_pressed}, roundRect(focusedColor, radius, strokeWidth, COLOR_BRAND));
        drawable.addState(new int[]{}, roundRect(normalColor, radius, strokeWidth, strokeColor));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isCompactWidth() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return widthDp > 0 && widthDp < 600;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapWrapLeft(int left) {
        LinearLayout.LayoutParams params = wrapWrap();
        params.leftMargin = dp(left);
        return params;
    }

    /**
     * A pill TextView plus the colours currently applied to it, so an unchanged refresh tick
     * neither re-inflates the view nor allocates a new Drawable.
     */
    private final class Pill {
        private final TextView view;
        private int backgroundColor = Integer.MIN_VALUE;
        private int textColor = Integer.MIN_VALUE;

        Pill(TextView view) {
            this.view = view;
        }

        void apply(String label, int background, int foreground) {
            if (!TextUtils.equals(label, view.getText())) view.setText(label);
            if (textColor != foreground) {
                view.setTextColor(foreground);
                textColor = foreground;
            }
            if (backgroundColor != background) {
                view.setBackground(roundRect(background, dp(999), 0, 0));
                backgroundColor = background;
            }
        }
    }

    private static final class RuntimeBadge {
        final String label;
        final int containerColor;
        final int contentColor;

        RuntimeBadge(String label, int containerColor, int contentColor) {
            this.label = label;
            this.containerColor = containerColor;
            this.contentColor = contentColor;
        }
    }

    /** Everything the runtime refresh renders, read off the main thread. */
    private static final class RuntimeSnapshot {
        String stateLabel;
        int stateBackgroundColor;
        int stateTextColor;
        String phaseLabel;
        int phaseBackgroundColor;
        int phaseTextColor;
        String aceServeLabel;
        int aceServeBackgroundColor;
        int aceServeTextColor;
        String proxyLabel;
        int proxyBackgroundColor;
        int proxyTextColor;
        String errorText;
        boolean desiredRunning;
        boolean externalPlayerServerEnabled;
        boolean lanModeEnabled;
        boolean ipfsEnabled;
        String ipfsLabel;
        int ipfsBackgroundColor;
        int ipfsTextColor;
        String ipfsErrorText;
        String ipfsGatewayUrl;
        List<HapRows.Endpoint> endpoints;
        String logText;
        HapServiceStates services;
        /** Real connected-client count, or -1 when the runtime did not answer (M4, S4). */
        int connectedClients = -1;
    }

    /** One playlist file found in the app's lists folder. */
    private static final class DeviceListModel {
        final String path;
        final String name;
        final String detail;
        /** The registered source for this file, or null when it is not a source yet. */
        final String sourceId;

        DeviceListModel(String path, String name, String detail, String sourceId) {
            this.path = path;
            this.name = name;
            this.detail = detail;
            this.sourceId = sourceId;
        }
    }

    /** A device playlist: its name, its size and the one action it offers. */
    private final class DeviceListRow implements InPlaceList.Row<DeviceListModel> {

        private final LinearLayout row;
        private final TextView nameView;
        private final TextView detailView;
        private final Button action;
        private DeviceListModel item;

        DeviceListRow(int topMargin) {
            row = new LinearLayout(HapConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = topMargin;
            row.setLayoutParams(params);

            LinearLayout texts = new LinearLayout(HapConfigActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            nameView = text("", 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(nameView, matchWrap());

            detailView = text("", 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            detailView.setPadding(0, dp(2), 0, 0);
            texts.addView(detailView, matchWrap());

            action = button("");
            action.setOnClickListener(v -> toggle());
            row.addView(action, wrapWrapLeft(10));
        }

        @Override
        public View view() {
            return row;
        }

        @Override
        public void bind(DeviceListModel next) {
            item = next;
            applyText(nameView, next.name);
            applyText(detailView, next.detail);
            applyText(action, next.sourceId == null
                    ? getString(R.string.button_add_to_sources)
                    : getString(R.string.button_remove_from_sources));
        }

        private void applyText(TextView view, String value) {
            if (!value.contentEquals(view.getText())) view.setText(value);
        }

        private void toggle() {
            DeviceListModel target = item;
            if (target == null) return;
            boolean adding = target.sourceId == null;
            showMessage(getString(adding ? R.string.message_adding_device_list : R.string.message_removing_device_list));
            workExecutor.execute(() -> {
                HapBridge.SaveResult result = adding
                        ? HapBridge.saveM3uSource(HapConfigActivity.this, "", target.name,
                                LocalSourceFile.valueFor(new File(target.path)))
                        : HapBridge.deleteM3uSource(HapConfigActivity.this, target.sourceId);
                main(() -> {
                    showMessage(result.message);
                    showSourceFeedback(result.message, result.success ? COLOR_SUCCESS : COLOR_ERROR);
                    if (result.success) refreshConfigState();
                });
            });
        }
    }

    private static final class PanelViews {
        final LinearLayout container;
        final LinearLayout body;

        PanelViews(LinearLayout container, LinearLayout body) {
            this.container = container;
            this.body = body;
        }
    }
}
