package app.perroute.platform.macos.routing;

import app.perroute.persistence.PrivateStateFiles;
import app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.Client;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

public final class MacTunSessionClientTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static Exception failure(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception error) { check(type.isInstance(error)); return error; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static final List<ExecutableRoute> ROUTES = List.of(new ExecutableRoute("/Applications/Test.app/Contents/MacOS/Test",17890));
    private static final class Peer implements Client {
        final Path marker;
        final List<String> calls = new ArrayList<>();
        String generation = UUID.randomUUID().toString();
        boolean unavailable, missing, alwaysStarting, badOwnership, cleanupFails, failAfterStart, interruptStart, started;
        Peer(Path marker) { this.marker = marker; }
        Map<String,Object> state() { return Map.of("pending",true,"owned",badOwnership ? 1 : true,
                "phase",alwaysStarting ? "starting" : "ready","session",generation); }
        @Override public Map<String,Object> request(String operation, Map<String,Object> values) throws IOException {
            calls.add(operation);
            return switch (operation) {
                case "prepare" -> Map.of();
                case "tun-capabilities" -> Map.of("protocol",1,"euid",0,"payloadAvailable",!unavailable);
                case "stop-tun" -> {
                    if (missing) yield Map.of("registered",false);
                    if (cleanupFails) yield Map.of("pending",true,"owned",false,"phase","stopped");
                    started = false; yield Map.of("pending",false,"owned",false,"phase","stopped");
                }
                case "start-tun" -> {
                    check(Files.isRegularFile(marker)); check(values.keySet().equals(Set.of("bindings","corePid")));
                    check(values.get("corePid").equals(123L)); started = true;
                    if (failAfterStart) cleanupFails = true;
                    if (interruptStart) Thread.currentThread().interrupt();
                    yield state();
                }
                case "tun-status" -> state();
                default -> throw new IOException("Unexpected operation " + operation);
            };
        }
    }
    private static MacTunSessionClient client(Peer peer) {
        AtomicLong clock = new AtomicLong();
        return new MacTunSessionClient(peer,peer.marker,clock::get,ms -> clock.addAndGet(ms*1_000_000));
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("perroute-tun-client-").toRealPath();
        try {
            Path marker = root.resolve("pending");
            var source = new ArrayList<>(ROUTES); var request = new MacTunStartRequest(source,123); source.clear();
            var bindings = (List<?>)request.values().get("bindings"); check(bindings.size() == 1);
            failure(UnsupportedOperationException.class, () -> request.values().clear());
            failure(UnsupportedOperationException.class,bindings::clear);
            failure(UnsupportedOperationException.class, () -> ((Map<?,?>)bindings.getFirst()).clear());
            check(((Map<?,?>)bindings.getFirst()).get("executable").equals(ROUTES.getFirst().executablePath()));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,1));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,2147483648L));
            Peer peer = new Peer(marker); var service = client(peer);
            service.start(ROUTES,123); check(peer.started && service.pendingRecovery()); service.verifySession();
            peer.generation = UUID.randomUUID().toString(); failure(IOException.class,service::verifySession); check(service.pendingRecovery());
            service.stop(); check(!peer.started && !service.pendingRecovery()); service.stop(); failure(IOException.class,service::verifySession);
            peer = new Peer(marker); peer.unavailable = true; var unavailable = client(peer);
            failure(IOException.class, () -> unavailable.start(ROUTES,123)); check(!peer.calls.contains("start-tun") && !Files.exists(marker));
            peer = new Peer(marker); peer.badOwnership = true; var bad = client(peer);
            failure(IOException.class, () -> bad.start(ROUTES,123)); check(!bad.pendingRecovery());
            peer = new Peer(marker); peer.badOwnership = true; peer.failAfterStart = true; var pending = client(peer);
            Exception error = failure(IOException.class, () -> pending.start(ROUTES,123)); check(error.getSuppressed().length == 1 && pending.pendingRecovery());
            peer.cleanupFails = false; pending.stop(); check(!pending.pendingRecovery());
            peer = new Peer(marker); peer.alwaysStarting = true; AtomicLong time = new AtomicLong();
            var timeout = new MacTunSessionClient(peer,marker,() -> time.getAndAdd(16_000_000_000L),ms -> {});
            failure(IOException.class, () -> timeout.start(ROUTES,123)); check(!timeout.pendingRecovery());
            peer = new Peer(marker); peer.interruptStart = true; var cancelled = client(peer);
            try { failure(InterruptedException.class, () -> cancelled.start(ROUTES,123)); check(!cancelled.pendingRecovery()); }
            finally { Thread.interrupted(); }
            peer = new Peer(marker); var earlyCancelled = client(peer); Thread.currentThread().interrupt();
            try { failure(InterruptedException.class, () -> earlyCancelled.start(ROUTES,123)); check(peer.calls.isEmpty()); }
            finally { Thread.interrupted(); }
            peer = new Peer(marker); peer.missing = true; var missing = client(peer); missing.stop(); check(!missing.pendingRecovery());
            PrivateStateFiles.write(marker,new byte[]{1}); failure(IOException.class,missing::stop); check(missing.pendingRecovery());
            peer.missing = false; missing.stop(); check(!missing.pendingRecovery());
            PrivateStateFiles.write(marker,new byte[]{0}); failure(IOException.class,missing::pendingRecovery); check(Files.readAllBytes(marker)[0] == 0); Files.delete(marker);
            Path target = root.resolve("target"); PrivateStateFiles.write(target,new byte[]{1}); Files.createSymbolicLink(marker,target);
            failure(IOException.class,missing::pendingRecovery); check(Files.isSymbolicLink(marker)); Files.delete(marker); Files.delete(target);
            peer = new Peer(marker); var valid = client(peer); failure(IllegalArgumentException.class, () -> valid.start(List.of(),123)); check(peer.calls.isEmpty());
            System.out.println("macOS TUN Java lifecycle: " + checks + " checks passed; simulated helper, isolated recovery files");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
