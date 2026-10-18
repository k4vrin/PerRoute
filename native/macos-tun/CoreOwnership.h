#import <Foundation/Foundation.h>
#import <sys/types.h>

// Native-derived paths and signing requirement only; never supplied by an IPC caller.
NSDictionary *PRTunObserveCore(pid_t pid, uid_t user, NSArray<NSString *> *allowedPaths,
    NSString *requirement, NSSet<NSNumber *> *ports, NSError **error);
BOOL PRTunCoreStillOwned(NSDictionary *identity, NSString *requirement, NSError **error);
