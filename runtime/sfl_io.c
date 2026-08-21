/*
 * The world outside the program: the console, the filesystem, processes, the
 * environment, the clock, and the reflective builtins that let a program ask the
 * runtime about itself.
 *
 * The interpreter implements all of this against the JDK, so wherever Java's
 * behaviour is observable it is reproduced literally rather than delegated to the
 * nearest POSIX call: paths are folded the way java.io.File folds them (which is
 * not what basename(3) and dirname(3) do), a child killed by a signal reports
 * 128 + signal, and a temporary file is created empty and owner-only. Everything
 * underneath is plain POSIX.
 */

/* Asks glibc for POSIX.2008 (stat, opendir, realpath, setenv, clock_gettime),
   and Darwin for its full set, which _POSIX_C_SOURCE would otherwise narrow. */
#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE 1

#include "sfl.h"

/* Names beginning with __ exist only so the standard library can be written in SFL;
   they are not part of the language people write, so they stay out of the listings. */
static int is_internal(const char *name) { return name[0] == '_' && name[1] == '_'; }

#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/utsname.h>
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

/* The UTF-8 of a value's display form. The caller frees it with sfl_raw_free. */
static char *display_utf8(SflVal v) {
  SflVal s = sfl_display(v);
  return sfl_str_dup_utf8(s);
}

/* Writes a value the way print does: display form, encoded as UTF-8 through the
   charset-encoder rules, since the interpreter's PrintStream turns an unpaired
   surrogate into '?'. The length is taken from the string rather than from
   strlen, so an embedded U+0000 survives instead of truncating the line. */
static void write_display(FILE *out, SflVal v) {
  SflVal s = sfl_display(v);
  int64_t n = sfl_utf8_java_len(s);
  char *buf = (char *)sfl_raw_alloc((size_t)n + 1);
  sfl_utf8_java_write(s, buf);
  fwrite(buf, 1, (size_t)n, out);
  sfl_raw_free(buf);
}

/* Adds one field. `o` and `v` are parameters, so both stay reachable through the
   conservative stack scan if allocating the key triggers a collection. */
static void put_field(SflVal o, const char *key, SflVal v) {
  sfl_obj_put(o, sfl_str_utf8(key, -1), v);
}

static void ascii_lower(char *s) {
  for (; *s; s++)
    if (*s >= 'A' && *s <= 'Z') *s = (char)(*s + 32);
}

/* ------------------------------------------------------------------------- */
/* Console                                                                    */
/* ------------------------------------------------------------------------- */

static SflVal print_joined(FILE *out, int64_t argc, SflVal *argv, int newline) {
  for (int64_t i = 0; i < argc; i++) {
    if (i) fputc(' ', out);
    write_display(out, argv[i]);
  }
  if (newline) fputc('\n', out);
  return sfl_null;
}

SflVal sfl_p_print(int64_t argc, SflVal *argv) {
  return print_joined(stdout, argc, argv, 0);
}

SflVal sfl_p_println(int64_t argc, SflVal *argv) {
  return print_joined(stdout, argc, argv, 1);
}

SflVal sfl_p_eprintln(int64_t argc, SflVal *argv) {
  return print_joined(stderr, argc, argv, 1);
}

SflVal sfl_p_printf(int64_t argc, SflVal *argv) {
  /* The interpreter routes printf through the same formatter as format(), down
     to the "format: ..." text of a bad pattern, so this does too. */
  sfl_arg_str(argv, 0, "printf");
  SflVal s = sfl_p_format(argc, argv);
  write_display(stdout, s);
  return sfl_null;
}

SflVal sfl_p_flush(int64_t argc, SflVal *argv) {
  fflush(stdout);
  return sfl_null;
}

SflVal sfl_p_readLine(int64_t argc, SflVal *argv) {
  if (argc > 0) {
    write_display(stdout, argv[0]);
    fflush(stdout);
  }
  size_t cap = 128, n = 0;
  char *buf = (char *)sfl_raw_alloc(cap);
  int c;
  while ((c = fgetc(stdin)) != EOF && c != '\n') {
    if (n + 1 >= cap) {
      cap *= 2;
      buf = (char *)sfl_raw_realloc(buf, cap);
    }
    buf[n++] = (char)c;
  }
  if (c == EOF && n == 0) { /* end of input, as readLine() reports with null */
    sfl_raw_free(buf);
    return sfl_null;
  }
  if (n > 0 && buf[n - 1] == '\r') n--; /* a CRLF terminator, which readLine drops */
  SflVal s = sfl_str_utf8(buf, (int64_t)n);
  sfl_raw_free(buf);
  return s;
}

/* Everything a stream still has to give. Returns a raw buffer and its length. */
static char *slurp(FILE *f, int64_t *len) {
  size_t cap = 8192, n = 0;
  char *buf = (char *)sfl_raw_alloc(cap);
  for (;;) {
    if (n == cap) {
      cap *= 2;
      buf = (char *)sfl_raw_realloc(buf, cap);
    }
    size_t got = fread(buf + n, 1, cap - n, f);
    if (got == 0) break;
    n += got;
  }
  *len = (int64_t)n;
  return buf;
}

SflVal sfl_p_readAll(int64_t argc, SflVal *argv) {
  int64_t n = 0;
  char *buf = slurp(stdin, &n);
  SflVal s = sfl_str_utf8(buf, n);
  sfl_raw_free(buf);
  return s;
}

/*
 * The typed fast paths. Where inference proves a machine type the compiler emits
 * these instead of boxing a value only to print it; they must render exactly as
 * sfl_display would.
 */
void sfl_print_i64(int64_t v, int nl) {
  fprintf(stdout, nl ? "%lld\n" : "%lld", (long long)v);
}

void sfl_print_f64(double v, int nl) {
  char buf[64];
  sfl_write_double(buf, sizeof buf, v);
  fputs(buf, stdout);
  if (nl) fputc('\n', stdout);
}

void sfl_print_bool(int v, int nl) {
  fputs(v ? "true" : "false", stdout);
  if (nl) fputc('\n', stdout);
}

void sfl_print_val(SflVal v, int nl) {
  write_display(stdout, v);
  if (nl) fputc('\n', stdout);
}

void sfl_print_sep(void) { fputc(' ', stdout); }

/* ------------------------------------------------------------------------- */
/* Files                                                                      */
/* ------------------------------------------------------------------------- */

/* FileUtil.read: the two common mistakes get their own message before the
   generic one, because "file not found: x" is actionable and "cannot read" is
   not. The caller frees the buffer. `shown` is the path as the message spells
   it: the interpreter opens the toCString bytes but reports the string it was
   given, so the two part ways when a surrogate became '?' on the way down. */
static char *read_whole_file(const char *path, const char *shown, int64_t *len) {
  struct stat st;
  if (stat(path, &st) != 0) sfl_raise("file not found: %s", shown);
  if (S_ISDIR(st.st_mode)) sfl_raise("'%s' is a directory, not a file", shown);
  FILE *f = fopen(path, "rb");
  if (f == NULL) sfl_raise("cannot read '%s': %s", shown, strerror(errno));
  char *data = slurp(f, len);
  int failed = ferror(f);
  int saved = errno;
  fclose(f);
  if (failed) {
    sfl_raw_free(data);
    sfl_raise("cannot read '%s': %s", shown, strerror(saved));
  }
  return data;
}

static void write_whole_file(const char *path, const char *shown, SflVal content, int append) {
  FILE *f = fopen(path, append ? "ab" : "wb");
  if (f == NULL) sfl_raise("cannot write '%s': %s", shown, strerror(errno));
  SflVal s = sfl_display(content);
  /* An OutputStreamWriter encodes what it is given, '?' for an unpaired surrogate. */
  int64_t n = sfl_utf8_java_len(s);
  char *buf = (char *)sfl_raw_alloc((size_t)n + 1);
  sfl_utf8_java_write(s, buf);
  int ok = fwrite(buf, 1, (size_t)n, f) == (size_t)n;
  sfl_raw_free(buf);
  int saved = ok ? 0 : errno;
  if (fclose(f) != 0) { /* the write may only fail as the last buffer drains */
    if (ok) saved = errno;
    ok = 0;
  }
  if (!ok) sfl_raise("cannot write '%s': %s", shown, strerror(saved));
}

SflVal sfl_p_readFile(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "readFile"));
  char *shown = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "readFile"));
  int64_t n = 0;
  char *data = read_whole_file(path, shown, &n);
  sfl_raw_free(shown);
  sfl_raw_free(path);
  SflVal s = sfl_str_utf8(data, n);
  sfl_raw_free(data);
  return s;
}

SflVal sfl_p_readLines(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "readLines"));
  char *shown = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "readLines"));
  int64_t n = 0;
  char *data = read_whole_file(path, shown, &n);
  sfl_raw_free(shown);
  sfl_raw_free(path);

  /* split("\r\n|\n|\r", -1): every terminator counts and the trailing empty
     field is kept, so a file ending in a newline yields a final "". */
  SflVal out = sfl_arr_new(8);
  int64_t start = 0;
  for (int64_t i = 0; i <= n; i++) {
    int eol = i < n && (data[i] == '\n' || data[i] == '\r');
    if (i == n || eol) {
      SflVal line = sfl_str_utf8(data + start, i - start);
      sfl_arr_push(out, line);
      if (i < n && data[i] == '\r' && i + 1 < n && data[i + 1] == '\n') i++;
      start = i + 1;
    }
  }
  sfl_raw_free(data);
  return out;
}

SflVal sfl_p_writeFile(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "writeFile"));
  char *shown = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "writeFile"));
  write_whole_file(path, shown, argv[1], 0);
  sfl_raw_free(shown);
  sfl_raw_free(path);
  return sfl_null;
}

SflVal sfl_p_appendFile(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "appendFile"));
  char *shown = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "appendFile"));
  write_whole_file(path, shown, argv[1], 1);
  sfl_raw_free(shown);
  sfl_raw_free(path);
  return sfl_null;
}

SflVal sfl_p_removeFile(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "removeFile"));
  /* remove(3) unlinks a file and rmdirs an empty directory, which is exactly the
     pair File.delete() covers. */
  int ok = remove(path) == 0;
  sfl_raw_free(path);
  return sfl_bool(ok);
}

static int cmp_strval(const void *a, const void *b) {
  return (int)sfl_compare(*(SflVal const *)a, *(SflVal const *)b);
}

SflVal sfl_p_listFiles(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "listFiles"));
  DIR *d = opendir(path);
  sfl_raw_free(path);
  if (d == NULL) return sfl_null; /* File.list() returns null for a non-directory */

  SflVal out = sfl_arr_new(16);
  struct dirent *e;
  while ((e = readdir(d)) != NULL) {
    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
    SflVal name = sfl_str_utf8(e->d_name, -1);
    sfl_arr_push(out, name);
  }
  closedir(d);
  /* Sorting cannot allocate, so the side buffer is safe to hand to qsort. */
  qsort(out->u.a.items, out->aux, sizeof(SflVal), cmp_strval);
  return out;
}

static int path_stat(SflVal *argv, const char *fn, struct stat *st) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, fn));
  int ok = stat(path, st) == 0;
  sfl_raw_free(path);
  return ok;
}

SflVal sfl_p_exists(int64_t argc, SflVal *argv) {
  struct stat st;
  return sfl_bool(path_stat(argv, "exists", &st));
}

SflVal sfl_p_isFile(int64_t argc, SflVal *argv) {
  struct stat st;
  return sfl_bool(path_stat(argv, "isFile", &st) && S_ISREG(st.st_mode));
}

SflVal sfl_p_isDir(int64_t argc, SflVal *argv) {
  struct stat st;
  return sfl_bool(path_stat(argv, "isDir", &st) && S_ISDIR(st.st_mode));
}

SflVal sfl_p_fileSize(int64_t argc, SflVal *argv) {
  struct stat st;
  if (!path_stat(argv, "fileSize", &st)) return sfl_int(-1);
  return sfl_int((int64_t)st.st_size);
}

SflVal sfl_p_fileMtime(int64_t argc, SflVal *argv) {
  struct stat st;
  if (!path_stat(argv, "fileMtime", &st)) return sfl_int(-1);
  /* Milliseconds since the epoch, as File.lastModified reports it. The
     sub-second field is spelled differently on the two platforms; both are
     folded in so a millisecond-resolution filesystem agrees with the
     interpreter's lastModified. */
#if defined(__APPLE__)
  int64_t ns = st.st_mtimespec.tv_nsec;
#else
  int64_t ns = st.st_mtim.tv_nsec;
#endif
  return sfl_int((int64_t)st.st_mtime * 1000 + ns / 1000000);
}

SflVal sfl_p_mkdirs(int64_t argc, SflVal *argv) {
  char *path = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "mkdirs"));
  struct stat st;
  if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
    sfl_raw_free(path);
    return sfl_true;
  }
  /* Create each parent in turn; only the final mkdir decides the result, as
     File.mkdirs() reports whether the directory itself came into being. The
     separator at index 0 is the root, which needs no creating. */
  for (char *s = path; *s; s++) {
    if (*s != '/' || s == path) continue;
    *s = '\0';
    mkdir(path, 0777);
    *s = '/';
  }
  int ok = mkdir(path, 0777) == 0;
  sfl_raw_free(path);
  return sfl_bool(ok);
}

SflVal sfl_p_copyFile(int64_t argc, SflVal *argv) {
  char *from = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "copyFile"));
  char *to = sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "copyFile"));
  /* The messages spell the paths as the program wrote them, not as open(2) saw them. */
  char *from_shown = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "copyFile"));
  char *to_shown = sfl_str_dup_utf8(sfl_arg_str(argv, 1, "copyFile"));

  int in = open(from, O_RDONLY);
  if (in < 0) sfl_raise_sig("copyFile", "%s: %s", from_shown, strerror(errno));
  struct stat st;
  if (fstat(in, &st) != 0) {
    int saved = errno;
    close(in);
    sfl_raise_sig("copyFile", "%s: %s", from_shown, strerror(saved));
  }
  /* REPLACE_EXISTING, and the destination inherits the source's permissions the
     way Files.copy creates it. */
  int out = open(to, O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 0777);
  if (out < 0) {
    int saved = errno;
    close(in);
    sfl_raise_sig("copyFile", "%s: %s", to_shown, strerror(saved));
  }
  char buf[65536];
  ssize_t got;
  int saved = 0;
  while ((got = read(in, buf, sizeof buf)) > 0) {
    ssize_t off = 0;
    while (off < got) {
      ssize_t put = write(out, buf + off, (size_t)(got - off));
      if (put <= 0) { saved = errno; break; }
      off += put;
    }
    if (saved) break;
  }
  if (got < 0 && !saved) saved = errno;
  close(in);
  if (close(out) != 0 && !saved) saved = errno;
  if (saved) sfl_raise_sig("copyFile", "%s: %s", to_shown, strerror(saved));
  sfl_raw_free(from);
  sfl_raw_free(to);
  sfl_raw_free(from_shown);
  sfl_raw_free(to_shown);
  return sfl_true;
}

SflVal sfl_p_moveFile(int64_t argc, SflVal *argv) {
  char *from = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "moveFile"));
  char *to = sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "moveFile"));
  /* rename(2) fails across filesystems, which is also when renameTo() is false. */
  int ok = rename(from, to) == 0;
  sfl_raw_free(from);
  sfl_raw_free(to);
  return sfl_bool(ok);
}

/* ------------------------------------------------------------------------- */
/* Randomness, used by uuid() and by temporary file names                     */
/* ------------------------------------------------------------------------- */

static void random_bytes(unsigned char *out, size_t n) {
  FILE *f = fopen("/dev/urandom", "rb");
  if (f != NULL) {
    size_t got = fread(out, 1, n, f);
    fclose(f);
    if (got == n) return;
  }
  /* Without the kernel pool, mix the clock, the pid and this frame's address:
     enough for a name nobody else in the process will pick. */
  uint64_t x = (uint64_t)time(NULL) ^ ((uint64_t)getpid() << 32) ^ (uintptr_t)out;
  for (size_t i = 0; i < n; i++) {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    out[i] = (unsigned char)(x >> 24);
  }
}

static const char *temp_dir(void) {
  const char *d = getenv("TMPDIR");
  return (d != NULL && *d != '\0') ? d : "/tmp";
}

/* An empty file nobody else holds, named like File.createTempFile's. Returns its
   path for the caller to free, or NULL with errno set. When `suffix_shown` is
   given, the file is created under `suffix` but the returned path carries the
   shown spelling — createTempFile keeps the String it was handed even where the
   encoder rewrote what reached the filesystem. */
static char *temp_create(const char *prefix, const char *suffix, const char *suffix_shown) {
  if (suffix_shown == NULL) suffix_shown = suffix;
  const char *dir = temp_dir();
  size_t dlen = strlen(dir);
  while (dlen > 1 && dir[dlen - 1] == '/') dlen--;
  for (int attempt = 0; attempt < 100; attempt++) {
    unsigned char r[8];
    random_bytes(r, sizeof r);
    char stem[32];
    snprintf(stem, sizeof stem, "%02x%02x%02x%02x%02x%02x%02x%02x", r[0], r[1], r[2],
             r[3], r[4], r[5], r[6], r[7]);
    size_t n = dlen + strlen(prefix) + strlen(stem) + strlen(suffix) + 2;
    char *path = (char *)sfl_raw_alloc(n);
    snprintf(path, n, "%.*s/%s%s%s", (int)dlen, dir, prefix, stem, suffix);
    int fd = open(path, O_CREAT | O_EXCL | O_RDWR, 0600);
    if (fd >= 0) {
      close(fd);
      if (suffix_shown != suffix) {
        size_t m = dlen + strlen(prefix) + strlen(stem) + strlen(suffix_shown) + 2;
        char *shown = (char *)sfl_raw_alloc(m);
        snprintf(shown, m, "%.*s/%s%s%s", (int)dlen, dir, prefix, stem, suffix_shown);
        sfl_raw_free(path);
        return shown;
      }
      return path;
    }
    sfl_raw_free(path);
    if (errno != EEXIST) return NULL;
  }
  return NULL;
}

SflVal sfl_p_tempFile(int64_t argc, SflVal *argv) {
  char *suffix = argc > 0 ? sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "tempFile")) : dup_cstr(".tmp");
  char *shown = argc > 0 ? sfl_str_dup_utf8(sfl_arg_str(argv, 0, "tempFile")) : NULL;
  char *path = temp_create("sfl-", suffix, shown);
  sfl_raw_free(suffix);
  if (shown != NULL) sfl_raw_free(shown);
  if (path == NULL) sfl_raise_sig("tempFile", "%s", strerror(errno));
  SflVal s = sfl_str_utf8(path, -1);
  sfl_raw_free(path);
  return s;
}

/* ------------------------------------------------------------------------- */
/* Paths                                                                      */
/* ------------------------------------------------------------------------- */

/*
 * UnixFileSystem.normalize: runs of separators collapse and a trailing separator
 * goes, unless the whole path is "/". "." and ".." are left alone — java.io.File
 * only folds those in getCanonicalPath. Rewrites in place.
 */
static void path_normalize(char *p) {
  size_t w = 0;
  for (size_t r = 0; p[r]; r++) {
    if (p[r] == '/' && w > 0 && p[w - 1] == '/') continue;
    p[w++] = p[r];
  }
  if (w > 1 && p[w - 1] == '/') w--;
  p[w] = '\0';
}

/* Index of the last separator, or -1. */
static long last_slash(const char *p, long n) {
  for (long i = n - 1; i >= 0; i--)
    if (p[i] == '/') return i;
  return -1;
}

SflVal sfl_p_baseName(int64_t argc, SflVal *argv) {
  char *p = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "baseName"));
  path_normalize(p);
  long n = (long)strlen(p);
  long prefix = (n > 0 && p[0] == '/') ? 1 : 0;
  long idx = last_slash(p, n);
  /* File.getName: everything past the last separator, or the whole path when the
     only separator is the root's — which is why getName("/") is "". */
  SflVal s = idx < prefix ? sfl_str_utf8(p + prefix, n - prefix)
                          : sfl_str_utf8(p + idx + 1, n - idx - 1);
  sfl_raw_free(p);
  return s;
}

SflVal sfl_p_dirName(int64_t argc, SflVal *argv) {
  char *p = sfl_str_dup_utf8(sfl_arg_str(argv, 0, "dirName"));
  path_normalize(p);
  long n = (long)strlen(p);
  long prefix = (n > 0 && p[0] == '/') ? 1 : 0;
  long idx = last_slash(p, n);
  SflVal s;
  if (idx < prefix)
    /* No parent above the prefix: "/a" has "/", but "a" and "/" have none, and a
       missing parent is reported as "." rather than null. */
    s = (prefix > 0 && n > prefix) ? sfl_str_utf8("/", 1) : sfl_str_utf8(".", 1);
  else
    s = sfl_str_utf8(p, idx);
  sfl_raw_free(p);
  return s;
}

/* UnixFileSystem.resolve(parent, child), both already normalised. */
static char *path_resolve(const char *parent, const char *child) {
  size_t pl = strlen(parent), cl = strlen(child);
  int parent_is_root = (pl == 1 && parent[0] == '/');
  int child_absolute = (cl > 0 && child[0] == '/');
  size_t n = pl + cl + 2;
  char *out = (char *)sfl_raw_alloc(n);
  if (child_absolute && parent_is_root) snprintf(out, n, "%s", child);
  else if (child_absolute || parent_is_root) snprintf(out, n, "%s%s", parent, child);
  else snprintf(out, n, "%s/%s", parent, child);
  return out;
}

SflVal sfl_p_joinPath(int64_t argc, SflVal *argv) {
  char *acc = NULL;
  for (int64_t i = 0; i < argc; i++) {
    char *part = display_utf8(argv[i]);
    if (*part == '\0') { /* empty segments are dropped before joining */
      sfl_raw_free(part);
      continue;
    }
    if (acc == NULL) {
      /* A lone segment is returned verbatim: reduceLeft never calls File(a, b)
         and so never normalises it. */
      acc = part;
      continue;
    }
    path_normalize(acc);
    path_normalize(part);
    char *joined = path_resolve(acc, part);
    sfl_raw_free(acc);
    sfl_raw_free(part);
    acc = joined;
  }
  if (acc == NULL) return sfl_str_empty();
  SflVal s = sfl_str_utf8(acc, -1);
  sfl_raw_free(acc);
  return s;
}

/* Folds ".", ".." and duplicate separators out of an absolute path. This is what
   getCanonicalPath falls back to when the path does not exist and there are no
   symlinks left to resolve. */
static char *lexical_canonical(const char *path) {
  char *abs;
  if (path[0] == '/') {
    abs = dup_cstr(path);
  } else {
    char cwd[4096];
    if (getcwd(cwd, sizeof cwd) == NULL) return dup_cstr(path);
    size_t n = strlen(cwd) + strlen(path) + 2;
    abs = (char *)sfl_raw_alloc(n);
    snprintf(abs, n, "%s/%s", cwd, path);
  }
  size_t len = strlen(abs);
  char *out = (char *)sfl_raw_alloc(len + 2);
  size_t w = 0;
  for (size_t i = 0; i < len;) {
    while (i < len && abs[i] == '/') i++;
    size_t start = i;
    while (i < len && abs[i] != '/') i++;
    size_t seg = i - start;
    if (seg == 0 || (seg == 1 && abs[start] == '.')) continue;
    if (seg == 2 && abs[start] == '.' && abs[start + 1] == '.') {
      while (w > 0 && out[w - 1] != '/') w--;
      if (w > 0) w--; /* drop the separator too */
      continue;
    }
    out[w++] = '/';
    memcpy(out + w, abs + start, seg);
    w += seg;
  }
  if (w == 0) out[w++] = '/';
  out[w] = '\0';
  sfl_raw_free(abs);
  return out;
}

SflVal sfl_p_absPath(int64_t argc, SflVal *argv) {
  char *p = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "absPath"));
  char *real = realpath(p, NULL);
  SflVal s;
  if (real != NULL) {
    s = sfl_str_utf8(real, -1);
    free(real);
  } else {
    /* realpath needs the path to exist; getCanonicalPath does not. */
    char *folded = lexical_canonical(p);
    s = sfl_str_utf8(folded, -1);
    sfl_raw_free(folded);
  }
  sfl_raw_free(p);
  return s;
}

/* ------------------------------------------------------------------------- */
/* Process and environment                                                    */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_getEnv(int64_t argc, SflVal *argv) {
  char *name = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "getEnv"));
  const char *v = getenv(name);
  sfl_raw_free(name);
  if (v == NULL) return sfl_opt(argc, argv, 1);
  return sfl_str_utf8(v, -1);
}

SflVal sfl_p_setEnv(int64_t argc, SflVal *argv) {
  char *name = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "setEnv"));
  char *value = sfl_str_dup_utf8_java(sfl_display(argv[1]));
  setenv(name, value, 1);
  sfl_raw_free(name);
  sfl_raw_free(value);
  return sfl_null;
}

SflVal sfl_p_cwd(int64_t argc, SflVal *argv) {
  char buf[4096];
  if (getcwd(buf, sizeof buf) == NULL)
    sfl_raise("cannot read the current directory: %s", strerror(errno));
  return sfl_str_utf8(buf, -1);
}

SflVal sfl_p_args(int64_t argc, SflVal *argv) {
  /* A compiled program is the script, so its own arguments are everything after
     argv[0] — the same slice the interpreter publishes after the script name. */
  SflVal out = sfl_arr_new(sfl_argc > 1 ? sfl_argc - 1 : 1);
  for (int i = 1; i < sfl_argc; i++) {
    SflVal s = sfl_str_utf8(sfl_argv[i], -1);
    sfl_arr_push(out, s);
  }
  return out;
}

SflVal sfl_p_scriptPath(int64_t argc, SflVal *argv) {
  if (sfl_argc < 1 || sfl_argv[0] == NULL || sfl_argv[0][0] == '\0') return sfl_null;
  char *p = dup_cstr(sfl_argv[0]);
  path_normalize(p);
  SflVal s;
  if (p[0] == '/') {
    s = sfl_str_utf8(p, -1);
  } else {
    /* getAbsolutePath: prepend the working directory, without folding anything. */
    char cwd[4096];
    if (getcwd(cwd, sizeof cwd) == NULL) {
      s = sfl_str_utf8(p, -1);
    } else {
      size_t n = strlen(cwd) + strlen(p) + 2;
      char *full = (char *)sfl_raw_alloc(n);
      snprintf(full, n, "%s/%s", cwd, p);
      s = sfl_str_utf8(full, -1);
      sfl_raw_free(full);
    }
  }
  sfl_raw_free(p);
  return s;
}

/*
 * The interpreter reports the JVM's os.name, so this translates uname's spelling to
 * the same one. A program that branches on the platform must not see "Darwin" from
 * one and "Mac OS X" from the other.
 */
SflVal sfl_p_osName(int64_t argc, SflVal *argv) {
  struct utsname u;
  if (uname(&u) != 0) return sfl_str_utf8("unknown", -1);
  if (strcmp(u.sysname, "Darwin") == 0) return sfl_str_utf8("Mac OS X", -1);
  return sfl_str_utf8(u.sysname, -1);
}

SflVal sfl_p_sleep(int64_t argc, SflVal *argv) {
  int64_t ms = sfl_arg_int(argv, 0, "sleep");
  if (ms < 0) sfl_raise_sig("sleep", "duration must not be negative");
  struct timespec ts;
  ts.tv_sec = (time_t)(ms / 1000);
  ts.tv_nsec = (long)(ms % 1000) * 1000000L;
  /* Thread.sleep sleeps the whole duration, so resume with what is left over. */
  while (nanosleep(&ts, &ts) != 0 && errno == EINTR) continue;
  return sfl_null;
}

SflVal sfl_p_uuid(int64_t argc, SflVal *argv) {
  unsigned char b[16];
  random_bytes(b, sizeof b);
  b[6] = (unsigned char)((b[6] & 0x0f) | 0x40); /* version 4  */
  b[8] = (unsigned char)((b[8] & 0x3f) | 0x80); /* variant 10 */
  char s[40];
  snprintf(s, sizeof s,
           "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11],
           b[12], b[13], b[14], b[15]);
  return sfl_str_utf8(s, 36);
}

SflVal sfl_p_exit(int64_t argc, SflVal *argv) {
  int code = argc > 0 ? (int)sfl_arg_idx(argv, 0, "exit") : 0;
  /* On a spawned thread this becomes the thread's failure and never returns:
     only the main thread may stop the process. */
  sfl_thread_exit(code);
  fflush(NULL);
  exit(code);
}

/* Proc.splitCommand: whitespace separates, single and double quotes group and
   are themselves dropped. The result is NULL-terminated for execvp. */
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
  for (size_t i = 0; args[i] != NULL; i++) sfl_raw_free(args[i]);
  sfl_raw_free(args);
}

SflVal sfl_p_execute(int64_t argc, SflVal *argv) {
  char *cmd = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "execute"));
  int use_shell = argc > 1 ? sfl_truthy(argv[1]) : 1;

  char **args;
  if (use_shell) {
    args = (char **)sfl_raw_alloc(4 * sizeof(char *));
    args[0] = dup_cstr("/bin/sh");
    args[1] = dup_cstr("-c");
    args[2] = cmd; /* the array owns it from here */
    args[3] = NULL;
  } else {
    args = split_command(cmd);
    sfl_raw_free(cmd);
  }

  /* Output goes to files rather than pipes: reading two pipes in turn from one
     thread deadlocks as soon as the child fills the one nobody is draining. */
  char *outp = temp_create("sfl-out", ".tmp", NULL);
  char *errp = outp != NULL ? temp_create("sfl-err", ".tmp", NULL) : NULL;
  int ofd = outp != NULL ? open(outp, O_WRONLY | O_TRUNC) : -1;
  int efd = errp != NULL ? open(errp, O_WRONLY | O_TRUNC) : -1;
  int failfd[2] = {-1, -1};
  if (efd < 0 || pipe(failfd) != 0) {
    int saved = errno;
    free_argv(args);
    sfl_raise("cannot run command: %s", strerror(saved));
  }
  /* The child's end closes on a successful exec, which is how the parent learns
     that exec worked: a short read means it did. */
  fcntl(failfd[1], F_SETFD, FD_CLOEXEC);

  pid_t pid = fork();
  if (pid == 0) {
    dup2(ofd, STDOUT_FILENO);
    dup2(efd, STDERR_FILENO);
    close(ofd);
    close(efd);
    close(failfd[0]);
    if (args[0] != NULL) execvp(args[0], args);
    else errno = ENOENT; /* the command was blank, or all quotes */
    int e = errno;
    ssize_t ignored = write(failfd[1], &e, sizeof e);
    (void)ignored;
    _exit(127);
  }
  close(ofd);
  close(efd);
  close(failfd[1]);
  if (pid < 0) {
    int saved = errno;
    close(failfd[0]);
    free_argv(args);
    sfl_raise("cannot run command: %s", strerror(saved));
  }

  int child_errno = 0;
  ssize_t got = read(failfd[0], &child_errno, sizeof child_errno);
  close(failfd[0]);
  int status = 0;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) continue;
  free_argv(args);
  if (got == (ssize_t)sizeof child_errno) {
    unlink(outp);
    unlink(errp);
    sfl_raise("cannot run command: %s", strerror(child_errno));
  }
  /* Java reports a signalled child as 128 + signal; match it. */
  int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);

  int64_t on = 0, en = 0;
  char *ob = read_whole_file(outp, outp, &on);
  char *eb = read_whole_file(errp, errp, &en);
  unlink(outp);
  unlink(errp);
  sfl_raw_free(outp);
  sfl_raw_free(errp);

  SflVal res = sfl_obj_new();
  put_field(res, "code", sfl_int(code));
  put_field(res, "out", sfl_str_utf8(ob, on));
  put_field(res, "err", sfl_str_utf8(eb, en));
  sfl_raw_free(ob);
  sfl_raw_free(eb);
  return res;
}

/* ------------------------------------------------------------------------- */
/* Time                                                                       */
/* ------------------------------------------------------------------------- */

int64_t sfl_time_millis(void) {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

SflVal sfl_p_timeMillis(int64_t argc, SflVal *argv) { return sfl_int(sfl_time_millis()); }

int64_t sfl_time_nanos(void) {
  /* System.nanoTime is monotonic and unrelated to the wall clock. */
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

SflVal sfl_p_timeNanos(int64_t argc, SflVal *argv) { return sfl_int(sfl_time_nanos()); }

/* Seconds truncate toward zero, exactly as the interpreter's `millis / 1000`. */
static void broken_down(int64_t millis, int utc, struct tm *out) {
  time_t t = (time_t)(millis / 1000);
  struct tm *r = utc ? gmtime_r(&t, out) : localtime_r(&t, out);
  if (r == NULL) sfl_raise("cannot convert timestamp to a calendar time");
}

SflVal sfl_p_formatTime(int64_t argc, SflVal *argv) {
  int64_t millis = sfl_arg_int(argv, 0, "formatTime");
  char *fmt = argc > 1 ? sfl_str_dup_utf8_java(sfl_arg_str(argv, 1, "formatTime"))
                       : dup_cstr("%Y-%m-%d %H:%M:%S");
  int utc = argc > 2 && sfl_truthy(argv[2]);
  struct tm tmv;
  broken_down(millis, utc, &tmv);
  char buf[512];
  size_t n = strftime(buf, sizeof buf, fmt, &tmv);
  sfl_raw_free(fmt);
  /* strftime reports both "empty" and "did not fit" as 0, and so does the
     interpreter, which returns "" in that case. */
  return sfl_str_utf8(buf, (int64_t)n);
}

SflVal sfl_p_timeParts(int64_t argc, SflVal *argv) {
  int64_t millis = sfl_arg_int(argv, 0, "timeParts");
  int utc = argc > 1 && sfl_truthy(argv[1]);
  struct tm t;
  broken_down(millis, utc, &t);
  SflVal o = sfl_obj_new();
  put_field(o, "year", sfl_int(t.tm_year + 1900));
  put_field(o, "month", sfl_int(t.tm_mon + 1));
  put_field(o, "day", sfl_int(t.tm_mday));
  put_field(o, "hour", sfl_int(t.tm_hour));
  put_field(o, "minute", sfl_int(t.tm_min));
  put_field(o, "second", sfl_int(t.tm_sec));
  put_field(o, "weekday", sfl_int(t.tm_wday));
  put_field(o, "yearday", sfl_int(t.tm_yday + 1));
  put_field(o, "millis", sfl_int(millis));
  return o;
}

/* ------------------------------------------------------------------------- */
/* Errors as values                                                           */
/* ------------------------------------------------------------------------- */

/*
 * error() and assert() raise text the user wrote. When that text happens to start
 * with the raising builtin's own "name: ", the interpreter's runNative cannot
 * tell it from the builtin's complaint and attaches the signature note; the same
 * text has to gain the same note here.
 */
static void raise_user_message(const char *fn, SflVal v) __attribute__((noreturn));
static void raise_user_message(const char *fn, SflVal v) {
  char *t = display_utf8(v);
  size_t n = strlen(fn);
  int prefixed = strncmp(t, fn, n) == 0 && t[n] == ':' && t[n + 1] == ' ';
  sfl_raw_free(t);
  if (!prefixed) sfl_raise_val(v); /* the display form, so error(obj) reads as the object */
  const SflNative *nat = sfl_native_find(fn);
  char hint[256];
  hint[0] = '\0';
  if (nat) snprintf(hint, sizeof hint, "the signature is %s", nat->signature);
  sfl_raise_val_hint(v, hint);
}

SflVal sfl_p_error(int64_t argc, SflVal *argv) {
  raise_user_message("error", argv[0]);
}

SflVal sfl_p_assert(int64_t argc, SflVal *argv) {
  if (!sfl_truthy(argv[0])) {
    if (argc > 1) raise_user_message("assert", argv[1]);
    sfl_raise("assertion failed");
  }
  return sfl_null;
}

/*
 * try() and attempt() are the two places compiled code catches. The handler
 * stack lives in sfl_call.c: pushing one arms this setjmp, and a raise pops it,
 * restores the traceback depth and jumps here — which is why the catch path must
 * not pop again. Nothing below setjmp assigns to a local, so every value here is
 * still valid after the jump.
 */
SflVal sfl_p_try(int64_t argc, SflVal *argv) {
  SflVal body = sfl_arg_fn(argv, 0, "try");
  SflHandler h;
  sfl_handler_push(&h);
  if (setjmp(h.jb) == 0) {
    SflVal r = sfl_call0(body);
    sfl_handler_pop();
    return r;
  }
  if (argc <= 1) return sfl_null;
  /* The handler is only type-checked once there is something to hand it. */
  SflVal fn = sfl_arg_fn(argv, 1, "try");
  SflVal message = sfl_err_message();
  return sfl_call1(fn, message);
}

SflVal sfl_p_attempt(int64_t argc, SflVal *argv) {
  SflVal body = sfl_arg_fn(argv, 0, "attempt");
  SflHandler h;
  sfl_handler_push(&h);
  if (setjmp(h.jb) == 0) {
    SflVal v = sfl_call0(body);
    sfl_handler_pop();
    SflVal ok = sfl_obj_new();
    put_field(ok, "ok", sfl_true);
    put_field(ok, "value", v);
    return ok;
  }
  SflVal info = sfl_obj_new();
  put_field(info, "message", sfl_err_message());
  put_field(info, "kind", sfl_str_utf8("Runtime error", -1));
  put_field(info, "file", sfl_str_utf8(sfl_err_file(), -1));
  put_field(info, "line", sfl_int(sfl_err_line()));
  put_field(info, "help", sfl_err_help());
  put_field(info, "trace", sfl_err_trace());
  SflVal out = sfl_obj_new();
  put_field(out, "ok", sfl_false);
  put_field(out, "error", info);
  return out;
}

/* ------------------------------------------------------------------------- */
/* Reflection                                                                 */
/* ------------------------------------------------------------------------- */

/*
 * passthrough: the child keeps this process's own stdin, stdout and stderr, so it
 * can be interactive and its output appears as it happens. Nothing is captured, and
 * the answer is the exit code — Java reports a signalled child as 128 + signal, and
 * so does this.
 */
SflVal sfl_p_passthrough(int64_t argc, SflVal *argv) {
  char *cmd = sfl_str_dup_utf8_java(sfl_arg_str(argv, 0, "passthrough"));
  char *shown = sfl_str_dup_utf8(argv[0]);
  int64_t extra = 0;
  if (argc > 1 && argv[1]->tag != SFL_NULL) {
    if (argv[1]->tag != SFL_ARR)
      sfl_raise_sig("passthrough", "argument 2 must be an array, got %s", sfl_type_name(argv[1]));
    extra = (int64_t)argv[1]->aux;
    for (int64_t i = 0; i < extra; i++)
      if (argv[1]->u.a.items[i]->tag != SFL_STR)
        sfl_raise_sig("passthrough",
                      "argument %lld of the args array must be a string, got %s",
                      (long long)(i + 1), sfl_type_name(argv[1]->u.a.items[i]));
  }
  char **args = (char **)sfl_raw_alloc(((size_t)extra + 2) * sizeof(char *));
  args[0] = cmd;
  for (int64_t i = 0; i < extra; i++)
    args[i + 1] = sfl_str_dup_utf8_java(argv[1]->u.a.items[i]);
  args[extra + 1] = NULL;

  int failfd[2] = {-1, -1};
  if (pipe(failfd) != 0) {
    int saved = errno;
    free_argv(args);
    sfl_raw_free(shown);
    sfl_raise_sig("passthrough", "%s", strerror(saved));
  }
  /* The child's end closes on a successful exec, which is how the parent learns
     that exec worked: a short read means it did. */
  fcntl(failfd[1], F_SETFD, FD_CLOEXEC);

  pid_t pid = fork();
  if (pid == 0) {
    close(failfd[0]);
    execvp(args[0], args);
    int e = errno;
    ssize_t ignored = write(failfd[1], &e, sizeof e);
    (void)ignored;
    _exit(127);
  }
  close(failfd[1]);
  if (pid < 0) {
    int saved = errno;
    close(failfd[0]);
    free_argv(args);
    sfl_raw_free(shown);
    sfl_raise_sig("passthrough", "%s", strerror(saved));
  }
  int child_errno = 0;
  ssize_t got = read(failfd[0], &child_errno, sizeof child_errno);
  close(failfd[0]);
  int status = 0;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) continue;
  free_argv(args);
  if (got == (ssize_t)sizeof child_errno) {
    char msg[512];
    snprintf(msg, sizeof msg, "passthrough: cannot run '%s'", shown);
    sfl_raw_free(shown);
    sfl_raise_hint(msg, strerror(child_errno));
  }
  sfl_raw_free(shown);
  return sfl_int(WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status));
}

/*
 * The running binary's path: the loader's answer on macOS, /proc on Linux, and
 * nothing anywhere else. Canonicalised so callers can hand it to a subprocess
 * regardless of how the program was invoked.
 */
SflVal sfl_p_exePath(int64_t argc, SflVal *argv) {
  char raw[4096];
  const char *found = NULL;
#if defined(__APPLE__)
  uint32_t size = (uint32_t)sizeof raw;
  if (_NSGetExecutablePath(raw, &size) == 0) found = raw;
#elif defined(__linux__)
  found = "/proc/self/exe";
#endif
  if (found == NULL) return sfl_null;
  char resolved[4096];
  if (realpath(found, resolved) == NULL) return sfl_null;
  return sfl_str_utf8(resolved, -1);
}

SflVal sfl_p_builtins(int64_t argc, SflVal *argv) {
  SflVal out = sfl_arr_new(sfl_native_count);
  for (int i = 0; i < sfl_native_count; i++) {
    if (is_internal(sfl_natives[i].name)) continue;
    SflVal name = sfl_str_utf8(sfl_natives[i].name, -1);
    sfl_arr_push(out, name);
  }
  return out;
}

SflVal sfl_p_signature(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_NATIVE) return sfl_str_utf8(v->u.n.info->signature, -1);
  if (v->tag == SFL_FUN) return sfl_str_utf8(v->u.f.proto->signature, -1);
  sfl_raise_sig("signature", "expected a function, got %s", sfl_type_name(v));
}

SflVal sfl_p_describe(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  const SflNative *n;
  if (v->tag == SFL_NATIVE) {
    n = v->u.n.info;
  } else if (v->tag == SFL_STR) {
    char *name = sfl_str_dup_utf8(v);
    n = sfl_native_find(name);
    if (n == NULL) {
      char msg[256];
      snprintf(msg, sizeof msg, "no builtin named '%s'", name);
      sfl_raw_free(name);
      sfl_raise_sig("describe", "%s", msg);
    }
    sfl_raw_free(name);
  } else {
    sfl_raise_sig("describe", "expected a builtin or its name, got %s", sfl_type_name(v));
  }
  SflVal o = sfl_obj_new();
  put_field(o, "name", sfl_str_utf8(n->name, -1));
  put_field(o, "group", sfl_str_utf8(n->group, -1));
  put_field(o, "signature", sfl_str_utf8(n->signature, -1));
  put_field(o, "doc", sfl_str_utf8(n->doc, -1));
  put_field(o, "minArity", sfl_int(n->min));
  put_field(o, "maxArity", sfl_int(n->max));
  return o;
}

/* Grouped the way the reference is: by group, then by name inside it. */
static int cmp_native_ref(const void *a, const void *b) {
  const SflNative *x = *(const SflNative *const *)a;
  const SflNative *y = *(const SflNative *const *)b;
  int g = strcmp(x->group, y->group);
  return g != 0 ? g : strcmp(x->name, y->name);
}

static int contains_lower(const char *haystack, const char *needle) {
  char buf[512];
  snprintf(buf, sizeof buf, "%s", haystack);
  ascii_lower(buf);
  return strstr(buf, needle) != NULL;
}

/*
 * Builtins.helpText, in C: the same table, the same order, the same padding.
 * The interpreter is where the groups and the doc lines are written, and the
 * compiler copies both into the natives table, so both engines can print the
 * reference and print it identically. Colour is the interpreter's own; a
 * compiled program never has a terminal's opinion to consult here.
 */
SflVal sfl_p_help(int64_t argc, SflVal *argv) {
  char *filter = argc > 0 ? sfl_str_dup_utf8_java(sfl_display(argv[0])) : dup_cstr("");
  char *lower = dup_cstr(filter);
  ascii_lower(lower);

  const SflNative **hits =
      (const SflNative **)sfl_raw_alloc((size_t)sfl_native_count * sizeof(*hits));
  int n = 0;
  for (int i = 0; i < sfl_native_count; i++) {
    const SflNative *b = &sfl_natives[i];
    if (is_internal(b->name)) continue;
    if (*lower == '\0' || contains_lower(b->name, lower) ||
        contains_lower(b->doc, lower)) {
      hits[n++] = b;
      continue;
    }
    char grp[128];
    snprintf(grp, sizeof grp, "%s", b->group);
    ascii_lower(grp);
    if (strcmp(grp, lower) == 0) hits[n++] = b;
  }

  if (n == 0) {
    printf("no builtin matches '%s'\n", filter);
  } else {
    qsort(hits, (size_t)n, sizeof(*hits), cmp_native_ref);
    int i = 0;
    while (i < n) {
      int j = i;
      size_t width = 0;
      while (j < n && strcmp(hits[j]->group, hits[i]->group) == 0) {
        size_t len = strlen(hits[j]->signature);
        if (len > width) width = len;
        j++;
      }
      printf("%s\n", hits[i]->group);
      for (int k = i; k < j; k++) {
        printf("  %s%*s  %s\n", hits[k]->signature,
               (int)(width - strlen(hits[k]->signature)), "", hits[k]->doc);
      }
      /* A blank line after every group, including the last: the interpreter's
         helpText strips one trailing newline and println puts it back. */
      putchar('\n');
      i = j;
    }
  }
  sfl_raw_free(hits);
  sfl_raw_free(lower);
  sfl_raw_free(filter);
  return sfl_null;
}
