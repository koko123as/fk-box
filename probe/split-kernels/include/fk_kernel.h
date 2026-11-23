/*
 * fk-box split-kernel C ABI (probe). Every kernel library (libsingbox.so,
 * libmihomo.so, libxray.so) exports exactly these symbols. At most one of
 * them may be loaded in a process: each one carries its own Go runtime.
 *
 * Strings are UTF-8. Buffers returned by the library are freed with fk_free.
 * Return value 0 means success; on failure fk_last_error() describes it.
 */
#ifndef FK_KERNEL_H
#define FK_KERNEL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Platform callbacks supplied by the Rust host. Every pointer may be NULL
 * (desktop harness); the kernel then uses its own defaults. The struct must
 * stay valid until the process exits. */
typedef struct fk_host {
    void *ctx;
    /* VpnService.protect(fd): 0 on success. */
    int32_t (*protect)(void *ctx, int32_t fd);
    /* sing-box only: build the TUN from the kernel's options (JSON) and
     * return its fd in *fd_out. */
    int32_t (*open_tun)(void *ctx, const char *options_json, int32_t *fd_out);
    /* Connection owner: uid in *uid_out, package name in pkg_out. */
    int32_t (*find_owner)(void *ctx, int32_t ip_protocol,
                          const char *src, int32_t src_port,
                          const char *dst, int32_t dst_port,
                          int32_t *uid_out, char *pkg_out, int32_t pkg_cap);
    /* sing-box only: JSON array of the device's network interfaces
     * (index, mtu, name, addresses, flags, type, dns, metered), written to
     * out (at most cap bytes); returns the length or -1. */
    int32_t (*interfaces)(void *ctx, char *out, int32_t cap);
} fk_host;

/* cgo shims define FK_KERNEL_TYPES_ONLY: their exports come from _cgo_export.h. */
#ifndef FK_KERNEL_TYPES_ONLY
int32_t fk_init(const fk_host *host, const char *home_dir);
int32_t fk_check(const char *config, int32_t len);
/* tun_fd < 0: no TUN (desktop/proxy mode). sing-box ignores tun_fd and asks
 * open_tun instead. Calling fk_start while running reloads. */
int32_t fk_start(const char *config, int32_t len, int32_t tun_fd);
int32_t fk_stop(void);
/* Default network changed: {"index":N,"name":"wlan0","dns":["1.1.1.1"]};
 * index -1 means no network. */
int32_t fk_network_changed(const char *json);
char *fk_version(void);
char *fk_last_error(void);
void fk_free(char *p);
#endif

#ifdef __cplusplus
}
#endif

#endif
