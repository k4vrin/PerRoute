#import "ProxyHelper.h"
#import <ServiceManagement/ServiceManagement.h>
#import <Security/Security.h>

static NSXPCConnection *Connection;
static NSObject *ConnectionLock;

static NSDictionary *Failure(NSString *message) { return @{@"ok": @NO, @"error": message, @"version": @(PRProtocolVersion)}; }

static BOOL TrustedHelper(NSString *team) {
    NSURL *url = [NSBundle.mainBundle.bundleURL URLByAppendingPathComponent:@"Contents/Library/HelperTools/PerRouteProxyHelper"];
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    BOOL valid = SecStaticCodeCreateWithPath((__bridge CFURLRef)url, kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)PRRequirement(PRHelperLabel, team), kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return valid;
}

static NSDictionary *Remote(NSDictionary *request, NSString *team) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ ConnectionLock = [NSObject new]; });
    __block NSDictionary *result = nil;
    dispatch_semaphore_t ready = dispatch_semaphore_create(0);
    NSXPCConnection *connection;
    @synchronized(ConnectionLock) {
        if (!Connection) {
            Connection = [[NSXPCConnection alloc] initWithMachServiceName:PRHelperLabel options:NSXPCConnectionPrivileged];
            Connection.remoteObjectInterface = PRHelperInterface();
            [Connection setCodeSigningRequirement:PRRequirement(PRHelperLabel, team)];
            [Connection resume];
        }
        connection = Connection;
    }
    // The connection stays alive between calls so its invalidation identifies desktop process death.
    id<PRProxyHelper> proxy = [connection remoteObjectProxyWithErrorHandler:^(NSError *error) {
        (void)error;
        result = Failure(@"The background proxy service is unavailable. Check its approval in System Settings and retry");
        dispatch_semaphore_signal(ready);
    }];
    [proxy request:request reply:^(NSDictionary *reply) { result = reply; dispatch_semaphore_signal(ready); }];
    if (dispatch_semaphore_wait(ready, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
        @synchronized(ConnectionLock) { if (Connection == connection) { [Connection invalidate]; Connection = nil; } }
        return Failure(@"Background proxy service timed out. Recovery will be retried; do not assume the settings were unchanged");
    }
    if (![result[@"ok"] boolValue]) {
        @synchronized(ConnectionLock) { if (Connection == connection) { [Connection invalidate]; Connection = nil; } }
    }
    return result;
}

static NSDictionary *Perform(NSDictionary *request) {
    NSString *operation = request[@"operation"];
    NSString *team = PRSigningTeam();
    BOOL packaged = [NSBundle.mainBundle.bundleIdentifier isEqual:PRAppIdentifier];
    BOOL managed = packaged && team != nil;
    if ([operation isEqual:@"availability"]) {
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"managed": @(managed)}};
    }
    if (!managed || !TrustedHelper(team)) return Failure(@"The background proxy service requires a consistently signed PerRoute installation. Use Local proxy until setup is complete");
    SMAppService *service = [SMAppService daemonServiceWithPlistName:[PRHelperLabel stringByAppendingString:@".plist"]];
    if ([operation isEqual:@"prepare"]) {
        NSError *error = nil;
        if (service.status == SMAppServiceStatusNotRegistered || service.status == SMAppServiceStatusNotFound) {
            if (![service registerAndReturnError:&error] && service.status != SMAppServiceStatusRequiresApproval)
                return Failure(@"Could not register the background proxy service. Install PerRoute in Applications and retry");
        }
        if (service.status == SMAppServiceStatusRequiresApproval) {
            [SMAppService openSystemSettingsLoginItems];
            return Failure(@"Approve the PerRoute background service in System Settings → General → Login Items & Extensions, then click Connect again");
        }
        return service.status == SMAppServiceStatusEnabled ? @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{}} : Failure(@"The background proxy service is not enabled");
    }
    // Startup/Disconnect never registers a service or opens an authorization dialog.
    if (service.status == SMAppServiceStatusNotRegistered && [operation isEqual:@"restore"])
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"pending": @NO, @"registered": @NO}};
    if (service.status != SMAppServiceStatusEnabled) return Failure(@"Enable the PerRoute background service in System Settings to restore pending proxy settings");
    return Remote(request, team);
}

__attribute__((visibility("default"))) char *perroute_helper_request(const char *input) {
    @autoreleasepool {
        NSDictionary *result;
        @try {
            if (!input || strnlen(input, 65537) > 65536) result = Failure(@"Invalid helper request");
            else {
                NSData *data = [NSData dataWithBytes:input length:strlen(input)];
                id request = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
                result = [request isKindOfClass:NSDictionary.class] && [request[@"version"] isEqual:@(PRProtocolVersion)] ? Perform(request) : Failure(@"Unsupported helper protocol");
            }
        } @catch (NSException *exception) { (void)exception; result = Failure(@"Could not initialize the background proxy service"); }
        NSData *output = [NSJSONSerialization dataWithJSONObject:result options:0 error:NULL];
        return output ? strndup(output.bytes, output.length) : NULL;
    }
}
__attribute__((visibility("default"))) void perroute_helper_free(void *value) { free(value); }
