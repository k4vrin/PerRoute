package app.perroute.platform.macos;

import app.perroute.backend.api.*;
import app.perroute.model.ApplicationIdentity;
import app.perroute.backend.core.NetworkDiagnostics;
import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.XrayProcessManager;
import app.perroute.service.XrayRuntime;
import app.perroute.service.XrayStatsReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns its local Xray child and an optional, explicitly wired system-proxy lifecycle. */
public final class MacConnectionBackend implements ConnectionBackend, StartupRecovery {
    private final BackendCapabilities capabilities;
    private final app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy;
    private final XrayRuntime runtime;
    private final XrayProcessManager process;
    private final NetworkDiagnostics diagnostics = new NetworkDiagnostics();

    public MacConnectionBackend(XrayRuntime runtime, Path data) { this(runtime, data, false); }
    public MacConnectionBackend(XrayRuntime runtime, Path data, boolean persistent) {
        this(runtime, data, persistent, null);
    }
    public MacConnectionBackend(XrayRuntime runtime, Path data, boolean persistent,
                                app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy) {
        if (systemProxy != null && !persistent) throw new IllegalArgumentException("System proxy requires durable recovery state");
        this.systemProxy = systemProxy;
        capabilities = systemProxy != null ? BackendCapabilities.macosSystemProxy()
                : persistent ? BackendCapabilities.macosLocal() : BackendCapabilities.macosLocalPreview();
        if (runtime.target() == XrayRuntime.Target.WINDOWS_X64) throw new IllegalArgumentException("Darwin runtime required");
        this.runtime = runtime;
        this.process = new XrayProcessManager(runtime, data);
    }
    @Override public BackendCapabilities capabilities() { return capabilities; }
    @Override public boolean coreReady() {
        try { runtime.requireExecutable(false); return true; } catch (java.io.IOException error) { return false; }
    }
    @Override public boolean perAppReady() { return false; }
    @Override public boolean elevated() { return false; }
    @Override public void validate(String config, boolean tun) throws Exception { process.validate(config, tun); }
    @Override public void start(String config, boolean tun) throws Exception { process.start(config, tun); }
    @Override public void stop() { process.stop(); }
    @Override public boolean isRunning() { return process.isRunning(); }
    @Override public void addLogListener(Consumer<String> listener) { process.addLogListener(listener); }
    @Override public void addRunningListener(Consumer<Boolean> listener) { process.addStateListener(listener); }
    @Override public TunnelStatus checkTunnel(int port) {
        var result = new ProxyHealthCheck().check("127.0.0.1", port);
        return new TunnelStatus(result.success(), result.detail());
    }
    @Override public Map<String, Long> readInboundCounters() throws Exception { return new XrayStatsReader(runtime).readInboundCounters(); }
    @Override public RouteObservation lookupPublicAddress(Integer port) { return diagnostics.lookupPublicAddress(port); }
    @Override public List<String> systemDnsServers() { return diagnostics.systemDnsServers(); }
    @Override public String publicResolver() { return diagnostics.publicResolver(); }
    @Override public void stopGateway() { /* This adapter never owns a gateway. */ }
    @Override public boolean gatewayRunning() { return false; }
    @Override public Map<String, AppGateway.Counters> gatewayCounters() { return Map.of(); }
    @Override public void addGatewayListener(Consumer<GatewayConnection> listener) { }
    @Override public void startGateway(List<AppGateway.Route> routes) { throw unavailable("Per-app gateway"); }
    @Override public void setGatewayLimits(String path, long down, long up) { throw unavailable("Per-app limits"); }
    @Override public void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) {
        throw unavailable("Live route replacement"); // The existing controller falls back to an owned-core restart.
    }
    @Override public void stopOrphan() { throw unavailable("Orphan process recovery"); }
    @Override public void applySystemProxy() throws Exception {
        if (systemProxy == null) throw unavailable("System proxy");
        if (!process.isRunning()) throw new java.io.IOException("The owned proxy engine must be running before applying system settings");
        for (int port : new int[]{app.perroute.service.AppProxyLauncher.HTTP_PORT, app.perroute.service.AppProxyLauncher.SOCKS_PORT}) {
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
            }
        }
        systemProxy.apply();
        if (!process.isRunning()) {
            systemProxy.restore();
            throw new java.io.IOException("The proxy engine stopped while applying system settings");
        }
    }
    @Override public void restoreSystemProxy() throws Exception {
        if (systemProxy == null) throw unavailable("System proxy recovery");
        systemProxy.restore();
    }
    @Override public void verifySystemProxySession() throws Exception {
        if (systemProxy != null) systemProxy.verifySession();
    }
    @Override public void applyPerApp(List<String> apps) { throw unavailable("Per-app routing"); }
    @Override public void restorePerApp() { throw unavailable("Per-app recovery"); }
    @Override public void stopPerAppSidecar() { throw unavailable("Per-app sidecar"); }
    @Override public String selectApplication(String path) throws java.io.IOException {
        String raw = ApplicationIdentity.path(path);
        if (raw == null || !raw.startsWith("/")) throw new java.io.IOException("Select a macOS executable from the application list.");
        Path executable = Path.of(raw).toRealPath();
        if (!java.nio.file.Files.isRegularFile(executable) || !java.nio.file.Files.isExecutable(executable))
            throw new java.io.IOException("Select an executable file, rather than its application folder.");
        return ApplicationIdentity.macExecutable(executable.toString());
    }
    @Override public String restoreApplication(String identity) throws java.io.IOException {
        if (!ApplicationIdentity.isMac(identity)) throw new java.io.IOException("Reselect this application on macOS; its old path cannot be migrated safely.");
        String resolved = selectApplication(identity);
        if (!resolved.equals(identity)) throw new java.io.IOException("The application's executable path changed. Select it again.");
        return resolved;
    }
    @Override public List<ApplicationDescriptor> installedApplications() {
        return descriptors(MacApplicationCatalog.system()::installed);
    }
    @Override public List<ApplicationDescriptor> runningApplications() {
        return descriptors(MacApplicationCatalog.system()::running);
    }
    private static List<ApplicationDescriptor> descriptors(CatalogRead source) {
        try {
            var snapshot = source.read();
            if (!snapshot.warnings().isEmpty()) throw new java.io.IOException(String.join("; ", snapshot.warnings()));
            return snapshot.applications().stream()
                    .map(app -> new ApplicationDescriptor(app.name(), ApplicationIdentity.macExecutable(app.executablePath().toString()))).toList();
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @FunctionalInterface private interface CatalogRead { MacApplicationCatalog.Snapshot read() throws java.io.IOException; }
    @Override public void installCore(Consumer<String> progress) { throw unavailable("Core installation; supply a matching Darwin Xray binary"); }
    @Override public void installPerApp(Consumer<String> progress) { throw unavailable("Driver installation"); }
    @Override public UpdateDescriptor checkForUpdate(String feed) { throw unavailable("Automatic updates"); }
    @Override public Path downloadUpdate(UpdateDescriptor update, Consumer<Integer> progress) { throw unavailable("Automatic updates"); }
    @Override public void launchUpdate(Path installer) { throw unavailable("Automatic updates"); }
    @Override public void recover() { throw unavailable("Network recovery"); }
    @Override public void runGuard(Long parentPid) { throw unavailable("Network guard"); }
    private static UnsupportedOperationException unavailable(String feature) {
        return new UnsupportedOperationException(feature + " is unavailable on this macOS backend");
    }
}
