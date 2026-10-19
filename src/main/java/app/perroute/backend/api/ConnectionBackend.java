package app.perroute.backend.api;

import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.service.XrayConfigGenerator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Machine operations used by the desktop controller. Implementations own platform-specific effects. */
public interface ConnectionBackend {
    BackendCapabilities capabilities();
    boolean coreReady();
    boolean perAppReady();
    boolean elevated();
    default boolean requiresPerAppGateway() { return false; }
    default boolean supportsPerAppKillSwitch() { return true; }
    default String perAppReadinessMessage() { return "Smart per-app needs the bundled Windows driver. Reinstall PerRoute with the Full Installer and approve its single UAC prompt, or choose Local proxy/System proxy."; }
    /** Recover previous platform routing custody before a new explicit connection, without approval prompts. */
    default void prepareConnection() throws Exception { }
    default void verifyPerAppSession() throws Exception { }

    /** Reject an invalid configuration without changing the running core. */
    void validate(String config, boolean tun) throws Exception;
    void start(String config, boolean tun) throws Exception;
    void stop() throws Exception;
    boolean isRunning();
    void stopOrphan() throws Exception;
    void addLogListener(Consumer<String> listener);
    void addRunningListener(Consumer<Boolean> listener);

    void applyPerApp(List<String> applications) throws Exception;
    void restorePerApp() throws Exception;
    void stopPerAppSidecar() throws Exception;
    void applySystemProxy() throws Exception;
    void restoreSystemProxy() throws Exception;
    /** Read-only check of an owned helper session. Backends without a helper session do not monitor it. */
    default void verifySystemProxySession() throws Exception { }

    TunnelStatus checkTunnel(int socksPort);
    Map<String, Long> readInboundCounters() throws Exception;
    /** A null port observes the direct route rather than a loopback SOCKS inbound. */
    RouteObservation lookupPublicAddress(Integer socksPort);
    List<String> systemDnsServers();
    String publicResolver();
    /** Resolve an explicit user selection without guessing the spelling of a saved platform identity. */
    default String selectApplication(String path) throws java.io.IOException {
        if (app.perroute.model.ApplicationIdentity.isMac(path)) throw new java.io.IOException("This application belongs to macOS.");
        return Path.of(path).toAbsolutePath().normalize().toString();
    }
    /** Validate a saved selection before applying it to this platform. */
    default String restoreApplication(String identity) throws java.io.IOException { return selectApplication(identity); }
    List<ApplicationDescriptor> installedApplications();
    List<ApplicationDescriptor> runningApplications();

    /** Replace one live route; unsupported implementations must throw so the controller can restart safely. */
    void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) throws Exception;
    /** Bind all gateway listeners atomically or throw without leaving a partial gateway running. */
    void startGateway(List<AppGateway.Route> routes) throws Exception;
    void stopGateway();
    void setGatewayLimits(String applicationPath, long downloadBytesPerSecond, long uploadBytesPerSecond);
    Map<String, AppGateway.Counters> gatewayCounters();
    boolean gatewayRunning();
    void addGatewayListener(Consumer<GatewayConnection> listener);

    void installCore(Consumer<String> progress) throws Exception;
    void installPerApp(Consumer<String> progress) throws Exception;
    UpdateDescriptor checkForUpdate(String feed) throws Exception;
    Path downloadUpdate(UpdateDescriptor update, Consumer<Integer> progress) throws Exception;
    void launchUpdate(Path installer) throws Exception;
}
