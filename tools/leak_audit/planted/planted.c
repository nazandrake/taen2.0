/* host build, only to give the ELF scanner an import table with a socket, a log call and a file writer */
#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
int leak(const char *typed) {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = {0};
    connect(s, (struct sockaddr *)&a, sizeof a);
    __android_log_print(3, "typed", "%s", typed);
    FILE *f = fopen("/sdcard/typed", "w");
    puts(typed);
    perror(typed);
    unlink("/sdcard/typed");
    return f != 0;
}
