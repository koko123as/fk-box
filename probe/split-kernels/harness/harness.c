/*
 * Split-kernel probe harness. One kernel library per process:
 *
 *   harness run <lib.so> <config> <home> <proxy_port> <http_port> [controller]
 *     dlopen the library, fk_init/fk_check/fk_start, fetch
 *     http://127.0.0.1:<http_port>/ through the kernel's SOCKS5 inbound,
 *     query the kernel's own controller (optional), fk_stop, start and fetch
 *     again (stop/start in one process), fk_stop, exit 0.
 *     controller: tcp:<port> (Clash API GET /version) or
 *                 unix:<path> (HTTP over a unix socket, GET /version) or
 *                 sock:<path> (only connect(), for Xray's gRPC API)
 *
 *   harness two <libA.so> <configA> <libB.so> <configB> <home> <portA> <portB> <http_port>
 *     negative test: start A, then dlopen B in the same process and call it.
 *
 * Nothing here forks or execs; the workflow starts each harness process.
 */
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "fk_kernel.h"

typedef struct {
    void *handle;
    int32_t (*init)(const fk_host *, const char *);
    int32_t (*check)(const char *, int32_t);
    int32_t (*start)(const char *, int32_t, int32_t);
    int32_t (*stop)(void);
    char *(*version)(void);
    char *(*last_error)(void);
    void (*release)(char *);
} kernel;

static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void *sym(void *h, const char *name) {
    void *p = dlsym(h, name);
    if (!p) {
        fprintf(stderr, "FAIL dlsym %s: %s\n", name, dlerror());
        exit(2);
    }
    return p;
}

static int load(kernel *k, const char *path) {
    double t = now();
    k->handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!k->handle) {
        fprintf(stderr, "FAIL dlopen %s: %s\n", path, dlerror());
        return -1;
    }
    k->init = sym(k->handle, "fk_init");
    k->check = sym(k->handle, "fk_check");
    k->start = sym(k->handle, "fk_start");
    k->stop = sym(k->handle, "fk_stop");
    k->version = sym(k->handle, "fk_version");
    k->last_error = sym(k->handle, "fk_last_error");
    k->release = sym(k->handle, "fk_free");
    printf("dlopen %s: %.0f ms\n", path, (now() - t) * 1000);
    return 0;
}

static char *slurp(const char *path, int32_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        exit(2);
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc(n + 1);
    if (fread(buf, 1, n, f) != (size_t)n) exit(2);
    buf[n] = 0;
    fclose(f);
    *len = (int32_t)n;
    return buf;
}

static int report(kernel *k, const char *what, int32_t rc) {
    if (rc == 0) return 0;
    char *e = k->last_error();
    fprintf(stderr, "FAIL %s: %s\n", what, e);
    k->release(e);
    return -1;
}

static int connect_tcp(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(port)};
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    struct timeval tv = {.tv_sec = 10};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    if (connect(fd, (struct sockaddr *)&a, sizeof a) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int connect_unix(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un a = {.sun_family = AF_UNIX};
    snprintf(a.sun_path, sizeof a.sun_path, "%s", path);
    struct timeval tv = {.tv_sec = 10};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    if (connect(fd, (struct sockaddr *)&a, sizeof a) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int read_all(int fd, char *buf, int cap) {
    int n = 0, r;
    while (n < cap - 1 && (r = read(fd, buf + n, cap - 1 - n)) > 0) n += r;
    buf[n] = 0;
    return n;
}

/* SOCKS5 CONNECT 127.0.0.1:http_port, then HTTP/1.0 GET /. */
static int quiet;

static int fetch_via_socks(int proxy_port, int http_port) {
    int fd = -1;
    for (int i = 0; i < 50 && fd < 0; i++) {
        fd = connect_tcp(proxy_port);
        if (fd < 0) usleep(100000);
    }
    if (fd < 0) {
        fprintf(stderr, "FAIL connect proxy %d: %s\n", proxy_port, strerror(errno));
        return -1;
    }
    unsigned char hello[] = {5, 1, 0}, reply[16];
    if (write(fd, hello, 3) != 3 || read(fd, reply, 2) != 2 || reply[0] != 5 || reply[1] != 0) {
        fprintf(stderr, "FAIL socks greeting\n");
        close(fd);
        return -1;
    }
    unsigned char req[10] = {5, 1, 0, 1, 127, 0, 0, 1, (unsigned char)(http_port >> 8), (unsigned char)http_port};
    if (write(fd, req, 10) != 10 || read(fd, reply, 10) != 10 || reply[1] != 0) {
        fprintf(stderr, "FAIL socks connect (rep=%d)\n", reply[1]);
        close(fd);
        return -1;
    }
    const char *get = "GET /probe.txt HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n";
    if (write(fd, get, strlen(get)) < 0) return -1;
    static char buf[8192];
    read_all(fd, buf, sizeof buf);
    close(fd);
    int ok = strstr(buf, " 200 ") && strstr(buf, "fk-probe-ok");
    if (!quiet) printf("fetch via socks5 :%d -> %s\n", proxy_port, ok ? "HTTP 200 fk-probe-ok" : "BAD");
    if (!ok) fprintf(stderr, "response: %.300s\n", buf);
    return ok ? 0 : -1;
}

static int query_controller(const char *spec) {
    int fd = -1;
    int grpc = strncmp(spec, "sock:", 5) == 0;
    for (int i = 0; i < 50 && fd < 0; i++) {
        if (strncmp(spec, "tcp:", 4) == 0) fd = connect_tcp(atoi(spec + 4));
        else fd = connect_unix(spec + 5);
        if (fd < 0) usleep(100000);
    }
    if (fd < 0) {
        fprintf(stderr, "FAIL controller %s: %s\n", spec, strerror(errno));
        return -1;
    }
    if (grpc) {
        printf("controller %s: connected (gRPC API listening)\n", spec);
        close(fd);
        return 0;
    }
    const char *get = "GET /version HTTP/1.0\r\nHost: localhost\r\nAuthorization: Bearer fk-probe\r\n\r\n";
    if (write(fd, get, strlen(get)) < 0) return -1;
    char buf[4096];
    read_all(fd, buf, sizeof buf);
    close(fd);
    char *body = strstr(buf, "\r\n\r\n");
    int ok = strstr(buf, " 200 ") != NULL;
    printf("controller %s GET /version -> %s %s\n", spec, ok ? "200" : "BAD", body ? body + 4 : "");
    return ok ? 0 : -1;
}

static int run(int argc, char **argv) {
    if (argc < 7) return 2;
    kernel k;
    if (load(&k, argv[2]) != 0) return 1;
    int32_t len;
    char *config = slurp(argv[3], &len);
    int proxy = atoi(argv[5]), http = atoi(argv[6]);
    const char *controller = argc > 7 ? argv[7] : NULL;
    if (report(&k, "fk_init", k.init(NULL, argv[4]))) return 1;
    char *v = k.version();
    printf("version: %s\n", v);
    k.release(v);
    if (report(&k, "fk_check", k.check(config, len))) return 1;
    printf("fk_check: ok\n");
    if (k.check("{not a config", 13) == 0) {
        fprintf(stderr, "FAIL fk_check accepted a broken config\n");
        return 1;
    }
    char *e = k.last_error();
    printf("fk_check(broken config) rejected as expected: %s\n", e);
    k.release(e);
    for (int round = 1; round <= 2; round++) {
        double t = now();
        if (report(&k, "fk_start", k.start(config, len, -1))) return 1;
        printf("round %d fk_start: %.0f ms\n", round, (now() - t) * 1000);
        if (fetch_via_socks(proxy, http)) return 1;
        if (controller && query_controller(controller)) return 1;
        t = now();
        if (report(&k, "fk_stop", k.stop())) return 1;
        printf("round %d fk_stop: %.0f ms\n", round, (now() - t) * 1000);
        int fd = connect_tcp(proxy);
        if (fd >= 0) {
            fprintf(stderr, "FAIL proxy port %d still open after fk_stop\n", proxy);
            return 1;
        }
        printf("round %d port %d released\n", round, proxy);
    }
    printf("PASS %s\n", argv[2]);
    return 0;
}

static int two(int argc, char **argv) {
    if (argc < 7) return 2;
    kernel a, b;
    int32_t la, lb;
    char *ca = slurp(argv[3], &la), *cb = slurp(argv[5], &lb);
    if (load(&a, argv[2]) != 0) return 1;
    if (report(&a, "A fk_init", a.init(NULL, argv[6]))) return 1;
    if (report(&a, "A fk_start", a.start(ca, la, -1))) return 1;
    quiet = 1;
    printf("A started; loading B into the same process\n");
    fflush(stdout);
    if (load(&b, argv[4]) != 0) {
        printf("RESULT second library refused by dlopen\n");
        return 3;
    }
    printf("B dlopen returned\n");
    fflush(stdout);
    char *v = b.version();
    printf("B version: %s\n", v);
    fflush(stdout);
    int32_t rc = b.init(NULL, argv[6]);
    printf("B fk_init rc=%d\n", rc);
    fflush(stdout);
    rc = b.start(cb, lb, -1);
    printf("B fk_start rc=%d\n", rc);
    fflush(stdout);
    if (rc != 0 || argc < 10) {
        printf("RESULT second kernel did not start (rc=%d)\n", rc);
        return 5;
    }
    /* Both runtimes serve traffic for a while: goroutine preemption (SIGURG),
     * GC and the netpollers of both run concurrently. */
    int pa = atoi(argv[7]), pb = atoi(argv[8]), http = atoi(argv[9]), failures = 0;
    double t = now();
    int i = 0;
    for (; now() - t < 20; i++) failures += (fetch_via_socks(i % 2 ? pb : pa, http) != 0);
    printf("RESULT both kernels in one process: %d fetches in 20 s, %d failed, no crash "
           "(undefined behaviour per golang/go#18976; not a supported state)\n", i, failures);
    return 4;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc > 1 && strcmp(argv[1], "run") == 0) return run(argc, argv);
    if (argc > 1 && strcmp(argv[1], "two") == 0) return two(argc, argv);
    fprintf(stderr, "usage: harness run|two ...\n");
    return 2;
}
