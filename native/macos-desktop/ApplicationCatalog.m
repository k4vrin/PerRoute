#import <AppKit/AppKit.h>
#import <Security/Security.h>

static NSDictionary *Application(NSURL *url) {
    url = url.URLByResolvingSymlinksInPath.URLByStandardizingPath;
    NSBundle *bundle = [NSBundle bundleWithURL:url];
    NSURL *executable = bundle.executableURL.URLByResolvingSymlinksInPath.URLByStandardizingPath;
    NSString *identifier = bundle.bundleIdentifier;
    if (!bundle || !executable || !identifier.length || ![NSFileManager.defaultManager isExecutableFileAtPath:executable.path]) return nil;
    // Resolve both sides consistently for system application aliases. Nonconventional wrappers
    // such as iOS-on-Mac bundles are outside this initial catalog; do not invent executable identities.
    if (![executable.path hasPrefix:[url.path stringByAppendingString:@"/Contents/MacOS/"]]) return nil;
    NSString *name = [bundle objectForInfoDictionaryKey:@"CFBundleDisplayName"] ?: [bundle objectForInfoDictionaryKey:@"CFBundleName"];
    if (![name isKindOfClass:NSString.class] || !name.length) name = url.lastPathComponent.stringByDeletingPathExtension;
    SecStaticCodeRef code = NULL;
    CFDictionaryRef info = NULL;
    BOOL valid = NO;
    NSString *team = @"", *signingIdentifier = @"";
    if (SecStaticCodeCreateWithPath((__bridge CFURLRef)url, kSecCSDefaultFlags, &code) == errSecSuccess) {
        valid = SecStaticCodeCheckValidity(code, kSecCSBasicValidateOnly, NULL) == errSecSuccess;
        if (valid && SecCodeCopySigningInformation(code, kSecCSSigningInformation, &info) == errSecSuccess) {
            NSDictionary *metadata = (__bridge NSDictionary *)info;
            team = metadata[(__bridge NSString *)kSecCodeInfoTeamIdentifier] ?: @"";
            signingIdentifier = metadata[(__bridge NSString *)kSecCodeInfoIdentifier] ?: @"";
        }
    }
    if (info) CFRelease(info);
    if (code) CFRelease(code);
    // Paths identify catalog entries only. Signing metadata is observational, not routing authority.
    return @{@"name": name, @"bundlePath": url.URLByStandardizingPath.path,
        @"executablePath": executable.URLByStandardizingPath.path, @"bundleIdentifier": identifier,
        @"teamIdentifier": team, @"signingIdentifier": signingIdentifier, @"signatureValid": @(valid)};
}

__attribute__((visibility("default"))) char *perroute_catalog_request(const char *operation) {
    @autoreleasepool {
        NSMutableDictionary *entries = [NSMutableDictionary dictionary];
        NSMutableArray *warnings = [NSMutableArray array];
        NSString *op = operation ? [NSString stringWithUTF8String:operation] : nil;
        if ([op isEqual:@"installed"]) {
            NSArray *roots = @[@"/Applications", @"/System/Applications", [NSHomeDirectory() stringByAppendingPathComponent:@"Applications"]];
            for (NSString *root in roots) {
                if (![NSFileManager.defaultManager fileExistsAtPath:root]) continue;
                NSDirectoryEnumerator *enumerator = [NSFileManager.defaultManager enumeratorAtURL:[NSURL fileURLWithPath:root]
                    includingPropertiesForKeys:nil options:NSDirectoryEnumerationSkipsHiddenFiles | NSDirectoryEnumerationSkipsPackageDescendants
                    errorHandler:^BOOL(NSURL *url, NSError *error) { (void)url; (void)error;
                        if (!warnings.count) [warnings addObject:@"Some application folders could not be read"];
                        return YES;
                    }];
                for (NSURL *url in enumerator) {
                    if (![url.pathExtension isEqual:@"app"]) continue;
                    NSDictionary *app = Application(url);
                    if (app) entries[app[@"bundlePath"]] = app;
                    if (entries.count > 4096) break;
                }
                if (entries.count > 4096) break;
            }
        } else if ([op isEqual:@"running"]) {
            // AppKit's regular apps are a catalog, not arbitrary process/helper attribution.
            for (NSRunningApplication *running in NSWorkspace.sharedWorkspace.runningApplications) {
                if (running.activationPolicy != NSApplicationActivationPolicyRegular || !running.bundleURL) continue;
                NSDictionary *app = Application(running.bundleURL);
                if (app) entries[app[@"bundlePath"]] = app;
                if (entries.count > 4096) break;
            }
        }
        NSDictionary *result;
        if (!([op isEqual:@"installed"] || [op isEqual:@"running"])) result = @{@"error": @"Unsupported catalog operation"};
        else if (entries.count > 4096) result = @{@"error": @"Application catalog exceeds its size limit"};
        else result = @{@"version": @1, @"applications": entries.allValues, @"warnings": warnings};
        NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:NULL];
        return data ? strndup(data.bytes, data.length) : NULL;
    }
}
__attribute__((visibility("default"))) void perroute_catalog_free(void *value) { free(value); }
