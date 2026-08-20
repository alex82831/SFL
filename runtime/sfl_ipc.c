/*
 * Talking to somebody else: subprocesses joined by pipes, TCP and UDP sockets,
 * TLS, byte buffers, line-oriented file handles, named pipes and signals.
 *
 * The interpreter builds all of this out of the same POSIX calls, so the shape of
 * what a program observes is the JDK's rather than POSIX's: a reader is a
 * BufferedReader, which means readLine() accepts "\n", "\r" and "\r\n" and
 * remembers a dangling carriage return until the next call; a socket timeout
 * yields null rather than an error; a child killed by a signal reports
 * 128 + signal; and a write to a file is buffered until something flushes it
 * while a write to a socket or a child is flushed at once. All of that is
 * reproduced here on top of plain file descriptors.
 *
 * A live resource is an SFL_HANDLE whose payload is a struct this file owns. The
 * collector traces the handle but knows nothing about the payload, so every
 * close() frees it and stores NULL in its place: that null is also the "already
 * closed" flag, which is what makes closing twice the no-op it is in Java and
 * what turns use-after-close into the same "Socket closed" error the JDK raises.
 *
 * TLS is an OpenSSL or LibreSSL found at run time, reached through dlopen so
 * that a machine without one still links and only fails when a program actually
 * wraps a connection. The HTTP builtins live in stdlib/http.sfl on top of the
 * sockets and TLS here; nothing in the runtime speaks HTTP any more.
 */

/* Asks glibc for POSIX.2008 (getaddrinfo, poll, sigaction, strcasecmp) and
   Darwin for its full set, which _POSIX_C_SOURCE would otherwise narrow. */
#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE 1

#include "sfl.h"

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------------- */
/* Small shared helpers                                                       */
/* ------------------------------------------------------------------------- */

static char *dup_cstr(const char *s) {
  size_t n = strlen(s) + 1;
  char *out = (char *)sfl_raw_alloc(n);
  memcpy(out, s, n);
  return out;
}

/* The UTF-8 of a value's display form, NUL-terminated, through the charset
   encoder's rules: everything written here leaves the process, and the
   interpreter sends it through getBytes(UTF_8), which spells an unpaired
   surrogate '?'. `len` is the byte count taken from the string rather than from
   strlen, so an embedded U+0000 is written out instead of cutting the text
   short. The caller frees it. */
static char *display_bytes(SflVal v, size_t *len) {
  SflVal s = sfl_display(v);
  int64_t n = sfl_utf8_java_len(s);
  char *buf = (char *)sfl_raw_alloc((size_t)n + 1);
  sfl_utf8_java_write(s, buf);
  buf[n] = '\0';
  if (len != NULL) *len = (size_t)n;
  return buf;
}

static void put_field(SflVal o, const char *key, SflVal v) {
  sfl_obj_put(o, sfl_str_utf8(key, -1), v);
}

/*
 * A write to a pipe or a socket the other end has dropped kills a C program by
 * default; the JVM disarms SIGPIPE at startup so that Java sees an IOException
 * instead, and a program compiled from the same source has to see the same
 * thing. An existing handler is left alone, in case the program installed one.
 */
static void ignore_sigpipe(void) {
  static int done;
  if (done) return;
  done = 1;
  struct sigaction old;
  if (sigaction(SIGPIPE, NULL, &old) != 0) return;
  if (old.sa_handler != SIG_DFL) return;
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_handler = SIG_IGN;
  sigaction(SIGPIPE, &sa, NULL);
}

static ssize_t read_retry(int fd, void *buf, size_t n) {
  ssize_t got;
  do {
    got = read(fd, buf, n);
  } while (got < 0 && errno == EINTR);
  return got;
}

/* Drains the whole buffer, since a pipe or a socket may take it in pieces. */
static int write_all(int fd, const char *buf, size_t n) {
  size_t off = 0;
  while (off < n) {
    ssize_t put = write(fd, buf + off, n - off);
    if (put < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    off += (size_t)put;
  }
  return 0;
}

/* Deliberately does not retry: after EINTR the descriptor is already gone on
   Linux, so a second close would land on whatever was opened next. */
static void close_fd(int fd) {
  if (fd >= 0) close(fd);
}

/* Milliseconds left before `deadline`, clamped to what poll(2) takes. */
static int millis_left(int64_t deadline) {
  int64_t left = deadline - sfl_time_millis();
  if (left < 0) return 0;
  if (left > 2000000000LL) return 2000000000;
  return (int)left;
}

/* Waits for one of `events`; 1 ready, 0 timed out, -1 failed. A timeout of 0 or
   less waits forever, as setSoTimeout(0) does. */
static int wait_ready(int fd, short events, int timeout_ms) {
  int64_t deadline = timeout_ms > 0 ? sfl_time_millis() + timeout_ms : 0;
  for (;;) {
    struct pollfd p;
    p.fd = fd;
    p.events = events;
    p.revents = 0;
    int rc = poll(&p, 1, timeout_ms > 0 ? millis_left(deadline) : -1);
    if (rc < 0) {
      if (errno == EINTR) continue; /* a signal is not the timeout expiring */
      return -1;
    }
    return rc == 0 ? 0 : 1;
  }
}

/* ------------------------------------------------------------------------- */
/* TLS, on an OpenSSL or LibreSSL found at run time                           */
/* ------------------------------------------------------------------------- */

/*
 * Nothing here includes openssl headers: the client-side entry points and the
 * handful of constants below are declared by hand, so the runtime builds on a
 * machine with no TLS development files and a program that never wraps a
 * connection runs on a machine with no TLS library at all.
 *
 * The descriptor is switched to non-blocking for the life of the TLS session and
 * every SSL_read/SSL_write is driven through poll, because a renegotiating read
 * may need to write and vice versa — and because that is the only way a read
 * deadline can be honoured mid-record. The interpreter runs this same loop.
 */

typedef struct Tls {
  void *ssl;      /* SSL* */
  void *own_ctx;  /* an SSL_CTX private to this connection (custom CA), or NULL */
  char proto[32]; /* the ALPN protocol the handshake settled on, or ""          */
  char err[256];  /* the reason of the last failure, when errno cannot say      */
} Tls;

#define TLS_VERIFY_PEER 1
#define TLS_CTRL_SET_TLSEXT_HOSTNAME 55
#define TLS_NAMETYPE_HOST 0
#define TLS_CTRL_SET_MIN_PROTO 123
#define TLS_1_2_VERSION 0x0303
#define TLS_ERR_WANT_READ 2
#define TLS_ERR_WANT_WRITE 3
#define TLS_ERR_SYSCALL 5
#define TLS_ERR_ZERO_RETURN 6

static void *tls_lib;
static void *tls_shared_ctx;
static pthread_mutex_t tls_lock = PTHREAD_MUTEX_INITIALIZER;

static int (*p_tls_init)(uint64_t, const void *);
static void *(*p_ctx_new)(const void *);
static const void *(*p_client_method)(void);
static void (*p_ctx_set_verify)(void *, int, void *);
static int (*p_ctx_default_paths)(void *);
static int (*p_ctx_load_verify)(void *, const char *, const char *);
static long (*p_ctx_ctrl)(void *, int, long, void *);
static int (*p_ctx_set_min_proto)(void *, int);
static void (*p_ctx_free)(void *);
static void *(*p_ssl_new)(void *);
static int (*p_ssl_set_fd)(void *, int);
static long (*p_ssl_ctrl)(void *, int, long, void *);
static int (*p_ssl_set1_host)(void *, const char *);
static void *(*p_ssl_get0_param)(void *);
static int (*p_param_set1_host)(void *, const char *, size_t);
static int (*p_param_set1_ip)(void *, const char *);
static int (*p_ssl_connect)(void *);
static int (*p_ssl_read)(void *, void *, int);
static int (*p_ssl_write)(void *, const void *, int);
static int (*p_ssl_pending)(const void *);
static int (*p_ssl_shutdown)(void *);
static void (*p_ssl_free)(void *);
static int (*p_ssl_get_error)(const void *, int);
static long (*p_ssl_verify_result)(const void *);
static const char *(*p_verify_string)(long);
static unsigned long (*p_err_get_error)(void);
static const char *(*p_err_reason)(unsigned long);
static void (*p_err_clear)(void);
/* The server side, loaded with the rest and required only by tlsAccept. */
static const void *(*p_server_method)(void);
static int (*p_use_cert_chain)(void *, const char *);
static int (*p_use_priv_key)(void *, const char *, int);
static int (*p_check_priv_key)(const void *);
static int (*p_ssl_accept)(void *);
static void (*p_ctx_set_alpn_cb)(void *, void *, void *);
static void (*p_get0_alpn)(const void *, const unsigned char **, unsigned int *);
static int (*p_set_alpn_protos)(void *, const unsigned char *, unsigned int);

static void *tls_sym(const char *name) {
  /* dlsym hands back an object pointer; every platform this runs on lets it be
     a function pointer too, which is the only way to reach a library by name. */
  return dlsym(tls_lib, name);
}

static unsigned char *alpn_wire(SflVal *argv, int i, const char *fn, size_t *out_len);

static void tls_missing(const char *fn) __attribute__((noreturn));
static void tls_missing(const char *fn) {
  char msg[256];
  snprintf(msg, sizeof msg, "%s: no TLS library was found, so this program cannot make TLS connections",
           fn);
  sfl_raise_hint(msg, "install OpenSSL 3: via Homebrew on macOS, the libssl3 package on Linux");
}

/*
 * The interpreter probes the same names in the same order, so on one machine
 * both engines negotiate with the same library. The unversioned system names
 * are deliberately absent on macOS: Apple's /usr/lib/libssl.dylib aborts the
 * process on load, while the versioned LibreSSL alongside it loads and works.
 */
static void tls_load_locked(const char *fn) {
  if (p_ssl_connect != NULL) return;
  static const char *const names[] = {
      "/opt/homebrew/opt/openssl@3/lib/libssl.3.dylib", /* Homebrew, Apple Silicon */
      "/usr/local/opt/openssl@3/lib/libssl.3.dylib",    /* Homebrew, Intel         */
      "/opt/local/lib/libssl.3.dylib",                  /* MacPorts                */
      "libssl.so.3",     "libssl.so.1.1",               /* Linux                   */
      "libssl.48.dylib", "libssl.47.dylib",             /* macOS system LibreSSL   */
      "libssl.46.dylib", "libssl.so",                   NULL};
  for (int i = 0; names[i] != NULL && tls_lib == NULL; i++)
    tls_lib = dlopen(names[i], RTLD_LAZY | RTLD_LOCAL);
  if (tls_lib == NULL) tls_missing(fn);

  *(void **)&p_tls_init = tls_sym("OPENSSL_init_ssl");
  *(void **)&p_ctx_new = tls_sym("SSL_CTX_new");
  *(void **)&p_client_method = tls_sym("TLS_client_method");
  *(void **)&p_ctx_set_verify = tls_sym("SSL_CTX_set_verify");
  *(void **)&p_ctx_default_paths = tls_sym("SSL_CTX_set_default_verify_paths");
  *(void **)&p_ctx_load_verify = tls_sym("SSL_CTX_load_verify_locations");
  *(void **)&p_ctx_ctrl = tls_sym("SSL_CTX_ctrl");
  *(void **)&p_ctx_set_min_proto = tls_sym("SSL_CTX_set_min_proto_version");
  *(void **)&p_ctx_free = tls_sym("SSL_CTX_free");
  *(void **)&p_ssl_new = tls_sym("SSL_new");
  *(void **)&p_ssl_set_fd = tls_sym("SSL_set_fd");
  *(void **)&p_ssl_ctrl = tls_sym("SSL_ctrl");
  *(void **)&p_ssl_set1_host = tls_sym("SSL_set1_host");
  *(void **)&p_ssl_get0_param = tls_sym("SSL_get0_param");
  *(void **)&p_param_set1_host = tls_sym("X509_VERIFY_PARAM_set1_host");
  *(void **)&p_param_set1_ip = tls_sym("X509_VERIFY_PARAM_set1_ip_asc");
  *(void **)&p_ssl_connect = tls_sym("SSL_connect");
  *(void **)&p_ssl_read = tls_sym("SSL_read");
  *(void **)&p_ssl_write = tls_sym("SSL_write");
  *(void **)&p_ssl_pending = tls_sym("SSL_pending");
  *(void **)&p_ssl_shutdown = tls_sym("SSL_shutdown");
  *(void **)&p_ssl_free = tls_sym("SSL_free");
  *(void **)&p_ssl_get_error = tls_sym("SSL_get_error");
  *(void **)&p_ssl_verify_result = tls_sym("SSL_get_verify_result");
  *(void **)&p_verify_string = tls_sym("X509_verify_cert_error_string");
  *(void **)&p_err_get_error = tls_sym("ERR_get_error");
  *(void **)&p_err_reason = tls_sym("ERR_reason_error_string");
  *(void **)&p_err_clear = tls_sym("ERR_clear_error");
  *(void **)&p_server_method = tls_sym("TLS_server_method");
  *(void **)&p_use_cert_chain = tls_sym("SSL_CTX_use_certificate_chain_file");
  *(void **)&p_use_priv_key = tls_sym("SSL_CTX_use_PrivateKey_file");
  *(void **)&p_check_priv_key = tls_sym("SSL_CTX_check_private_key");
  *(void **)&p_ssl_accept = tls_sym("SSL_accept");
  *(void **)&p_ctx_set_alpn_cb = tls_sym("SSL_CTX_set_alpn_select_cb");
  *(void **)&p_get0_alpn = tls_sym("SSL_get0_alpn_selected");
  *(void **)&p_set_alpn_protos = tls_sym("SSL_set_alpn_protos");
  if (p_ctx_new == NULL || p_client_method == NULL || p_ctx_set_verify == NULL ||
      p_ctx_default_paths == NULL || p_ssl_new == NULL || p_ssl_set_fd == NULL ||
      p_ssl_ctrl == NULL || p_ssl_connect == NULL || p_ssl_read == NULL ||
      p_ssl_write == NULL || p_ssl_pending == NULL || p_ssl_free == NULL ||
      p_ssl_get_error == NULL) {
    dlclose(tls_lib);
    tls_lib = NULL;
    p_ssl_connect = NULL;
    tls_missing(fn);
  }
  if (p_tls_init != NULL) p_tls_init(0, NULL);
}

/* Fetches the library's reason for the last failure into t->err. */
static void tls_error_text(Tls *t, const char *fallback) {
  unsigned long e = p_err_get_error != NULL ? p_err_get_error() : 0;
  const char *reason = (e != 0 && p_err_reason != NULL) ? p_err_reason(e) : NULL;
  snprintf(t->err, sizeof t->err, "%s", reason != NULL ? reason : fallback);
}

/*
 * A verifying client context. `ca_file` overrides the trust store; NULL takes
 * the library's default paths, which also honour SSL_CERT_FILE and SSL_CERT_DIR
 * from the environment. Raises on failure. Called with tls_lock held.
 */
static void *tls_ctx_new_locked(const char *fn, const char *ca_file) {
  if (p_err_clear != NULL) p_err_clear();
  void *ctx = p_ctx_new(p_client_method());
  if (ctx == NULL) sfl_raise_sig(fn, "could not create a TLS context");
  p_ctx_set_verify(ctx, TLS_VERIFY_PEER, NULL);
  /* SSL_CTX_set_min_proto_version is a real function in LibreSSL and a macro
     over SSL_CTX_ctrl in OpenSSL, so try the symbol first and fall back. */
  if (p_ctx_set_min_proto != NULL) p_ctx_set_min_proto(ctx, TLS_1_2_VERSION);
  else if (p_ctx_ctrl != NULL) p_ctx_ctrl(ctx, TLS_CTRL_SET_MIN_PROTO, TLS_1_2_VERSION, NULL);
  int ok;
  if (ca_file != NULL) {
    if (p_ctx_load_verify == NULL) {
      p_ctx_free(ctx);
      sfl_raise_sig(fn, "this TLS library cannot load a CA file");
    }
    ok = p_ctx_load_verify(ctx, ca_file, NULL);
  } else {
    ok = p_ctx_default_paths(ctx);
  }
  if (ok != 1) {
    p_ctx_free(ctx);
    if (ca_file != NULL) {
      char msg[512];
      snprintf(msg, sizeof msg, "cannot read the CA file '%s'", ca_file);
      sfl_raise_sig(fn, "%s", msg);
    }
    sfl_raise_sig(fn, "could not load the system certificate store");
  }
  return ctx;
}

/* Waits for what the last SSL call asked for; 1 ready, 0 deadline hit, -1 failed.
   A deadline of 0 waits forever. */
static int tls_wait(int fd, int want_write, int64_t deadline) {
  int ms = 0;
  if (deadline > 0) {
    ms = millis_left(deadline);
    if (ms <= 0) return 0;
  }
  return wait_ready(fd, want_write ? POLLOUT : POLLIN, ms);
}

/* 0 connected, -1 failed with t->err set, -2 the deadline expired. `accepting`
   picks the server side of the handshake. */
static int tls_handshake(Tls *t, int fd, int64_t deadline, int accepting) {
  for (;;) {
    if (p_err_clear != NULL) p_err_clear();
    errno = 0;
    int rc = accepting ? p_ssl_accept(t->ssl) : p_ssl_connect(t->ssl);
    if (rc == 1) {
      /* Remember what ALPN settled on, for tlsProto and the h2 dispatch. */
      if (p_get0_alpn != NULL) {
        const unsigned char *sel = NULL;
        unsigned int n = 0;
        p_get0_alpn(t->ssl, &sel, &n);
        if (sel != NULL && n > 0 && n < sizeof t->proto) {
          memcpy(t->proto, sel, n);
          t->proto[n] = '\0';
        }
      }
      return 0;
    }
    int err = p_ssl_get_error(t->ssl, rc);
    if (err == TLS_ERR_WANT_READ || err == TLS_ERR_WANT_WRITE) {
      int ready = tls_wait(fd, err == TLS_ERR_WANT_WRITE, deadline);
      if (ready == 0) return -2;
      if (ready < 0) {
        snprintf(t->err, sizeof t->err, "%s", strerror(errno));
        return -1;
      }
      continue;
    }
    if (err == TLS_ERR_SYSCALL && errno == EINTR) continue;
    long vr = p_ssl_verify_result != NULL ? p_ssl_verify_result(t->ssl) : 0;
    if (vr != 0 && p_verify_string != NULL) {
      /* X509_V_OK is 0; anything else is why the certificate was rejected. */
      snprintf(t->err, sizeof t->err, "certificate verify failed: %s", p_verify_string(vr));
    } else if (err == TLS_ERR_SYSCALL) {
      snprintf(t->err, sizeof t->err, "%s",
               errno != 0 ? strerror(errno) : "the connection was closed during the handshake");
    } else {
      tls_error_text(t, "the TLS handshake failed");
    }
    return -1;
  }
}

/* >0 bytes read, 0 end of stream, -1 timed out, -2 failed with t->err set. */
static int tls_read(Tls *t, int fd, char *buf, size_t cap, int timeout_ms) {
  int64_t deadline = timeout_ms > 0 ? sfl_time_millis() + timeout_ms : 0;
  int take = cap > (size_t)INT32_MAX ? INT32_MAX : (int)cap;
  for (;;) {
    if (p_err_clear != NULL) p_err_clear();
    errno = 0;
    int n = p_ssl_read(t->ssl, buf, take);
    if (n > 0) return n;
    int err = p_ssl_get_error(t->ssl, n);
    if (err == TLS_ERR_ZERO_RETURN) return 0;
    if (err == TLS_ERR_WANT_READ || err == TLS_ERR_WANT_WRITE) {
      int ready = tls_wait(fd, err == TLS_ERR_WANT_WRITE, deadline);
      if (ready == 0) return -1;
      if (ready < 0) {
        snprintf(t->err, sizeof t->err, "%s", strerror(errno));
        return -2;
      }
      continue;
    }
    if (err == TLS_ERR_SYSCALL) {
      if (errno == EINTR) continue;
      /* The peer went away without a close_notify; readers treat it as EOF,
         which is what the JDK's SSLSocket does for a truncated stream too. */
      if (errno == 0) return 0;
      snprintf(t->err, sizeof t->err, "%s", strerror(errno));
      return -2;
    }
    tls_error_text(t, "the TLS connection failed");
    return -2;
  }
}

/* Drains the whole buffer through the session; 0 ok, -1 failed with t->err set. */
static int tls_write_all(Tls *t, int fd, const char *buf, size_t n) {
  size_t off = 0;
  while (off < n) {
    if (p_err_clear != NULL) p_err_clear();
    errno = 0;
    size_t left = n - off;
    int take = left > (size_t)INT32_MAX ? INT32_MAX : (int)left;
    int put = p_ssl_write(t->ssl, buf + off, take);
    if (put > 0) {
      off += (size_t)put;
      continue;
    }
    int err = p_ssl_get_error(t->ssl, put);
    if (err == TLS_ERR_WANT_READ || err == TLS_ERR_WANT_WRITE) {
      int ready = tls_wait(fd, err == TLS_ERR_WANT_WRITE, 0);
      if (ready < 0) {
        snprintf(t->err, sizeof t->err, "%s", strerror(errno));
        return -1;
      }
      continue;
    }
    if (err == TLS_ERR_SYSCALL && errno == EINTR) continue;
    if (err == TLS_ERR_SYSCALL && errno != 0) {
      snprintf(t->err, sizeof t->err, "%s", strerror(errno));
      return -1;
    }
    tls_error_text(t, "the TLS connection failed");
    return -1;
  }
  return 0;
}

static int tls_pending(Tls *t) { return t->ssl != NULL && p_ssl_pending(t->ssl) > 0; }

/* Sends a quiet close_notify and releases the session; never blocks or raises. */
static void tls_free_conn(Tls *t) {
  if (t == NULL) return;
  if (t->ssl != NULL) {
    if (p_ssl_shutdown != NULL) p_ssl_shutdown(t->ssl); /* one best-effort try */
    p_ssl_free(t->ssl);
  }
  if (t->own_ctx != NULL) p_ctx_free(t->own_ctx);
  sfl_raw_free(t);
}

/* ------------------------------------------------------------------------- */
/* Handles                                                                    */
/* ------------------------------------------------------------------------- */

static void *handle_of(SflVal *argv, int i, const char *kind, const char *fn) {
  SflVal v = argv[i];
  if (v->tag == SFL_HANDLE && strcmp(v->u.h.kind, kind) == 0) return v->u.h.payload;
  /* Deliberately terser than the generic argument check: this is what the
     interpreter's handleOf reports, without the value's repr. */
  sfl_raise_sig(fn, "argument %d must be a %s, got %s", i + 1, kind, sfl_type_name(v));
}

/* ------------------------------------------------------------------------- */
/* A BufferedReader over a descriptor                                         */
/* ------------------------------------------------------------------------- */

/*
 * Line splitting happens on the bytes rather than after decoding, which is safe
 * because no byte of a multi-byte UTF-8 sequence can be '\n' or '\r'; only the
 * finished line is decoded. `skip_lf` is BufferedReader's own carry flag: a line
 * that ended at '\r' swallows a '\n' at the start of the next one.
 */
typedef struct {
  int fd;
  Tls *tls; /* reads go through the TLS session once a connection is wrapped */
  char *buf;
  size_t cap, len, pos;
  char *held; /* a partial line a timeout interrupted */
  size_t held_len;
  int eof;
  int skip_lf;
} LineIn;

#define IO_BUFSZ 8192

static void line_in_init(LineIn *r, int fd) {
  memset(r, 0, sizeof *r);
  r->fd = fd;
}

static void line_in_free(LineIn *r) {
  sfl_raw_free(r->buf);
  sfl_raw_free(r->held);
  r->buf = NULL;
  r->held = NULL;
}

/* A read would not block: bytes already buffered, a held partial line, recorded
   EOF, or a decrypted TLS record the descriptor cannot show. poll() counts these
   as ready without touching the descriptor, because the bytes the descriptor once
   had are now in here and only a read can see them. */
static int line_in_buffered(const LineIn *r) {
  return r->pos < r->len || r->held != NULL || r->eof ||
         (r->tls != NULL && tls_pending(r->tls));
}

/* What to blame a failed read on: the TLS session's reason when it has one,
   errno otherwise. */
static const char *line_in_why(const LineIn *r) {
  if (r->tls != NULL && r->tls->err[0] != '\0') return r->tls->err;
  return strerror(errno);
}

/* 1 there is data, 0 end of stream, -1 timed out, -2 failed with errno set. */
static int line_fill(LineIn *r, int timeout_ms) {
  if (r->pos < r->len) return 1;
  r->pos = r->len = 0;
  if (r->eof) return 0;
  if (r->buf == NULL) { /* like the JDK's lazily created reader, only on first use */
    r->buf = (char *)sfl_raw_alloc(IO_BUFSZ);
    r->cap = IO_BUFSZ;
  }
  if (r->tls != NULL) {
    /* The TLS loop polls for itself: a mid-record read may need to write. */
    int got = tls_read(r->tls, r->fd, r->buf, r->cap, timeout_ms);
    if (got < 0) return got;
    if (got == 0) {
      r->eof = 1;
      return 0;
    }
    r->len = (size_t)got;
    return 1;
  }
  if (timeout_ms > 0) {
    int ready = wait_ready(r->fd, POLLIN, timeout_ms);
    if (ready == 0) return -1;
    if (ready < 0) return -2;
  }
  ssize_t got = read_retry(r->fd, r->buf, r->cap);
  if (got < 0) return -2;
  if (got == 0) {
    r->eof = 1;
    return 0;
  }
  r->len = (size_t)got;
  return 1;
}

/*
 * BufferedReader.readLine, byte for byte: 1 with a line in *out, 0 at the end of
 * the stream, -1 timed out, -2 failed. Where the JDK throws away whatever it had
 * accumulated when a read times out, this parks it in `held` and picks it up
 * next time, so a slow peer cannot lose the front of a line.
 */
static int line_read(LineIn *r, int timeout_ms, char **out, size_t *outlen) {
  char *acc = r->held;
  size_t alen = r->held_len, acap = r->held_len;
  r->held = NULL;
  r->held_len = 0;
  int first = 1;
  for (;;) {
    int st = line_fill(r, timeout_ms);
    if (st == 0) {
      if (alen > 0) {
        *out = acc;
        *outlen = alen;
        return 1;
      }
      sfl_raw_free(acc);
      return 0;
    }
    if (st < 0) {
      r->held = acc;
      r->held_len = alen;
      return st;
    }
    if (first && r->skip_lf && r->buf[r->pos] == '\n') r->pos++;
    r->skip_lf = 0;
    first = 0;
    size_t i = r->pos;
    while (i < r->len && r->buf[i] != '\n' && r->buf[i] != '\r') i++;
    size_t seg = i - r->pos;
    if (seg > 0) {
      if (alen + seg + 1 > acap) {
        acap = (alen + seg + 1) * 2;
        acc = (char *)sfl_raw_realloc(acc, acap);
      }
      memcpy(acc + alen, r->buf + r->pos, seg);
      alen += seg;
    }
    r->pos = i;
    if (i < r->len) {
      char c = r->buf[r->pos++];
      if (c == '\r') r->skip_lf = 1;
      if (acc == NULL) acc = (char *)sfl_raw_alloc(1);
      *out = acc;
      *outlen = alen;
      return 1;
    }
  }
}

/* Everything the stream still has. NULL means the read failed. */
static char *line_slurp(LineIn *r, size_t *outlen) {
  size_t cap = IO_BUFSZ, n = 0;
  char *out = (char *)sfl_raw_alloc(cap);
  if (r->held != NULL) {
    while (n + r->held_len + 1 > cap) cap *= 2;
    out = (char *)sfl_raw_realloc(out, cap);
    memcpy(out, r->held, r->held_len);
    n = r->held_len;
    sfl_raw_free(r->held);
    r->held = NULL;
    r->held_len = 0;
  }
  for (;;) {
    if (r->pos >= r->len) {
      int st = line_fill(r, 0);
      if (st == 0) break;
      if (st < 0) {
        sfl_raw_free(out);
        return NULL;
      }
    }
    size_t seg = r->len - r->pos;
    if (n + seg + 1 > cap) {
      while (n + seg + 1 > cap) cap *= 2;
      out = (char *)sfl_raw_realloc(out, cap);
    }
    memcpy(out + n, r->buf + r->pos, seg);
    n += seg;
    r->pos = r->len;
  }
  out[n] = '\0';
  *outlen = n;
  return out;
}

/* ------------------------------------------------------------------------- */
/* A BufferedWriter over a descriptor                                         */
/* ------------------------------------------------------------------------- */

typedef struct {
  int fd;
  char *buf;
  size_t cap, len;
} LineOut;

static void line_out_init(LineOut *w, int fd) {
  memset(w, 0, sizeof *w);
  w->fd = fd;
}

static int line_out_flush(LineOut *w) {
  if (w->len == 0) return 0;
  size_t n = w->len;
  /* Emptied before the write, so a failure cannot be retried: an error only
     arrives after a partial write, and sending the buffer again would repeat
     whatever part of it already reached the other end. */
  w->len = 0;
  return write_all(w->fd, w->buf, n);
}

static int line_out_write(LineOut *w, const char *p, size_t n) {
  if (w->buf == NULL) {
    w->buf = (char *)sfl_raw_alloc(IO_BUFSZ);
    w->cap = IO_BUFSZ;
  }
  while (n > 0) {
    if (w->len == w->cap && line_out_flush(w) != 0) return -1;
    size_t room = w->cap - w->len;
    size_t take = n < room ? n : room;
    memcpy(w->buf + w->len, p, take);
    w->len += take;
    p += take;
    n -= take;
  }
  return 0;
}

static void line_out_free(LineOut *w) {
  sfl_raw_free(w->buf);
  w->buf = NULL;
}

/* ------------------------------------------------------------------------- */
/* Subprocesses                                                               */
/* ------------------------------------------------------------------------- */

typedef struct {
  pid_t pid;
  int in_fd; /* the child's standard input, -1 once closed */
  LineIn out;
  LineIn err;
  int reaped;
  int code;
} Child;

static Child *child_of(SflVal *argv, int i, const char *fn) {
  /* A process handle has no close, so its payload is live for as long as it is. */
  return (Child *)handle_of(argv, i, "process", fn);
}

/* Proc.splitCommand: whitespace separates, single and double quotes group and
   are themselves dropped. NULL-terminated, for execvp. */
static char **split_command(const char *cmd) {
  size_t n = strlen(cmd);
  char **out = (char **)sfl_raw_alloc((n + 2) * sizeof(char *));
  char *word = (char *)sfl_raw_alloc(n + 1);
  size_t count = 0, w = 0;
  char quote = ' ';
  int started = 0;
  for (size_t i = 0; i < n; i++) {
    char c = cmd[i];
    if (quote != ' ') {
      if (c == quote) quote = ' ';
      else word[w++] = c;
    } else if (c == '"' || c == '\'') {
      quote = c;
      started = 1;
    } else if (c == ' ' || c == '\t') {
      if (started) {
        word[w] = '\0';
        out[count++] = dup_cstr(word);
        w = 0;
        started = 0;
      }
    } else {
      word[w++] = c;
      started = 1;
    }
  }
  if (started) {
    word[w] = '\0';
    out[count++] = dup_cstr(word);
  }
  out[count] = NULL;
  sfl_raw_free(word);
  return out;
}

static void free_argv(char **args) {
  if (args == NULL) return;
  for (size_t i = 0; args[i] != NULL; i++) sfl_raw_free(args[i]);
  sfl_raw_free(args);
}

/* Collects the child's status once, since only the first waitpid sees it. */
static int child_reap(Child *c, int block) {
  if (c->reaped) return 1;
  int status = 0;
  pid_t r;
  do {
    r = waitpid(c->pid, &status, block ? 0 : WNOHANG);
  } while (r < 0 && errno == EINTR);
  if (r == 0) return 0;
  c->reaped = 1;
  if (r < 0) {
    c->code = 0; /* ECHILD: gone, and nobody can say with what status */
  } else if (WIFEXITED(status)) {
    c->code = WEXITSTATUS(status);
  } else if (WIFSIGNALED(status)) {
    c->code = 128 + WTERMSIG(status); /* what Process.exitValue reports */
  } else {
    c->code = 0;
  }
  return 1;
}

SflVal sfl_p_processStart(int64_t argc, SflVal *argv) {
  ignore_sigpipe();
  char **args = NULL;
  char *shown = NULL;
  char *head_shown = NULL; /* argv[0] as "Cannot run program" spells it */

  if (argv[0]->tag == SFL_STR) {
    /* exec sees the mallocCString spelling; the label and the failure message
       keep the command as the program wrote it. The two splits agree on word
       boundaries, since neither spelling of a surrogate contains an ASCII
       space, tab or quote. */
    char *cmd = sfl_str_dup_utf8_java(argv[0]);
    shown = sfl_str_dup_utf8(argv[0]);
    int use_shell = argc > 1 ? sfl_truthy(argv[1]) : 1;
    if (!use_shell) {
      args = split_command(cmd);
      char **words = split_command(shown);
      head_shown = words[0] != NULL ? dup_cstr(words[0]) : dup_cstr("");
      free_argv(words);
    } else {
      args = (char **)sfl_raw_alloc(4 * sizeof(char *));
      args[0] = dup_cstr("/bin/sh");
      args[1] = dup_cstr("-c");
      args[2] = dup_cstr(cmd);
      args[3] = NULL;
      head_shown = dup_cstr("/bin/sh");
    }
    sfl_raw_free(cmd);
  } else if (argv[0]->tag == SFL_ARR) {
    SflVal arr = argv[0];
    if (arr->aux == 0) sfl_raise_sig("processStart", "the argv array is empty");
    uint32_t n = arr->aux;
    args = (char **)sfl_raw_alloc(((size_t)n + 1) * sizeof(char *));
    for (uint32_t i = 0; i < n; i++) {
      /* Read the side buffer afresh each time: display() below can allocate,
         and no pointer into a heap object survives a collection unexamined. */
      args[i] = display_bytes(arr->u.a.items[i], NULL);
    }
    args[n] = NULL;
    /* The label joins the display forms themselves, as mkString does. */
    size_t total = 0;
    char **parts = (char **)sfl_raw_alloc((size_t)n * sizeof(char *));
    for (uint32_t i = 0; i < n; i++) {
      parts[i] = sfl_str_dup_utf8(sfl_display(arr->u.a.items[i]));
      total += strlen(parts[i]) + 1;
    }
    shown = (char *)sfl_raw_alloc(total + 1);
    size_t w = 0;
    for (uint32_t i = 0; i < n; i++) {
      if (i) shown[w++] = ' ';
      size_t len = strlen(parts[i]);
      memcpy(shown + w, parts[i], len);
      w += len;
    }
    shown[w] = '\0';
    head_shown = dup_cstr(parts[0]);
    for (uint32_t i = 0; i < n; i++) sfl_raw_free(parts[i]);
    sfl_raw_free(parts);
  } else {
    sfl_raise_sig("processStart", "expected a command string or argv array, got %s",
              sfl_type_name(argv[0]));
  }

  int inp[2] = {-1, -1}, outp[2] = {-1, -1}, errp[2] = {-1, -1}, failp[2] = {-1, -1};
  if (pipe(inp) != 0 || pipe(outp) != 0 || pipe(errp) != 0 || pipe(failp) != 0) {
    int saved = errno;
    for (int i = 0; i < 2; i++) {
      close_fd(inp[i]);
      close_fd(outp[i]);
      close_fd(errp[i]);
      close_fd(failp[i]);
    }
    free_argv(args);
    sfl_raw_free(shown);
    sfl_raw_free(head_shown);
    sfl_raise_sig("processStart", "%s", strerror(saved));
  }
  /* The child's end closes on a successful exec, which is how the parent learns
     that exec worked: a short read means it did. */
  fcntl(failp[1], F_SETFD, FD_CLOEXEC);

  pid_t pid = fork();
  if (pid == 0) {
    dup2(inp[0], STDIN_FILENO);
    dup2(outp[1], STDOUT_FILENO);
    dup2(errp[1], STDERR_FILENO);
    close_fd(inp[0]);
    close_fd(inp[1]);
    close_fd(outp[0]);
    close_fd(outp[1]);
    close_fd(errp[0]);
    close_fd(errp[1]);
    close_fd(failp[0]);
    /* The child gets a clean slate, as it does under ProcessBuilder: a pipeline
       that expects to die of SIGPIPE must be able to. */
    signal(SIGPIPE, SIG_DFL);
    if (args[0] != NULL) execvp(args[0], args);
    else errno = ENOENT; /* the command was blank, or nothing but quotes */
    int e = errno;
    ssize_t ignored = write(failp[1], &e, sizeof e);
    (void)ignored;
    _exit(127);
  }
  close_fd(inp[0]);
  close_fd(outp[1]);
  close_fd(errp[1]);
  close_fd(failp[1]);
  if (pid < 0) {
    int saved = errno;
    close_fd(inp[1]);
    close_fd(outp[0]);
    close_fd(errp[0]);
    close_fd(failp[0]);
    free_argv(args);
    sfl_raw_free(shown);
    sfl_raw_free(head_shown);
    sfl_raise_sig("processStart", "%s", strerror(saved));
  }

  int child_errno = 0;
  ssize_t got = read_retry(failp[0], &child_errno, sizeof child_errno);
  close_fd(failp[0]);
  if (got == (ssize_t)sizeof child_errno) {
    close_fd(inp[1]);
    close_fd(outp[0]);
    close_fd(errp[0]);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) continue;
    char msg[512];
    snprintf(msg, sizeof msg, "Cannot run program \"%s\": error=%d, %s",
             head_shown != NULL ? head_shown : "", child_errno, strerror(child_errno));
    free_argv(args);
    sfl_raw_free(shown);
    sfl_raw_free(head_shown);
    sfl_raise_sig("processStart", "%s", msg);
  }
  free_argv(args);
  sfl_raw_free(head_shown);

  Child *c = (Child *)sfl_raw_alloc(sizeof *c);
  memset(c, 0, sizeof *c);
  c->pid = pid;
  c->in_fd = inp[1];
  line_in_init(&c->out, outp[0]);
  line_in_init(&c->err, errp[0]);

  SflVal full = sfl_str_utf8(shown, -1);
  sfl_raw_free(shown);
  /* take(40) counts UTF-16 units, so cut the string rather than its bytes. */
  int64_t keep = full->aux < 40 ? (int64_t)full->aux : 40;
  SflVal label = sfl_str_utf16(full->u.s.chars, keep);
  return sfl_handle_new("process", c, label);
}

/* Both writers flush every time, so there is nothing to buffer on this side. */
static SflVal child_write(int64_t argc, SflVal *argv, const char *fn, int newline) {
  Child *c = child_of(argv, 0, fn);
  if (c->in_fd < 0) sfl_raise_sig(fn, "Stream closed");
  size_t n = 0;
  char *text = display_bytes(argv[1], &n);
  int ok = write_all(c->in_fd, text, n) == 0;
  int saved = errno;
  sfl_raw_free(text);
  if (ok && newline) {
    ok = write_all(c->in_fd, "\n", 1) == 0;
    saved = errno;
  }
  if (!ok) sfl_raise_sig(fn, "%s", strerror(saved));
  return sfl_null;
}

SflVal sfl_p_processWrite(int64_t argc, SflVal *argv) {
  return child_write(argc, argv, "processWrite", 0);
}

SflVal sfl_p_processWriteLine(int64_t argc, SflVal *argv) {
  return child_write(argc, argv, "processWriteLine", 1);
}

SflVal sfl_p_processCloseInput(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processCloseInput");
  /* BufferedWriter.close() on an already closed writer does nothing. */
  if (c->in_fd >= 0) {
    close_fd(c->in_fd);
    c->in_fd = -1;
  }
  return sfl_null;
}

static SflVal read_one_line(LineIn *r, const char *fn, int timeout_ms) {
  char *line = NULL;
  size_t n = 0;
  int st = line_read(r, timeout_ms, &line, &n);
  if (st == 0) return sfl_null;                 /* end of stream */
  if (st == -1) return sfl_null;                /* the timeout expired */
  if (st < 0) sfl_raise_sig(fn, "%s", line_in_why(r));
  SflVal s = sfl_str_utf8(line, (int64_t)n);
  sfl_raw_free(line);
  return s;
}

SflVal sfl_p_processReadLine(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processReadLine");
  return read_one_line(&c->out, "processReadLine", 0);
}

SflVal sfl_p_processReadErrLine(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processReadErrLine");
  return read_one_line(&c->err, "processReadErrLine", 0);
}

SflVal sfl_p_processReadAll(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processReadAll");
  size_t n = 0;
  char *data = line_slurp(&c->out, &n);
  if (data == NULL) sfl_raise_sig("processReadAll", "%s", strerror(errno));
  SflVal s = sfl_str_utf8(data, (int64_t)n);
  sfl_raw_free(data);
  return s;
}

SflVal sfl_p_processWait(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processWait");
  if (argc > 1 && argv[1] != sfl_null) {
    int64_t ms = sfl_arg_int(argv, 1, "processWait");
    int64_t deadline = sfl_time_millis() + ms;
    int64_t step = 1;
    for (;;) {
      if (child_reap(c, 0)) return sfl_int(c->code);
      int64_t left = deadline - sfl_time_millis();
      if (left <= 0) return sfl_null;
      /* No SIGCHLD wait that is both portable and interruptible, so poll — the
         interval opens out quickly so a long wait costs almost nothing. */
      if (step > left) step = left;
      struct timespec ts;
      ts.tv_sec = (time_t)(step / 1000);
      ts.tv_nsec = (long)(step % 1000) * 1000000L;
      while (nanosleep(&ts, &ts) != 0 && errno == EINTR) continue;
      if (step < 10) step *= 2;
    }
  }
  child_reap(c, 1);
  return sfl_int(c->code);
}

SflVal sfl_p_processAlive(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processAlive");
  return sfl_bool(!child_reap(c, 0));
}

SflVal sfl_p_processKill(int64_t argc, SflVal *argv) {
  Child *c = child_of(argv, 0, "processKill");
  int force = argc > 1 && sfl_truthy(argv[1]);
  /* Only signal a child still running: a reaped pid can already belong to
     somebody else, which is the same reason the JDK checks hasExited first. */
  if (!child_reap(c, 0)) kill(c->pid, force ? SIGKILL : SIGTERM);
  return sfl_null;
}

/* ------------------------------------------------------------------------- */
/* TCP sockets                                                                */
/* ------------------------------------------------------------------------- */

typedef struct {
  int fd;
  int port;
} Server;

typedef struct {
  int fd;
  Tls *tls; /* NULL until tlsWrap upgrades the connection */
  LineIn in;
  char peer[80];
} Conn;

static Server *server_of(SflVal *argv, int i, const char *fn) {
  Server *s = (Server *)handle_of(argv, i, "server", fn);
  if (s == NULL) sfl_raise_sig(fn, "Socket closed");
  return s;
}

static Conn *conn_of(SflVal *argv, int i, const char *fn) {
  Conn *c = (Conn *)handle_of(argv, i, "connection", fn);
  if (c == NULL) sfl_raise_sig(fn, "Socket closed");
  return c;
}

/*
 * InetAddress.getHostAddress. Java hands an IPv4-mapped address back as an
 * Inet4Address, and prints a real IPv6 address as eight uncompressed groups —
 * "0:0:0:0:0:0:0:1", never inet_ntop's "::1".
 */
static void format_host(const struct sockaddr *sa, char *out, size_t cap) {
  if (sa->sa_family == AF_INET) {
    const struct sockaddr_in *v4 = (const struct sockaddr_in *)(const void *)sa;
    if (inet_ntop(AF_INET, &v4->sin_addr, out, (socklen_t)cap) == NULL) snprintf(out, cap, "0.0.0.0");
    return;
  }
  if (sa->sa_family == AF_INET6) {
    const struct sockaddr_in6 *v6 = (const struct sockaddr_in6 *)(const void *)sa;
    const unsigned char *b = (const unsigned char *)&v6->sin6_addr;
    if (IN6_IS_ADDR_V4MAPPED(&v6->sin6_addr)) {
      snprintf(out, cap, "%u.%u.%u.%u", b[12], b[13], b[14], b[15]);
      return;
    }
    size_t w = 0;
    for (int i = 0; i < 8 && w + 1 < cap; i++) {
      unsigned group = (unsigned)((b[i * 2] << 8) | b[i * 2 + 1]);
      w += (size_t)snprintf(out + w, cap - w, i ? ":%x" : "%x", group);
      if (w >= cap) break;
    }
    if (v6->sin6_scope_id != 0 && w < cap)
      snprintf(out + w, cap - w, "%%%u", (unsigned)v6->sin6_scope_id);
    return;
  }
  snprintf(out, cap, "0.0.0.0");
}

static int port_of(const struct sockaddr *sa) {
  if (sa->sa_family == AF_INET)
    return ntohs(((const struct sockaddr_in *)(const void *)sa)->sin_port);
  if (sa->sa_family == AF_INET6)
    return ntohs(((const struct sockaddr_in6 *)(const void *)sa)->sin6_port);
  return 0;
}

static void format_peer(const struct sockaddr *sa, char *out, size_t cap) {
  char host[64];
  format_host(sa, host, sizeof host);
  snprintf(out, cap, "%s:%d", host, port_of(sa));
}

SflVal sfl_p_serverSocket(int64_t argc, SflVal *argv) {
  ignore_sigpipe();
  int port = (int)sfl_arg_idx(argv, 0, "serverSocket");
  int backlog = argc > 1 ? (int)sfl_arg_idx(argv, 1, "serverSocket") : 50;
  if (port < 0 || port > 65535) sfl_raise("Port value out of range: %d", port);
  if (backlog < 1) backlog = 50; /* ServerSocket.bind's own floor */

  /* Java listens on a dual-stack socket where the machine has IPv6, so that one
     server answers both families; a v4-only host falls back. */
  int fd = socket(AF_INET6, SOCK_STREAM, 0);
  int family = AF_INET6;
  if (fd < 0) {
    fd = socket(AF_INET, SOCK_STREAM, 0);
    family = AF_INET;
  }
  if (fd < 0) sfl_raise_sig("serverSocket", "%s", strerror(errno));

  int on = 1;
  setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
  if (family == AF_INET6) {
    int off = 0;
    setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &off, sizeof off);
  }

  int rc;
  if (family == AF_INET6) {
    struct sockaddr_in6 a;
    memset(&a, 0, sizeof a);
    a.sin6_family = AF_INET6;
    a.sin6_addr = in6addr_any;
    a.sin6_port = htons((uint16_t)port);
    rc = bind(fd, (struct sockaddr *)&a, sizeof a);
  } else {
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons((uint16_t)port);
    rc = bind(fd, (struct sockaddr *)&a, sizeof a);
  }
  if (rc != 0 || listen(fd, backlog) != 0) {
    int saved = errno;
    close_fd(fd);
    sfl_raise_sig("serverSocket", "%s", strerror(saved));
  }

  struct sockaddr_storage bound;
  socklen_t blen = sizeof bound;
  int bound_port = port;
  if (getsockname(fd, (struct sockaddr *)&bound, &blen) == 0)
    bound_port = port_of((struct sockaddr *)&bound);

  Server *s = (Server *)sfl_raw_alloc(sizeof *s);
  s->fd = fd;
  s->port = bound_port;
  char label[32];
  snprintf(label, sizeof label, "port=%d", bound_port);
  return sfl_handle_new("server", s, sfl_str_utf8(label, -1));
}

SflVal sfl_p_serverPort(int64_t argc, SflVal *argv) {
  return sfl_int(server_of(argv, 0, "serverPort")->port);
}

SflVal sfl_p_accept(int64_t argc, SflVal *argv) {
  Server *s = server_of(argv, 0, "accept");
  int ms = (argc > 1 && argv[1] != sfl_null) ? (int)sfl_arg_idx(argv, 1, "accept") : 0;

  if (ms > 0) {
    int ready = wait_ready(s->fd, POLLIN, ms);
    if (ready == 0) return sfl_null; /* SocketTimeoutException, which accept() maps to null */
    if (ready < 0) sfl_raise_sig("accept", "%s", strerror(errno));
  }
  struct sockaddr_storage from;
  socklen_t flen = sizeof from;
  int fd;
  do {
    flen = sizeof from;
    fd = accept(s->fd, (struct sockaddr *)&from, &flen);
  } while (fd < 0 && errno == EINTR);
  if (fd < 0) sfl_raise_sig("accept", "%s", strerror(errno));

  Conn *c = (Conn *)sfl_raw_alloc(sizeof *c);
  memset(c, 0, sizeof *c);
  c->fd = fd;
  line_in_init(&c->in, fd);
  format_peer((struct sockaddr *)&from, c->peer, sizeof c->peer);
  return sfl_handle_new("connection", c, sfl_str_utf8(c->peer, -1));
}

/* Non-blocking connect with a deadline, which is how Socket.connect(addr, ms)
   behaves. 0 connected, -1 failed with errno set, -2 the timeout expired. */
static int connect_deadline(int fd, const struct sockaddr *sa, socklen_t salen, int ms) {
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) return -1;
  int rc;
  do {
    rc = connect(fd, sa, salen);
  } while (rc != 0 && errno == EINTR);
  if (rc != 0 && errno != EINPROGRESS) return -1;
  if (rc != 0) {
    int ready = wait_ready(fd, POLLOUT, ms > 0 ? ms : 0);
    if (ready == 0) return -2;
    if (ready < 0) return -1;
    int err = 0;
    socklen_t elen = sizeof err;
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &elen) != 0) return -1;
    if (err != 0) {
      errno = err;
      return -1;
    }
  }
  fcntl(fd, F_SETFL, flags);
  return 0;
}

SflVal sfl_p_connect(int64_t argc, SflVal *argv) {
  ignore_sigpipe();
  char *host = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "connect"));
  /* What getaddrinfo receives: the interpreter's toCString spelling. */
  char *chost = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "connect"));
  int port = (int)sfl_arg_idx(argv, 1, "connect");
  int ms = argc > 2 ? (int)sfl_arg_idx(argv, 2, "connect") : 10000;
  if (port < 0 || port > 65535) {
    sfl_raw_free(host);
    sfl_raw_free(chost);
    sfl_raise("port out of range:%d", port); /* InetSocketAddress's own wording */
  }

  char service[8];
  snprintf(service, sizeof service, "%d", port);
  struct addrinfo hints;
  memset(&hints, 0, sizeof hints);
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *list = NULL;
  int gai;
  do {
    gai = getaddrinfo(chost, service, &hints, &list);
  } while (gai == EAI_SYSTEM && errno == EINTR);
  sfl_raw_free(chost);
  if (gai != 0 || list == NULL) {
    /* UnknownHostException carries nothing but the name it could not resolve. */
    char msg[300];
    snprintf(msg, sizeof msg, "%s", host);
    sfl_raw_free(host);
    sfl_raise_sig("connect", "%s", msg);
  }

  int fd = -1, saved = ECONNREFUSED, timed_out = 0;
  for (struct addrinfo *a = list; a != NULL; a = a->ai_next) {
    fd = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
    if (fd < 0) {
      saved = errno;
      continue;
    }
    int rc = connect_deadline(fd, a->ai_addr, a->ai_addrlen, ms);
    if (rc == 0) break;
    if (rc == -2) timed_out = 1;
    else saved = errno;
    close_fd(fd);
    fd = -1;
    if (timed_out) break; /* the deadline was for the whole call, not per address */
  }
  freeaddrinfo(list);
  if (fd < 0) {
    sfl_raw_free(host);
    if (timed_out) sfl_raise_sig("connect", "connect timed out");
    sfl_raise_sig("connect", "%s", strerror(saved));
  }

  Conn *c = (Conn *)sfl_raw_alloc(sizeof *c);
  memset(c, 0, sizeof *c);
  c->fd = fd;
  line_in_init(&c->in, fd);
  struct sockaddr_storage to;
  socklen_t tlen = sizeof to;
  if (getpeername(fd, (struct sockaddr *)&to, &tlen) == 0)
    format_peer((struct sockaddr *)&to, c->peer, sizeof c->peer);
  else snprintf(c->peer, sizeof c->peer, "%s:%d", host, port);

  char label[300];
  snprintf(label, sizeof label, "%s:%d", host, port);
  sfl_raw_free(host);
  return sfl_handle_new("connection", c, sfl_str_utf8(label, -1));
}

/* Sends bytes down the connection, through the TLS session when there is one. */
static int conn_send(Conn *c, const char *bytes, size_t n) {
  if (c->tls != NULL) return tls_write_all(c->tls, c->fd, bytes, n);
  return write_all(c->fd, bytes, n);
}

static const char *conn_why(const Conn *c, int saved_errno) {
  if (c->tls != NULL && c->tls->err[0] != '\0') return c->tls->err;
  return strerror(saved_errno);
}

static SflVal conn_write(int64_t argc, SflVal *argv, const char *fn, int newline) {
  Conn *c = conn_of(argv, 0, fn);
  size_t n = 0;
  char *text = display_bytes(argv[1], &n);
  int ok = conn_send(c, text, n) == 0;
  int saved = errno;
  sfl_raw_free(text);
  if (ok && newline) {
    ok = conn_send(c, "\n", 1) == 0;
    saved = errno;
  }
  if (!ok) sfl_raise_sig(fn, "%s", conn_why(c, saved));
  return sfl_null;
}

SflVal sfl_p_socketWrite(int64_t argc, SflVal *argv) {
  return conn_write(argc, argv, "socketWrite", 0);
}

SflVal sfl_p_socketWriteLine(int64_t argc, SflVal *argv) {
  return conn_write(argc, argv, "socketWriteLine", 1);
}

SflVal sfl_p_socketReadLine(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketReadLine");
  int ms = (argc > 1 && argv[1] != sfl_null) ? (int)sfl_arg_idx(argv, 1, "socketReadLine") : 0;
  return read_one_line(&c->in, "socketReadLine", ms);
}

SflVal sfl_p_socketPeer(int64_t argc, SflVal *argv) {
  return sfl_str_utf8(conn_of(argv, 0, "socketPeer")->peer, -1);
}

SflVal sfl_p_closeSocket(int64_t argc, SflVal *argv) {
  Conn *c = (Conn *)handle_of(argv, 0, "connection", "closeSocket");
  if (c == NULL) return sfl_null; /* Socket.close() twice is a no-op */
  tls_free_conn(c->tls);          /* the close_notify wants the descriptor alive */
  close_fd(c->fd);
  line_in_free(&c->in);
  sfl_raw_free(c);
  argv[0]->u.h.payload = NULL;
  return sfl_null;
}

SflVal sfl_p_closeServer(int64_t argc, SflVal *argv) {
  Server *s = (Server *)handle_of(argv, 0, "server", "closeServer");
  if (s == NULL) return sfl_null;
  close_fd(s->fd);
  sfl_raw_free(s);
  argv[0]->u.h.payload = NULL;
  return sfl_null;
}

/*
 * Upgrades a connection to TLS in place and returns the same handle. The wrap
 * comes after any plaintext prelude — a proxy CONNECT, a STARTTLS exchange — so
 * it refuses when the reader still holds plaintext bytes the handshake would
 * then never see.
 */
SflVal sfl_p_tlsWrap(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "tlsWrap");
  if (c->tls != NULL) sfl_raise_sig("tlsWrap", "the connection is already TLS");
  if (c->in.pos < c->in.len || c->in.held != NULL)
    sfl_raise_sig("tlsWrap", "the connection has buffered input");
  char *host = sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "tlsWrap"));
  char *ca = (argc > 2 && argv[2] != sfl_null)
                 ? sfl_str_dup_utf8_java(sfl_arg_str(argv, 2, "tlsWrap"))
                 : NULL;
  int ms = (argc > 3 && argv[3] != sfl_null) ? (int)sfl_arg_idx(argv, 3, "tlsWrap") : 0;

  /* Everything that can raise before the handshake frees these first; a raise
     leaves by longjmp and would never come back to do it. */
  pthread_mutex_lock(&tls_lock);
  SflHandler guard;
  sfl_handler_push(&guard);
  void *ctx = NULL;
  if (setjmp(guard.jb) != 0) {
    pthread_mutex_unlock(&tls_lock);
    sfl_raw_free(host);
    sfl_raw_free(ca);
    sfl_raise_val(sfl_err_message());
  }
  tls_load_locked("tlsWrap");
  if (ca != NULL) {
    ctx = tls_ctx_new_locked("tlsWrap", ca);
  } else {
    if (tls_shared_ctx == NULL) tls_shared_ctx = tls_ctx_new_locked("tlsWrap", NULL);
    ctx = tls_shared_ctx;
  }
  sfl_handler_pop();
  pthread_mutex_unlock(&tls_lock);

  Tls *t = (Tls *)sfl_raw_alloc(sizeof *t);
  memset(t, 0, sizeof *t);
  t->own_ctx = ca != NULL ? ctx : NULL;
  sfl_raw_free(ca);
  t->ssl = p_ssl_new(ctx);
  if (t->ssl == NULL) {
    tls_free_conn(t);
    sfl_raw_free(host);
    sfl_raise_sig("tlsWrap", "could not create a TLS connection");
  }
  p_ssl_set_fd(t->ssl, c->fd);

  /* SNI and hostname verification. An IP literal is verified as an IP and sent
     no server name, which is what both curl and the JDK do. */
  struct in_addr probe4;
  struct in6_addr probe6;
  int is_ip = inet_pton(AF_INET, host, &probe4) == 1 || inet_pton(AF_INET6, host, &probe6) == 1;
  int named = 0;
  if (is_ip) {
    if (p_ssl_get0_param != NULL && p_param_set1_ip != NULL)
      named = p_param_set1_ip(p_ssl_get0_param(t->ssl), host) == 1;
  } else {
    p_ssl_ctrl(t->ssl, TLS_CTRL_SET_TLSEXT_HOSTNAME, TLS_NAMETYPE_HOST, host);
    if (p_ssl_set1_host != NULL) named = p_ssl_set1_host(t->ssl, host) == 1;
    else if (p_ssl_get0_param != NULL && p_param_set1_host != NULL)
      named = p_param_set1_host(p_ssl_get0_param(t->ssl), host, 0) == 1;
  }
  if (!named) {
    tls_free_conn(t);
    sfl_raw_free(host);
    sfl_raise_sig("tlsWrap", "this TLS library cannot verify the peer's name");
  }

  /* Offer ALPN protocols, when the caller brought a preference list. */
  if (argc > 4 && argv[4] != sfl_null) {
    size_t wn = 0;
    unsigned char *wire = alpn_wire(argv, 4, "tlsWrap", &wn);
    if (wire != NULL) {
      if (p_set_alpn_protos == NULL || p_set_alpn_protos(t->ssl, wire, (unsigned int)wn) != 0) {
        sfl_raw_free(wire);
        tls_free_conn(t);
        sfl_raw_free(host);
        sfl_raise_sig("tlsWrap", "this TLS library cannot offer ALPN");
      }
      sfl_raw_free(wire); /* the client-side setter copies the list */
    }
  }

  /* The descriptor stays non-blocking for the life of the session: the WANT
     loops in tls_read/tls_write drive it through poll from here on. */
  int flags = fcntl(c->fd, F_GETFL, 0);
  if (flags >= 0) fcntl(c->fd, F_SETFL, flags | O_NONBLOCK);

  int64_t deadline = ms > 0 ? sfl_time_millis() + ms : 0;
  int rc = tls_handshake(t, c->fd, deadline, 0);
  if (rc != 0) {
    char why[256];
    snprintf(why, sizeof why, "%s", rc == -2 ? "handshake timed out" : t->err);
    tls_free_conn(t);
    if (flags >= 0) fcntl(c->fd, F_SETFL, flags);
    sfl_raw_free(host);
    sfl_raise_sig("tlsWrap", "%s", why);
  }
  sfl_raw_free(host);
  c->tls = t;
  c->in.tls = t;
  return argv[0];
}

/* ------------------------------------------------------------------------- */
/* TLS, server side                                                           */
/* ------------------------------------------------------------------------- */

/*
 * ALPN protocol lists travel in wire format: a length byte before each name.
 * Built from an SFL array of strings; the caller frees it.
 */
static unsigned char *alpn_wire(SflVal *argv, int i, const char *fn, size_t *out_len) {
  SflVal arr = sfl_arg_arr(argv, i, fn);
  size_t total = 0;
  for (uint32_t k = 0; k < arr->aux; k++) {
    SflVal el = arr->u.a.items[k];
    if (el->tag != SFL_STR) sfl_raise_sig(fn, "alpn entries must be strings");
    int64_t n = sfl_utf8_java_len(el);
    if (n < 1 || n > 255) sfl_raise_sig(fn, "an alpn protocol name must be 1..255 bytes");
    total += (size_t)n + 1;
  }
  if (total == 0) {
    *out_len = 0;
    return NULL;
  }
  unsigned char *wire = (unsigned char *)sfl_raw_alloc(total);
  size_t at = 0;
  for (uint32_t k = 0; k < arr->aux; k++) {
    SflVal el = arr->u.a.items[k];
    int64_t n = sfl_utf8_java_len(el);
    wire[at++] = (unsigned char)n;
    sfl_utf8_java_write(el, (char *)wire + at);
    at += (size_t)n;
  }
  *out_len = total;
  return wire;
}

/* The cached server identity the ALPN callback below reads. */
static void *tls_srv_ctx;
static char *tls_srv_key;
static const unsigned char *tls_srv_alpn;
static size_t tls_srv_alpn_len;

/*
 * The server's ALPN pick: first protocol of our preference list that the client
 * offered. The preference list lives with the cached server context, so the
 * globals above are it. Runs inside the TLS library during SSL_accept.
 */
static int tls_alpn_select(void *ssl, const unsigned char **out, unsigned char *outlen,
                           const unsigned char *in, unsigned int inlen, void *arg) {
  (void)ssl;
  (void)arg;
  const unsigned char *pref = tls_srv_alpn;
  size_t pref_len = tls_srv_alpn_len;
  size_t i = 0;
  while (i < pref_len) {
    unsigned char n = pref[i];
    unsigned int j = 0;
    while (j < inlen) {
      unsigned char m = in[j];
      if (m == n && memcmp(pref + i + 1, in + j + 1, n) == 0) {
        *out = pref + i + 1;
        *outlen = n;
        return 0; /* SSL_TLSEXT_ERR_OK */
      }
      j += (unsigned int)m + 1;
    }
    i += (size_t)n + 1;
  }
  return 3; /* SSL_TLSEXT_ERR_NOACK: no overlap, carry on without ALPN */
}

/*
 * One cached server context, keyed by certificate, key and ALPN list: a server
 * accepts thousands of connections with the same identity, and parsing the
 * PEM files once per connection would dominate the handshake.
 */
static void *tls_ctx_server_locked(const char *fn, const char *cert, const char *key,
                                   unsigned char *alpn, size_t alpn_len) {
  char wanted[1024];
  snprintf(wanted, sizeof wanted, "%s\n%s\n%zu", cert, key, alpn_len);
  if (tls_srv_ctx != NULL && tls_srv_key != NULL && strcmp(tls_srv_key, wanted) == 0 &&
      alpn_len == tls_srv_alpn_len &&
      (alpn_len == 0 || memcmp(alpn, tls_srv_alpn, alpn_len) == 0)) {
    sfl_raw_free(alpn);
    return tls_srv_ctx;
  }
  if (p_server_method == NULL || p_use_cert_chain == NULL || p_use_priv_key == NULL ||
      p_ssl_accept == NULL) {
    sfl_raw_free(alpn);
    sfl_raise_sig(fn, "this TLS library cannot run a server");
  }
  if (p_err_clear != NULL) p_err_clear();
  void *ctx = p_ctx_new(p_server_method());
  if (ctx == NULL) {
    sfl_raw_free(alpn);
    sfl_raise_sig(fn, "could not create a TLS context");
  }
  if (p_ctx_set_min_proto != NULL) p_ctx_set_min_proto(ctx, TLS_1_2_VERSION);
  else if (p_ctx_ctrl != NULL) p_ctx_ctrl(ctx, TLS_CTRL_SET_MIN_PROTO, TLS_1_2_VERSION, NULL);
  if (p_use_cert_chain(ctx, cert) != 1) {
    p_ctx_free(ctx);
    sfl_raw_free(alpn);
    char msg[512];
    snprintf(msg, sizeof msg, "cannot load the certificate '%s'", cert);
    sfl_raise_sig(fn, "%s", msg);
  }
  if (p_use_priv_key(ctx, key, 1 /* SSL_FILETYPE_PEM */) != 1 ||
      (p_check_priv_key != NULL && p_check_priv_key(ctx) != 1)) {
    p_ctx_free(ctx);
    sfl_raw_free(alpn);
    char msg[512];
    snprintf(msg, sizeof msg, "cannot load the private key '%s'", key);
    sfl_raise_sig(fn, "%s", msg);
  }

  /* Replace the cache: context, key string and the ALPN list the callback reads. */
  if (tls_srv_ctx != NULL) p_ctx_free(tls_srv_ctx);
  sfl_raw_free(tls_srv_key);
  sfl_raw_free((void *)tls_srv_alpn);
  tls_srv_ctx = ctx;
  tls_srv_key = dup_cstr(wanted);
  tls_srv_alpn = alpn;
  tls_srv_alpn_len = alpn_len;
  if (alpn_len > 0 && p_ctx_set_alpn_cb != NULL)
    p_ctx_set_alpn_cb(ctx, (void *)tls_alpn_select, NULL);
  return ctx;
}

/*
 * Upgrades an accepted connection to server-side TLS in place and returns the
 * same handle: tlsAccept(c, certFile, keyFile, alpn?, timeoutMs?). `alpn` lists
 * the protocols this server prefers, most preferred first; what the handshake
 * settled on is readable with tlsProto(c).
 */
SflVal sfl_p_tlsAccept(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "tlsAccept");
  if (c->tls != NULL) sfl_raise_sig("tlsAccept", "the connection is already TLS");
  if (c->in.pos < c->in.len || c->in.held != NULL)
    sfl_raise_sig("tlsAccept", "the connection has buffered input");
  char *cert = sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "tlsAccept"));
  char *key = sfl_str_dup_utf8_java(sfl_arg_str(argv, 2, "tlsAccept"));
  int ms = (argc > 4 && argv[4] != sfl_null) ? (int)sfl_arg_idx(argv, 4, "tlsAccept") : 0;

  pthread_mutex_lock(&tls_lock);
  SflHandler guard;
  sfl_handler_push(&guard);
  void *ctx = NULL;
  if (setjmp(guard.jb) != 0) {
    pthread_mutex_unlock(&tls_lock);
    sfl_raw_free(cert);
    sfl_raw_free(key);
    sfl_raise_val(sfl_err_message());
  }
  tls_load_locked("tlsAccept");
  size_t alpn_len = 0;
  unsigned char *alpn = (argc > 3 && argv[3] != sfl_null)
                            ? alpn_wire(argv, 3, "tlsAccept", &alpn_len)
                            : NULL;
  ctx = tls_ctx_server_locked("tlsAccept", cert, key, alpn, alpn_len);
  sfl_handler_pop();
  pthread_mutex_unlock(&tls_lock);
  sfl_raw_free(cert);
  sfl_raw_free(key);

  Tls *t = (Tls *)sfl_raw_alloc(sizeof *t);
  memset(t, 0, sizeof *t);
  t->ssl = p_ssl_new(ctx);
  if (t->ssl == NULL) {
    tls_free_conn(t);
    sfl_raise_sig("tlsAccept", "could not create a TLS connection");
  }
  p_ssl_set_fd(t->ssl, c->fd);

  int flags = fcntl(c->fd, F_GETFL, 0);
  if (flags >= 0) fcntl(c->fd, F_SETFL, flags | O_NONBLOCK);

  int64_t deadline = ms > 0 ? sfl_time_millis() + ms : 0;
  int rc = tls_handshake(t, c->fd, deadline, 1);
  if (rc != 0) {
    char why[256];
    snprintf(why, sizeof why, "%s", rc == -2 ? "handshake timed out" : t->err);
    tls_free_conn(t);
    if (flags >= 0) fcntl(c->fd, F_SETFL, flags);
    sfl_raise_sig("tlsAccept", "%s", why);
  }
  c->tls = t;
  c->in.tls = t;
  return argv[0];
}

/* The ALPN protocol the connection's handshake settled on, or null. */
SflVal sfl_p_tlsProto(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "tlsProto");
  if (c->tls == NULL || c->tls->proto[0] == '\0') return sfl_null;
  return sfl_str_utf8(c->tls->proto, -1);
}

/* ------------------------------------------------------------------------- */
/* Socket knobs and file transmission                                         */
/* ------------------------------------------------------------------------- */

/* TCP_NODELAY: small writes leave now instead of waiting for Nagle. */
SflVal sfl_p_socketNoDelay(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketNoDelay");
  int on = (argc > 1) ? sfl_truthy(argv[1]) : 1;
  if (setsockopt(c->fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof on) != 0)
    sfl_raise_sig("socketNoDelay", "%s", strerror(errno));
  return sfl_null;
}

/*
 * Sends `length` bytes of a file from `offset` down the connection — through
 * the TLS session when there is one — without the bytes ever becoming values.
 * Returns the count sent. A file shorter than the requested range raises,
 * because the framing above has usually promised that length already.
 */
SflVal sfl_p_socketSendFile(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketSendFile");
  char *path = sfl_str_dup_utf8(sfl_arg_str(argv, 1, "socketSendFile"));
  char *cpath = sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "socketSendFile"));
  int64_t off = (argc > 2 && argv[2] != sfl_null) ? sfl_arg_int(argv, 2, "socketSendFile") : 0;
  int64_t len = (argc > 3 && argv[3] != sfl_null) ? sfl_arg_int(argv, 3, "socketSendFile") : -1;
  if (off < 0) {
    sfl_raw_free(path);
    sfl_raw_free(cpath);
    sfl_raise_sig("socketSendFile", "offset must not be negative");
  }

  int fd;
  do {
    fd = open(cpath, O_RDONLY);
  } while (fd < 0 && errno == EINTR);
  sfl_raw_free(cpath);
  if (fd < 0) {
    char msg[512];
    snprintf(msg, sizeof msg, "%s (%s)", path, strerror(errno));
    sfl_raw_free(path);
    sfl_raise_sig("socketSendFile", "%s", msg);
  }
  struct stat st;
  if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
    close_fd(fd);
    char msg[512];
    snprintf(msg, sizeof msg, "%s (not a regular file)", path);
    sfl_raw_free(path);
    sfl_raise_sig("socketSendFile", "%s", msg);
  }
  if (len < 0) len = st.st_size > off ? st.st_size - off : 0;

  char buf[65536];
  int64_t sent = 0;
  while (sent < len) {
    size_t want = (size_t)(len - sent) < sizeof buf ? (size_t)(len - sent) : sizeof buf;
    ssize_t got;
    do {
      got = pread(fd, buf, want, (off_t)(off + sent));
    } while (got < 0 && errno == EINTR);
    if (got < 0) {
      int saved = errno;
      close_fd(fd);
      sfl_raw_free(path);
      sfl_raise_sig("socketSendFile", "%s", strerror(saved));
    }
    if (got == 0) {
      close_fd(fd);
      char msg[512];
      snprintf(msg, sizeof msg, "%s is shorter than the requested range", path);
      sfl_raw_free(path);
      sfl_raise_sig("socketSendFile", "%s", msg);
    }
    if (conn_send(c, buf, (size_t)got) != 0) {
      int saved = errno;
      close_fd(fd);
      sfl_raw_free(path);
      sfl_raise_sig("socketSendFile", "%s", conn_why(c, saved));
    }
    sent += got;
  }
  close_fd(fd);
  sfl_raw_free(path);
  return sfl_int(sent);
}

/* ------------------------------------------------------------------------- */
/* Byte buffers                                                               */
/* ------------------------------------------------------------------------- */

/*
 * A growable run of raw bytes, for framing that counts in bytes — an HTTP body,
 * a download — where decoding must happen once over the finished whole rather
 * than per chunk, or a multi-byte character split across two reads would decode
 * as two broken halves. The interpreter's twin is a growable Array[Byte].
 */
typedef struct {
  char *data;
  size_t len, cap;
} Buf;

static Buf *buf_of(SflVal *argv, int i, const char *fn) {
  /* A buffer has no close, so its payload is live for as long as it is. */
  return (Buf *)handle_of(argv, i, "buffer", fn);
}

static void buf_append(Buf *b, const char *bytes, size_t n) {
  if (n == 0) return;
  if (b->len + n > b->cap) {
    size_t cap = b->cap ? b->cap : 4096;
    while (b->len + n > cap) cap *= 2;
    b->data = (char *)sfl_raw_realloc(b->data, cap);
    b->cap = cap;
  }
  memcpy(b->data + b->len, bytes, n);
  b->len += n;
}

SflVal sfl_p_bufNew(int64_t argc, SflVal *argv) {
  Buf *b = (Buf *)sfl_raw_alloc(sizeof *b);
  memset(b, 0, sizeof *b);
  return sfl_handle_new("buffer", b, sfl_str_empty());
}

SflVal sfl_p_bufSize(int64_t argc, SflVal *argv) {
  return sfl_int((int64_t)buf_of(argv, 0, "bufSize")->len);
}

SflVal sfl_p_bufString(int64_t argc, SflVal *argv) {
  Buf *b = buf_of(argv, 0, "bufString");
  return sfl_str_utf8(b->data != NULL ? b->data : "", (int64_t)b->len);
}

SflVal sfl_p_bufClear(int64_t argc, SflVal *argv) {
  Buf *b = buf_of(argv, 0, "bufClear");
  sfl_raw_free(b->data);
  b->data = NULL;
  b->len = 0;
  b->cap = 0;
  return sfl_null;
}

/*
 * Reads up to maxBytes from the connection into the buffer: the count read as an
 * int, 0 at the end of the stream, null when the timeout expires. Bytes the line
 * reader already buffered are handed over first — including the '\n' a line that
 * ended at '\r' still owes — so line reads and byte reads interleave without
 * losing a byte, which is exactly what reading HTTP headers then a body does.
 */
SflVal sfl_p_socketReadToBuf(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketReadToBuf");
  Buf *b = buf_of(argv, 1, "socketReadToBuf");
  int64_t want = sfl_arg_int(argv, 2, "socketReadToBuf");
  if (want <= 0) sfl_raise_sig("socketReadToBuf", "maxBytes must be positive");
  if (want > (1 << 26)) want = 1 << 26;
  int ms = (argc > 3 && argv[3] != sfl_null) ? (int)sfl_arg_idx(argv, 3, "socketReadToBuf") : 0;

  LineIn *r = &c->in;
  size_t took = 0;

  /* A held partial line: raw bytes a timed-out line read kept, first in line. */
  if (r->held != NULL) {
    size_t take = r->held_len < (size_t)want ? r->held_len : (size_t)want;
    buf_append(b, r->held, take);
    took += take;
    if (take == r->held_len) {
      sfl_raw_free(r->held);
      r->held = NULL;
      r->held_len = 0;
    } else {
      memmove(r->held, r->held + take, r->held_len - take);
      r->held_len -= take;
    }
  }

  for (;;) {
    /* The '\n' owed by a line that ended at '\r' belongs to that line, not to
       the bytes: swallow it the moment it is visible. */
    if (r->skip_lf && r->pos < r->len) {
      if (r->buf[r->pos] == '\n') r->pos++;
      r->skip_lf = 0;
    }
    if (took < (size_t)want && r->pos < r->len) {
      size_t avail = r->len - r->pos;
      size_t take = avail < (size_t)want - took ? avail : (size_t)want - took;
      buf_append(b, r->buf + r->pos, take);
      r->pos += take;
      took += take;
    }
    if (took > 0) return sfl_int((int64_t)took);
    if (r->eof) return sfl_int(0);

    int st = line_fill(r, ms);
    if (st == 0) return sfl_int(0);
    if (st == -1) return sfl_null; /* the timeout expired */
    if (st == -2) sfl_raise_sig("socketReadToBuf", "%s", line_in_why(r));
    /* Loop: the fill may have produced only the swallowed '\n'. */
  }
}

/* Writes the buffer's bytes to the connection as they are, no decoding, no
   newline — the byte-pump half of proxying and file transfer. */
SflVal sfl_p_socketWriteBuf(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketWriteBuf");
  Buf *b = buf_of(argv, 1, "socketWriteBuf");
  if (b->len == 0) return sfl_null;
  if (conn_send(c, b->data, b->len) != 0)
    sfl_raise_sig("socketWriteBuf", "%s", conn_why(c, errno));
  return sfl_null;
}

/*
 * The byte read SFL code sees: up to maxBytes as an array of ints, [] at the end
 * of the stream, null when the timeout expires. Drains exactly what
 * socketReadToBuf drains — held partial line first, then the line reader's
 * buffer, owed '\n' swallowed — so line reads and byte reads interleave without
 * losing a byte. The interpreter's socketReadBytes runs this same loop.
 */
SflVal sfl_p_socketReadBytes(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketReadBytes");
  int64_t want = sfl_arg_int(argv, 1, "socketReadBytes");
  if (want <= 0) sfl_raise_sig("socketReadBytes", "maxBytes must be positive");
  if (want > (1 << 26)) want = 1 << 26;
  int ms = (argc > 2 && argv[2] != sfl_null) ? (int)sfl_arg_idx(argv, 2, "socketReadBytes") : 0;

  LineIn *r = &c->in;
  Buf b;
  memset(&b, 0, sizeof b);

  if (r->held != NULL) {
    size_t take = r->held_len < (size_t)want ? r->held_len : (size_t)want;
    buf_append(&b, r->held, take);
    if (take == r->held_len) {
      sfl_raw_free(r->held);
      r->held = NULL;
      r->held_len = 0;
    } else {
      memmove(r->held, r->held + take, r->held_len - take);
      r->held_len -= take;
    }
  }

  for (;;) {
    if (r->skip_lf && r->pos < r->len) {
      if (r->buf[r->pos] == '\n') r->pos++;
      r->skip_lf = 0;
    }
    if (b.len < (size_t)want && r->pos < r->len) {
      size_t avail = r->len - r->pos;
      size_t take = avail < (size_t)want - b.len ? avail : (size_t)want - b.len;
      buf_append(&b, r->buf + r->pos, take);
      r->pos += take;
    }
    if (b.len > 0) break;
    if (r->eof) break;
    int st = line_fill(r, ms);
    if (st == 0) break;
    if (st == -1) {
      sfl_raw_free(b.data);
      return sfl_null; /* the timeout expired */
    }
    if (st == -2) {
      sfl_raw_free(b.data);
      sfl_raise_sig("socketReadBytes", "%s", line_in_why(r));
    }
    /* Loop: the fill may have produced only the swallowed '\n'. */
  }
  SflVal out = sfl_bytes_arr((const uint8_t *)b.data, (int64_t)b.len);
  sfl_raw_free(b.data);
  return out;
}

/* Writes an array of ints 0..255 to the connection as raw bytes. */
SflVal sfl_p_socketWriteBytes(int64_t argc, SflVal *argv) {
  Conn *c = conn_of(argv, 0, "socketWriteBytes");
  int64_t n;
  uint8_t *bytes = sfl_arg_bytes(argv, 1, "socketWriteBytes", &n);
  if (n > 0 && conn_send(c, (const char *)bytes, (size_t)n) != 0) {
    int saved = errno;
    sfl_raw_free(bytes);
    sfl_raise_sig("socketWriteBytes", "%s", conn_why(c, saved));
  }
  sfl_raw_free(bytes);
  return sfl_null;
}

/* ------------------------------------------------------------------------- */
/* UDP sockets                                                                */
/* ------------------------------------------------------------------------- */

/*
 * Datagrams, shaped after DatagramSocket: one handle both sends and receives,
 * binding happens at creation (port 0 picks a free one), and a receive hands
 * back the datagram together with who sent it. A datagram is decoded in one
 * piece, so the split-character problem byte streams have cannot arise.
 */
typedef struct {
  int fd;
  int family;
  int port;
} Udp;

static Udp *udp_of(SflVal *argv, int i, const char *fn) {
  Udp *u = (Udp *)handle_of(argv, i, "udp", fn);
  if (u == NULL) sfl_raise_sig(fn, "Socket closed");
  return u;
}

SflVal sfl_p_udpSocket(int64_t argc, SflVal *argv) {
  ignore_sigpipe();
  int port = (argc > 0 && argv[0] != sfl_null) ? (int)sfl_arg_idx(argv, 0, "udpSocket") : 0;
  if (port < 0 || port > 65535) sfl_raise("Port value out of range: %d", port);

  /* Dual-stack like serverSocket, so one socket speaks both families. */
  int fd = socket(AF_INET6, SOCK_DGRAM, 0);
  int family = AF_INET6;
  if (fd < 0) {
    fd = socket(AF_INET, SOCK_DGRAM, 0);
    family = AF_INET;
  }
  if (fd < 0) sfl_raise_sig("udpSocket", "%s", strerror(errno));
  if (family == AF_INET6) {
    int off = 0;
    setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &off, sizeof off);
  }

  int rc;
  if (family == AF_INET6) {
    struct sockaddr_in6 a;
    memset(&a, 0, sizeof a);
    a.sin6_family = AF_INET6;
    a.sin6_addr = in6addr_any;
    a.sin6_port = htons((uint16_t)port);
    rc = bind(fd, (struct sockaddr *)&a, sizeof a);
  } else {
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons((uint16_t)port);
    rc = bind(fd, (struct sockaddr *)&a, sizeof a);
  }
  if (rc != 0) {
    int saved = errno;
    close_fd(fd);
    sfl_raise_sig("udpSocket", "%s", strerror(saved));
  }

  struct sockaddr_storage bound;
  socklen_t blen = sizeof bound;
  int bound_port = port;
  if (getsockname(fd, (struct sockaddr *)&bound, &blen) == 0)
    bound_port = port_of((struct sockaddr *)&bound);

  Udp *u = (Udp *)sfl_raw_alloc(sizeof *u);
  u->fd = fd;
  u->family = family;
  u->port = bound_port;
  char label[32];
  snprintf(label, sizeof label, "port=%d", bound_port);
  return sfl_handle_new("udp", u, sfl_str_utf8(label, -1));
}

SflVal sfl_p_udpPort(int64_t argc, SflVal *argv) {
  return sfl_int(udp_of(argv, 0, "udpPort")->port);
}

SflVal sfl_p_udpSend(int64_t argc, SflVal *argv) {
  Udp *u = udp_of(argv, 0, "udpSend");
  char *host = sfl_str_dup_utf8(sfl_arg_str(argv, 1, "udpSend"));
  char *chost = sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "udpSend"));
  int port = (int)sfl_arg_idx(argv, 2, "udpSend");
  if (port < 0 || port > 65535) {
    sfl_raw_free(host);
    sfl_raw_free(chost);
    sfl_raise("port out of range:%d", port); /* InetSocketAddress's own wording */
  }

  char service[8];
  snprintf(service, sizeof service, "%d", port);
  struct addrinfo hints;
  memset(&hints, 0, sizeof hints);
  hints.ai_family = u->family;
  hints.ai_socktype = SOCK_DGRAM;
  /* A dual-stack socket sends to a v4-only host through its v4-mapped form. */
  if (u->family == AF_INET6) hints.ai_flags = AI_V4MAPPED;
  struct addrinfo *list = NULL;
  int gai;
  do {
    gai = getaddrinfo(chost, service, &hints, &list);
  } while (gai == EAI_SYSTEM && errno == EINTR);
  sfl_raw_free(chost);
  if (gai != 0 || list == NULL) {
    /* UnknownHostException carries nothing but the name it could not resolve. */
    char msg[300];
    snprintf(msg, sizeof msg, "%s", host);
    sfl_raw_free(host);
    sfl_raise_sig("udpSend", "%s", msg);
  }
  sfl_raw_free(host);

  size_t n = 0;
  char *text = display_bytes(argv[3], &n);
  ssize_t sent;
  do {
    sent = sendto(u->fd, text, n, 0, list->ai_addr, list->ai_addrlen);
  } while (sent < 0 && errno == EINTR);
  int saved = errno;
  freeaddrinfo(list);
  sfl_raw_free(text);
  if (sent < 0) sfl_raise_sig("udpSend", "%s", strerror(saved));
  return sfl_null;
}

SflVal sfl_p_udpReceive(int64_t argc, SflVal *argv) {
  Udp *u = udp_of(argv, 0, "udpReceive");
  int ms = (argc > 1 && argv[1] != sfl_null) ? (int)sfl_arg_idx(argv, 1, "udpReceive") : 0;

  if (ms > 0) {
    int ready = wait_ready(u->fd, POLLIN, ms);
    if (ready == 0) return sfl_null; /* the timeout expired, as receive() maps it */
    if (ready < 0) sfl_raise_sig("udpReceive", "%s", strerror(errno));
  }

  /* The largest possible datagram, so nothing is ever silently truncated. */
  char buf[65536];
  struct sockaddr_storage from;
  socklen_t flen;
  ssize_t got;
  do {
    flen = sizeof from;
    got = recvfrom(u->fd, buf, sizeof buf, 0, (struct sockaddr *)&from, &flen);
  } while (got < 0 && errno == EINTR);
  if (got < 0) sfl_raise_sig("udpReceive", "%s", strerror(errno));

  char host[64];
  format_host((const struct sockaddr *)&from, host, sizeof host);
  SflVal data = sfl_str_utf8(buf, (int64_t)got);
  SflVal o = sfl_obj_new();
  put_field(o, "data", data);
  put_field(o, "host", sfl_str_utf8(host, -1));
  put_field(o, "port", sfl_int(port_of((const struct sockaddr *)&from)));
  return o;
}

SflVal sfl_p_closeUdp(int64_t argc, SflVal *argv) {
  Udp *u = (Udp *)handle_of(argv, 0, "udp", "closeUdp");
  if (u == NULL) return sfl_null; /* close() twice is a no-op */
  close_fd(u->fd);
  sfl_raw_free(u);
  argv[0]->u.h.payload = NULL;
  return sfl_null;
}

/* ------------------------------------------------------------------------- */
/* Line-oriented files and named pipes                                        */
/* ------------------------------------------------------------------------- */

typedef struct {
  char *path;
  int reading;
  LineIn in;
  LineOut out;
} FileH;

static FileH *file_of(SflVal *argv, int i, const char *fn) {
  FileH *f = (FileH *)handle_of(argv, i, "file", fn);
  if (f == NULL) sfl_raise_sig(fn, "Stream closed");
  return f;
}

SflVal sfl_p_openFile(int64_t argc, SflVal *argv) {
  ignore_sigpipe();
  char *path = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "openFile"));
  /* What open(2) receives: the interpreter's toCString spelling of the path. */
  char *cpath = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "openFile"));
  char *mode = argc > 1 ? sfl_str_dup_utf8(sfl_arg_str(argv, 1, "openFile")) : dup_cstr("r");

  int reading = strcmp(mode, "r") == 0;
  int flags;
  if (reading) {
    flags = O_RDONLY;
  } else if (strcmp(mode, "w") == 0) {
    flags = O_WRONLY | O_CREAT | O_TRUNC;
  } else if (strcmp(mode, "a") == 0) {
    flags = O_WRONLY | O_CREAT | O_APPEND;
  } else {
    char msg[256];
    snprintf(msg, sizeof msg, "unknown mode '%s', expected 'r', 'w' or 'a'", mode);
    sfl_raw_free(path);
    sfl_raw_free(cpath);
    sfl_raw_free(mode);
    sfl_raise_sig("openFile", "%s", msg);
  }
  sfl_raw_free(mode);

  int fd;
  /* Opening a FIFO blocks until the other end arrives, so this can be interrupted. */
  do {
    fd = open(cpath, flags, 0666);
  } while (fd < 0 && errno == EINTR);
  if (fd < 0) {
    /* FileNotFoundException prints as "<path> (<reason>)". */
    char msg[512];
    snprintf(msg, sizeof msg, "%s (%s)", path, strerror(errno));
    sfl_raw_free(path);
    sfl_raw_free(cpath);
    sfl_raise_sig("openFile", "%s", msg);
  }
  struct stat st;
  if (reading && fstat(fd, &st) == 0 && S_ISDIR(st.st_mode)) {
    /* open(2) will hand back a directory; FileInputStream will not. */
    close_fd(fd);
    char msg[512];
    snprintf(msg, sizeof msg, "%s (Is a directory)", path);
    sfl_raw_free(path);
    sfl_raw_free(cpath);
    sfl_raise_sig("openFile", "%s", msg);
  }
  sfl_raw_free(cpath);

  FileH *f = (FileH *)sfl_raw_alloc(sizeof *f);
  memset(f, 0, sizeof *f);
  f->path = path;
  f->reading = reading;
  line_in_init(&f->in, reading ? fd : -1);
  line_out_init(&f->out, reading ? -1 : fd);
  return sfl_handle_new("file", f, sfl_str_utf8(path, -1));
}

SflVal sfl_p_fileReadLine(int64_t argc, SflVal *argv) {
  FileH *f = file_of(argv, 0, "fileReadLine");
  if (!f->reading) sfl_raise_sig("fileReadLine", "'%s' is open for writing", f->path);
  return read_one_line(&f->in, "fileReadLine", 0);
}

static SflVal file_write(int64_t argc, SflVal *argv, const char *fn, int newline) {
  FileH *f = file_of(argv, 0, fn);
  if (f->reading) sfl_raise_sig(fn, "'%s' is open for reading", f->path);
  size_t n = 0;
  char *text = display_bytes(argv[1], &n);
  int ok = line_out_write(&f->out, text, n) == 0;
  int saved = errno;
  sfl_raw_free(text);
  if (ok && newline) {
    ok = line_out_write(&f->out, "\n", 1) == 0 && line_out_flush(&f->out) == 0;
    saved = errno;
  }
  if (!ok) sfl_raise_sig(fn, "%s", strerror(saved));
  return sfl_null;
}

SflVal sfl_p_fileWrite(int64_t argc, SflVal *argv) {
  return file_write(argc, argv, "fileWrite", 0);
}

SflVal sfl_p_fileWriteLine(int64_t argc, SflVal *argv) {
  return file_write(argc, argv, "fileWriteLine", 1);
}

SflVal sfl_p_fileFlush(int64_t argc, SflVal *argv) {
  FileH *f = file_of(argv, 0, "fileFlush");
  if (!f->reading && line_out_flush(&f->out) != 0)
    sfl_raise_sig("fileFlush", "%s", strerror(errno));
  return sfl_null;
}

SflVal sfl_p_fileClose(int64_t argc, SflVal *argv) {
  FileH *f = (FileH *)handle_of(argv, 0, "file", "fileClose");
  if (f == NULL) return sfl_null; /* close() on a closed reader or writer does nothing */
  int failed = 0;
  if (!f->reading) failed = line_out_flush(&f->out) != 0; /* close() flushes first */
  int saved = errno;
  close_fd(f->reading ? f->in.fd : f->out.fd);
  line_in_free(&f->in);
  line_out_free(&f->out);
  sfl_raw_free(f->path);
  sfl_raw_free(f);
  argv[0]->u.h.payload = NULL;
  if (failed) sfl_raise_sig("fileClose", "%s", strerror(saved));
  return sfl_null;
}

SflVal sfl_p_mkfifo(int64_t argc, SflVal *argv) {
  char *cpath = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "mkfifo"));
  int mode = argc > 1 ? (int)sfl_arg_idx(argv, 1, "mkfifo") : 0600;
  int rc = mkfifo(cpath, (mode_t)mode);
  sfl_raw_free(cpath);
  if (rc != 0) {
    char *path = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "mkfifo"));
    char msg[512];
    snprintf(msg, sizeof msg, "could not create '%s' (it may already exist)", path);
    sfl_raw_free(path);
    sfl_raise_sig("mkfifo", "%s", msg);
  }
  return sfl_true;
}

/* ------------------------------------------------------------------------- */
/* Readiness multiplexing                                                     */
/* ------------------------------------------------------------------------- */

/* poll's trailing timeout: absent or null blocks until something is ready, and any
   negative value is that same "forever" spelled out — poll(2)'s own rule. */
static int64_t poll_timeout(int64_t argc, SflVal *argv) {
  if (argc > 1 && argv[1] != sfl_null) {
    int64_t ms = sfl_arg_int(argv, 1, "poll");
    return ms < 0 ? -1 : ms;
  }
  return -1;
}

SflVal sfl_p_poll(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "poll");
  int64_t timeout = poll_timeout(argc, argv);
  uint32_t n = arr->aux;

  /* One left-to-right pass settles every complaint before anything waits: the
     wrong kind, and the handle whose read would already raise. */
  for (uint32_t i = 0; i < n; i++) {
    SflVal v = arr->u.a.items[i];
    if (v->tag != SFL_HANDLE)
      sfl_raise_sig("poll", "element %u is %s, not a pollable handle", i + 1, sfl_type_name(v));
    const char *k = v->u.h.kind;
    if (strcmp(k, "connection") == 0 || strcmp(k, "server") == 0 || strcmp(k, "udp") == 0) {
      if (v->u.h.payload == NULL) sfl_raise_sig("poll", "Socket closed");
    } else if (strcmp(k, "file") == 0) {
      FileH *f = (FileH *)v->u.h.payload;
      if (f == NULL) sfl_raise_sig("poll", "Stream closed");
      if (!f->reading) sfl_raise_sig("poll", "'%s' is open for writing", f->path);
    } else if (strcmp(k, "process") != 0) {
      sfl_raise_sig("poll", "element %u is %s, not a pollable handle", i + 1, sfl_type_name(v));
    }
  }
  /* Nothing can ever become ready, and "wait forever" must not mean it literally. */
  if (n == 0) return sfl_arr_new(1);

  /* The pollfd set and the ready flags live in raw buffers, freed before anything
     below can raise — a raise leaves by longjmp and would never come back. */
  struct pollfd *fds = (struct pollfd *)sfl_raw_alloc((size_t)n * sizeof *fds);
  char *ready = (char *)sfl_raw_alloc(n);
  int any_buffered = 0;
  for (uint32_t i = 0; i < n; i++) {
    SflVal v = arr->u.a.items[i];
    const char *k = v->u.h.kind;
    LineIn *in = NULL;
    int fd = -1;
    if (strcmp(k, "connection") == 0) {
      Conn *c = (Conn *)v->u.h.payload;
      in = &c->in;
      fd = c->fd;
    } else if (strcmp(k, "server") == 0) {
      fd = ((Server *)v->u.h.payload)->fd;
    } else if (strcmp(k, "udp") == 0) {
      fd = ((Udp *)v->u.h.payload)->fd;
    } else if (strcmp(k, "process") == 0) {
      Child *c = (Child *)v->u.h.payload;
      in = &c->out;
      fd = c->out.fd;
    } else {
      FileH *f = (FileH *)v->u.h.payload;
      in = &f->in;
      fd = f->in.fd;
    }
    /* Readiness the descriptor cannot see: bytes the handle already holds. */
    ready[i] = in != NULL && line_in_buffered(in);
    if (ready[i]) any_buffered = 1;
    fds[i].fd = ready[i] ? -1 : fd; /* poll(2) ignores negative fds */
    fds[i].events = POLLIN;
    fds[i].revents = 0;
  }

  /* If anything is buffer-ready the wait collapses to a probe, because the answer
     must describe NOW; EINTR recomputes what is left of a bounded wait. */
  int64_t deadline = timeout > 0 ? sfl_time_millis() + timeout : 0;
  int rc;
  for (;;) {
    int wait_ms = (any_buffered || timeout == 0) ? 0
                  : timeout < 0                  ? -1
                                                 : millis_left(deadline);
    rc = poll(fds, n, wait_ms);
    if (rc >= 0 || errno != EINTR) break;
  }
  if (rc < 0) {
    int saved = errno;
    sfl_raw_free(fds);
    sfl_raw_free(ready);
    sfl_raise_sig("poll", "%s", strerror(saved));
  }

  for (uint32_t i = 0; i < n; i++)
    if (!ready[i] && (fds[i].revents & (POLLIN | POLLHUP | POLLERR))) ready[i] = 1;
  sfl_raw_free(fds);

  /* Built last: pushing can allocate, and the raw buffer above must not be live
     across a collection point it does not need to survive. */
  SflVal out = sfl_arr_new(n);
  for (uint32_t i = 0; i < n; i++)
    if (ready[i]) sfl_arr_push(out, arr->u.a.items[i]);
  sfl_raw_free(ready);
  return out;
}

/* ------------------------------------------------------------------------- */
/* Process identity and signals                                               */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_pid(int64_t argc, SflVal *argv) { return sfl_int((int64_t)getpid()); }

SflVal sfl_p_signalProcess(int64_t argc, SflVal *argv) {
  int target = (int)sfl_arg_idx(argv, 0, "signalProcess");
  int sig = (int)sfl_arg_idx(argv, 1, "signalProcess");
  if (kill((pid_t)target, sig) != 0)
    sfl_raise_sig("signalProcess", "could not signal process %d", target);
  return sfl_true;
}

/* ------------------------------------------------------------------------- */
/* Signal numbers                                                             */
/* ------------------------------------------------------------------------- */

/*
 * Taken from the host's headers rather than written out, so a compiled program
 * agrees with the platform it runs on. The interpreter hard-codes the same values
 * for macOS and Linux.
 */
#define SFL_SIGNAL(name, value)                                                \
  SflVal sfl_p_##name(int64_t argc, SflVal *argv) {                            \
    (void)argc;                                                                \
    (void)argv;                                                                \
    return sfl_int(value);                                                     \
  }

SFL_SIGNAL(SIGINT, SIGINT)
SFL_SIGNAL(SIGKILL, SIGKILL)
SFL_SIGNAL(SIGTERM, SIGTERM)
SFL_SIGNAL(SIGHUP, SIGHUP)
SFL_SIGNAL(SIGUSR1, SIGUSR1)
SFL_SIGNAL(SIGUSR2, SIGUSR2)

#undef SFL_SIGNAL
