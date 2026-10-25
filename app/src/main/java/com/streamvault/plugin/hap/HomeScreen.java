package com.streamvault.plugin.hap;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The {@code Inicio} section: server status, one dominant action, two shortcuts and a discreet
 * service row.
 *
 * <p>The screen is built once and then only re-labelled. {@link #applyServices} is called from
 * the activity's 1 Hz refresh and touches exactly the five {@link TextView}s and the status dot
 * that can change, so a refresh can never drop D-pad focus or scroll position (S7).
 *
 * <p>Every word that describes the runtime comes from {@link HapServiceStates}: nothing here is
 * hardcoded, and the four server states are plain-language sentences that say what happened and
 * what to do next (S3, S5).
 *
 * <p>One card is conditional: the first-run assistant (T8). It is created with the rest of the
 * screen and only shown or hidden, so the approved order above is exactly what the screen shows
 * once the assistant is no longer needed.
 */
public final class HomeScreen {

    /** What the home screen asks the activity to do. */
    public interface Listener {
        /** The dominant action: explain how to point an external player at HaP. */
        void onOpenHowToWatch();

        /** The "Mis listas" shortcut. */
        void onOpenSources();

        /** The "Comprobar canales" shortcut. */
        void onOpenChannels();

        /**
         * The first-run card: the assistant that turns the server on, adds a list and connects a
         * player, offered only while HaP has nothing to serve and the user has not closed it.
         */
        void onOpenFirstRun();
    }

    private final Activity activity;
    private final ScrollView root;

    /**
     * The first-run card. Created once and hidden, so offering it and taking the offer away is a
     * visibility change: when it is hidden it is not a focus cell either, and the approved home
     * order (S3) is exactly what the screen shows.
     */
    private final LinearLayout firstRunCard;

    private final TextView serverHeadingView;
    private final TextView serverReadyView;
    private final TextView serverBodyView;
    private final View serverDotView;
    private final TextView aceServeStateView;
    private final TextView proxyStateView;
    private final TextView ipfsStateView;

    private HapServiceStates.ServerState appliedServer;
    private HapServiceStates.ServiceState appliedAceServe;
    private HapServiceStates.ServiceState appliedProxy;
    private HapServiceStates.ServiceState appliedIpfs;

    /**
     * @param activity       the host activity, used for resources and as the view context
     * @param listener       navigation callbacks
     * @param statusCardBody the existing header panel, with the start/stop switch and its
     *                       actions; it keeps its behaviour and only loses its own card chrome
     *                       because it now lives inside the status card
     */
    public HomeScreen(Activity activity, Listener listener, View statusCardBody) {
        this.activity = activity;

        LinearLayout content = column();
        content.setPadding(0, 0, 0, space(R.dimen.hap_gap_block));

        TextView title = label(R.string.home_title, R.style.TextAppearance_HaP_Display);
        content.addView(title, matchWrap());

        TextView subtitle = label(R.string.home_subtitle, R.style.TextAppearance_HaP_Body);
        subtitle.setTextColor(color(R.color.hap_text_muted));
        subtitle.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        content.addView(subtitle, matchWrap());

        // 1. Status card: heading with the status dot, the two plain-language lines, and the
        // existing header panel (start/stop, restart, prepare) that keeps its behaviour.
        LinearLayout statusCard = card(false, 0);
        LinearLayout headingRow = row(Gravity.CENTER_VERTICAL);
        serverDotView = new View(activity);
        serverDotView.setBackgroundResource(R.drawable.dot_status_off);
        serverDotView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        headingRow.addView(serverDotView, new LinearLayout.LayoutParams(
                space(R.dimen.hap_status_dot),
                space(R.dimen.hap_status_dot)
        ));
        serverHeadingView = label(0, R.style.TextAppearance_HaP_Title);
        serverHeadingView.setPadding(space(R.dimen.hap_gap_row), 0, 0, 0);
        headingRow.addView(serverHeadingView, weight(1f, 0));
        statusCard.addView(headingRow, matchWrap());

        serverReadyView = label(0, R.style.TextAppearance_HaP_Body);
        serverReadyView.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        statusCard.addView(serverReadyView, matchWrap());

        serverBodyView = label(0, R.style.TextAppearance_HaP_Body);
        serverBodyView.setTextColor(color(R.color.hap_text_muted));
        serverBodyView.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        statusCard.addView(serverBodyView, matchWrap());

        // The header panel brought its own card chrome; the status card is the card now.
        statusCardBody.setBackgroundResource(0);
        statusCardBody.setPadding(0, space(R.dimen.hap_gap_block), 0, 0);
        statusCard.addView(statusCardBody, matchWrap());
        content.addView(statusCard, top(matchWrap(), R.dimen.hap_gap_block));

        // The first-run card sits immediately under the status card and only while it is needed:
        // nothing is configured yet, so the most useful next action is the assistant, and it is one
        // focusable card the user can ignore (T8).
        firstRunCard = card(true, R.drawable.bg_panel);
        firstRunCard.setContentDescription(
                description(R.string.first_run_card_title, R.string.first_run_card_subtitle));
        firstRunCard.setOnClickListener(v -> listener.onOpenFirstRun());
        addCardText(
                firstRunCard, R.string.first_run_card_title, R.string.first_run_card_subtitle);
        firstRunCard.setVisibility(View.GONE);
        content.addView(firstRunCard, top(matchWrap(), R.dimen.hap_gap_block));

        // 2. The one dominant action: the largest, brightest element of the screen.
        LinearLayout cta = new LinearLayout(activity);
        cta.setOrientation(LinearLayout.HORIZONTAL);
        cta.setGravity(Gravity.CENTER_VERTICAL);
        cta.setBackgroundResource(R.drawable.bg_cta);
        int ctaPadding = space(R.dimen.hap_card_padding);
        cta.setPadding(ctaPadding, ctaPadding, ctaPadding, ctaPadding);
        focusable(cta);
        cta.setContentDescription(description(R.string.home_cta_title, R.string.home_cta_subtitle));
        cta.setOnClickListener(v -> listener.onOpenHowToWatch());

        LinearLayout ctaText = column();
        ctaText.addView(label(R.string.home_cta_title, R.style.TextAppearance_HaP_Title), matchWrap());
        TextView ctaSubtitle = label(R.string.home_cta_subtitle, R.style.TextAppearance_HaP_Body);
        ctaSubtitle.setTextColor(color(R.color.hap_text_on_accent));
        ctaSubtitle.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        ctaText.addView(ctaSubtitle, matchWrap());
        cta.addView(ctaText, weight(1f, 0));

        ImageView chevron = new ImageView(activity);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cta.addView(chevron, new LinearLayout.LayoutParams(
                space(R.dimen.hap_chevron),
                space(R.dimen.hap_chevron)
        ));
        content.addView(cta, top(matchWrap(), R.dimen.hap_gap_block));

        // 3. Two secondary cards, side by side, that switch section.
        LinearLayout shortcuts = row(Gravity.TOP);
        LinearLayout lists = card(true, R.drawable.bg_panel);
        lists.setContentDescription(description(R.string.home_lists_title, R.string.home_lists_subtitle));
        lists.setOnClickListener(v -> listener.onOpenSources());
        addCardText(lists, R.string.home_lists_title, R.string.home_lists_subtitle);
        shortcuts.addView(lists, weight(1f, 0));

        LinearLayout channels = card(true, R.drawable.bg_panel);
        channels.setContentDescription(description(R.string.home_channels_title, R.string.home_channels_subtitle));
        channels.setOnClickListener(v -> listener.onOpenChannels());
        addCardText(channels, R.string.home_channels_title, R.string.home_channels_subtitle);
        LinearLayout.LayoutParams channelsParams = weight(1f, 0);
        channelsParams.leftMargin = space(R.dimen.hap_gap_block);
        shortcuts.addView(channels, channelsParams);
        content.addView(shortcuts, top(matchWrap(), R.dimen.hap_gap_block));

        // 4. Service status: names and static details here, the state word from the snapshot.
        TextView servicesHeading = label(R.string.services_heading, R.style.TextAppearance_HaP_Subtitle);
        servicesHeading.setTextColor(color(R.color.hap_text_muted));
        content.addView(servicesHeading, top(matchWrap(), R.dimen.hap_gap_block));

        LinearLayout servicesCard = card(false, 0);
        aceServeStateView = addServiceRow(
                servicesCard, R.string.label_aceserve, R.string.service_aceserve_detail);
        proxyStateView = addServiceRow(
                servicesCard, R.string.service_proxy_name, R.string.service_proxy_detail);
        ipfsStateView = addServiceRow(
                servicesCard, R.string.service_ipfs_name, R.string.service_ipfs_detail);
        content.addView(servicesCard, top(matchWrap(), R.dimen.hap_gap_row));

        root = new ScrollView(activity);
        root.setFillViewport(false);
        root.setFocusable(false);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // A first, honest paint instead of an empty card if no snapshot has arrived yet.
        applyServices(HapServiceStates.of(false, false, false, false, false, false, false));
    }

    /** The view to put into the section container. */
    public View getView() {
        return root;
    }

    /**
     * Applies the words of the live runtime snapshot, touching only what changed.
     *
     * <p>Called from the activity's refresh, which runs every second: an unchanged tick sets no
     * text and swaps no drawable, so it costs no allocation and never moves focus.
     */
    void applyServices(HapServiceStates states) {
        if (states == null) return;

        if (appliedServer != states.server) {
            appliedServer = states.server;
            serverHeadingView.setText(serverHeading(states.server));
            serverReadyView.setText(serverReady(states.server));
            serverBodyView.setText(serverBody(states.server));
            serverDotView.setBackgroundResource(serverDot(states.server));
        }
        if (appliedAceServe != states.aceServe) {
            appliedAceServe = states.aceServe;
            bindStateWord(aceServeStateView, states.aceServe);
        }
        if (appliedProxy != states.proxy) {
            appliedProxy = states.proxy;
            bindStateWord(proxyStateView, states.proxy);
        }
        if (appliedIpfs != states.ipfs) {
            appliedIpfs = states.ipfs;
            bindStateWord(ipfsStateView, states.ipfs);
        }
    }

    /**
     * Offers or withdraws the first-run card. Called with the answer of {@link FirstRunDecision},
     * computed off the main thread, and writing nothing when the answer did not change: an
     * unchanged tick touches no view, so it can never move the focus that is on the card (T8).
     */
    void applyFirstRunOffer(boolean offered) {
        int target = offered ? View.VISIBLE : View.GONE;
        if (firstRunCard.getVisibility() != target) firstRunCard.setVisibility(target);
    }

    private void bindStateWord(TextView view, HapServiceStates.ServiceState state) {
        view.setText(stateWord(state));
        view.setTextColor(color(stateColor(state)));
    }

    private static int stateWord(HapServiceStates.ServiceState state) {
        switch (state) {
            case ACTIVE:
                return R.string.service_state_active;
            case ACTIVE_FEMININE:
                return R.string.service_state_active_feminine;
            case DISABLED:
                return R.string.service_state_disabled;
            case STARTING:
                return R.string.service_state_starting;
            case ERROR:
                return R.string.service_state_error;
            case STOPPED:
            default:
                return R.string.service_state_stopped;
        }
    }

    private static int stateColor(HapServiceStates.ServiceState state) {
        switch (state) {
            case ACTIVE:
            case ACTIVE_FEMININE:
                return R.color.hap_success;
            case STARTING:
                return R.color.hap_warning;
            case ERROR:
                return R.color.hap_error;
            case DISABLED:
            case STOPPED:
            default:
                return R.color.hap_text_muted;
        }
    }

    private static int serverHeading(HapServiceStates.ServerState state) {
        switch (state) {
            case RUNNING:
                return R.string.server_state_running_heading;
            case STARTING:
                return R.string.server_state_starting_heading;
            case ERROR:
                return R.string.server_state_error_heading;
            case STOPPED:
            default:
                return R.string.server_state_stopped_heading;
        }
    }

    /**
     * The one-line state word and the "what to do next" sentence of the status card.
     *
     * <p>Package-private because the "Cómo ver mis canales" screen says the same two things about
     * the same four states: one owner of the approved wording means the two screens can never
     * disagree about what the server is doing (S3, S5).
     */
    static int serverReady(HapServiceStates.ServerState state) {
        switch (state) {
            case RUNNING:
                return R.string.server_state_running_ready;
            case STARTING:
                return R.string.server_state_starting_ready;
            case ERROR:
                return R.string.server_state_error_ready;
            case STOPPED:
            default:
                return R.string.server_state_stopped_ready;
        }
    }

    static int serverBody(HapServiceStates.ServerState state) {
        switch (state) {
            case RUNNING:
                return R.string.server_state_running_body;
            case STARTING:
                return R.string.server_state_starting_body;
            case ERROR:
                return R.string.server_state_error_body;
            case STOPPED:
            default:
                return R.string.server_state_stopped_body;
        }
    }

    private static int serverDot(HapServiceStates.ServerState state) {
        switch (state) {
            case RUNNING:
                return R.drawable.dot_status_ok;
            case STARTING:
                return R.drawable.dot_status_warning;
            case ERROR:
                return R.drawable.dot_status_error;
            case STOPPED:
            default:
                return R.drawable.dot_status_off;
        }
    }

    private TextView addServiceRow(LinearLayout card, int nameRes, int detailRes) {
        LinearLayout row = row(Gravity.CENTER_VERTICAL);
        if (card.getChildCount() > 0) {
            row.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        }

        LinearLayout left = column();
        left.addView(label(nameRes, R.style.TextAppearance_HaP_Body), matchWrap());
        TextView detail = label(detailRes, R.style.TextAppearance_HaP_Caption);
        detail.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        left.addView(detail, matchWrap());
        row.addView(left, weight(1f, 0));

        TextView state = label(0, R.style.TextAppearance_HaP_Body);
        state.setGravity(Gravity.END);
        state.setTextColor(color(R.color.hap_text_muted));
        row.addView(state, wrapWrap());

        card.addView(row, matchWrap());
        return state;
    }

    private void addCardText(LinearLayout card, int titleRes, int subtitleRes) {
        card.addView(label(titleRes, R.style.TextAppearance_HaP_Subtitle), matchWrap());
        TextView subtitle = label(subtitleRes, R.style.TextAppearance_HaP_Body);
        subtitle.setTextColor(color(R.color.hap_text_muted));
        subtitle.setPadding(0, space(R.dimen.hap_gap_row), 0, 0);
        card.addView(subtitle, matchWrap());
    }

    /**
     * A card surface. Focusable cards use the shared {@code bg_panel} state list, so focus is the
     * accent outline the rest of the app draws and never a code-side background swap.
     */
    private LinearLayout card(boolean focusable, int backgroundRes) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(backgroundRes == 0 ? R.drawable.bg_panel : backgroundRes);
        int padding = space(R.dimen.hap_card_padding);
        card.setPadding(padding, padding, padding, padding);
        if (focusable) focusable(card);
        return card;
    }

    private void focusable(View view) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
    }

    private TextView label(int textRes, int appearanceRes) {
        TextView view = new TextView(activity);
        if (textRes != 0) view.setText(textRes);
        view.setTextAppearance(appearanceRes);
        return view;
    }

    private String description(int titleRes, int subtitleRes) {
        return activity.getString(
                R.string.home_card_description,
                activity.getString(titleRes),
                activity.getString(subtitleRes)
        );
    }

    private int color(int colorRes) {
        return activity.getColor(colorRes);
    }

    private int space(int dimenRes) {
        return activity.getResources().getDimensionPixelSize(dimenRes);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout row(int gravity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weight(float value, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                value
        );
        params.leftMargin = leftMargin;
        return params;
    }

    private LinearLayout.LayoutParams top(LinearLayout.LayoutParams params, int dimenRes) {
        params.topMargin = space(dimenRes);
        return params;
    }
}
