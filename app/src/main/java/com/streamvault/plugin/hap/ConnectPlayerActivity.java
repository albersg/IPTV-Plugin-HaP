package com.streamvault.plugin.hap;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.jopsis.httpaceserveproxy.HapBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Cómo ver mis canales": how to point an external player at HaP.
 *
 * <p>This is the screen behind the home screen's dominant action, and it exists to state one thing
 * plainly: <b>HaP is a server, not a player</b> (S4). It has no player, no playback and no network
 * call of its own; it only explains, and shows the address the player needs.
 *
 * <p>The address is never composed here. {@link HapBridge#aioUrl} is the same {@code /aio} URL the
 * proxy serves, and {@link HapBridge#rewriteLocalUrlForLan} is the same LAN rewrite Cast and the
 * connections panel use, so this screen cannot advertise an address the proxy is not serving, and
 * it follows the LAN switch without a second source of truth.
 *
 * <p>The layout is inflated once and afterwards only re-labelled: a refresh touches the four texts
 * and two visibilities that can change, so it never rebuilds the tree, never moves focus and never
 * resets scroll (S6, S7). The runtime read runs on a daemon executor exactly like the shell's, so
 * nothing blocks the main thread, and every reference it holds is released in {@link #onDestroy()}.
 */
public class ConnectPlayerActivity extends Activity {

    /** Same cadence as the shell, so this screen and Inicio agree within a second. */
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean statusPollInFlight = new AtomicBoolean(false);
    private volatile boolean destroyed;
    private ExecutorService statusExecutor;

    private TextView serverStateView;
    private TextView serverBodyView;
    private View serverActionView;
    private TextView localAddressView;
    private View lanBlockView;
    private TextView lanAddressView;
    private TextView lanNoteView;

    private HapServiceStates.ServerState appliedServer;
    private String appliedAddress;
    private String appliedLanAddress;
    private boolean appliedLanMode;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            refreshRuntimeState();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connect_player);

        serverStateView = findViewById(R.id.connect_server_state);
        serverBodyView = findViewById(R.id.connect_server_body);
        serverActionView = findViewById(R.id.connect_server_action);
        localAddressView = findViewById(R.id.connect_local_address);
        lanBlockView = findViewById(R.id.connect_lan_block);
        lanAddressView = findViewById(R.id.connect_lan_address);
        lanNoteView = findViewById(R.id.connect_lan_note);

        serverActionView.setOnClickListener(v -> turnOnServer());

        statusExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "hap-connect-player-status");
            thread.setDaemon(true);
            return thread;
        });

        // The local address is a compile-time constant of the proxy, so the first paint needs no
        // I/O at all. Only the LAN row waits for the background read, and it stays hidden until the
        // read says the proxy is actually serving on a LAN address.
        bindAddress(HapBridge.aioUrl(this));
        localAddressView.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        // Starts the 1 Hz loop and reads the state immediately, so a server started or stopped
        // while this screen was in the background is never shown stale.
        refreshRunnable.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // A posted callback or an in-flight status read must never touch a view after teardown.
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        statusExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * Starts the server through exactly the entry point the home switch uses, so there is one
     * start path, not two. It posts a foreground-service intent; no disk or network work happens
     * on the main thread here.
     */
    private void turnOnServer() {
        HapBridge.start(this);
        // Immediate feedback, and it also rules out a second tap: the next tick shows the state.
        setVisible(serverActionView, false);
    }

    /**
     * Reads the runtime state and both addresses off the main thread.
     *
     * <p>{@link HapBridge#status} copies the service log under its lock, reads SharedPreferences and
     * enumerates the network interfaces in LAN mode, so it runs on the executor and the result is
     * applied on the main thread. At most one read is in flight, so a slow read cannot pile up.
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
        ScreenState state = null;
        try {
            state = readScreenState();
        } catch (Throwable ignored) {
            // A failed status read must not stop the refresh loop.
        } finally {
            statusPollInFlight.set(false);
        }
        if (state == null || destroyed) return;
        ScreenState ready = state;
        handler.post(() -> applyState(ready));
    }

    /** The real address, the real LAN rewrite and the real state words; no string is composed. */
    private ScreenState readScreenState() {
        HapBridge.HapStatus status = HapBridge.status(this);
        String address = HapBridge.aioUrl(this);
        String lanAddress = HapBridge.rewriteLocalUrlForLan(this, address);
        HapServiceStates states = HapServiceStates.of(
                status.desiredRunning,
                status.aceRunning,
                status.proxyRunning,
                !status.lastError.isEmpty(),
                status.ipfsEnabled,
                status.ipfsRunning,
                !status.ipfsError.isEmpty()
        );
        // The rewrite returns the local URL unchanged when there is no LAN address to use, which is
        // the signal that the proxy is not reachable from the network right now.
        boolean reachableFromLan = !TextUtils.equals(address, lanAddress);
        return new ScreenState(
                states.server,
                address,
                status.serverModeEnabled,
                reachableFromLan ? lanAddress : ""
        );
    }

    /** Applies a snapshot, touching only the views whose value actually changed (S7). */
    private void applyState(ScreenState state) {
        if (destroyed || state == null) return;

        if (appliedServer != state.server) {
            appliedServer = state.server;
            serverStateView.setText(HomeScreen.serverReady(state.server));
            serverBodyView.setText(HomeScreen.serverBody(state.server));
            setVisible(serverActionView, canStart(state.server));
        }
        if (!TextUtils.equals(appliedAddress, state.address)) {
            bindAddress(state.address);
        }
        if (appliedLanMode != state.lanModeEnabled
                || !TextUtils.equals(appliedLanAddress, state.lanAddress)) {
            appliedLanMode = state.lanModeEnabled;
            appliedLanAddress = state.lanAddress;
            applyLan(state);
        }
    }

    private void bindAddress(String address) {
        appliedAddress = address;
        localAddressView.setText(address);
        localAddressView.setContentDescription(
                addressDescription(R.string.connect_address_local, address)
        );
    }

    /**
     * LAN mode is the difference between "another device can watch this" and "only this device
     * can", so both cases are said out loud instead of leaving the user with a missing row:
     * off means say where the switch lives, on without an address means the network is the problem.
     */
    private void applyLan(ScreenState state) {
        boolean hasLanAddress = !state.lanAddress.isEmpty();
        if (hasLanAddress) {
            lanAddressView.setText(state.lanAddress);
            lanAddressView.setContentDescription(
                    addressDescription(R.string.connect_address_lan, state.lanAddress)
            );
        }
        setVisible(lanBlockView, hasLanAddress);

        if (!state.lanModeEnabled) {
            showLanNote(R.string.connect_lan_off, R.color.hap_text_muted);
        } else if (!hasLanAddress) {
            showLanNote(R.string.connect_lan_no_address, R.color.hap_warning);
        } else {
            setVisible(lanNoteView, false);
        }
    }

    private void showLanNote(int textRes, int colorRes) {
        lanNoteView.setText(textRes);
        if (lanNoteView.getCurrentTextColor() != getColor(colorRes)) {
            lanNoteView.setTextColor(getColor(colorRes));
        }
        setVisible(lanNoteView, true);
    }

    private CharSequence addressDescription(int labelRes, String address) {
        return getString(R.string.home_card_description, getString(labelRes), address);
    }

    /** The action only makes sense while nothing is running yet: stopped or failed. */
    private static boolean canStart(HapServiceStates.ServerState state) {
        return state == HapServiceStates.ServerState.STOPPED || state == HapServiceStates.ServerState.ERROR;
    }

    /**
     * Shows or hides a block without ever stranding focus: a block that disappears while it holds
     * the focus hands it to the address, which is always on screen.
     */
    private void setVisible(View view, boolean visible) {
        int target = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() == target) return;
        // hasFocus() is true for the focused view and for any of its ancestors.
        boolean hadFocus = view.hasFocus();
        view.setVisibility(target);
        if (!visible && hadFocus) localAddressView.requestFocus();
    }

    /** The immutable result of one background read, handed to the main thread as a whole. */
    private static final class ScreenState {
        final HapServiceStates.ServerState server;
        final String address;
        final boolean lanModeEnabled;
        /** Empty when LAN mode is off or when the proxy is not reachable from the network. */
        final String lanAddress;

        ScreenState(
                HapServiceStates.ServerState server,
                String address,
                boolean lanModeEnabled,
                String lanAddress
        ) {
            this.server = server;
            this.address = address;
            this.lanModeEnabled = lanModeEnabled;
            this.lanAddress = lanAddress == null ? "" : lanAddress;
        }
    }
}
