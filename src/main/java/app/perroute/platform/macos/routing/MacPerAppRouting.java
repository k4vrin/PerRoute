package app.perroute.platform.macos.routing;

import app.perroute.gateway.AppGateway;
import app.perroute.model.ApplicationIdentity;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import static app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

/** Keeps explicit routing intent across owned-core restarts; Restore clears it only after native cleanup. */
public final class MacPerAppRouting {
    private final MacTunSessionClient session;
    private final AppGateway gateway;
    private List<AppGateway.Route> routes = List.of();
    private List<String> selected = List.of();

    public MacPerAppRouting(MacTunSessionClient session, AppGateway gateway) {
        this.session = Objects.requireNonNull(session);
        this.gateway = Objects.requireNonNull(gateway);
    }
    public synchronized void startGateway(List<AppGateway.Route> values) throws IOException {
        if (!selected.isEmpty()) throw new IOException("Restore per-app routing before replacing gateway bindings");
        var snapshot = List.copyOf(values);
        if (snapshot.isEmpty() || snapshot.size() > 256) throw new IOException("Select between 1 and 256 executables");
        for (int i=0;i<snapshot.size();i++) {
            var route = snapshot.get(i);
            if (!ApplicationIdentity.isMac(route.applicationPath()) || route.applicationIndex() != i)
                throw new IOException("Expected ordered macOS executable gateway bindings");
        }
        // Reuse the native wire validator before binding any socket, including duplicate paths/ports.
        new MacTunStartRequest(snapshot.stream().map(route -> new ExecutableRoute(
                ApplicationIdentity.path(route.applicationPath()),route.listenPort())).toList(),2,
                snapshot.stream().map(AppGateway.Route::upstreamPort).toList());
        gateway.start(snapshot);
        routes = snapshot;
    }
    public synchronized void apply(List<String> applications, long corePid) throws Exception {
        var snapshot = List.copyOf(applications);
        if (!gateway.running() || !snapshot.equals(routes.stream().map(AppGateway.Route::applicationPath).toList()))
            throw new IOException("The selected executables do not match the running gateway bindings");
        start(snapshot,corePid);
        selected = snapshot;
    }
    private void start(List<String> applications, long corePid) throws Exception {
        if (applications.isEmpty()) throw new IOException("Select at least one executable");
        session.start(routes.stream().map(route -> new ExecutableRoute(
                ApplicationIdentity.path(route.applicationPath()),route.listenPort())).toList(),corePid,
                routes.stream().map(AppGateway.Route::upstreamPort).toList());
    }
    public synchronized void coreStopping() throws IOException {
        if (!selected.isEmpty() || session.pendingRecovery()) session.stop();
    }
    public synchronized void coreStarted(long corePid) throws Exception {
        if (!selected.isEmpty()) {
            if (!gateway.running()) throw new IOException("The per-app gateway stopped during the core restart");
            start(selected,corePid);
        }
    }
    public synchronized void restore() throws IOException {
        session.stop();
        selected = List.of();
    }
    public synchronized void stopGateway() throws IOException {
        restore();
        gateway.stop();
        routes = List.of();
    }
    public synchronized void verify() throws IOException {
        if (selected.isEmpty()) throw new IOException("There is no owned per-app routing session");
        session.verifySession();
    }
}
