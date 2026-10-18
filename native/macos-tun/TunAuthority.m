#import "TunAuthority.h"
#import <time.h>
static double Now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec+t.tv_nsec/1e9; }
static BOOL Fail(NSError **error, NSString *message) { if (error) *error = PRTunError(message); return NO; }
@implementation PRTunAuthority {
    PRTunStore *_store;
    PRTunSession *_session;
    PRTunHealth _health;
    id _owner;
    uid_t _user;
    double _deadline;
    BOOL _ready;
}
- (instancetype)initWithStore:(PRTunStore *)store {
    self = [super init]; if (self) _store = store; return self;
}
- (BOOL)active { return _session != nil; }
- (BOOL)recoveryForUser:(uid_t)uid { return _session && !_owner && uid == _user; }
- (BOOL)ownedBy:(id)owner user:(uid_t)uid { return _session && owner && owner == _owner && uid == _user; }
- (BOOL)stopInternal:(NSError **)error {
    if (_session && ![_session stop:error]) return NO;
    _session = nil; _health = nil; _owner = nil; _user = 0; _ready = NO;
    return YES;
}
- (NSDictionary *)startOwner:(id)owner user:(uid_t)uid create:(PRTunCreate)create health:(PRTunHealth)health error:(NSError **)error {
    if (!owner || !uid || !create || !health) { Fail(error,@"Invalid native TUN ownership context"); return nil; }
    if (_session) { Fail(error,@"Stop the existing per-app session before starting another"); return nil; }
    if (![self recover:error]) return nil;
    PRTunSession *session = create(error);
    if (!session) return nil;
    _session = session; _owner = owner; _user = uid; _health = [health copy]; _deadline = Now()+10; _ready = NO;
    return [self statusOwner:owner user:uid error:error];
}
- (BOOL)observe:(NSError **)error {
    if (!_session) return YES;
    NSError *failure = nil;
    BOOL ready = NO;
    BOOL healthy = [_session poll:&failure] && _health(&ready,&failure);
    if (!healthy || (!ready && Now() >= _deadline) || (_ready && !ready)) {
        if (!failure) failure = PRTunError(_ready ? @"The active tunnel interface or routes were lost" : @"The tunnel did not become ready before its deadline");
        NSError *cleanup = nil;
        if (![self stopInternal:&cleanup]) failure = cleanup ?: failure;
        if (error) *error = failure;
        return NO;
    }
    _ready = ready;
    return YES;
}
- (NSDictionary *)statusOwner:(id)owner user:(uid_t)uid error:(NSError **)error {
    if (_session && ![self ownedBy:owner user:uid]) {
        if ([self recoveryForUser:uid]) return @{@"pending":@YES,@"owned":@NO,@"phase":@"recovering"};
        Fail(error,@"Another authenticated connection owns per-app routing"); return nil;
    }
    if (![self observe:error]) return nil;
    NSArray *sessions = [_store sessions:error];
    if (!sessions) return nil;
    if (!_session) return @{@"pending":sessions.count ? @YES : @NO,@"owned":@NO,@"phase":@"stopped"};
    return @{@"pending":@YES,@"owned":@YES,@"phase":_ready ? @"ready" : @"starting",@"session":_session.identifier};
}
- (BOOL)stopOwner:(id)owner user:(uid_t)uid error:(NSError **)error {
    if (_session && ![self ownedBy:owner user:uid] && ![self recoveryForUser:uid]) return Fail(error,@"Another authenticated connection owns per-app routing");
    return [self stopInternal:error] && [self recover:error];
}
- (void)invalidate:(id)owner {
    if (owner && owner == _owner) {
        NSError *error = nil;
        if (![self stopInternal:&error]) {
            // The dead connection conveys no live ownership. The same user may
            // explicitly retry cleanup; no replacement session may start meanwhile.
            _owner = nil;
            NSLog(@"PerRoute TUN owner-loss recovery remains pending");
        }
    }
}
- (BOOL)recover:(NSError **)error {
    NSError *failure = nil;
    BOOL observed = [self observe:&failure];
    NSArray *sessions = [_store sessions:error];
    if (!sessions) return NO;
    BOOL recovered = YES;
    for (NSString *session in sessions) {
        if (_session && [session isEqual:_session.identifier]) continue;
        NSError *cleanup = nil;
        if (![_store cleanup:session error:&cleanup]) { recovered = NO; if (!failure) failure = cleanup; }
    }
    if ((!observed || !recovered) && error) *error = failure ?: PRTunError(@"TUN recovery remains pending");
    return observed && recovered;
}
@end
