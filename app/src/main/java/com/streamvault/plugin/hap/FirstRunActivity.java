package com.streamvault.plugin.hap;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jopsis.httpaceserveproxy.HapBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The first-run assistant (T8): turn the server on, add or pick a list, connect a player.
 *
 * <p>It exists as its own activity for one reason: it is offered <b>only</b> while HaP cannot serve
 * anything yet, so it must not be a sixth rail section and must not be a dialog chain over
 * {@code Inicio}. {@link FirstRunDecision} owns the rule that offers it, {@link FirstRunStore} owns
 * the facts it reads, and the home screen shows it as one card and nothing more.
 *
 * <p>Three steps, one scrollable screen, every one of them optional:
 * <ul>
 *   <li><b>Step 1</b> shows the real server state through {@link HapServiceStates} in the same words
 *       the home screen uses, and starts it through {@link HapBridge#start}, the single start path
 *       the home switch and "Cómo ver mis canales" also use. There is no second start route.</li>
 *   <li><b>Step 2</b> lists the lists the user already has and adds a new one through
 *       {@link HapBridge#saveM3uSource}, which is the existing validation flow: no address is
 *       checked here, and an invalid one reports the same message Fuentes would report, plus what
 *       to do next.</li>
 *   <li><b>Step 3</b> opens {@link ConnectPlayerActivity} instead of repeating its addresses, so
 *       there is one place that says which URL a player needs (S4).</li>
 * </ul>
 *
 * <p><b>Never traps the user.</b> Every step can be skipped by not pressing its action, "not now"
 * closes the assistant and records the dismissal, "finish" records the completion, and BACK leaves
 * from any step without recording anything — the card is then still on Inicio, one focus away.
 * Neither a dismissal nor a completion is ever cleared, so the assistant never reappears on its
 * own.
 *
 * <p><b>Nothing blocking on the main thread.</b> Counting the saved lists, the 1 Hz status read and
 * the save itself (which validates over the network) all run on daemon executors and apply their
 * result on the main thread; at most one status read is in flight, so a slow read cannot queue.
 * The layout is inflated once and afterwards only re-labelled, so a refresh or a saved list that
 * grows never rebuilds the tree and never moves the focus (S6, S7).
 *
 * <p><b>D-pad only (S6).</b> {@link FirstRunFocus} owns UP/DOWN over the column of this screen,
 * because the rail and the shell's {@link FocusNavigator} do not exist here. OK activates, BACK
 * leaves, and the text fields keep LEFT/RIGHT for their caret. On open the first control the
 * column has takes the focus: the step 1 action when the server is off, and the address field when
 * it is already running, which is the next thing to do in that case.
 */
public class FirstRunActivity extends Activity {

    /** Same cadence as the shell, so step 1 and Inicio agree within a second. */
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Writes and validation, which reads a file and validates over the network. */
    private final ExecutorService workExecutor = newSingleThread("hap-first-run-work");
    /** The 1 Hz status read, on its own thread so a validation never delays it. */
    private final ExecutorService statusExecutor = newSingleThread("hap-first-run-status");
    private final AtomicBoolean statusPollInFlight = new AtomicBoolean(false);
    private volatile boolean destroyed;

    private FirstRunFocus focus;

    private TextView serverStateView;
    private TextView serverBodyView;
    private View serverActionView;

    private TextView savedHeadingView;
    private LinearLayout savedListContainer;
    private InPlaceList<HapRows.Source> savedList;

    private EditText sourceNameEdit;
    private EditText sourceUrlEdit;
    private View sourceSaveView;
    private TextView sourceFeedbackView;

    private View playerRowView;

    private HapServiceStates.ServerState appliedServer;
    /** One save at a time: a second press while validating would validate the same URL twice. */
    private boolean saveInFlight;

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
        setContentView(R.layout.activity_first_run);

        serverStateView = findViewById(R.id.first_run_server_state);
        serverBodyView = findViewById(R.id.first_run_server_body);
        serverActionView = findViewById(R.id.first_run_server_action);
        savedHeadingView = findViewById(R.id.first_run_saved_heading);
        savedListContainer = findViewById(R.id.first_run_saved_list);
        sourceNameEdit = findViewById(R.id.first_run_source_name);
        sourceUrlEdit = findViewById(R.id.first_run_source_url);
        sourceSaveView = findViewById(R.id.first_run_source_save);
        sourceFeedbackView = findViewById(R.id.first_run_source_feedback);
        playerRowView = findViewById(R.id.first_run_player);
        View finishView = findViewById(R.id.first_run_finish);
        View dismissView = findViewById(R.id.first_run_dismiss);

        focus = new FirstRunFocus(findViewById(R.id.first_run_root), findViewById(R.id.first_run_content));

        serverActionView.setOnClickListener(v -> turnOnServer());
        sourceSaveView.setOnClickListener(v -> saveSource());
        playerRowView.setContentDescription(activityDescription());
        playerRowView.setOnClickListener(v -> openHowToWatch());
        finishView.setOnClickListener(v -> close(true));
        dismissView.setOnClickListener(v -> close(false));

        savedList = new InPlaceList<>(savedListContainer, SavedSourceRow::new);

        loadSavedSources();
        focus.focusFirst();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        // Reads the state immediately, so a server started elsewhere is never shown stale.
        refreshRunnable.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // A posted callback or an in-flight read must never touch a view after teardown.
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        statusExecutor.shutdownNow();
        workExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * The one entry point of every D-pad press on this screen. UP/DOWN are resolved over the live
     * column by {@link FirstRunFocus}; every other key (OK, BACK, the caret keys of the text
     * fields) keeps its own behaviour, so nothing here can trap the user.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!destroyed && focus != null && focus.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ the three steps

    /**
     * Step 1: starts the server through the existing entry point, so there is one start path.
     * It posts a foreground-service intent; no disk or network work happens on the main thread.
     */
    private void turnOnServer() {
        HapBridge.start(this);
        // Immediate feedback, and it also rules out a second press: the next tick shows the state.
        setVisible(serverActionView, false);
    }

    /** Step 3: the screen that explains how to point an external player at HaP. */
    private void openHowToWatch() {
        startActivity(new Intent(this, ConnectPlayerActivity.class));
    }

    /**
     * Step 2: adds a list through the existing validation flow.
     *
     * <p>{@link HapBridge#saveM3uSource} is exactly what "Añadir fuente" in Fuentes calls, so the
     * URL is validated once, by one owner, and the success or error message the user sees is the
     * one that flow produces. Only the "what to do next" line is added here (S5).
     */
    private void saveSource() {
        if (saveInFlight) return;
        saveInFlight = true;
        String name = sourceNameEdit.getText().toString();
        String url = sourceUrlEdit.getText().toString();
        showFeedback(getString(R.string.first_run_saving), R.color.hap_text_muted);

        workExecutor.execute(() -> {
            HapBridge.SaveResult result;
            try {
                result = HapBridge.saveM3uSource(getApplicationContext(), "", name, url);
            } catch (Throwable error) {
                result = null;
            }
            HapBridge.SaveResult ready = result;
            main(() -> applySaveResult(ready));
        });
    }

    private void applySaveResult(HapBridge.SaveResult result) {
        saveInFlight = false;
        if (destroyed) return;
        if (result != null && result.success) {
            sourceNameEdit.setText("");
            sourceUrlEdit.setText("");
            showFeedback(result.message, R.color.hap_success);
            loadSavedSources();
            return;
        }
        String message = result != null && !result.message.isEmpty()
                ? result.message
                : getString(R.string.first_run_error_generic);
        showFeedback(message + "\n" + getString(R.string.first_run_error_next), R.color.hap_error);
    }

    /** Marks a list the user already has as the active one, through the existing selection path. */
    private void useSource(String id, String label) {
        workExecutor.execute(() -> {
            String message;
            int color;
            try {
                HapBridge.setSourceSelected(getApplicationContext(), id, true);
                message = getString(R.string.first_run_source_active, label);
                color = R.color.hap_success;
            } catch (Throwable error) {
                message = getString(R.string.first_run_error_generic);
                color = R.color.hap_error;
            }
            String readyMessage = message;
            int readyColor = color;
            main(() -> {
                if (destroyed) return;
                showFeedback(readyMessage, readyColor);
                loadSavedSources();
            });
        });
    }

    /**
     * Closes the assistant and records the user's answer.
     *
     * <p>The write is a preferences commit, so it runs off the main thread and the screen closes
     * when it is done. {@code completed} false means "not now". Neither decision is ever cleared.
     */
    private void close(boolean completed) {
        workExecutor.execute(() -> {
            if (completed) FirstRunStore.markCompleted(getApplicationContext());
            else FirstRunStore.markDismissed(getApplicationContext());
            main(() -> {
                if (!destroyed) finish();
            });
        });
    }

    // ------------------------------------------------------------------ saved lists

    /**
     * Reads the lists the user already has, off the main thread, and shows them at the top of
     * step 2. The generated {@code Custom} list is not one of them: it is managed in Fuentes.
     */
    private void loadSavedSources() {
        if (destroyed) return;
        workExecutor.execute(() -> {
            List<HapRows.Source> rows = new ArrayList<>();
            try {
                for (HapBridge.SourceInfo source : HapBridge.sources(getApplicationContext())) {
                    if (!source.removable) continue;
                    rows.add(new HapRows.Source(
                            source.id,
                            source.label,
                            source.value,
                            source.value,
                            source.selected,
                            true
                    ));
                }
            } catch (Throwable ignored) {
                // A failed read leaves the list as it is; the URL field below still works.
            }
            List<HapRows.Source> ready = rows;
            main(() -> applySavedSources(ready));
        });
    }

    /**
     * Applies the saved list. {@link InPlaceList} rebinds instead of rebuilding, so a list that
     * grows after a save keeps every existing row and its focus.
     */
    private void applySavedSources(List<HapRows.Source> rows) {
        if (destroyed) return;
        boolean any = rows != null && !rows.isEmpty();
        savedList.apply(rows);
        setVisible(savedHeadingView, any);
        setVisible(savedListContainer, any);
    }

    // ------------------------------------------------------------------ live server state

    /**
     * Schedules a runtime read. {@link HapBridge#status} copies the service log, reads
     * SharedPreferences and can enumerate the network interfaces, so it runs on the executor and
     * the result is applied on the main thread.
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
        HapServiceStates.ServerState state = null;
        try {
            state = readServerState();
        } catch (Throwable ignored) {
            // A failed status read must not stop the refresh loop.
        } finally {
            statusPollInFlight.set(false);
        }
        if (state == null || destroyed) return;
        HapServiceStates.ServerState ready = state;
        main(() -> applyServerState(ready));
    }

    /** The same four states the home card shows, from the same snapshot factory. */
    private HapServiceStates.ServerState readServerState() {
        HapBridge.HapStatus status = HapBridge.status(this);
        return HapServiceStates.of(
                status.desiredRunning,
                status.aceRunning,
                status.proxyRunning,
                !status.lastError.isEmpty(),
                status.ipfsEnabled,
                status.ipfsRunning,
                !status.ipfsError.isEmpty()
        ).server;
    }

    /** Writes only the words that changed, so a 1 Hz tick never moves focus (S7). */
    private void applyServerState(HapServiceStates.ServerState state) {
        if (destroyed || state == null || state == appliedServer) return;
        appliedServer = state;
        applyText(serverStateView, getString(HomeScreen.serverReady(state)));
        applyText(serverBodyView, getString(HomeScreen.serverBody(state)));
        // The action only makes sense while nothing is running yet: stopped or failed.
        setVisible(serverActionView, state == HapServiceStates.ServerState.STOPPED
                || state == HapServiceStates.ServerState.ERROR);
    }

    /**
     * Writes a label only when the view does not already say it: an unchanged tick reaches neither
     * {@code setText} nor a re-layout.
     */
    private void applyText(TextView view, String text) {
        String value = text == null ? "" : text;
        if (ListPatch.needsText(view.getText(), value)) view.setText(value);
    }

    /**
     * Shows or hides a block without ever stranding focus: a block that disappears while it holds
     * the focus hands it to the first control still on screen.
     */
    private void setVisible(View view, boolean visible) {
        int target = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() == target) return;
        // hasFocus() is true for the focused view and for any of its ancestors.
        boolean hadFocus = view.hasFocus();
        view.setVisibility(target);
        if (!visible && hadFocus && focus != null) focus.focusFirst();
    }

    private void showFeedback(String message, int colorRes) {
        if (destroyed) return;
        applyText(sourceFeedbackView, message);
        if (sourceFeedbackView.getCurrentTextColor() != getColor(colorRes)) {
            sourceFeedbackView.setTextColor(getColor(colorRes));
        }
        setVisible(sourceFeedbackView, true);
    }

    private CharSequence activityDescription() {
        return getString(
                R.string.home_card_description,
                getString(R.string.home_cta_title),
                getString(R.string.home_cta_subtitle)
        );
    }

    private void main(Runnable runnable) {
        handler.post(runnable);
    }

    // ------------------------------------------------------------------ rows

    /**
     * One list the user already has: its name, its address, and OK to make it the active one.
     * Focus is the shared {@code bg_panel} outline, exactly like every other card of the app.
     */
    private final class SavedSourceRow implements InPlaceList.Row<HapRows.Source> {

        private final LinearLayout view;
        private final TextView nameView;
        private final TextView addressView;
        private String id = "";
        private String label = "";

        SavedSourceRow() {
            view = new LinearLayout(FirstRunActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setBackgroundResource(R.drawable.bg_panel);
            int padding = space(R.dimen.hap_card_padding);
            view.setPadding(padding, padding, padding, padding);
            view.setFocusable(true);
            view.setClickable(true);
            view.setDefaultFocusHighlightEnabled(false);
            view.setOnClickListener(v -> useSource(id, label));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = space(R.dimen.hap_gap_row);
            view.setLayoutParams(params);

            nameView = label(R.style.TextAppearance_HaP_Body, R.color.hap_text_primary);
            view.addView(nameView, matchWrap());

            addressView = label(R.style.TextAppearance_HaP_Caption, R.color.hap_text_muted);
            addressView.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
            view.addView(addressView, matchWrap());
        }

        @Override
        public View view() {
            return view;
        }

        @Override
        public void bind(HapRows.Source source) {
            if (source == null) return;
            id = source.id;
            label = source.label;
            applyText(nameView, source.label);
            applyText(addressView, source.secondary);
            view.setContentDescription(source.label + ". " + source.secondary);
        }

        private TextView label(int appearanceRes, int colorRes) {
            TextView text = new TextView(FirstRunActivity.this);
            text.setTextAppearance(appearanceRes);
            text.setTextColor(getColor(colorRes));
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            return text;
        }

        private LinearLayout.LayoutParams matchWrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private int space(int dimenRes) {
        return getResources().getDimensionPixelSize(dimenRes);
    }
}
