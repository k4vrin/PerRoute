package app.perroute.platform.macos;

import app.perroute.gateway.AppGateway;
import app.perroute.model.ApplicationIdentity;
import app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle;
import app.perroute.platform.macos.proxy.MacSystemProxyLifecycle;
import app.perroute.platform.macos.routing.MacTunSessionClient;
import app.perroute.service.XrayRuntime;
import app.perroute.util.Json;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real loopback Xray and gateway traffic; simulated helper never activates capture or changes host settings. */
public final class MacPerAppBackendTests {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception error) { check(type.isInstance(error),error.toString()); return; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static final class Peer implements HelperSystemProxyLifecycle.Client {
        final List<Long> pids = new ArrayList<>();
        boolean cleanupFails, rejectStart;
        int refuseStarts;
        String generation = UUID.randomUUID().toString();
        List<?> bindings = List.of(), upstreams = List.of();
        @Override public Map<String,Object> request(String operation, Map<String,Object> values) throws IOException {
            return switch (operation) {
                case "prepare" -> Map.of();
                case "tun-capabilities" -> Map.of("protocol",1,"endpointProtocol",2,"euid",0,"payloadAvailable",true);
                case "stop-tun" -> {
                    if (cleanupFails) throw new IOException("Simulated pending native recovery");
                    yield Map.of("pending",false,"owned",false,"phase","stopped");
                }
                case "start-tun" -> {
                    if (rejectStart) throw new IOException("Simulated native startup refusal");
                    if (refuseStarts > 0) { refuseStarts--; throw new IOException("Simulated one-shot native startup refusal"); }
                    long pid = ((Number)values.get("corePid")).longValue();
                    check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Only a live owned Xray PID is supplied");
                    pids.add(pid); bindings = (List<?>)values.get("bindings"); upstreams = (List<?>)values.get("upstreamPorts");
                    generation = UUID.randomUUID().toString();
                    yield state();
                }
                case "tun-status" -> state();
                default -> throw new IOException("Unexpected helper operation: " + operation);
            };
        }
        Map<String,Object> state() { return Map.of("pending",true,"owned",true,"phase","ready","session",generation); }
    }
    private static final class ProxyLifecycle implements MacSystemProxyLifecycle {
        int restores;
        @Override public void apply() { throw new AssertionError("System proxies must not be applied"); }
        @Override public void restore() { restores++; }
    }
    private static int port() throws IOException {
        try (var socket = new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static String configuration(int... ports) {
        var inbounds = new ArrayList<Map<String,Object>>();
        for (int port : ports) inbounds.add(Map.of("tag","app-"+port,"listen","127.0.0.1","port",port,
                "protocol","socks","settings",Map.of("auth","noauth","udp",false)));
        return Json.stringify(Map.of("log",Map.of("loglevel","none"),"inbounds",inbounds,
                "outbounds",List.of(Map.of("protocol","freedom","tag","direct"))));
    }
    private static String executable(Path root, String name) throws IOException {
        Path file = root.resolve(name); Files.writeString(file,"fixture; never executed");
        check(file.toFile().setExecutable(true,true),"Executable fixture permission");
        return ApplicationIdentity.macExecutable(file.toRealPath().toString());
    }
    private static void request(int gateway, int destination) throws Exception {
        try (var socket = new Socket(new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",gateway)))) {
            socket.connect(new InetSocketAddress("127.0.0.1",destination),3000); socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET /local HTTP/1.1\r\nHost: loopback\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(),StandardCharsets.US_ASCII);
            check(response.endsWith("OK"),"Real gateway-to-Xray loopback response");
        }
    }
    private static final class Destination implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final Thread worker;
        volatile Throwable failure;
        Destination() throws IOException {
            worker = Thread.ofVirtual().name("perroute-loopback-destination").start(() -> {
                try {
                    while (!socket.isClosed()) try (var client = socket.accept()) {
                        client.setSoTimeout(3000);
                        var reader = new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));
                        String line; while ((line=reader.readLine()) != null && !line.isEmpty()) { }
                        client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (SocketException closed) { if (!socket.isClosed()) failure=closed; }
                catch (Throwable error) { failure=error; }
            });
        }
        public void close() throws Exception {
            socket.close(); worker.join(4000);
            check(!worker.isAlive() && failure == null,"Loopback destination stopped cleanly");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("perroute-mac-backend-").toRealPath();
        try {
            var runtime = XrayRuntime.forPlatform(System.getProperty("os.name"),System.getProperty("os.arch"),Path.of(args[0]));
            runtime.requireExecutable(false);
            String first = executable(root,"SelectedOne"), second = executable(root,"SelectedTwo");
            Path marker = root.resolve("routing.pending");
            var peer = new Peer(); var proxy = new ProxyLifecycle();
            var backend = new MacConnectionBackend(runtime,root.resolve("core"),true,proxy,new MacTunSessionClient(peer,marker));
            check(backend.perAppReady() && backend.requiresPerAppGateway() && !backend.supportsPerAppKillSwitch(),"Mac routing capabilities are explicit");
            int one=port(), two=port(), upOne=port(), upTwo=port();
            var routes = List.of(new AppGateway.Route(first,0,one,upOne),new AppGateway.Route(second,1,two,upTwo));
            var events = new CopyOnWriteArrayList<app.perroute.gateway.GatewayConnection>(); backend.addGatewayListener(events::add);
            try (var destination = new Destination()) {
                String config = configuration(upOne,upTwo);
                backend.prepareConnection(); backend.start(config,false);
                backend.startGateway(routes); backend.setGatewayLimits(first,1_000_000,1_000_000);
                fails(IOException.class,() -> backend.applyPerApp(List.of(second,first)));
                check(peer.pids.isEmpty(),"Mismatched app order cannot acquire capture authority");
                backend.applyPerApp(List.of(first,second)); backend.verifyPerAppSession();
                check(Files.exists(marker) && proxy.restores > 0,"Durable marker and proxy recovery precede per-app start");
                check(peer.upstreams.equals(List.of(upOne,upTwo)),"Upstream binding order is preserved");
                check(((Map<?,?>)peer.bindings.getFirst()).get("executable").equals(ApplicationIdentity.path(first)),"Only decoded executable paths cross the native boundary");
                request(one,destination.socket.getLocalPort());
                long deadline = System.nanoTime()+3_000_000_000L;
                while (backend.gatewayCounters().get(first).openConnections() != 0 && System.nanoTime()<deadline) Thread.sleep(10);
                var before = backend.gatewayCounters().get(first);
                check(before.downloadBytes()>0 && before.uploadBytes()>0,"Gateway counts actual bytes");
                request(two,destination.socket.getLocalPort());
                check(backend.gatewayCounters().get(first).equals(before),"The other app does not alter the first app's counters");
                long firstPid=peer.pids.getLast();
                backend.stop();
                check(!backend.isRunning() && backend.gatewayRunning() && !Files.exists(marker),"Restart stops capture/core but preserves gateway");
                backend.start(config,false); backend.verifyPerAppSession();
                check(peer.pids.getLast()!=firstPid && !ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false),"Restart rebinds to a new owned Xray process");
                check(backend.gatewayCounters().get(first).equals(before),"Restart preserves gateway counters");
                request(one,destination.socket.getLocalPort());
                check(events.stream().map(event -> event.applicationPath()).distinct().count()==2,"Inspector events preserve app identities");
                backend.stop(); peer.rejectStart=true;
                fails(IOException.class,() -> backend.start(config,false));
                check(!backend.isRunning(),"Failed routing resume does not leave a newly started core");
                peer.rejectStart=false; backend.start(config,false); backend.verifyPerAppSession();
                check(backend.isRunning(),"Rollback can restore the previous routing intent");
                peer.generation=UUID.randomUUID().toString(); fails(IOException.class,backend::verifyPerAppSession);
                peer.cleanupFails=true;
                fails(IOException.class,backend::restorePerApp); fails(IOException.class,backend::stop);
                fails(UncheckedIOException.class,backend::stopGateway);
                check(Files.exists(marker) && backend.isRunning() && backend.gatewayRunning(),"Pending recovery preserves marker and tracked resources");
                peer.cleanupFails=false; backend.restorePerApp();
                int starts=peer.pids.size(); backend.stop(); backend.start(config,false);
                check(peer.pids.size()==starts && !Files.exists(marker),"Explicit restore clears restart intent");
            } finally {
                peer.cleanupFails=false; peer.rejectStart=false;
                backend.restorePerApp(); backend.stop(); backend.stopGateway();
            }
            check(!backend.isRunning() && !backend.gatewayRunning() && !Files.exists(marker),"All owned resources are stopped");
            familyFlow(runtime,root);
            recoveryComposition(root);
            exactGatewayKeys();
            controllerFlow(runtime,first,second);
            System.out.println("macOS per-app backend: " + checks + " checks passed; real loopback Xray/gateway, simulated helper, no capture");
        } finally {
            try (var paths=Files.walk(root)) { for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void familyFlow(XrayRuntime runtime, Path root) throws Exception {
        Path bundle=root.resolve("Browser.app"), main=bundle.resolve("Contents/MacOS/Browser"),
                helper=bundle.resolve("Contents/Frameworks/Network.app/Contents/MacOS/Network");
        var rows=new ArrayList<Map<String,Object>>();
        for (Path path:List.of(main,helper)) {
            Files.createDirectories(path.getParent()); Files.writeString(path,"fixture; never executed");
            check(path.toFile().setExecutable(true,true),"Family fixture executable permission");
            rows.add(Map.of("name",path.getFileName().toString(),"bundlePath",path.getParent().getParent().getParent().toString(),
                    "executablePath",path.toString(),"bundleIdentifier","fixture.browser","signingIdentifier","","teamIdentifier","","signatureValid",false));
        }
        var unavailable=new boolean[1];
        var catalog=new MacApplicationCatalog(op -> {
            if (unavailable[0]) throw new IOException("Inventory unavailable during core restart");
            return Json.stringify(Map.of("version",1,"applications",rows,"warnings",List.of()));
        });
        var peer=new Peer(); var proxy=new ProxyLifecycle();
        Path marker=root.resolve("family.pending");
        var backend=new MacConnectionBackend(runtime,root.resolve("family-core"),true,proxy,new MacTunSessionClient(peer,marker),catalog);
        String family=ApplicationIdentity.macAppFamily(main.toString());
        int gateway=port(), upstream=port();
        var events=new CopyOnWriteArrayList<app.perroute.gateway.GatewayConnection>(); backend.addGatewayListener(events::add);
        try (var destination=new Destination()) {
            check(backend.selectApplication(family).equals(family) && backend.restoreApplication(family).equals(family),"Backend preserves explicit family identity");
            check(backend.installedApplications().stream().anyMatch(app -> app.path().equals(family)),"Backend exposes explicit family picker choice");
            backend.prepareConnection(); backend.start(configuration(upstream),false);
            backend.startGateway(List.of(new AppGateway.Route(family,0,gateway,upstream)));
            backend.applyPerApp(List.of(family));
            check(peer.bindings.size()==2 && peer.upstreams.equals(List.of(upstream)),"Helper receives main/helper paths with one Xray upstream");
            check(peer.bindings.stream().allMatch(value -> ((Number)((Map<?,?>)value).get("socksPort")).intValue()==gateway),"Family paths map to the same actual gateway");
            backend.setGatewayLimits(family,1_000_000,1_000_000);
            request(gateway,destination.socket.getLocalPort());
            check(backend.gatewayCounters().size()==1 && backend.gatewayCounters().get(family).downloadBytes()>0,"Real loopback bytes aggregate under one family counter key");
            check(events.size()==1 && events.getFirst().applicationPath().equals(family),"Inspector event retains logical family identity");
            var frozen=List.copyOf(peer.bindings); long pid=peer.pids.getLast();
            unavailable[0]=true;
            backend.stop(); backend.start(configuration(upstream),false); backend.verifyPerAppSession();
            check(peer.pids.getLast()!=pid && peer.bindings.equals(frozen),"Core restart preserves frozen membership without expanding a changed inventory");
        } finally {
            backend.restorePerApp(); backend.stop(); backend.stopGateway();
        }
        check(!Files.exists(marker) && !backend.isRunning() && !backend.gatewayRunning(),"Family session resources restored");
    }
    private static void recoveryComposition(Path root) throws Exception {
        String resources=System.getProperty("compose.application.resources.dir");
        Path marker=root.resolve("helper-tun.pending");
        var journal=new app.perroute.platform.macos.proxy.ProxyJournal() {
            public Optional<Lease> load() { throw new AssertionError("Composition cannot restore or inspect host settings"); }
            public void save(Optional<Lease> value) { throw new AssertionError("Composition cannot change settings"); }
        };
        try {
            System.clearProperty("compose.application.resources.dir");
            var services=HelperSystemProxyLifecycle.preferredServices(journal,root.resolve("helper-proxy.pending"));
            check(services.perApp()==null,"Development fallback cannot expose native per-app mode");
            app.perroute.persistence.PrivateStateFiles.write(marker,new byte[]{1});
            fails(IOException.class,() -> HelperSystemProxyLifecycle.preferredServices(journal,root.resolve("helper-proxy.pending")));
            check(Files.readAllBytes(marker)[0]==1,"A fallback build cannot clear pending capture recovery");
        } finally {
            Files.deleteIfExists(marker);
            if (resources==null) System.clearProperty("compose.application.resources.dir");
            else System.setProperty("compose.application.resources.dir",resources);
        }
    }
    private static void controllerFlow(XrayRuntime runtime, String first, String second) throws Exception {
        try (var storage=MacPreviewSession.open()) {
            var peer=new Peer(); var proxy=new ProxyLifecycle();
            Path directory=storage.directory().toRealPath();
            Path marker=directory.resolve("routing.pending");
            var backend=new MacConnectionBackend(runtime,directory,true,proxy,new MacTunSessionClient(peer,marker));
            var callback=new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Boolean>>();
            var isolated=(app.perroute.backend.api.ConnectionBackend)java.lang.reflect.Proxy.newProxyInstance(
                    app.perroute.backend.api.ConnectionBackend.class.getClassLoader(),
                    new Class<?>[]{app.perroute.backend.api.ConnectionBackend.class},(unused,method,arguments) -> {
                        return switch(method.getName()) {
                            case "addRunningListener" -> {
                                @SuppressWarnings("unchecked") var listener=(java.util.function.Consumer<Boolean>)arguments[0];
                                callback.set(listener); backend.addRunningListener(listener); yield null;
                            }
                            case "checkTunnel" -> new app.perroute.backend.api.TunnelStatus(true,"Controlled test");
                            case "lookupPublicAddress" -> new app.perroute.backend.api.RouteObservation("127.0.0.1",null,null);
                            case "systemDnsServers" -> List.of();
                            case "publicResolver" -> null;
                            default -> {
                                try { yield method.invoke(backend,arguments); }
                                catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                            }
                        };
                    });
            var state=new app.perroute.model.AppState();
            state.setConnectionMode(app.perroute.model.ConnectionMode.NATIVE_PER_APP);
            state.appPaths().addAll(List.of(first,second));
            for (String key:List.of("one","two")) state.nodes().add(new app.perroute.model.ProxyNode(key,key,
                    app.perroute.model.Protocol.VLESS,"127.0.0.1",port(),UUID.randomUUID().toString(),Map.of("security","none"),""));
            state.setSelectedNodeKey("one");
            var preferences=new MemoryPreferences(); preferences.putBoolean("v3.initialized",true);
            for (String key:List.of("v3.qualityBackground","v3.routeMonitoring","v3.trafficGateway","autoReconnect","autoFailover","autoSpeedTest"))
                preferences.putBoolean(key,false);
            preferences.putBoolean("perAppKillSwitch",true);
            try (var controller=new app.perroute.compose.controller.PerRouteController(state,
                    app.perroute.persistence.AppStateStore.inMemory(),isolated,preferences)) {
                check(controller.trafficGateway() && !controller.killSwitch(),"Mac forces gateway and does not activate a saved Windows kill switch");
                check(!controller.setTrafficGateway(false).getOk(),"Required gateway cannot be disabled");
                var result=controller.connect(); check(result.getOk(),"Controller Connect: " + result.getMessage());
                check(backend.gatewayRunning() && Files.exists(marker),"Controller starts gateway before acquiring capture");
                var returned=new java.util.concurrent.CountDownLatch(1);
                Thread producer;
                synchronized(controller) {
                    producer=Thread.ofPlatform().start(() -> { callback.get().accept(false); returned.countDown(); });
                    check(returned.await(2,java.util.concurrent.TimeUnit.SECONDS),"Backend exit callback cannot wait for the controller lock");
                }
                producer.join(2000);
                check(!producer.isAlive() && controller.isConnected(),"Delayed exit from an old core cannot disconnect a live replacement");
                long oldPid=peer.pids.getLast();
                result=controller.switchNode("two"); check(result.getOk(),"Controller main server switch: " + result.getMessage());
                check(peer.pids.getLast()!=oldPid && controller.isConnected(),"Controller restart rebinds capture to the new core");
                peer.refuseStarts=1;
                result=controller.switchNode("one");
                check(!result.getOk() && controller.isConnected() && state.selectedNodeKey().equals("two"),"Failed native resume rolls back the previous complete connection");
                peer.generation=UUID.randomUUID().toString();
                long deadline=System.nanoTime()+8_000_000_000L;
                while (controller.isConnected() && System.nanoTime()<deadline) Thread.sleep(25);
                // Disconnect marks readiness false before stopping resources; await its bounded cleanup too.
                while ((backend.isRunning() || backend.gatewayRunning()) && System.nanoTime()<deadline) Thread.sleep(25);
                check(!controller.isConnected() && !backend.isRunning() && !backend.gatewayRunning(),"Session generation loss disconnects controller and cleans resources");
                check(controller.connect().getOk(),"Reconnect succeeds after session-loss recovery");
                peer.cleanupFails=true;
                result=controller.disconnect();
                check(!result.getOk() && result.getMessage().contains("cleanup") && Files.exists(marker),"Disconnect reports pending native cleanup instead of escaping or clearing its marker");
                peer.cleanupFails=false;
                check(controller.disconnect().getOk() && !backend.isRunning() && !backend.gatewayRunning(),"Retry Disconnect completes pending cleanup");
                controller.setRouteMonitoring(true);
                check(controller.connect().getOk(),"Runtime-control connection starts");
                long livePid=peer.pids.getLast(); int liveStarts=peer.pids.size();
                result=controller.switchNode("one");
                check(result.getOk(),"Actual Xray route API accepts the main route replacement: " + result.getMessage());
                check(peer.pids.size()==liveStarts && peer.pids.getLast()==livePid && controller.isConnected(),"Live route replacement preserves core and native routing custody");
                check(controller.disconnect().getOk(),"Runtime-control connection disconnects");
                check(controller.connect().getOk(),"Core-exit recovery test connects");
                backend.stop();
                deadline=System.nanoTime()+5_000_000_000L;
                while ((controller.isConnected() || backend.gatewayRunning()) && System.nanoTime()<deadline) Thread.sleep(25);
                check(!controller.isConnected() && !backend.gatewayRunning(),"Actual owned-core stop dispatches serialized routing cleanup");
                check(controller.disconnect().getOk(),"Core-exit cleanup remains retryable");
            } finally {
                peer.cleanupFails=false; peer.rejectStart=false; peer.refuseStarts=0;
                backend.restorePerApp(); backend.stop(); backend.stopGateway();
            }
        }
    }
    private static void exactGatewayKeys() throws Exception {
        String upper=ApplicationIdentity.macExecutable("/Applications/A.app/Contents/MacOS/A");
        String lower=ApplicationIdentity.macExecutable("/Applications/a.app/Contents/MacOS/a");
        try (var gateway=new AppGateway(null,null)) {
            gateway.start(List.of(new AppGateway.Route(upper,0,port(),port()),new AppGateway.Route(lower,1,port(),port())));
            check(gateway.carries(upper) && gateway.carries(lower) && gateway.counters().size()==2,"Case variants keep distinct gateway channels");
        }
        try (var gateway=new AppGateway(null,null)) {
            gateway.start(List.of(new AppGateway.Route("C:\\Test.EXE",0,port(),port())));
            check(gateway.carries("c:\\test.exe"),"Legacy Windows gateway comparison remains case insensitive");
        }
    }
}
