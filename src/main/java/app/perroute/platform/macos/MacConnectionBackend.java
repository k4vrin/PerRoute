package app.perroute.platform.macos;

import app.perroute.backend.api.*;
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

/** Owns only its local Xray child. No OS networking operations or process enumeration are available. */
public final class MacConnectionBackend implements ConnectionBackend, StartupRecovery {
    private final XrayRuntime runtime;
    private final XrayProcessManager process;
    private final NetworkDiagnostics diagnostics = new NetworkDiagnostics();

    public MacConnectionBackend(XrayRuntime runtime, Path data) {
        if (runtime.target() == XrayRuntime.Target.WINDOWS_X64) throw new IllegalArgumentException("Darwin runtime required");
        this.runtime = runtime;
        this.process = new XrayProcessManager(runtime, data);
    }
    @Override public BackendCapabilities capabilities() { return BackendCapabilities.macosLocalPreview(); }
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
    @Override public void applySystemProxy() { throw unavailable("System proxy"); }
    @Override public void restoreSystemProxy() { throw unavailable("System proxy recovery"); }
    @Override public void applyPerApp(List<String> apps) { throw unavailable("Per-app routing"); }
    @Override public void restorePerApp() { throw unavailable("Per-app recovery"); }
    @Override public void stopPerAppSidecar() { throw unavailable("Per-app sidecar"); }
    @Override public List<ApplicationDescriptor> installedApplications() { throw unavailable("Application discovery"); }
    @Override public List<ApplicationDescriptor> runningApplications() { throw unavailable("Application discovery"); }
    @Override public void installCore(Consumer<String> progress) { throw unavailable("Core installation; supply a matching Darwin Xray binary"); }
    @Override public void installPerApp(Consumer<String> progress) { throw unavailable("Driver installation"); }
    @Override public UpdateDescriptor checkForUpdate(String feed) { throw unavailable("Automatic updates"); }
    @Override public Path downloadUpdate(UpdateDescriptor update, Consumer<Integer> progress) { throw unavailable("Automatic updates"); }
    @Override public void launchUpdate(Path installer) { throw unavailable("Automatic updates"); }
    @Override public void recover() { throw unavailable("Network recovery"); }
    @Override public void runGuard(Long parentPid) { throw unavailable("Network guard"); }
    private static UnsupportedOperationException unavailable(String feature) {
        return new UnsupportedOperationException(feature + " is unavailable in the macOS local preview");
    }
}
