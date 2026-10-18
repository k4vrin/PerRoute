#import "CoreOwnership.h"
#import <sys/wait.h>
#import <unistd.h>
static unsigned Checks;
#define CHECK(value) do { Checks++; if (!(value)) { fprintf(stderr,"Core check line %d: %s\n",__LINE__,#value); exit(1); } } while(0)
static NSMutableArray<NSTask *> *Tasks;
static void Cleanup(void) { for (NSTask *task in Tasks) { if (task.running) kill(task.processIdentifier,SIGKILL); [task waitUntilExit]; } }
static NSTask *Start(NSString *fixture, NSString *mode, NSSet **ports) {
    NSString *ready = [@"/private/tmp/perroute-core-" stringByAppendingString:NSUUID.UUID.UUIDString];
    NSTask *task = [NSTask new]; task.executableURL = [NSURL fileURLWithPath:fixture]; task.arguments = @[ready,mode];
    CHECK([task launchAndReturnError:NULL]); [Tasks addObject:task];
    NSString *text;
    for (int i=0;i<200 && !(text=[NSString stringWithContentsOfFile:ready encoding:NSUTF8StringEncoding error:NULL]);i++) usleep(25000);
    CHECK(text);
    NSMutableSet *found = [NSMutableSet set];
    for (NSString *port in [text componentsSeparatedByString:@" "]) [found addObject:@(port.intValue)];
    CHECK(found.count == 2); *ports = found;
    CHECK([[NSFileManager defaultManager] removeItemAtPath:ready error:NULL]);
    return task;
}
int main(int argc, const char **argv) { @autoreleasepool {
    Tasks = [NSMutableArray array]; atexit(Cleanup);
    CHECK(argc == 2 && geteuid() != 0);
    NSString *fixture = [NSString stringWithUTF8String:argv[1]];
    NSString *requirement = @"identifier \"app.perroute.core-fixture\"";
    NSSet *ports;
    NSTask *task = Start(fixture,@"loopback",&ports);
    NSDictionary *identity = PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],requirement,ports,NULL);
    CHECK(identity); CHECK(PRTunCoreStillOwned(identity,requirement,NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid()+1,@[fixture],requirement,ports,NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[@"/wrong/path"],requirement,ports,NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],@"identifier \"wrong\"",ports,NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],requirement,[NSSet setWithObject:@1],NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],requirement,[NSSet setWithObject:@YES],NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],requirement,[NSSet set],NULL));
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],requirement,[NSSet setWithObject:@1.5],NULL));
    NSMutableDictionary *stale = [identity mutableCopy]; stale[@"microseconds"] = @([identity[@"microseconds"] longLongValue]+1);
    CHECK(!PRTunCoreStillOwned(stale,requirement,NULL));
    kill(task.processIdentifier,SIGTERM); [task waitUntilExit];
    CHECK(!PRTunCoreStillOwned(identity,requirement,NULL));
    task = Start(fixture,@"wildcard",&ports);
    CHECK(!PRTunObserveCore(task.processIdentifier,geteuid(),@[fixture],requirement,ports,NULL));
    kill(task.processIdentifier,SIGTERM); [task waitUntilExit];
    printf("Signed Xray endpoint ownership: %u checks passed; disposable loopback listeners only\n",Checks);
    return 0;
} }
