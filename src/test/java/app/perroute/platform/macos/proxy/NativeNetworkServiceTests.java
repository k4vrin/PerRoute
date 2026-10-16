package app.perroute.platform.macos.proxy;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static app.perroute.platform.macos.proxy.NativePropertyLists.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

/** Native CF fixtures live only in memory. Host inspection calls read-only SystemConfiguration APIs. */
public final class NativeNetworkServiceTests {
    interface FixtureFoundation extends Library {
        Pointer CFDataCreate(Pointer allocator, byte[] bytes, NativeLong length);
        Pointer CFPropertyListCreateWithData(Pointer allocator, Pointer data, NativeLong options, Pointer format, Pointer error);
    }
    private static int assertions;
    public static void main(String[] args) throws Exception {
        nativeValues();
        var inspector = new MacNetworkServiceInspector();
        var first = inspector.inspect();
        check(!first.services().isEmpty(), "Host has native services to inspect");
        check(first.services().stream().allMatch(service -> !service.id().isBlank()), "All native services have stable IDs");
        check(first.preferences() != Consistency.UNKNOWN, "Native preferences signature is readable");
        check(first.services().stream().anyMatch(service -> !service.interfaces().isEmpty()), "Native interface metadata is present");
        var second = inspector.inspect();
        check(second.services().stream().map(Service::id).distinct().count() == second.services().size(), "Repeated native inventory has unique IDs");
        check(!first.toString().contains("127.0.0.1") && !first.toString().contains("en0"), "Inspection display contains no raw settings or interface identifiers");
        long candidates = first.services().stream().filter(service -> service.scope() == Scope.CANDIDATE).count();
        long runtime = first.services().stream().filter(service -> service.runtimeState().status() == Status.PRESENT).count();
        long configured = first.services().stream().filter(service -> service.configured().status() == Status.PRESENT).count();
        System.out.println("Native network service checks passed (" + assertions + " assertions); services=" + first.services().size()
                + ", candidates=" + candidates + ", configured=" + configured + ", runtimeEntries=" + runtime
                + ", global=" + first.globalProxies().status() + ", preferences=" + first.preferences());
    }
    private static void nativeValues() throws Exception {
        var value = decode("""
                <dict><key>HTTPPort</key><integer>8080</integer><key>enabled</key><true/>
                <key>float</key><real>1.25</real><key>nested</key><dict><key>unicode</key><string>شبکه 😀</string></dict>
                <key>ExceptionsList</key><array><string>*.example</string><string>localhost</string></array></dict>
                """);
        check(value.values().get("HTTPPort").equals(java.math.BigDecimal.valueOf(8080).stripTrailingZeros()), "Native integer preserves numeric value");
        check(value.values().get("enabled").equals(true), "Native CFBoolean remains boolean");
        check(value.values().get("float").equals(new java.math.BigDecimal("1.25")), "Native floating value preserved");
        check(value.values().get("nested").equals(Map.of("unicode", "شبکه 😀")), "Native Unicode and nested dictionaries preserved");
        check(value.values().get("ExceptionsList").equals(List.of("*.example", "localhost")), "Native array order preserved");
        check(decode("<dict/>").values().isEmpty(), "Empty native dictionary is present, not absent");
        String ipState = "<dict><key>metadata</key><data>AQID</data><key>Addresses</key><array><string>127.0.0.1</string></array></dict>";
        check(decodeField(ipState, "Addresses").equals(List.of("127.0.0.1")), "IP activity reads only the requested field despite opaque metadata");
        check(decodeField(ipState, "Missing") == null, "Missing native field stays absent");
        try { decodeField(ipState, "metadata"); throw new AssertionError("Opaque selected field accepted"); }
        catch (IOException expected) { check(true, "Opaque selected field is refused"); }
        for (String xml : List.of("<dict><key>opaque</key><data>AQID</data></dict>",
                "<dict><key>date</key><date>2026-10-16T00:00:00Z</date></dict>", "<array/>",
                "<dict><key>huge</key><string>" + "x".repeat(400_000) + "</string></dict>",
                "<dict><key>deep</key>" + "<array>".repeat(34) + "<string>x</string>" + "</array>".repeat(34) + "</dict>")) {
            try { decode(xml); throw new AssertionError("Unsupported native value accepted"); }
            catch (IOException expected) { check(true, "Unsupported or oversized native property list refused"); }
        }
        // Repeated decoding exercises Create/Copy ownership while borrowed children stay valid.
        for (int i = 0; i < 20; i++) check(decode("<dict><key>value</key><integer>1</integer></dict>").values().size() == 1, "Repeated native dictionary remains readable");
    }
    private static ProxySettings decode(String xml) throws IOException {
        return (ProxySettings) decode(xml, null);
    }
    private static Object decodeField(String xml, String field) throws IOException {
        return decode(xml, field);
    }
    private static Object decode(String xml, String field) throws IOException {
        var fixture = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", FixtureFoundation.class);
        byte[] bytes = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">" + xml + "</plist>").getBytes(StandardCharsets.UTF_8);
        Pointer data = fixture.CFDataCreate(null, bytes, new NativeLong(bytes.length));
        if (data == null) throw new IOException("Native fixture data could not be allocated");
        Pointer value = null;
        try {
            value = fixture.CFPropertyListCreateWithData(null, data, new NativeLong(0), null, null);
            if (value == null) throw new IOException("Native fixture could not be decoded");
            var codec = new NativePropertyLists();
            return field == null ? codec.dictionary(value) : codec.dictionaryField(value, field);
        } finally { release(value); release(data); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
