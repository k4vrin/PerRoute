#import "TunNetwork.h"
#import <arpa/inet.h>
#import <net/route.h>
static unsigned Checks;
#define CHECK(value) do { Checks++; if (!(value)) { fprintf(stderr,"Network check line %d: %s\n",__LINE__,#value); exit(1); } } while(0)
static NSMutableData *Route(uint32_t network, uint32_t mask) {
    struct rt_msghdr2 header = {.rtm_msglen=sizeof(header)+2*sizeof(struct sockaddr_in),.rtm_version=RTM_VERSION,
        .rtm_addrs=RTA_DST|RTA_NETMASK,.rtm_index=17,.rtm_flags=RTF_UP};
    struct sockaddr_in destination = {.sin_len=sizeof(destination),.sin_family=AF_INET,.sin_addr.s_addr=htonl(network)};
    struct sockaddr_in netmask = {.sin_len=sizeof(netmask),.sin_family=AF_UNSPEC,.sin_addr.s_addr=htonl(mask)};
    NSMutableData *bytes = [NSMutableData dataWithBytes:&header length:sizeof(header)];
    [bytes appendBytes:&destination length:sizeof(destination)]; [bytes appendBytes:&netmask length:sizeof(netmask)]; return bytes;
}
int main(void) { @autoreleasepool {
    NSError *error = nil;
    NSArray *actual = PRTunReadRoutes(&error);
    if (!actual) fprintf(stderr,"route read error: %s\n",error.description.UTF8String);
    CHECK(actual); // Actual host kernel dump decoding, never a route mutation.
    NSArray *routes = PRTunDecodeRoutes(Route(0x0a010203U,0xffffff00U),NULL);
    CHECK(routes.count == 1); CHECK([routes[0][@"network"] unsignedIntValue] == 0x0a010200U);
    CHECK([routes[0][@"mask"] unsignedIntValue] == 0xffffff00U && [routes[0][@"index"] intValue] == 17);
    CHECK(PRTunDecodeRoutes(Route(0,0),NULL).count == 1);
    NSMutableData *compressed = Route(0x0a000000U,0xff000000U);
    unsigned char *maskBytes = (unsigned char *)compressed.mutableBytes+sizeof(struct rt_msghdr2)+sizeof(struct sockaddr_in);
    maskBytes[0] = 5; maskBytes[1] = 255;
    compressed.length -= 8;
    ((struct rt_msghdr2 *)compressed.mutableBytes)->rtm_msglen = compressed.length;
    CHECK([PRTunDecodeRoutes(compressed,NULL)[0][@"mask"] unsignedIntValue] == 0xff000000U);
    CHECK(!PRTunDecodeRoutes(Route(0,0xff00ff00),NULL));
    NSMutableData *bytes = Route(0,0); bytes.length--;
    CHECK(!PRTunDecodeRoutes(bytes,NULL));
    bytes = Route(0,0); ((struct rt_msghdr2 *)bytes.mutableBytes)->rtm_msglen = 0;
    CHECK(!PRTunDecodeRoutes(bytes,NULL));
    bytes = Route(0,0); ((struct rt_msghdr2 *)bytes.mutableBytes)->rtm_version = 0;
    CHECK(!PRTunDecodeRoutes(bytes,NULL));
    bytes = Route(0,0); ((unsigned char *)bytes.mutableBytes)[sizeof(struct rt_msghdr2)] = 255;
    CHECK(!PRTunDecodeRoutes(bytes,NULL));
    bytes = Route(0,0); [bytes appendBytes:"x" length:1];
    CHECK(!PRTunDecodeRoutes(bytes,NULL));
    CHECK(!PRTunDecodeRoutes([NSMutableData dataWithLength:4194305],NULL));
    CHECK(PRTunDecodeRoutes([NSData data],NULL).count == 0);
    printf("TUN network observation: %u checks passed, including read-only host route decoding\n",Checks);
    return 0;
} }
