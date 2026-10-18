package app.perroute.platform.macos.routing;

import app.perroute.util.Json;
import java.util.List;
import java.util.Map;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

/** Only typed endpoint bindings and an observed core PID cross the privileged boundary. */
public final class MacTunStartRequest {
    private final Map<String, Object> values;
    public MacTunStartRequest(List<ExecutableRoute> selected, long corePid) {
        if (corePid < 2 || corePid > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid Xray process ID");
        // Reuse the bounded, exact-path preparation contract; freeze the parsed nested values.
        var prepared = Json.object(new MacTunPreparationRequest(selected).toJson());
        @SuppressWarnings("unchecked")
        var bindings = (List<Map<String, Object>>) prepared.get("bindings");
        values = Map.of("corePid", corePid, "bindings", bindings.stream().map(Map::copyOf).toList());
        if ((Json.stringify(Map.of("version", 1, "operation", "start-tun", "corePid", corePid,
                "bindings", values.get("bindings")))).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("TUN start message exceeds 64 KiB");
    }
    public Map<String, Object> values() { return values; }
}
