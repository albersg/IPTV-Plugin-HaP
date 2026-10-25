package com.streamvault.plugin.hap;

/**
 * The state words the home screen shows for the server and its three services.
 *
 * <p>The home screen must never hardcode a word: the same 1 Hz runtime snapshot that feeds the
 * rest of the screen decides whether AceServe, the proxy and IPFS are active, starting, stopped,
 * disabled or in error, and whether the server as a whole is running, starting, stopped or
 * failed. Keeping that decision here — plain Java, no android.* imports — makes it testable on
 * the JVM and keeps the view code to "read this enum, set this text".
 *
 * <p>Immutable: the snapshot computed off the main thread is handed straight to the view.
 */
final class HapServiceStates {

    /** The four states of the server card, in the words the user reads. */
    enum ServerState {
        RUNNING,
        STARTING,
        STOPPED,
        ERROR
    }

    /** The six state words of the design (Activo, Activa, Desactivado, Detenido, Iniciando, Error). */
    enum ServiceState {
        ACTIVE,
        ACTIVE_FEMININE,
        DISABLED,
        STOPPED,
        STARTING,
        ERROR
    }

    final ServerState server;
    final ServiceState aceServe;
    final ServiceState proxy;
    final ServiceState ipfs;

    private HapServiceStates(
            ServerState server,
            ServiceState aceServe,
            ServiceState proxy,
            ServiceState ipfs
    ) {
        this.server = server;
        this.aceServe = aceServe;
        this.proxy = proxy;
        this.ipfs = ipfs;
    }

    /**
     * @param desiredRunning user asked HaP to run
     * @param aceRunning     AceServe answers
     * @param proxyRunning   HTTPAceProxy answers, which is what "the server is up" means
     * @param hasError       a service error is stored for the runtime
     * @param ipfsEnabled    the optional IPFS feature is switched on
     * @param ipfsRunning    the IPFS gateway answers
     * @param ipfsHasError   IPFS reported a failure
     */
    static HapServiceStates of(
            boolean desiredRunning,
            boolean aceRunning,
            boolean proxyRunning,
            boolean hasError,
            boolean ipfsEnabled,
            boolean ipfsRunning,
            boolean ipfsHasError
    ) {
        ServerState server;
        if (proxyRunning) {
            server = ServerState.RUNNING;
        } else if (hasError) {
            server = ServerState.ERROR;
        } else if (desiredRunning) {
            server = ServerState.STARTING;
        } else {
            server = ServerState.STOPPED;
        }

        return new HapServiceStates(
                server,
                managedService(desiredRunning, aceRunning, hasError),
                managedService(desiredRunning, proxyRunning, hasError),
                optionalService(ipfsEnabled, ipfsRunning, ipfsHasError)
        );
    }

    /** AceServe and the proxy run whenever HaP runs, so they report on their own flag. */
    private static ServiceState managedService(boolean desiredRunning, boolean running, boolean hasError) {
        if (running) return ServiceState.ACTIVE;
        if (desiredRunning) return hasError ? ServiceState.ERROR : ServiceState.STARTING;
        return ServiceState.STOPPED;
    }

    /** IPFS is optional: switched off reads as "disabled", never as a failure. */
    private static ServiceState optionalService(boolean enabled, boolean running, boolean hasError) {
        if (!enabled) return ServiceState.DISABLED;
        if (hasError) return ServiceState.ERROR;
        if (running) return ServiceState.ACTIVE_FEMININE;
        return ServiceState.STARTING;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HapServiceStates)) return false;
        HapServiceStates that = (HapServiceStates) other;
        return server == that.server
                && aceServe == that.aceServe
                && proxy == that.proxy
                && ipfs == that.ipfs;
    }

    @Override
    public int hashCode() {
        int result = server.hashCode();
        result = 31 * result + aceServe.hashCode();
        result = 31 * result + proxy.hashCode();
        result = 31 * result + ipfs.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "HapServiceStates{server=" + server
                + ", aceServe=" + aceServe
                + ", proxy=" + proxy
                + ", ipfs=" + ipfs + '}';
    }
}
