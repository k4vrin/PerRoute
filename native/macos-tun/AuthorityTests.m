#import "TunAuthority.h"
#import <fcntl.h>
#import <unistd.h>
static unsigned Checks;
#define CHECK(value) do { Checks++; if (!(value)) { fprintf(stderr,"Authority check line %d: %s\n",__LINE__,#value); exit(1); } } while(0)
static PRTunStore *Store;
static void Cleanup(void) { @autoreleasepool { for (NSString *session in [Store sessions:NULL]) [Store cleanup:session error:NULL]; } }
int main(int argc, const char **argv) { @autoreleasepool {
    CHECK(argc == 3 && geteuid() != 0);
    char root[] = "/private/tmp/perroute-authority-XXXXXX"; CHECK(mkdtemp(root));
    int directory = open(root,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    Store = [[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL]; close(directory); CHECK(Store); atexit(Cleanup);
    NSString *rootPath = [NSString stringWithUTF8String:root];
    NSString *engine = [NSString stringWithUTF8String:argv[1]], *worker = [NSString stringWithUTF8String:argv[2]];
    PRTunAuthority *authority = [[PRTunAuthority alloc] initWithStore:Store];
    NSObject *owner = [NSObject new], *other = [NSObject new];
    __block unsigned created = 0;
    __block PRTunSession *session;
    PRTunCreate create = ^PRTunSession *(NSError **error) {
        created++;
        session = [[PRTunSession alloc] initWithStore:Store user:geteuid() engine:engine worker:worker
            configurationBuilder:^NSData *(NSString *staged, NSError **buildError) {
                (void)buildError; CHECK([staged hasPrefix:rootPath] && [staged hasSuffix:@"/engine"]);
                return [@"N" dataUsingEncoding:NSUTF8StringEncoding];
            } engineRequirement:@"identifier \"app.perroute.session-engine\"" workerRequirement:@"identifier \"app.perroute.session-worker\"" error:error];
        return session;
    };
    __block BOOL ready = NO, healthy = YES;
    PRTunHealth health = ^BOOL(BOOL *isReady, NSError **error) { (void)error; *isReady = ready; return healthy; };
    CHECK(![authority startOwner:nil user:geteuid() create:create health:health error:NULL]); CHECK(!created);
    NSDictionary *state = [authority startOwner:owner user:geteuid() create:create health:health error:NULL];
    CHECK([state[@"phase"] isEqual:@"starting"] && [state[@"owned"] isEqual:@YES]); CHECK(created == 1);
    CHECK(authority.active && [authority ownedBy:owner user:geteuid()]);
    CHECK(![authority ownedBy:other user:geteuid()]); CHECK(![authority ownedBy:owner user:geteuid()+1]);
    CHECK(![authority statusOwner:other user:geteuid() error:NULL]);
    CHECK(![authority stopOwner:other user:geteuid() error:NULL]);
    CHECK(![authority startOwner:owner user:geteuid() create:create health:health error:NULL]); CHECK(created == 1);
    NSString *stale = [Store createForUser:geteuid() error:NULL]; CHECK(stale); CHECK([Store sessions:NULL].count == 2);
    CHECK([authority recover:NULL]); CHECK([Store sessions:NULL].count == 1); CHECK(authority.active);
    [authority invalidate:other]; CHECK(authority.active);
    ready = YES; state = [authority statusOwner:owner user:geteuid() error:NULL]; CHECK([state[@"phase"] isEqual:@"ready"]);
    ready = NO; CHECK(![authority statusOwner:owner user:geteuid() error:NULL]); CHECK(!authority.active); CHECK([Store sessions:NULL].count == 0);
    ready = YES;
    CHECK([authority startOwner:owner user:geteuid() create:create health:health error:NULL]);
    [authority invalidate:owner]; CHECK(!authority.active); CHECK([Store sessions:NULL].count == 0);
    CHECK([authority startOwner:owner user:geteuid() create:create health:health error:NULL]);
    healthy = NO; CHECK(![authority recover:NULL]); CHECK(!authority.active); CHECK([Store sessions:NULL].count == 0); healthy = YES;
    NSError *observedFailure = nil;
    CHECK(![authority statusOwner:owner user:geteuid() error:&observedFailure]);
    CHECK(observedFailure && !authority.active);
    CHECK([[authority statusOwner:other user:geteuid() error:NULL][@"phase"] isEqual:@"stopped"]);
    CHECK([authority stopOwner:owner user:geteuid() error:NULL]);
    CHECK([[authority statusOwner:owner user:geteuid() error:NULL][@"phase"] isEqual:@"stopped"]);
    CHECK([authority startOwner:owner user:geteuid() create:create health:health error:NULL]);
    CHECK(!kill(session.workerPID,SIGKILL)); usleep(100000);
    CHECK(![authority statusOwner:owner user:geteuid() error:NULL]); CHECK(!authority.active); CHECK([Store sessions:NULL].count == 0);
    CHECK([authority startOwner:owner user:geteuid() create:create health:health error:NULL]);
    NSString *identifier = session.identifier;
    CHECK([Store write:[@"{}" dataUsingEncoding:NSUTF8StringEncoding] session:identifier file:@"capture.json" error:NULL]);
    CHECK(![authority stopOwner:owner user:geteuid() error:NULL]); CHECK(authority.active); CHECK([Store sessions:NULL].count == 1);
    [authority invalidate:owner]; CHECK([authority recoveryForUser:geteuid()]);
    CHECK(![authority recoveryForUser:geteuid()+1]);
    CHECK([[authority statusOwner:other user:geteuid() error:NULL][@"phase"] isEqual:@"recovering"]);
    CHECK(![authority stopOwner:other user:geteuid()+1 error:NULL]);
    CHECK(!unlink([Store path:identifier file:@"capture.json"].fileSystemRepresentation));
    CHECK([authority stopOwner:other user:geteuid() error:NULL]); // Only stale cleanup may transfer to the same user.
    CHECK(!authority.active && [Store sessions:NULL].count == 0);
    CHECK([authority recover:NULL]);
    state = [authority statusOwner:owner user:geteuid() error:NULL]; CHECK([state[@"phase"] isEqual:@"stopped"] && [state[@"owned"] isEqual:@NO]);
    CHECK(!rmdir(root));
    printf("TUN authenticated custody: %u checks passed; simulated connection objects, real disposable workers\n",Checks);
    return 0;
} }
