#import <Foundation/Foundation.h>

// Preparation only. The future authenticated supervisor supplies its own verified engine
// paths; they are never accepted from the wire. This API performs no file/process/network I/O.
NSData *PRTunPrepareConfiguration(NSData *request, NSArray<NSString *> *trustedEngineExecutables, NSError **error);
