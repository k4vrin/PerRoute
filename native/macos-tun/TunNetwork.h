#import <Foundation/Foundation.h>
extern NSString *const PRTunInterface;
NSArray<NSDictionary *> *PRTunDecodeRoutes(NSData *bytes, NSError **error);
NSArray<NSDictionary *> *PRTunReadRoutes(NSError **error);
NSDictionary *PRTunNetworkBaseline(NSError **error);
BOOL PRTunRoutesCoverCaptureRange(NSArray<NSDictionary *> *routes, unsigned index);
BOOL PRTunNetworkReady(NSDictionary *baseline, NSError **error);
