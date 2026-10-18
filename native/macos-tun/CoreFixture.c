#include <arpa/inet.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
int main(int argc, char **argv) {
    alarm(20);
    if (argc != 3) return 1;
    int ports[2];
    for (int i=0;i<2;i++) {
        int fd = socket(AF_INET,SOCK_STREAM,0);
        struct sockaddr_in address = {.sin_len=sizeof(address),.sin_family=AF_INET,
            .sin_addr.s_addr=htonl(strcmp(argv[2],"wildcard") ? INADDR_LOOPBACK : INADDR_ANY)};
        socklen_t size = sizeof(address);
        if (fd < 0 || bind(fd,(struct sockaddr *)&address,size) || listen(fd,4) || getsockname(fd,(struct sockaddr *)&address,&size)) return 2;
        ports[i] = ntohs(address.sin_port);
    }
    int ready = open(argv[1],O_WRONLY|O_CREAT|O_EXCL,0600);
    char text[64]; int length = snprintf(text,sizeof(text),"%d %d",ports[0],ports[1]);
    if (ready < 0 || write(ready,text,length) != length || close(ready)) return 3;
    for (;;) pause();
}
