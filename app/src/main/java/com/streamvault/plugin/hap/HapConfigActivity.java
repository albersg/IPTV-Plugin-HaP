package com.streamvault.plugin.hap;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.jopsis.httpaceserveproxy.ChannelTestBatch;
import com.jopsis.httpaceserveproxy.HapBridge;
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
import java.util.ArrayList;
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
    private HapSections.Section currentSection = HapSections.Section.INICIO;
    private HomeScreen homeScreen;

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
    private LinearLayout customListContainer;
    private LinearLayout clientsContainer;
    private TextView clientsStatusView;
    private LinearLayout pluginTabsContainer;
    private LinearLayout channelRowsContainer;
    private TextView channelFeedbackView;
    private Button stopChannelTestButton;
    private TextView logView;
    private Switch enabledSwitch;
    private Switch externalServerSwitch;
    private Switch lanSwitch;
    private Switch ipfsSwitch;
    private Pill ipfsStatePill;
    private TextView ipfsErrorView;
    private LinearLayout ipfsGatewayContainer;
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

    private List<HapBridge.SourceInfo> currentSources = new ArrayList<>();
    private List<HapBridge.CustomChannelInfo> currentCustomChannels = new ArrayList<>();
    private List<HapBridge.PluginChannelsInfo> channelPlugins = new ArrayList<>();
    private String editingM3uSourceId = "";
    private String selectedPluginId;
    private String selectedChannelContentId;
    private int customEditingIndex = -1;
    private volatile HapBridge.PeerRequest inFlightPeerRequest;
    private volatile boolean destroyed;
    private List<EndpointModel> renderedEndpoints;
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
    private EditText updateIntervalEdit;
    private EditText updateRepositoryEdit;
    private UpdateDecision currentUpdate;
    private volatile File downloadedApk;
    private volatile boolean updateCheckInFlight;
    private volatile boolean updateDownloadInProgress;
    private volatile AtomicBoolean updateCancelFlag = new AtomicBoolean(false);
    private boolean suppressUpdateCallbacks;
    private int lastUpdateProgressPercent = -1;
    /** True when the user started the download from the action row, so init can take over focus. */
    private boolean updateActionsHadFocus;

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
        maybeRunAutomaticUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        refreshRunnable.run();
        // The system installer owns the screen during an update, so its result is picked up here.
        showPendingInstallResult();
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
        HapSections.Section target = HapSections.backTarget(currentSection);
        if (target != null) {
            showSection(target, false);
            TextView item = navItemFor(target);
            if (item != null) item.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // A stale posted callback must never resurrect the poll loop after teardown.
        destroyed = true;
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
        bindNavigation();
        buildSections();
        showSection(HapSections.Section.INICIO, false);
    }

    /** Wires the five rail entries to their section, in {@link HapSections.Section} order. */
    private void bindNavigation() {
        navItems.clear();
        for (HapSections.Section section : HapSections.Section.values()) {
            TextView item = findViewById(navItemId(section));
            item.setContentDescription(getString(R.string.nav_item_description, item.getText()));
            item.setOnClickListener(v -> showSection(section, false));
            navItems.add(item);
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
        homeScreen = new HomeScreen(this, homeListener(), buildHeaderPanel());
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
    private View buildSectionView(HapSections.Section section) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (HapSections.Panel panel : HapSections.panels(section)) {
            column.addView(
                    buildPanel(panel),
                    column.getChildCount() == 0 ? matchWrap() : matchWrapTop(20)
            );
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scroll;
    }

    /** Maps a panel of {@link HapSections} to its existing builder; nothing is re-implemented. */
    private View buildPanel(HapSections.Panel panel) {
        switch (panel) {
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
        };
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

        if (focusContent) {
            View entry = firstFocusableIn(sectionView(section));
            if (entry != null) entry.requestFocus();
        }
    }

    private TextView navItemFor(HapSections.Section section) {
        int index = section.ordinal();
        return index >= 0 && index < navItems.size() ? navItems.get(index) : null;
    }

    private View sectionView(HapSections.Section section) {
        return sectionViews.get(section.ordinal());
    }

    /**
     * D-pad movement between the rail and the content (S6 groundwork; T6 owns the full chain).
     *
     * <p>RIGHT on a rail entry enters the visible section, LEFT from a view that has nothing to
     * its left returns to the rail, and UP/DOWN are left to the platform so each column keeps its
     * own order. LEFT is only taken when the platform finds no target and the focused view is not
     * a text field, where LEFT belongs to the caret.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && !destroyed) {
            View focused = getCurrentFocus();
            if (focused != null) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && isInsideRail(focused)) {
                    View entry = firstFocusableIn(sectionView(currentSection));
                    if (entry != null) {
                        entry.requestFocus();
                        return true;
                    }
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                        && !(focused instanceof EditText)
                        && isInsideSection(focused)) {
                    View next = focused.focusSearch(View.FOCUS_LEFT);
                    if (next == null || next == focused) {
                        TextView item = navItemFor(currentSection);
                        if (item != null) {
                            item.requestFocus();
                            return true;
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isInsideRail(View view) {
        return isDescendantOf(view, navRail);
    }

    private boolean isInsideSection(View view) {
        return isDescendantOf(view, sectionView(currentSection));
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        if (view == null || ancestor == null) return false;
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** First focusable, visible view in reading order; used to enter a section from the rail. */
    private static View firstFocusableIn(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable()) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = firstFocusableIn(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

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

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(titleRow, matchWrap());

        titleRow.addView(text(getString(R.string.screen_title), 24, COLOR_TEXT_PRIMARY, Typeface.BOLD));
        statePill = new Pill(pill(getString(R.string.status_stopped), COLOR_ERROR_MUTED, COLOR_ERROR));
        titleRow.addView(statePill.view, wrapWrapLeft(10));
        phasePill = new Pill(pill(getString(R.string.status_idle), COLOR_SURFACE, COLOR_TEXT_SECONDARY));
        titleRow.addView(phasePill.view, wrapWrapLeft(8));

        runtimePillsContainer = new LinearLayout(this);
        runtimePillsContainer.setOrientation(LinearLayout.HORIZONTAL);
        runtimePillsContainer.setGravity(Gravity.CENTER_VERTICAL);
        runtimePillsContainer.setPadding(0, dp(10), 0, 0);
        left.addView(runtimePillsContainer, matchWrap());

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
        left.addView(runtimeView, matchWrap());

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

        switchRow.addView(text(getString(R.string.label_enable_hap), 13, COLOR_TEXT_SECONDARY, Typeface.BOLD));
        enabledSwitch = new Switch(this);
        enabledSwitch.setPadding(dp(8), 0, 0, 0);
        // The label sits next to the switch, so the control itself says what it does.
        enabledSwitch.setContentDescription(getString(R.string.hap_enable_switch_description));
        enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            setEnabled(checked);
        });
        busyControls.add(enabledSwitch);
        switchRow.addView(enabledSwitch, wrapWrap());

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        buttonRow.setPadding(0, dp(10), 0, 0);
        actions.addView(buttonRow, wrapWrap());

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

        // The advanced block is its own panel in Ajustes, so it is not nested twice here.
        return panel.container;
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

        return panel.container;
    }

    private View buildChannelStatusPanel() {
        PanelViews panel = panel(getString(R.string.section_channel_status), true, false);

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

        ScrollView channelsScroll = new ScrollView(this);
        channelsScroll.setFillViewport(false);
        channelsScroll.setFocusable(false);
        channelsScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        channelRowsContainer = new LinearLayout(this);
        channelRowsContainer.setOrientation(LinearLayout.VERTICAL);
        channelRowsContainer.setFocusable(false);
        channelsScroll.addView(channelRowsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        listParams.topMargin = dp(10);
        panel.body.addView(channelsScroll, listParams);
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

    /** Lightweight automatic check: once per configured interval, never during a download. */
    private void maybeRunAutomaticUpdateCheck() {
        if (destroyed || updateCheckInFlight || updateDownloadInProgress) return;
        if (!UpdateSettings.isAutomaticCheckDue(this, System.currentTimeMillis())) return;
        startUpdateCheck(false);
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
        updateActionsHadFocus = updateDownloadButton.hasFocus() || updateLaterButton.hasFocus();
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
        updateConfirmContainer.setVisibility(View.VISIBLE);
        // The confirmation is the next D-pad step: take focus only when the download was started
        // from the action row, never while the user is somewhere else on the screen.
        if (updateActionsHadFocus) {
            updateActionsHadFocus = false;
            updateInstallButton.requestFocus();
        }
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
        updateActionsHadFocus = false;
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
        try {
            snapshot = buildRuntimeSnapshot(HapBridge.status(this));
        } catch (Throwable ignored) {
            // A failed status read must not stop the 1s refresh loop.
        } finally {
            statusPollInFlight.set(false);
        }
        if (snapshot == null) return;
        RuntimeSnapshot ready = snapshot;
        main(() -> applyRuntimeSnapshot(ready));
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

        if (!sameEndpoints(renderedEndpoints, snapshot.endpoints)) renderEndpoints(snapshot.endpoints);

        if (!TextUtils.equals(renderedLogText, snapshot.logText)) {
            renderedLogText = snapshot.logText;
            logView.setText(snapshot.logText);
        }

        // Only the words that changed are re-set, so a refresh never rebuilds or re-focuses.
        if (homeScreen != null) homeScreen.applyServices(snapshot.services);
    }

    private void applyCaption(TextView view, String text) {
        boolean visible = text != null && !text.isEmpty();
        if (visible && !TextUtils.equals(text, view.getText())) view.setText(text);
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
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
    }

    /** Computed off the main thread; the LAN URLs come from the same status read. */
    private List<EndpointModel> endpointRows(HapBridge.HapStatus status) {
        List<EndpointModel> rows = new ArrayList<>();
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

    private void addEndpointRow(List<EndpointModel> rows, List<String> shown, String label, String url) {
        if (shown.contains(url)) return;
        shown.add(url);
        rows.add(new EndpointModel(label, url));
    }

    private boolean sameEndpoints(List<EndpointModel> left, List<EndpointModel> right) {
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!TextUtils.equals(left.get(i).label, right.get(i).label)) return false;
            if (!TextUtils.equals(left.get(i).url, right.get(i).url)) return false;
        }
        return true;
    }

    /** Rebuilds the endpoint rows; only reached when the rendered set actually changed. */
    private void renderEndpoints(List<EndpointModel> rows) {
        renderedEndpoints = rows;
        endpointsContainer.removeAllViews();
        for (EndpointModel endpoint : rows) addEndpointRow(endpoint.label, endpoint.url);
    }

    private void addEndpointRow(String label, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        row.addView(pill(label, COLOR_SURFACE, COLOR_TEXT_SECONDARY), wrapWrap());

        TextView value = text(url, 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        value.setPadding(dp(8), 0, dp(8), 0);
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = smallButton(getString(R.string.button_copy));
        copy.setOnClickListener(v -> copyToClipboard("HaP URL", url));
        row.addView(copy, wrapWrap());

        endpointsContainer.addView(row, matchWrap());
    }

    private void renderSources() {
        sourcesListContainer.removeAllViews();

        boolean hasM3uSources = false;
        for (HapBridge.SourceInfo source : currentSources) {
            if (!"M3U".equals(source.format)) continue;
            hasM3uSources = true;
            sourcesListContainer.addView(sourceRow(source), matchWrapTop(sourcesListContainer.getChildCount() == 0 ? 0 : 8));
        }

        if (!hasM3uSources) {
            sourcesListContainer.addView(text(getString(R.string.message_no_sources), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
        }

        HapBridge.SourceInfo custom = customSource();
        if (custom != null) {
            sourcesListContainer.addView(sourceRow(custom), matchWrapTop(10));
            // The custom channel list has its own panel in Fuentes now, so this only refreshes
            // it instead of building a second copy inside the sources panel.
            renderCustomChannels();
        }
    }

    private View sourceRow(HapBridge.SourceInfo source) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));

        CheckBox selected = new CheckBox(this);
        selected.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_BRAND));
        selected.setChecked(source.selected);
        selected.setOnCheckedChangeListener((buttonView, checked) -> setSourceSelected(source.id, checked));
        row.addView(selected, wrapWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(4), 0, dp(8), 0);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        left.addView(text(source.label, 14, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());
        TextView value = text("CUSTOM".equals(source.format) ? source.description : source.value, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        left.addView(value, matchWrap());

        if (source.removable) {
            Button edit = smallButton(getString(R.string.button_edit));
            edit.setOnClickListener(v -> editM3uSource(source));
            row.addView(edit, wrapWrapLeft(8));

            Button delete = smallButton(getString(R.string.button_delete));
            delete.setOnClickListener(v -> deleteM3uSource(source.id));
            row.addView(delete, wrapWrapLeft(8));
        }

        return row;
    }

    private void renderCustomChannels() {
        if (customListContainer == null) return;
        customListContainer.removeAllViews();
        if (currentCustomChannels.isEmpty()) {
            customListContainer.addView(text(getString(R.string.message_no_custom_channels), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
            return;
        }

        for (HapBridge.CustomChannelInfo channel : currentCustomChannels) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            left.addView(text(channel.name, 14, COLOR_TEXT_PRIMARY, Typeface.BOLD), matchWrap());
            TextView content = text(channel.contentId, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            content.setSingleLine(true);
            content.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(content, matchWrap());

            Button edit = smallButton(getString(R.string.button_edit));
            edit.setOnClickListener(v -> editCustomChannel(channel));
            row.addView(edit, wrapWrapLeft(8));

            Button delete = smallButton(getString(R.string.button_delete));
            delete.setOnClickListener(v -> deleteCustomChannel(channel.index));
            row.addView(delete, wrapWrapLeft(8));

            customListContainer.addView(row, matchWrapTop(customListContainer.getChildCount() == 0 ? 0 : 8));
        }
    }

    private void renderClients(List<HapBridge.ConnectedClientInfo> clients) {
        clientsContainer.removeAllViews();
        clientsStatusView.setText(clients.isEmpty()
                ? getString(R.string.message_no_active_clients)
                : getString(R.string.message_client_count, clients.size()));
        for (HapBridge.ConnectedClientInfo client : clients) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(10), 0, 0));

            row.addView(weightedText(client.ip, 0.18f, COLOR_TEXT_PRIMARY, true));
            row.addView(weightedText(client.device, 0.22f, COLOR_TEXT_SECONDARY, false));
            row.addView(weightedText(client.channel, 0.43f, COLOR_TEXT_SECONDARY, false));
            row.addView(text(String.format(Locale.US, "%.1f MB", client.megabytes), 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL), wrapWrap());

            clientsContainer.addView(row, matchWrapTop(clientsContainer.getChildCount() == 0 ? 0 : 8));
        }
    }

    private void renderChannelPlugins() {
        pluginTabsContainer.removeAllViews();
        for (HapBridge.PluginChannelsInfo plugin : channelPlugins) {
            boolean selected = plugin.id.equals(selectedPluginId);
            TextView tab = pill(plugin.label + " (" + plugin.channels.size() + ")",
                    selected ? COLOR_BRAND_MUTED : COLOR_SURFACE,
                    selected ? COLOR_BRAND : COLOR_TEXT_SECONDARY);
            tab.setBackground(focusBackground(
                    selected ? COLOR_BRAND_MUTED : COLOR_SURFACE,
                    COLOR_SURFACE_FOCUSED,
                    dp(999),
                    dp(1),
                    selected ? COLOR_BRAND : COLOR_STROKE
            ));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setMinHeight(dp(36));
            tab.setOnClickListener(v -> {
                selectedPluginId = plugin.id;
                selectedChannelContentId = plugin.channels.isEmpty() ? null : plugin.channels.get(0).contentId;
                renderChannelPlugins();
                renderChannels();
                renderChannelFeedback(null);
            });
            pluginTabsContainer.addView(tab, wrapWrapLeft(pluginTabsContainer.getChildCount() == 0 ? 0 : 8));
        }
        renderChannels();
    }

    private void renderChannels() {
        channelRowsContainer.removeAllViews();
        HapBridge.PluginChannelsInfo selectedPlugin = selectedPlugin();
        if (selectedPlugin == null) {
            channelRowsContainer.addView(text(getString(R.string.message_load_channels_hint), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
            return;
        }

        for (HapBridge.ChannelInfo channel : selectedPlugin.channels) {
            HapBridge.PeerResultInfo result = peerResults.get(channel.contentId);
            boolean selected = channel.contentId.equals(selectedChannelContentId);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(focusBackground(
                    selected ? COLOR_BRAND_MUTED : COLOR_PANEL_SOFT,
                    COLOR_SURFACE_FOCUSED,
                    dp(10),
                    dp(1),
                    selected ? COLOR_BRAND : COLOR_STROKE
            ));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                selectedChannelContentId = channel.contentId;
                renderChannels();
                checkChannel(channel.contentId, channel.name);
            });

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = text(channel.name, 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(name, matchWrap());

            TextView content = text(channel.contentId, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
            content.setSingleLine(true);
            content.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            left.addView(content, matchWrap());

            if (result != null) {
                TextView detail = text(peerDetailText(result), 12, peerColor(result), Typeface.NORMAL);
                detail.setMaxLines(2);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                left.addView(detail, matchWrap());
                row.addView(pill(peerShortStatus(result), result.available ? COLOR_SUCCESS_MUTED : COLOR_SURFACE, result.available ? COLOR_SUCCESS : COLOR_TEXT_TERTIARY), wrapWrapLeft(8));
            }

            channelRowsContainer.addView(row, matchWrapTop(channelRowsContainer.getChildCount() == 0 ? 0 : 8));
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
            channelFeedbackView.setVisibility(View.GONE);
        } else {
            channelFeedbackView.setText(feedback);
            channelFeedbackView.setVisibility(View.VISIBLE);
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

    /** Builds the gateway row once, then only the value text is updated in place. */
    private void applyIpfsGateway(String url) {
        String value = url == null ? "" : url;
        if (value.isEmpty()) {
            ipfsGatewayValue = "";
            if (ipfsGatewayContainer.getChildCount() > 0) {
                ipfsGatewayContainer.removeAllViews();
                ipfsGatewayValueView = null;
            }
            return;
        }

        ipfsGatewayValue = value;
        if (ipfsGatewayValueView != null) {
            if (!TextUtils.equals(value, ipfsGatewayValueView.getText())) {
                ipfsGatewayValueView.setText(value);
            }
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(pill(getString(R.string.label_ipfs_gateway_url), COLOR_SURFACE, COLOR_TEXT_SECONDARY), wrapWrap());

        TextView valueView = text(value, 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        valueView.setPadding(dp(8), 0, dp(8), 0);
        row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = smallButton(getString(R.string.button_copy));
        copy.setOnClickListener(v -> copyToClipboard("HaP IPFS gateway", ipfsGatewayValue));
        row.addView(copy, wrapWrap());

        ipfsGatewayValueView = valueView;
        ipfsGatewayContainer.addView(row, matchWrap());
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

    private void editM3uSource(HapBridge.SourceInfo source) {
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

    private void editCustomChannel(HapBridge.CustomChannelInfo channel) {
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
        clientsStatusView.setText(getString(R.string.message_loading_clients));
        workExecutor.execute(() -> {
            try {
                List<HapBridge.ConnectedClientInfo> clients = HapBridge.connectedClients();
                main(() -> renderClients(clients));
            } catch (Throwable error) {
                main(() -> {
                    clientsStatusView.setText(errorMessage(error));
                    clientsContainer.removeAllViews();
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
        container.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
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
        List<EndpointModel> endpoints;
        String logText;
        HapServiceStates services;
    }

    private static final class EndpointModel {
        final String label;
        final String url;

        EndpointModel(String label, String url) {
            this.label = label;
            this.url = url;
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
