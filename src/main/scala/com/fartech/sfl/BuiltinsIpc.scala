package com.fartech.sfl

import Builtins.*
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.scalanative.libc.{errno as cerr, signal as csignal, stdlib as cstdlib, string as cstr}
import scala.scalanative.posix.{errno as perr, fcntl, netdb, poll as spoll, signal as psignal, unistd}
import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.netdbOps.*
import scala.scalanative.posix.netinet.in
import scala.scalanative.posix.netinet.inOps.*
import scala.scalanative.posix.pollOps.*
import scala.scalanative.posix.sys.{socket as psock, stat, wait as pwait}
import scala.scalanative.posix.sys.socketOps.*
import scala.scalanative.posix.sys.statOps.statOps
// `define` is excluded: scalanative.unsafe has a @define annotation of that name,
// which would shadow Builtins.define for every registration below.
import scala.scalanative.unsafe.{define as _, *}
import scala.scalanative.unsigned.*

/**
 * open(2), redeclared with the blocking annotation posixlib's fcntl.open lacks:
 * opening a FIFO parks the thread until the other end arrives, and a long extern
 * call without the annotation stalls every other thread's GC handshake for as long
 * as it runs.
 */
@extern
private object BlockingFcntl:
  @name("open")
  @blocking
  def open(pathname: CString, flags: CInt, mode: CInt): CInt = extern

/**
 * Communication with other processes: pipes to subprocesses, TCP sockets, line-oriented
 * file handles, named pipes and signals.
 *
 * Everything here is built directly on file descriptors — the same structures, field
 * for field, as the C runtime's sfl_ipc.c — rather than on java.io and java.net, so
 * that poll() can multiplex over them and so that the two engines agree by
 * construction: the same line splitting, the same held partial lines, the same
 * strerror-based error texts. What a program observes is still the JDK's shape,
 * because sfl_ipc.c was written to reproduce it; see the comment at the top of that
 * file for the catalogue.
 *
 * A closed handle keeps its object but drops its descriptor to -1 (files flip a
 * `closed` flag), which is this side's version of the C runtime's payload-NULL:
 * closing twice stays a no-op and use-after-close raises the same "Socket closed" /
 * "Stream closed" the JDK would.
 */
object BuiltinsIpc:

  def register(): Unit =
    subprocesses()
    sockets()
    buffers()
    tls()
    udp()
    files()
    readiness()
    signals()

  private def handleOf[T](a: Array[Value], i: Int, kind: String, fn: String): T = a(i) match
    case h: VHandle if h.kind == kind => h.payload.asInstanceOf[T]
    case v => Err.eval(s"$fn: argument ${i + 1} must be a $kind, got ${v.typeName}")

  // -------------------------------------------------------------------------
  // Small shared helpers
  // -------------------------------------------------------------------------

  private final val IoBufSz = 8192
  private final val Newline = Array[Byte]('\n'.toByte)

  private def strerrorText(err: Int): String = fromCString(cstr.strerror(err))

  /*
   * A write to a pipe or a socket the other end has dropped kills the process by
   * default; the JVM disarms SIGPIPE at startup so Java sees an IOException instead,
   * and both engines have to see the same thing. Unconditional, unlike the C
   * runtime's check-first version: this binary owns its whole process and nothing
   * else in it installs a SIGPIPE handler to preserve.
   */
  private var sigpipeDone = false
  private def ignoreSigpipe(): Unit =
    if !sigpipeDone then
      sigpipeDone = true
      csignal.signal(psignal.SIGPIPE, csignal.SIG_IGN)

  private def closeFd(fd: Int): Unit =
    // Deliberately does not retry on EINTR: after it the descriptor is already gone
    // on Linux, so a second close would land on whatever was opened next.
    if fd >= 0 then unistd.close(fd)

  /** Milliseconds left before `deadline`, clamped to what poll(2) takes. */
  private def millisLeft(deadline: Long): Int =
    val left = deadline - System.currentTimeMillis()
    if left < 0 then 0
    else if left > 2000000000L then 2000000000
    else left.toInt

  /** Waits for one of `events`; 1 ready, 0 timed out, -1 failed. A timeout of 0 or
    * less waits forever, as setSoTimeout(0) does. */
  private def waitReady(fd: Int, events: Int, timeoutMs: Int): Int =
    val deadline = if timeoutMs > 0 then System.currentTimeMillis() + timeoutMs else 0L
    val p = stackalloc[spoll.struct_pollfd]()
    var out = -2
    while out == -2 do
      p.fd = fd
      p.events = events.toShort
      p.revents = 0
      val rc = spoll.poll(p, 1.toUInt, if timeoutMs > 0 then millisLeft(deadline) else -1)
      if rc < 0 then
        if cerr.errno != perr.EINTR then out = -1 // a signal is not the timeout expiring
      else out = if rc == 0 then 0 else 1
    out

  private def readRetry(fd: Int, buf: Array[Byte], n: Int): Int =
    var got = -1
    while
      got = unistd.read(fd, buf.at(0).asInstanceOf[CVoidPtr], n.toUSize)
      got < 0 && cerr.errno == perr.EINTR
    do ()
    got

  /** Drains the whole buffer, since a pipe or a socket may take it in pieces. */
  private def writeAll(fd: Int, bytes: Array[Byte], off0: Int, end: Int): Boolean =
    var off = off0
    while off < end do
      val put = unistd.write(fd, bytes.at(off).asInstanceOf[CVoidPtr], (end - off).toUSize)
      if put < 0 then
        if cerr.errno != perr.EINTR then return false
      else off += put
    true

  private def writeAll(fd: Int, bytes: Array[Byte]): Boolean = writeAll(fd, bytes, 0, bytes.length)

  /** UTF-8 copy on the C heap; the caller frees it. Malloc rather than a Zone so the
    * bytes survive fork() into a child that must not touch the Scala heap. */
  private def mallocCString(s: String): CString =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val p = cstdlib.malloc((bytes.length + 1).toUSize).asInstanceOf[Ptr[Byte]]
    var i = 0
    while i < bytes.length do
      p(i) = bytes(i)
      i += 1
    p(bytes.length) = 0.toByte
    p

  // -------------------------------------------------------------------------
  // A BufferedReader over a descriptor
  // -------------------------------------------------------------------------

  /**
   * The C runtime's LineIn, field for field. Line splitting happens on the bytes
   * rather than after decoding, which is safe because no byte of a multi-byte UTF-8
   * sequence can be '\n' or '\r'; only the finished line is decoded. `skipLf` is
   * BufferedReader's own carry flag: a line that ended at '\r' swallows a '\n' at
   * the start of the next one.
   */
  private final class LineIn(var fd: Int):
    /** Reads go through the TLS session once a connection is wrapped. */
    var tls: Tls.Session = null
    var buf: Array[Byte] = null
    var len = 0
    var pos = 0
    /** A partial line a timeout interrupted. */
    var held: Array[Byte] = null
    var eof = false
    var skipLf = false
    /** The finished line the last successful read() produced. */
    var line: String = null

    /** A read would not block: buffered bytes, a held partial line, recorded EOF,
      * or a decrypted TLS record the descriptor cannot show. */
    def buffered: Boolean = pos < len || held != null || eof || (tls != null && Tls.pending(tls))

    /** What to blame a failed read on: the TLS session's reason when it has one,
      * errno otherwise. */
    def why: String =
      if tls != null && tls.err.nonEmpty then tls.err else strerrorText(cerr.errno)

    /** 1 there is data, 0 end of stream, -1 timed out, -2 failed with errno set. */
    def fill(timeoutMs: Int): Int =
      if pos < len then return 1
      pos = 0
      len = 0
      if eof then return 0
      if buf == null then buf = new Array[Byte](IoBufSz) // lazily, like the JDK's reader
      if tls != null then
        // The TLS loop polls for itself: a mid-record read may need to write.
        val got = Tls.read(tls, fd, buf, IoBufSz, timeoutMs)
        if got < 0 then return got
        if got == 0 then
          eof = true
          return 0
        len = got
        return 1
      if timeoutMs > 0 then
        val ready = waitReady(fd, spoll.POLLIN, timeoutMs)
        if ready == 0 then return -1
        if ready < 0 then return -2
      val got = readRetry(fd, buf, IoBufSz)
      if got < 0 then return -2
      if got == 0 then
        eof = true
        return 0
      len = got
      1

    /**
     * BufferedReader.readLine, byte for byte: 1 with the line in `line`, 0 at the
     * end of the stream, -1 timed out, -2 failed with errno set. Where the JDK
     * throws away whatever it had accumulated when a read times out, this parks it
     * in `held` and picks it up next time, so a slow peer cannot lose the front of
     * a line.
     */
    def read(timeoutMs: Int): Int =
      var acc = held
      var alen = if held != null then held.length else 0
      held = null
      var first = true
      while true do
        val st = fill(timeoutMs)
        if st == 0 then
          if alen > 0 then
            line = new String(acc, 0, alen, StandardCharsets.UTF_8)
            return 1
          return 0
        if st < 0 then
          if alen > 0 then held = java.util.Arrays.copyOf(acc, alen)
          return st
        if first && skipLf && buf(pos) == '\n'.toByte then pos += 1
        skipLf = false
        first = false
        var i = pos
        while i < len && buf(i) != '\n'.toByte && buf(i) != '\r'.toByte do i += 1
        val seg = i - pos
        if seg > 0 then
          val need = alen + seg
          if acc == null then acc = new Array[Byte](math.max(need, 16))
          else if need > acc.length then
            acc = java.util.Arrays.copyOf(acc, math.max(need, acc.length * 2))
          System.arraycopy(buf, pos, acc, alen, seg)
          alen += seg
        pos = i
        if i < len then
          val c = buf(pos)
          pos += 1
          if c == '\r'.toByte then skipLf = true
          line = if acc == null then "" else new String(acc, 0, alen, StandardCharsets.UTF_8)
          return 1
      -2 // unreachable

    /** Everything the stream still has; null when a read failed. */
    def slurp(): String =
      val out = new java.io.ByteArrayOutputStream(IoBufSz)
      if held != null then
        out.write(held, 0, held.length)
        held = null
      var going = true
      var failed = false
      while going do
        if pos >= len then
          val st = fill(0)
          if st == 0 then going = false
          else if st < 0 then
            failed = true
            going = false
        if going && pos < len then
          out.write(buf, pos, len - pos)
          pos = len
      if failed then null else new String(out.toByteArray, StandardCharsets.UTF_8)

  /** null at end of stream or when the timeout expired, as the JDK's readers map both. */
  private def readOneLine(r: LineIn, fn: String, timeoutMs: Int): Value =
    r.read(timeoutMs) match
      case 1  => VStr(r.line)
      case 0  => VNull
      case -1 => VNull
      case _  => Err.eval(s"$fn: ${r.why}")

  // -------------------------------------------------------------------------
  // A BufferedWriter over a descriptor
  // -------------------------------------------------------------------------

  private final class LineOut(var fd: Int):
    var buf: Array[Byte] = null
    var len = 0

    def flush(): Boolean =
      if len == 0 then return true
      val n = len
      // Emptied before the write, so a failure cannot be retried: an error only
      // arrives after a partial write, and sending the buffer again would repeat
      // whatever part of it already reached the other end.
      len = 0
      writeAll(fd, buf, 0, n)

    def write(bytes: Array[Byte]): Boolean =
      if buf == null then buf = new Array[Byte](IoBufSz)
      var off = 0
      var n = bytes.length
      while n > 0 do
        if len == buf.length && !flush() then return false
        val take = math.min(n, buf.length - len)
        System.arraycopy(bytes, off, buf, len, take)
        len += take
        off += take
        n -= take
      true

  // -------------------------------------------------------------------------
  // TLS, on an OpenSSL or LibreSSL found at run time
  // -------------------------------------------------------------------------

  /**
   * The C runtime's TLS shim, symbol for symbol: the same dlopen candidates in the
   * same order, so on one machine both engines negotiate with the same library; the
   * same non-blocking WANT_READ/WANT_WRITE loops driven through poll; the same
   * error texts. Nothing links against libssl — a machine without one only fails
   * when a program actually wraps a connection.
   */
  private object Tls:
    import scala.scalanative.posix.dlfcn
    import java.util.concurrent.locks.ReentrantLock

    final class Session(var ssl: CVoidPtr, var ownCtx: CVoidPtr):
      /** The reason of the last failure, when errno cannot say. */
      var err: String = ""

    private final val VerifyPeer = 1
    private final val CtrlSetTlsextHostname = 55
    private final val NametypeHost = 0L
    private final val CtrlSetMinProto = 123
    private final val Tls12Version = 0x0303L
    private final val ErrWantRead = 2
    private final val ErrWantWrite = 3
    private final val ErrSyscall = 5
    private final val ErrZeroReturn = 6

    private type FInit = CFuncPtr2[ULong, Ptr[Byte], CInt]
    private type FMethod = CFuncPtr0[Ptr[Byte]]
    private type FCtxNew = CFuncPtr1[Ptr[Byte], Ptr[Byte]]
    private type FCtxVerify = CFuncPtr3[Ptr[Byte], CInt, Ptr[Byte], Unit]
    private type FCtxPaths = CFuncPtr1[Ptr[Byte], CInt]
    private type FCtxLoad = CFuncPtr3[Ptr[Byte], CString, CString, CInt]
    private type FCtxCtrl = CFuncPtr4[Ptr[Byte], CInt, Long, Ptr[Byte], Long]
    private type FCtxMinProto = CFuncPtr2[Ptr[Byte], CInt, CInt]
    private type FCtxFree = CFuncPtr1[Ptr[Byte], Unit]
    private type FSslNew = CFuncPtr1[Ptr[Byte], Ptr[Byte]]
    private type FSetFd = CFuncPtr2[Ptr[Byte], CInt, CInt]
    private type FSslCtrl = CFuncPtr4[Ptr[Byte], CInt, Long, Ptr[Byte], Long]
    private type FSet1Host = CFuncPtr2[Ptr[Byte], CString, CInt]
    private type FGet0Param = CFuncPtr1[Ptr[Byte], Ptr[Byte]]
    private type FParamHost = CFuncPtr3[Ptr[Byte], CString, USize, CInt]
    private type FParamIp = CFuncPtr2[Ptr[Byte], CString, CInt]
    private type FConnect = CFuncPtr1[Ptr[Byte], CInt]
    private type FRead = CFuncPtr3[Ptr[Byte], Ptr[Byte], CInt, CInt]
    private type FWrite = CFuncPtr3[Ptr[Byte], Ptr[Byte], CInt, CInt]
    private type FPending = CFuncPtr1[Ptr[Byte], CInt]
    private type FShutdown = CFuncPtr1[Ptr[Byte], CInt]
    private type FFree = CFuncPtr1[Ptr[Byte], Unit]
    private type FGetError = CFuncPtr2[Ptr[Byte], CInt, CInt]
    private type FVerifyResult = CFuncPtr1[Ptr[Byte], Long]
    private type FVerifyString = CFuncPtr1[Long, CString]
    private type FErrGet = CFuncPtr0[ULong]
    private type FErrReason = CFuncPtr1[ULong, CString]
    private type FErrClear = CFuncPtr0[Unit]

    private var lib: CVoidPtr = null
    private var sharedCtx: Ptr[Byte] = null
    private val lock = new ReentrantLock()

    private var pInit: FInit = null
    private var pCtxNew: FCtxNew = null
    private var pClientMethod: FMethod = null
    private var pCtxSetVerify: FCtxVerify = null
    private var pCtxDefaultPaths: FCtxPaths = null
    private var pCtxLoadVerify: FCtxLoad = null
    private var pCtxCtrl: FCtxCtrl = null
    private var pCtxSetMinProto: FCtxMinProto = null
    private var pCtxFree: FCtxFree = null
    private var pSslNew: FSslNew = null
    private var pSslSetFd: FSetFd = null
    private var pSslCtrl: FSslCtrl = null
    private var pSslSet1Host: FSet1Host = null
    private var pSslGet0Param: FGet0Param = null
    private var pParamSet1Host: FParamHost = null
    private var pParamSet1Ip: FParamIp = null
    private var pSslConnect: FConnect = null
    private var pSslRead: FRead = null
    private var pSslWrite: FWrite = null
    private var pSslPending: FPending = null
    private var pSslShutdown: FShutdown = null
    private var pSslFree: FFree = null
    private var pSslGetError: FGetError = null
    private var pVerifyResult: FVerifyResult = null
    private var pVerifyString: FVerifyString = null
    private var pErrGet: FErrGet = null
    private var pErrReason: FErrReason = null
    private var pErrClear: FErrClear = null

    private def sym(name: String): CVoidPtr =
      Zone.acquire { implicit z => dlfcn.dlsym(lib, toCString(name)) }

    private def missing(fn: String): Nothing =
      Err.evalHint(
        s"$fn: no TLS library was found, so this program cannot make TLS connections",
        "install OpenSSL 3: via Homebrew on macOS, the libssl3 package on Linux"
      )

    /**
     * The C runtime probes the same names in the same order. The unversioned system
     * names are deliberately absent on macOS: Apple's /usr/lib/libssl.dylib aborts
     * the process on load, while the versioned LibreSSL alongside it loads and works.
     */
    private def loadLocked(fn: String): Unit =
      if pSslConnect != null then return
      val names = Seq(
        "/opt/homebrew/opt/openssl@3/lib/libssl.3.dylib", // Homebrew, Apple Silicon
        "/usr/local/opt/openssl@3/lib/libssl.3.dylib",    // Homebrew, Intel
        "/opt/local/lib/libssl.3.dylib",                  // MacPorts
        "libssl.so.3", "libssl.so.1.1",                   // Linux
        "libssl.48.dylib", "libssl.47.dylib",             // macOS system LibreSSL
        "libssl.46.dylib", "libssl.so"
      )
      Zone.acquire { implicit z =>
        for n <- names if lib == null do
          lib = dlfcn.dlopen(toCString(n), dlfcn.RTLD_LAZY | dlfcn.RTLD_LOCAL)
      }
      if lib == null then missing(fn)

      def opt[F <: CFuncPtr](name: String)(using Tag.CFuncPtrTag[F]): F =
        val p = sym(name)
        if p == null then null.asInstanceOf[F] else CFuncPtr.fromPtr[F](p)

      pInit = opt[FInit]("OPENSSL_init_ssl")
      pCtxNew = opt[FCtxNew]("SSL_CTX_new")
      pClientMethod = opt[FMethod]("TLS_client_method")
      pCtxSetVerify = opt[FCtxVerify]("SSL_CTX_set_verify")
      pCtxDefaultPaths = opt[FCtxPaths]("SSL_CTX_set_default_verify_paths")
      pCtxLoadVerify = opt[FCtxLoad]("SSL_CTX_load_verify_locations")
      pCtxCtrl = opt[FCtxCtrl]("SSL_CTX_ctrl")
      pCtxSetMinProto = opt[FCtxMinProto]("SSL_CTX_set_min_proto_version")
      pCtxFree = opt[FCtxFree]("SSL_CTX_free")
      pSslNew = opt[FSslNew]("SSL_new")
      pSslSetFd = opt[FSetFd]("SSL_set_fd")
      pSslCtrl = opt[FSslCtrl]("SSL_ctrl")
      pSslSet1Host = opt[FSet1Host]("SSL_set1_host")
      pSslGet0Param = opt[FGet0Param]("SSL_get0_param")
      pParamSet1Host = opt[FParamHost]("X509_VERIFY_PARAM_set1_host")
      pParamSet1Ip = opt[FParamIp]("X509_VERIFY_PARAM_set1_ip_asc")
      pSslConnect = opt[FConnect]("SSL_connect")
      pSslRead = opt[FRead]("SSL_read")
      pSslWrite = opt[FWrite]("SSL_write")
      pSslPending = opt[FPending]("SSL_pending")
      pSslShutdown = opt[FShutdown]("SSL_shutdown")
      pSslFree = opt[FFree]("SSL_free")
      pSslGetError = opt[FGetError]("SSL_get_error")
      pVerifyResult = opt[FVerifyResult]("SSL_get_verify_result")
      pVerifyString = opt[FVerifyString]("X509_verify_cert_error_string")
      pErrGet = opt[FErrGet]("ERR_get_error")
      pErrReason = opt[FErrReason]("ERR_reason_error_string")
      pErrClear = opt[FErrClear]("ERR_clear_error")
      if pCtxNew == null || pClientMethod == null || pCtxSetVerify == null ||
        pCtxDefaultPaths == null || pSslNew == null || pSslSetFd == null ||
        pSslCtrl == null || pSslConnect == null || pSslRead == null ||
        pSslWrite == null || pSslPending == null || pSslFree == null ||
        pSslGetError == null
      then
        lib = null
        pSslConnect = null
        missing(fn)
      if pInit != null then pInit(0.toULong, null)

    /** Fetches the library's reason for the last failure. */
    private def errorText(fallback: String): String =
      val e = if pErrGet != null then pErrGet() else 0.toULong
      if e.toLong != 0 && pErrReason != null then
        val r = pErrReason(e)
        if r == null then fallback else fromCString(r)
      else fallback

    private def clearErrors(): Unit = if pErrClear != null then pErrClear()

    /** A verifying client context; ca overrides the trust store. Called with the
      * lock held. Raises on failure. */
    private def ctxNewLocked(fn: String, ca: String): Ptr[Byte] =
      clearErrors()
      val ctx = pCtxNew(pClientMethod())
      if ctx == null then Err.eval(s"$fn: could not create a TLS context")
      pCtxSetVerify(ctx, VerifyPeer, null)
      // SSL_CTX_set_min_proto_version is a real function in LibreSSL and a macro
      // over SSL_CTX_ctrl in OpenSSL, so try the symbol first and fall back.
      if pCtxSetMinProto != null then pCtxSetMinProto(ctx, Tls12Version.toInt)
      else if pCtxCtrl != null then pCtxCtrl(ctx, CtrlSetMinProto, Tls12Version, null)
      val ok =
        if ca != null then
          if pCtxLoadVerify == null then
            pCtxFree(ctx)
            Err.eval(s"$fn: this TLS library cannot load a CA file")
          Zone.acquire { implicit z => pCtxLoadVerify(ctx, toCString(ca), null) }
        else pCtxDefaultPaths(ctx)
      if ok != 1 then
        pCtxFree(ctx)
        if ca != null then Err.eval(s"$fn: cannot read the CA file '$ca'")
        Err.eval(s"$fn: could not load the system certificate store")
      ctx

    /** The context for a wrap: the shared verifying one, or a fresh one for a CA. */
    def contextFor(fn: String, ca: String): Ptr[Byte] =
      lock.lock()
      try
        loadLocked(fn)
        if ca != null then ctxNewLocked(fn, ca)
        else
          if sharedCtx == null then sharedCtx = ctxNewLocked(fn, null)
          sharedCtx
      finally lock.unlock()

    def newSession(fn: String, ctx: Ptr[Byte], own: Boolean): Session =
      val ssl = pSslNew(ctx)
      if ssl == null then
        if own then pCtxFree(ctx)
        Err.eval(s"$fn: could not create a TLS connection")
      new Session(ssl, if own then ctx else null)

    def setFd(s: Session, fd: Int): Unit = pSslSetFd(s.ssl.asInstanceOf[Ptr[Byte]], fd)

    /** SNI and hostname verification; false when the library cannot verify names. */
    def nameSession(s: Session, host: String, isIp: Boolean): Boolean =
      val ssl = s.ssl.asInstanceOf[Ptr[Byte]]
      Zone.acquire { implicit z =>
        val chost = toCString(host)
        if isIp then
          pSslGet0Param != null && pParamSet1Ip != null &&
          pParamSet1Ip(pSslGet0Param(ssl), chost) == 1
        else
          pSslCtrl(ssl, CtrlSetTlsextHostname, NametypeHost, chost.asInstanceOf[Ptr[Byte]])
          if pSslSet1Host != null then pSslSet1Host(ssl, chost) == 1
          else
            pSslGet0Param != null && pParamSet1Host != null &&
            pParamSet1Host(pSslGet0Param(ssl), chost, 0.toUSize) == 1
      }

    /** Waits for what the last SSL call asked for; 1 ready, 0 deadline hit, -1 failed.
      * A deadline of 0 waits forever. */
    private def waitFor(fd: Int, wantWrite: Boolean, deadline: Long): Int =
      var ms = 0
      if deadline > 0 then
        ms = millisLeft(deadline)
        if ms <= 0 then return 0
      waitReady(fd, if wantWrite then spoll.POLLOUT else spoll.POLLIN, ms)

    /** 0 connected, -1 failed with s.err set, -2 the deadline expired. */
    def handshake(s: Session, fd: Int, deadline: Long): Int =
      val ssl = s.ssl.asInstanceOf[Ptr[Byte]]
      while true do
        clearErrors()
        cerr.errno = 0
        val rc = pSslConnect(ssl)
        if rc == 1 then return 0
        val err = pSslGetError(ssl, rc)
        if err == ErrWantRead || err == ErrWantWrite then
          val ready = waitFor(fd, err == ErrWantWrite, deadline)
          if ready == 0 then return -2
          if ready < 0 then
            s.err = strerrorText(cerr.errno)
            return -1
        else if err == ErrSyscall && cerr.errno == perr.EINTR then ()
        else
          val vr = if pVerifyResult != null then pVerifyResult(ssl) else 0L
          if vr != 0 && pVerifyString != null then
            // X509_V_OK is 0; anything else is why the certificate was rejected.
            s.err = s"certificate verify failed: ${fromCString(pVerifyString(vr))}"
          else if err == ErrSyscall then
            s.err =
              if cerr.errno != 0 then strerrorText(cerr.errno)
              else "the connection was closed during the handshake"
          else s.err = errorText("the TLS handshake failed")
          return -1
      -1 // unreachable

    /** >0 bytes read, 0 end of stream, -1 timed out, -2 failed with s.err set. */
    def read(s: Session, fd: Int, buf: Array[Byte], cap: Int, timeoutMs: Int): Int =
      val ssl = s.ssl.asInstanceOf[Ptr[Byte]]
      val deadline = if timeoutMs > 0 then System.currentTimeMillis() + timeoutMs else 0L
      while true do
        clearErrors()
        cerr.errno = 0
        val n = pSslRead(ssl, buf.at(0).asInstanceOf[Ptr[Byte]], cap)
        if n > 0 then return n
        val err = pSslGetError(ssl, n)
        if err == ErrZeroReturn then return 0
        if err == ErrWantRead || err == ErrWantWrite then
          val ready = waitFor(fd, err == ErrWantWrite, deadline)
          if ready == 0 then return -1
          if ready < 0 then
            s.err = strerrorText(cerr.errno)
            return -2
        else if err == ErrSyscall then
          if cerr.errno == perr.EINTR then ()
          // The peer went away without a close_notify; readers treat it as EOF,
          // which is what the JDK's SSLSocket does for a truncated stream too.
          else if cerr.errno == 0 then return 0
          else
            s.err = strerrorText(cerr.errno)
            return -2
        else
          s.err = errorText("the TLS connection failed")
          return -2
      -2 // unreachable

    /** Drains the whole slice through the session; true ok, false with s.err set. */
    def writeAll(s: Session, fd: Int, bytes: Array[Byte], off0: Int, end: Int): Boolean =
      val ssl = s.ssl.asInstanceOf[Ptr[Byte]]
      var off = off0
      while off < end do
        clearErrors()
        cerr.errno = 0
        val put = pSslWrite(ssl, bytes.at(off).asInstanceOf[Ptr[Byte]], end - off)
        if put > 0 then off += put
        else
          val err = pSslGetError(ssl, put)
          if err == ErrWantRead || err == ErrWantWrite then
            val ready = waitFor(fd, err == ErrWantWrite, 0L)
            if ready < 0 then
              s.err = strerrorText(cerr.errno)
              return false
          else if err == ErrSyscall && cerr.errno == perr.EINTR then ()
          else if err == ErrSyscall && cerr.errno != 0 then
            s.err = strerrorText(cerr.errno)
            return false
          else
            s.err = errorText("the TLS connection failed")
            return false
      true

    def pending(s: Session): Boolean =
      s.ssl != null && pSslPending(s.ssl.asInstanceOf[Ptr[Byte]]) > 0

    /** Sends a quiet close_notify and releases the session; never blocks or raises. */
    def freeSession(s: Session): Unit =
      if s == null then return
      if s.ssl != null then
        if pSslShutdown != null then pSslShutdown(s.ssl.asInstanceOf[Ptr[Byte]]) // best effort
        pSslFree(s.ssl.asInstanceOf[Ptr[Byte]])
        s.ssl = null
      if s.ownCtx != null then
        pCtxFree(s.ownCtx.asInstanceOf[Ptr[Byte]])
        s.ownCtx = null

  // -------------------------------------------------------------------------
  // Subprocesses connected by pipes
  // -------------------------------------------------------------------------

  /** A live child process: the C runtime's Child, field for field. */
  private final class Child(val pid: Int, var inFd: Int, val out: LineIn, val err: LineIn):
    var reaped = false
    var code = 0

    /** Collects the child's status once, since only the first waitpid sees it. */
    def reap(block: Boolean): Boolean =
      if reaped then return true
      val status = stackalloc[CInt]()
      var r = 0
      while
        r = pwait.waitpid(pid, status, if block then 0 else pwait.WNOHANG)
        r < 0 && cerr.errno == perr.EINTR
      do ()
      if r == 0 then return false
      reaped = true
      code =
        if r < 0 then 0 // ECHILD: gone, and nobody can say with what status
        else if pwait.WIFEXITED(!status) then pwait.WEXITSTATUS(!status)
        else if pwait.WIFSIGNALED(!status) then 128 + pwait.WTERMSIG(!status) // Process.exitValue's report
        else 0
      true

  private def childOf(a: Array[Value], i: Int, fn: String): Child =
    // A process handle has no close, so its payload is live for as long as it is.
    handleOf[Child](a, i, "process", fn)

  private def subprocesses(): Unit =
    define("processStart", 1, 2, "ipc", "processStart(command, useShell?)",
      "Starts a child process with pipes on its streams; command is a string or an argv array.") { a =>
      ignoreSigpipe()
      val (argvSeq, shown) = a(0) match
        case VStr(cmd) =>
          val useShell = if a.length > 1 then a(1).truthy else true
          val list =
            if !useShell then Proc.splitCommand(cmd).asScala.toSeq
            else Seq("/bin/sh", "-c", cmd)
          (list, cmd)
        case arr: VArr =>
          if arr.items.isEmpty then Err.eval("processStart: the argv array is empty")
          val list = arr.items.toSeq.map(_.display)
          (list, list.mkString(" "))
        case v => Err.eval(s"processStart: expected a command string or argv array, got ${v.typeName}")

      // Everything the child touches is prepared before the fork: between fork and
      // exec it may only call straight into libc — never allocate on the Scala
      // heap, whose locks another thread may hold at the moment of the fork. The
      // same async-signal discipline the C runtime observes.
      val nArgs = argvSeq.length
      val argv = cstdlib.malloc(((nArgs + 1) * sizeof[CString].toInt).toUSize).asInstanceOf[Ptr[CString]]
      var k = 0
      for s <- argvSeq do
        argv(k) = mallocCString(s)
        k += 1
      argv(nArgs) = null

      def freeArgv(): Unit =
        var i = 0
        while i < nArgs do
          cstdlib.free(argv(i).asInstanceOf[CVoidPtr])
          i += 1
        cstdlib.free(argv.asInstanceOf[CVoidPtr])

      val inp = stackalloc[CInt](2)
      val outp = stackalloc[CInt](2)
      val errp = stackalloc[CInt](2)
      val failp = stackalloc[CInt](2)
      inp(0) = -1; inp(1) = -1; outp(0) = -1; outp(1) = -1
      errp(0) = -1; errp(1) = -1; failp(0) = -1; failp(1) = -1
      if unistd.pipe(inp) != 0 || unistd.pipe(outp) != 0 || unistd.pipe(errp) != 0 ||
        unistd.pipe(failp) != 0
      then
        val saved = cerr.errno
        closeFd(inp(0)); closeFd(inp(1)); closeFd(outp(0)); closeFd(outp(1))
        closeFd(errp(0)); closeFd(errp(1)); closeFd(failp(0)); closeFd(failp(1))
        freeArgv()
        Err.eval(s"processStart: ${strerrorText(saved)}")
      // The child's end closes on a successful exec, which is how the parent learns
      // that exec worked: a short read means it did.
      fcntl.fcntl(failp(1), fcntl.F_SETFD, fcntl.FD_CLOEXEC)

      val pid = unistd.fork()
      if pid == 0 then
        unistd.dup2(inp(0), 0)
        unistd.dup2(outp(1), 1)
        unistd.dup2(errp(1), 2)
        unistd.close(inp(0)); unistd.close(inp(1))
        unistd.close(outp(0)); unistd.close(outp(1))
        unistd.close(errp(0)); unistd.close(errp(1))
        unistd.close(failp(0))
        // The child gets a clean slate, as it does under ProcessBuilder: a pipeline
        // that expects to die of SIGPIPE must be able to.
        csignal.signal(psignal.SIGPIPE, csignal.SIG_DFL)
        if nArgs > 0 then unistd.execvp(argv(0), argv)
        else cerr.errno = perr.ENOENT // the command was blank, or nothing but quotes
        val e = stackalloc[CInt]()
        !e = cerr.errno
        unistd.write(failp(1), e.asInstanceOf[CVoidPtr], sizeof[CInt])
        unistd._exit(127)

      closeFd(inp(0)); closeFd(outp(1)); closeFd(errp(1)); closeFd(failp(1))
      if pid < 0 then
        val saved = cerr.errno
        closeFd(inp(1)); closeFd(outp(0)); closeFd(errp(0)); closeFd(failp(0))
        freeArgv()
        Err.eval(s"processStart: ${strerrorText(saved)}")

      val childErr = stackalloc[CInt]()
      !childErr = 0
      var got = 0
      while
        got = unistd.read(failp(0), childErr.asInstanceOf[CVoidPtr], sizeof[CInt])
        got < 0 && cerr.errno == perr.EINTR
      do ()
      closeFd(failp(0))
      if got == 4 then
        closeFd(inp(1)); closeFd(outp(0)); closeFd(errp(0))
        val status = stackalloc[CInt]()
        while pwait.waitpid(pid, status, 0) < 0 && cerr.errno == perr.EINTR do ()
        val e = !childErr
        val head = if nArgs > 0 then argvSeq.head else ""
        freeArgv()
        Err.eval(s"processStart: Cannot run program \"$head\": error=$e, ${strerrorText(e)}")
      freeArgv()

      val c = new Child(pid, inp(1), new LineIn(outp(0)), new LineIn(errp(0)))
      new VHandle("process", c, shown.take(40))
    }

    // Both writers flush every time, so there is nothing to buffer on this side.
    def childWrite(a: Array[Value], fn: String, newline: Boolean): Value =
      val c = childOf(a, 0, fn)
      if c.inFd < 0 then Err.eval(s"$fn: Stream closed")
      val text = a(1).display.getBytes(StandardCharsets.UTF_8)
      var ok = writeAll(c.inFd, text)
      var saved = cerr.errno
      if ok && newline then
        ok = writeAll(c.inFd, Newline)
        saved = cerr.errno
      if !ok then Err.eval(s"$fn: ${strerrorText(saved)}")
      VNull

    define("processWrite", 2, 2, "ipc", "processWrite(p, text)",
      "Writes to the child's standard input and flushes.") { a =>
      childWrite(a, "processWrite", newline = false)
    }

    define("processWriteLine", 2, 2, "ipc", "processWriteLine(p, text)",
      "Writes a line to the child's standard input and flushes.") { a =>
      childWrite(a, "processWriteLine", newline = true)
    }

    define("processCloseInput", 1, 1, "ipc", "processCloseInput(p)",
      "Closes the child's standard input, so it sees end of input.") { a =>
      val c = childOf(a, 0, "processCloseInput")
      // BufferedWriter.close() on an already closed writer does nothing.
      if c.inFd >= 0 then
        closeFd(c.inFd)
        c.inFd = -1
      VNull
    }

    define("processReadLine", 1, 1, "ipc", "processReadLine(p)",
      "Reads one line of the child's output; null at end of stream. Blocks.") { a =>
      readOneLine(childOf(a, 0, "processReadLine").out, "processReadLine", 0)
    }

    define("processReadErrLine", 1, 1, "ipc", "processReadErrLine(p)",
      "Reads one line of the child's error stream; null at end of stream.") { a =>
      readOneLine(childOf(a, 0, "processReadErrLine").err, "processReadErrLine", 0)
    }

    define("processReadAll", 1, 1, "ipc", "processReadAll(p)",
      "Reads the child's remaining output. Blocks until the stream ends.") { a =>
      val data = childOf(a, 0, "processReadAll").out.slurp()
      if data == null then Err.eval(s"processReadAll: ${strerrorText(cerr.errno)}")
      VStr(data)
    }

    define("processWait", 1, 2, "ipc", "processWait(p, timeoutMs?)",
      "Waits for the child and returns its exit code; null if the timeout expires.") { a =>
      val c = childOf(a, 0, "processWait")
      if a.length > 1 && (a(1) ne VNull) then
        val ms = argInt(a, 1, "processWait")
        val deadline = System.currentTimeMillis() + ms
        var step = 1L
        var out: Value = null
        // No SIGCHLD wait that is both portable and interruptible, so poll — the
        // interval opens out quickly so a long wait costs almost nothing.
        while out == null do
          if c.reap(false) then out = VInt.of(c.code.toLong)
          else
            val left = deadline - System.currentTimeMillis()
            if left <= 0 then out = VNull
            else
              Thread.sleep(math.min(step, left))
              if step < 10 then step *= 2
        out
      else
        c.reap(true)
        VInt.of(c.code.toLong)
    }

    define("processAlive", 1, 1, "ipc", "processAlive(p)", "True while the child is running.") { a =>
      VBool.of(!childOf(a, 0, "processAlive").reap(false))
    }

    define("processKill", 1, 2, "ipc", "processKill(p, force?)",
      "Terminates the child; pass true to kill it outright.") { a =>
      val c = childOf(a, 0, "processKill")
      val force = a.length > 1 && a(1).truthy
      // Only signal a child still running: a reaped pid can already belong to
      // somebody else, which is the same reason the JDK checks hasExited first.
      if !c.reap(false) then
        psignal.kill(c.pid, if force then psignal.SIGKILL else psignal.SIGTERM)
      VNull
    }

  // -------------------------------------------------------------------------
  // TCP sockets
  // -------------------------------------------------------------------------

  private final class Server(var fd: Int, val port: Int)
  private final class Conn(var fd: Int, val in: LineIn, val peer: String):
    /** null until tlsWrap upgrades the connection. */
    var tls: Tls.Session = null

  private def serverOf(a: Array[Value], i: Int, fn: String): Server =
    val s = handleOf[Server](a, i, "server", fn)
    if s.fd < 0 then Err.eval(s"$fn: Socket closed")
    s

  private def connOf(a: Array[Value], i: Int, fn: String): Conn =
    val c = handleOf[Conn](a, i, "connection", fn)
    if c.fd < 0 then Err.eval(s"$fn: Socket closed")
    c

  /*
   * InetAddress.getHostAddress. Java hands an IPv4-mapped address back as an
   * Inet4Address, and prints a real IPv6 address as eight uncompressed groups —
   * "0:0:0:0:0:0:0:1", never inet_ntop's "::1".
   */
  private def formatHost(sa: Ptr[psock.sockaddr]): String =
    val fam = sa.sa_family.toInt
    if fam == psock.AF_INET then
      val v4 = sa.asInstanceOf[Ptr[in.sockaddr_in]]
      val buf = stackalloc[CChar](64)
      val s = inet.inet_ntop(psock.AF_INET, v4.at3.asInstanceOf[CVoidPtr], buf, 64.toUInt)
      if s == null then "0.0.0.0" else fromCString(buf)
    else if fam == psock.AF_INET6 then
      val v6 = sa.asInstanceOf[Ptr[in.sockaddr_in6]]
      val b = v6.at4.asInstanceOf[Ptr[Byte]]
      if in.IN6_IS_ADDR_V4MAPPED(v6.at4) != 0 then
        s"${b(12) & 0xff}.${b(13) & 0xff}.${b(14) & 0xff}.${b(15) & 0xff}"
      else
        val sb = new StringBuilder
        var i = 0
        while i < 8 do
          val group = ((b(i * 2) & 0xff) << 8) | (b(i * 2 + 1) & 0xff)
          if i > 0 then sb.append(':')
          sb.append(Integer.toHexString(group))
          i += 1
        val scope = v6.sin6_scope_id.toLong & 0xffffffffL
        if scope != 0 then sb.append('%').append(scope)
        sb.toString
    else "0.0.0.0"

  private def portOf(sa: Ptr[psock.sockaddr]): Int =
    val fam = sa.sa_family.toInt
    if fam == psock.AF_INET then
      inet.ntohs(sa.asInstanceOf[Ptr[in.sockaddr_in]].sin_port).toInt
    else if fam == psock.AF_INET6 then
      inet.ntohs(sa.asInstanceOf[Ptr[in.sockaddr_in6]].sin6_port).toInt
    else 0

  private def formatPeer(sa: Ptr[psock.sockaddr]): String = s"${formatHost(sa)}:${portOf(sa)}"

  /** Non-blocking connect with a deadline, which is how Socket.connect(addr, ms)
    * behaves. 0 connected, -1 failed with errno set, -2 the timeout expired. */
  private def connectDeadline(fd: Int, sa: Ptr[psock.sockaddr], salen: psock.socklen_t, ms: Int): Int =
    val flags = fcntl.fcntl(fd, fcntl.F_GETFL, 0)
    if flags < 0 || fcntl.fcntl(fd, fcntl.F_SETFL, flags | fcntl.O_NONBLOCK) != 0 then return -1
    var rc = 0
    while
      rc = psock.connect(fd, sa, salen)
      rc != 0 && cerr.errno == perr.EINTR
    do ()
    if rc != 0 && cerr.errno != perr.EINPROGRESS then return -1
    if rc != 0 then
      val ready = waitReady(fd, spoll.POLLOUT, if ms > 0 then ms else 0)
      if ready == 0 then return -2
      if ready < 0 then return -1
      val err = stackalloc[CInt]()
      val elen = stackalloc[psock.socklen_t]()
      !elen = 4.toUInt
      if psock.getsockopt(fd, psock.SOL_SOCKET, psock.SO_ERROR, err.asInstanceOf[CVoidPtr], elen) != 0
      then return -1
      if !err != 0 then
        cerr.errno = !err
        return -1
    fcntl.fcntl(fd, fcntl.F_SETFL, flags)
    0

  private def sockets(): Unit =
    define("serverSocket", 1, 2, "ipc", "serverSocket(port, backlog?)",
      "Listens on a TCP port; port 0 picks a free one, which serverPort() then reports.") { a =>
      ignoreSigpipe()
      val port = argIdx(a, 0, "serverSocket")
      val backlog0 = if a.length > 1 then argIdx(a, 1, "serverSocket") else 50
      if port < 0 || port > 65535 then Err.eval(s"Port value out of range: $port")
      val backlog = if backlog0 < 1 then 50 else backlog0 // ServerSocket.bind's own floor

      // Java listens on a dual-stack socket where the machine has IPv6, so that one
      // server answers both families; a v4-only host falls back.
      var fd = psock.socket(psock.AF_INET6, psock.SOCK_STREAM, 0)
      var family = psock.AF_INET6
      if fd < 0 then
        fd = psock.socket(psock.AF_INET, psock.SOCK_STREAM, 0)
        family = psock.AF_INET
      if fd < 0 then Err.eval(s"serverSocket: ${strerrorText(cerr.errno)}")

      val on = stackalloc[CInt]()
      !on = 1
      psock.setsockopt(fd, psock.SOL_SOCKET, psock.SO_REUSEADDR, on.asInstanceOf[CVoidPtr], 4.toUInt)
      if family == psock.AF_INET6 then
        val off = stackalloc[CInt]()
        !off = 0
        psock.setsockopt(fd, in.IPPROTO_IPV6, in.IPV6_V6ONLY, off.asInstanceOf[CVoidPtr], 4.toUInt)

      var rc = 0
      if family == psock.AF_INET6 then
        val addr = stackalloc[in.sockaddr_in6]()
        cstr.memset(addr.asInstanceOf[CVoidPtr], 0, sizeof[in.sockaddr_in6])
        addr.sin6_family = psock.AF_INET6.toUShort
        addr.sin6_port = inet.htons(port.toUShort)
        rc = psock.bind(fd, addr.asInstanceOf[Ptr[psock.sockaddr]], sizeof[in.sockaddr_in6].toUInt)
      else
        val addr = stackalloc[in.sockaddr_in]()
        cstr.memset(addr.asInstanceOf[CVoidPtr], 0, sizeof[in.sockaddr_in])
        addr.sin_family = psock.AF_INET.toUShort
        addr.sin_port = inet.htons(port.toUShort)
        rc = psock.bind(fd, addr.asInstanceOf[Ptr[psock.sockaddr]], sizeof[in.sockaddr_in].toUInt)
      if rc != 0 || psock.listen(fd, backlog) != 0 then
        val saved = cerr.errno
        closeFd(fd)
        Err.eval(s"serverSocket: ${strerrorText(saved)}")

      val bound = stackalloc[psock.sockaddr_storage]()
      val blen = stackalloc[psock.socklen_t]()
      !blen = sizeof[psock.sockaddr_storage].toUInt
      val boundPort =
        if psock.getsockname(fd, bound.asInstanceOf[Ptr[psock.sockaddr]], blen) == 0 then
          portOf(bound.asInstanceOf[Ptr[psock.sockaddr]])
        else port
      new VHandle("server", new Server(fd, boundPort), s"port=$boundPort")
    }

    define("serverPort", 1, 1, "ipc", "serverPort(srv)", "The port the server is bound to.") { a =>
      VInt.of(serverOf(a, 0, "serverPort").port.toLong)
    }

    define("accept", 1, 2, "ipc", "accept(srv, timeoutMs?)",
      "Waits for a client; null if the timeout expires.") { a =>
      val s = serverOf(a, 0, "accept")
      val ms = if a.length > 1 && (a(1) ne VNull) then argIdx(a, 1, "accept") else 0
      val readyRc = if ms > 0 then waitReady(s.fd, spoll.POLLIN, ms) else 1
      if readyRc == 0 then VNull // SocketTimeoutException, which accept() maps to null
      else if readyRc < 0 then Err.eval(s"accept: ${strerrorText(cerr.errno)}")
      else
        val from = stackalloc[psock.sockaddr_storage]()
        val flen = stackalloc[psock.socklen_t]()
        var fd = -1
        while
          !flen = sizeof[psock.sockaddr_storage].toUInt
          fd = psock.accept(s.fd, from.asInstanceOf[Ptr[psock.sockaddr]], flen)
          fd < 0 && cerr.errno == perr.EINTR
        do ()
        if fd < 0 then Err.eval(s"accept: ${strerrorText(cerr.errno)}")
        val peer = formatPeer(from.asInstanceOf[Ptr[psock.sockaddr]])
        new VHandle("connection", new Conn(fd, new LineIn(fd), peer), peer)
    }

    define("connect", 2, 3, "ipc", "connect(host, port, timeoutMs?)",
      "Opens a TCP connection to host:port.") { a =>
      ignoreSigpipe()
      val host = argStr(a, 0, "connect")
      val port = argIdx(a, 1, "connect")
      val ms = if a.length > 2 then argIdx(a, 2, "connect") else 10000
      if port < 0 || port > 65535 then Err.eval(s"port out of range:$port") // InetSocketAddress's own wording

      val (fd, timedOut, saved) = Zone.acquire { implicit z =>
        val hints = stackalloc[netdb.addrinfo]()
        cstr.memset(hints.asInstanceOf[CVoidPtr], 0, sizeof[netdb.addrinfo])
        hints.ai_family = psock.AF_UNSPEC
        hints.ai_socktype = psock.SOCK_STREAM
        val listPtr = stackalloc[Ptr[netdb.addrinfo]]()
        !listPtr = null
        val gai = netdb.getaddrinfo(toCString(host), toCString(port.toString), hints, listPtr)
        if gai != 0 || (!listPtr) == null then
          // UnknownHostException carries nothing but the name it could not resolve.
          Err.eval(s"connect: $host")
        var out = -1
        var timed = false
        var err = perr.ECONNREFUSED
        var ai = !listPtr
        while ai != null && out < 0 && !timed do
          val cand = psock.socket(ai.ai_family, ai.ai_socktype, ai.ai_protocol)
          if cand < 0 then err = cerr.errno
          else
            val rc = connectDeadline(cand, ai.ai_addr, ai.ai_addrlen, ms)
            if rc == 0 then out = cand
            else
              // The deadline was for the whole call, not per address.
              if rc == -2 then timed = true else err = cerr.errno
              closeFd(cand)
          ai = ai.ai_next
        netdb.freeaddrinfo(!listPtr)
        (out, timed, err)
      }
      if fd < 0 then
        if timedOut then Err.eval("connect: connect timed out")
        Err.eval(s"connect: ${strerrorText(saved)}")

      val to = stackalloc[psock.sockaddr_storage]()
      val tlen = stackalloc[psock.socklen_t]()
      !tlen = sizeof[psock.sockaddr_storage].toUInt
      val peer =
        if psock.getpeername(fd, to.asInstanceOf[Ptr[psock.sockaddr]], tlen) == 0 then
          formatPeer(to.asInstanceOf[Ptr[psock.sockaddr]])
        else s"$host:$port"
      new VHandle("connection", new Conn(fd, new LineIn(fd), peer), s"$host:$port")
    }

    def connSend(c: Conn, bytes: Array[Byte]): Boolean =
      if c.tls != null then Tls.writeAll(c.tls, c.fd, bytes, 0, bytes.length)
      else writeAll(c.fd, bytes)

    def connWhy(c: Conn, saved: Int): String =
      if c.tls != null && c.tls.err.nonEmpty then c.tls.err else strerrorText(saved)

    def connWrite(a: Array[Value], fn: String, newline: Boolean): Value =
      val c = connOf(a, 0, fn)
      val text = a(1).display.getBytes(StandardCharsets.UTF_8)
      var ok = connSend(c, text)
      var saved = cerr.errno
      if ok && newline then
        ok = connSend(c, Newline)
        saved = cerr.errno
      if !ok then Err.eval(s"$fn: ${connWhy(c, saved)}")
      VNull

    define("socketWrite", 2, 2, "ipc", "socketWrite(c, text)", "Writes to the connection and flushes.") { a =>
      connWrite(a, "socketWrite", newline = false)
    }

    define("socketWriteLine", 2, 2, "ipc", "socketWriteLine(c, text)",
      "Writes a line to the connection and flushes.") { a =>
      connWrite(a, "socketWriteLine", newline = true)
    }

    define("socketReadLine", 1, 2, "ipc", "socketReadLine(c, timeoutMs?)",
      "Reads one line; null at end of stream or when the timeout expires.") { a =>
      val c = connOf(a, 0, "socketReadLine")
      val ms = if a.length > 1 && (a(1) ne VNull) then argIdx(a, 1, "socketReadLine") else 0
      readOneLine(c.in, "socketReadLine", ms)
    }

    define("socketReadBytes", 2, 3, "ipc", "socketReadBytes(c, maxBytes, timeoutMs?)",
      "Reads up to maxBytes raw bytes as an array of ints; [] at end of stream, null when the timeout expires.") { a =>
      val c = connOf(a, 0, "socketReadBytes")
      val want0 = argInt(a, 1, "socketReadBytes")
      if want0 <= 0 then Err.eval("socketReadBytes: maxBytes must be positive")
      val want = math.min(want0, 1L << 26).toInt
      val ms = if a.length > 2 && (a(2) ne VNull) then argIdx(a, 2, "socketReadBytes") else 0

      // The exact drain the C runtime's byte read performs: a held partial line
      // first, then what the line reader already buffered — swallowing the '\n' a
      // line that ended at '\r' still owes — and only then the descriptor.
      val r = c.in
      val out = new java.io.ByteArrayOutputStream(math.min(want, IoBufSz))
      if r.held != null then
        val take = math.min(r.held.length, want)
        out.write(r.held, 0, take)
        r.held = if take == r.held.length then null else java.util.Arrays.copyOfRange(r.held, take, r.held.length)
      var result: Value = null
      while result == null do
        if r.skipLf && r.pos < r.len then
          if r.buf(r.pos) == '\n'.toByte then r.pos += 1
          r.skipLf = false
        if out.size < want && r.pos < r.len then
          val take = math.min(r.len - r.pos, want - out.size)
          out.write(r.buf, r.pos, take)
          r.pos += take
        if out.size > 0 then result = bytesValue(out.toByteArray)
        else if r.eof then result = new VArr(new scala.collection.mutable.ArrayBuffer[Value])
        else
          r.fill(ms) match
            case 0  => result = new VArr(new scala.collection.mutable.ArrayBuffer[Value])
            case -1 => result = VNull
            case -2 => Err.eval(s"socketReadBytes: ${r.why}")
            case _  => () // loop: the fill may have produced only the swallowed '\n'
      result
    }

    define("socketWriteBytes", 2, 2, "ipc", "socketWriteBytes(c, bytes)",
      "Writes raw bytes (an array of ints 0..255) to the connection as they are.") { a =>
      val c = connOf(a, 0, "socketWriteBytes")
      val bytes = argBytes(a, 1, "socketWriteBytes")
      if bytes.length > 0 then
        val ok = connSend(c, bytes)
        if !ok then Err.eval(s"socketWriteBytes: ${connWhy(c, cerr.errno)}")
      VNull
    }

    define("closeSocket", 1, 1, "ipc", "closeSocket(c)", "Closes a connection.") { a =>
      val c = handleOf[Conn](a, 0, "connection", "closeSocket")
      if c.fd >= 0 then // Socket.close() twice is a no-op
        Tls.freeSession(c.tls) // the close_notify wants the descriptor alive
        c.tls = null
        c.in.tls = null
        closeFd(c.fd)
        c.fd = -1
        c.in.fd = -1
      VNull
    }

    define("closeServer", 1, 1, "ipc", "closeServer(srv)", "Stops listening.") { a =>
      val s = handleOf[Server](a, 0, "server", "closeServer")
      if s.fd >= 0 then
        closeFd(s.fd)
        s.fd = -1
      VNull
    }

    define("socketPeer", 1, 1, "ipc", "socketPeer(c)", "The remote address as 'host:port'.") { a =>
      VStr(connOf(a, 0, "socketPeer").peer)
    }

  // -------------------------------------------------------------------------
  // Byte buffers
  // -------------------------------------------------------------------------

  /**
   * A growable run of raw bytes, for framing that counts in bytes — an HTTP body,
   * a download — where decoding must happen once over the finished whole rather
   * than per chunk, or a multi-byte character split across two reads would decode
   * as two broken halves. The C runtime's twin is a realloc'd char buffer.
   */
  private final class Buf:
    var data: Array[Byte] = null
    var len = 0

    def append(bytes: Array[Byte], off: Int, n: Int): Unit =
      if n == 0 then return
      if data == null then data = new Array[Byte](math.max(4096, n))
      else if len + n > data.length then
        data = java.util.Arrays.copyOf(data, math.max(len + n, data.length * 2))
      System.arraycopy(bytes, off, data, len, n)
      len += n

  private def bufOf(a: Array[Value], i: Int, fn: String): Buf =
    // A buffer has no close, so its payload is live for as long as it is.
    handleOf[Buf](a, i, "buffer", fn)

  private def buffers(): Unit =
    define("bufNew", 0, 0, "ipc", "bufNew()",
      "Creates an empty byte buffer for byte-counted reads; see socketReadToBuf.") { _ =>
      new VHandle("buffer", new Buf, "")
    }

    define("bufSize", 1, 1, "ipc", "bufSize(b)", "The number of bytes in the buffer.") { a =>
      VInt.of(bufOf(a, 0, "bufSize").len.toLong)
    }

    define("bufString", 1, 1, "ipc", "bufString(b)",
      "Decodes the whole buffer as UTF-8, in one piece, and returns the string.") { a =>
      val b = bufOf(a, 0, "bufString")
      if b.data == null then VStr.empty
      else VStr(new String(b.data, 0, b.len, StandardCharsets.UTF_8))
    }

    define("bufClear", 1, 1, "ipc", "bufClear(b)",
      "Empties the buffer and releases its storage.") { a =>
      val b = bufOf(a, 0, "bufClear")
      b.data = null
      b.len = 0
      VNull
    }

    define("socketReadToBuf", 3, 4, "ipc", "socketReadToBuf(c, b, maxBytes, timeoutMs?)",
      "Reads up to maxBytes from the connection into the buffer: the count read, " +
        "0 at end of stream, null when the timeout expires.") { a =>
      val c = connOf(a, 0, "socketReadToBuf")
      val b = bufOf(a, 1, "socketReadToBuf")
      var want = argInt(a, 2, "socketReadToBuf")
      if want <= 0 then Err.eval("socketReadToBuf: maxBytes must be positive")
      if want > (1 << 26) then want = 1 << 26
      val ms = if a.length > 3 && (a(3) ne VNull) then argIdx(a, 3, "socketReadToBuf") else 0

      val r = c.in
      var took = 0

      // A held partial line: raw bytes a timed-out line read kept, first in line.
      if r.held != null then
        val take = math.min(r.held.length, want.toInt)
        b.append(r.held, 0, take)
        took += take
        r.held =
          if take == r.held.length then null
          else java.util.Arrays.copyOfRange(r.held, take, r.held.length)

      var result: Value = null
      while result == null do
        // The '\n' owed by a line that ended at '\r' belongs to that line, not to
        // the bytes: swallow it the moment it is visible.
        if r.skipLf && r.pos < r.len then
          if r.buf(r.pos) == '\n'.toByte then r.pos += 1
          r.skipLf = false
        if took < want && r.pos < r.len then
          val take = math.min(r.len - r.pos, want.toInt - took)
          b.append(r.buf, r.pos, take)
          r.pos += take
          took += take
        if took > 0 then result = VInt.of(took.toLong)
        else if r.eof then result = VInt.Zero
        else
          r.fill(ms) match
            case 0  => result = VInt.Zero
            case -1 => result = VNull // the timeout expired
            case -2 => Err.eval(s"socketReadToBuf: ${r.why}")
            case _  => () // loop: the fill may have produced only the swallowed '\n'
      result
    }

    define("socketWriteBuf", 2, 2, "ipc", "socketWriteBuf(c, b)",
      "Writes the buffer's bytes to the connection as they are, no decoding.") { a =>
      val c = connOf(a, 0, "socketWriteBuf")
      val b = bufOf(a, 1, "socketWriteBuf")
      if b.len > 0 then
        val ok =
          if c.tls != null then Tls.writeAll(c.tls, c.fd, b.data, 0, b.len)
          else writeAll(c.fd, b.data, 0, b.len)
        if !ok then Err.eval(s"socketWriteBuf: ${connWhy2(c, cerr.errno)}")
      VNull
    }

  /** connWhy for sections registered outside sockets(); same text. */
  private def connWhy2(c: Conn, saved: Int): String =
    if c.tls != null && c.tls.err.nonEmpty then c.tls.err else strerrorText(saved)

  // -------------------------------------------------------------------------
  // TLS
  // -------------------------------------------------------------------------

  private def tls(): Unit =
    define("tlsWrap", 2, 4, "ipc", "tlsWrap(c, host, caFile?, timeoutMs?)",
      "Upgrades a connection to TLS in place: handshake, certificate and hostname " +
        "verification against host. caFile overrides the trust store.") { a =>
      val c = connOf(a, 0, "tlsWrap")
      if c.tls != null then Err.eval("tlsWrap: the connection is already TLS")
      if c.in.pos < c.in.len || c.in.held != null then
        Err.eval("tlsWrap: the connection has buffered input")
      val host = argStr(a, 1, "tlsWrap")
      val ca = if a.length > 2 && (a(2) ne VNull) then argStr(a, 2, "tlsWrap") else null
      val ms = if a.length > 3 && (a(3) ne VNull) then argIdx(a, 3, "tlsWrap") else 0

      val ctx = Tls.contextFor("tlsWrap", ca)
      val s = Tls.newSession("tlsWrap", ctx, own = ca != null)
      Tls.setFd(s, c.fd)

      // SNI and hostname verification. An IP literal is verified as an IP and
      // sent no server name, which is what both curl and the JDK do.
      val isIp = Zone.acquire { implicit z =>
        val chost = toCString(host)
        val probe = stackalloc[Byte](16)
        inet.inet_pton(psock.AF_INET, chost, probe.asInstanceOf[CVoidPtr]) == 1 ||
        inet.inet_pton(psock.AF_INET6, chost, probe.asInstanceOf[CVoidPtr]) == 1
      }
      if !Tls.nameSession(s, host, isIp) then
        Tls.freeSession(s)
        Err.eval("tlsWrap: this TLS library cannot verify the peer's name")

      // The descriptor stays non-blocking for the life of the session: the WANT
      // loops in Tls.read/Tls.writeAll drive it through poll from here on.
      val flags = fcntl.fcntl(c.fd, fcntl.F_GETFL, 0)
      if flags >= 0 then fcntl.fcntl(c.fd, fcntl.F_SETFL, flags | fcntl.O_NONBLOCK)

      val deadline = if ms > 0 then System.currentTimeMillis() + ms else 0L
      val rc = Tls.handshake(s, c.fd, deadline)
      if rc != 0 then
        val why = if rc == -2 then "handshake timed out" else s.err
        Tls.freeSession(s)
        if flags >= 0 then fcntl.fcntl(c.fd, fcntl.F_SETFL, flags)
        Err.eval(s"tlsWrap: $why")
      c.tls = s
      c.in.tls = s
      a(0)
    }

  // -------------------------------------------------------------------------
  // UDP sockets
  // -------------------------------------------------------------------------

  /**
   * Datagrams, shaped after DatagramSocket: one handle both sends and receives,
   * binding happens at creation (port 0 picks a free one), and a receive hands
   * back the datagram together with who sent it. A datagram is decoded in one
   * piece, so the split-character problem byte streams have cannot arise.
   */
  private final class Udp(var fd: Int, val family: Int, val port: Int)

  private def udpOf(a: Array[Value], i: Int, fn: String): Udp =
    val u = handleOf[Udp](a, i, "udp", fn)
    if u.fd < 0 then Err.eval(s"$fn: Socket closed")
    u

  private def udp(): Unit =
    define("udpSocket", 0, 1, "ipc", "udpSocket(port?)",
      "Opens a UDP socket bound to the port; no port (or 0) picks a free one.") { a =>
      ignoreSigpipe()
      val port = if a.nonEmpty && (a(0) ne VNull) then argIdx(a, 0, "udpSocket") else 0
      if port < 0 || port > 65535 then Err.eval(s"Port value out of range: $port")

      // Dual-stack like serverSocket, so one socket speaks both families.
      var fd = psock.socket(psock.AF_INET6, psock.SOCK_DGRAM, 0)
      var family = psock.AF_INET6
      if fd < 0 then
        fd = psock.socket(psock.AF_INET, psock.SOCK_DGRAM, 0)
        family = psock.AF_INET
      if fd < 0 then Err.eval(s"udpSocket: ${strerrorText(cerr.errno)}")
      if family == psock.AF_INET6 then
        val off = stackalloc[CInt]()
        !off = 0
        psock.setsockopt(fd, in.IPPROTO_IPV6, in.IPV6_V6ONLY, off.asInstanceOf[CVoidPtr], 4.toUInt)

      var rc = 0
      if family == psock.AF_INET6 then
        val addr = stackalloc[in.sockaddr_in6]()
        cstr.memset(addr.asInstanceOf[CVoidPtr], 0, sizeof[in.sockaddr_in6])
        addr.sin6_family = psock.AF_INET6.toUShort
        addr.sin6_port = inet.htons(port.toUShort)
        rc = psock.bind(fd, addr.asInstanceOf[Ptr[psock.sockaddr]], sizeof[in.sockaddr_in6].toUInt)
      else
        val addr = stackalloc[in.sockaddr_in]()
        cstr.memset(addr.asInstanceOf[CVoidPtr], 0, sizeof[in.sockaddr_in])
        addr.sin_family = psock.AF_INET.toUShort
        addr.sin_port = inet.htons(port.toUShort)
        rc = psock.bind(fd, addr.asInstanceOf[Ptr[psock.sockaddr]], sizeof[in.sockaddr_in].toUInt)
      if rc != 0 then
        val saved = cerr.errno
        closeFd(fd)
        Err.eval(s"udpSocket: ${strerrorText(saved)}")

      val bound = stackalloc[psock.sockaddr_storage]()
      val blen = stackalloc[psock.socklen_t]()
      !blen = sizeof[psock.sockaddr_storage].toUInt
      val boundPort =
        if psock.getsockname(fd, bound.asInstanceOf[Ptr[psock.sockaddr]], blen) == 0 then
          portOf(bound.asInstanceOf[Ptr[psock.sockaddr]])
        else port
      new VHandle("udp", new Udp(fd, family, boundPort), s"port=$boundPort")
    }

    define("udpPort", 1, 1, "ipc", "udpPort(u)", "The port the UDP socket is bound to.") { a =>
      VInt.of(udpOf(a, 0, "udpPort").port.toLong)
    }

    define("udpSend", 4, 4, "ipc", "udpSend(u, host, port, text)",
      "Sends one datagram to host:port.") { a =>
      val u = udpOf(a, 0, "udpSend")
      val host = argStr(a, 1, "udpSend")
      val port = argIdx(a, 2, "udpSend")
      if port < 0 || port > 65535 then Err.eval(s"port out of range:$port") // InetSocketAddress's own wording
      val text = a(3).display.getBytes(StandardCharsets.UTF_8)

      val (sent, saved) = Zone.acquire { implicit z =>
        val hints = stackalloc[netdb.addrinfo]()
        cstr.memset(hints.asInstanceOf[CVoidPtr], 0, sizeof[netdb.addrinfo])
        hints.ai_family = u.family
        hints.ai_socktype = psock.SOCK_DGRAM
        // A dual-stack socket sends to a v4-only host through its v4-mapped form.
        if u.family == psock.AF_INET6 then hints.ai_flags = netdb.AI_V4MAPPED
        val listPtr = stackalloc[Ptr[netdb.addrinfo]]()
        !listPtr = null
        val gai = netdb.getaddrinfo(toCString(host), toCString(port.toString), hints, listPtr)
        if gai != 0 || (!listPtr) == null then
          // UnknownHostException carries nothing but the name it could not resolve.
          Err.eval(s"udpSend: $host")
        val ai = !listPtr
        var n = 0
        val body = if text.isEmpty then null else text.at(0).asInstanceOf[CVoidPtr]
        while
          n = psock.sendto(u.fd, body, text.length.toUSize, 0, ai.ai_addr, ai.ai_addrlen).toInt
          n < 0 && cerr.errno == perr.EINTR
        do ()
        val e = cerr.errno
        netdb.freeaddrinfo(ai)
        (n, e)
      }
      if sent < 0 then Err.eval(s"udpSend: ${strerrorText(saved)}")
      VNull
    }

    define("udpReceive", 1, 2, "ipc", "udpReceive(u, timeoutMs?)",
      "Waits for one datagram: {data, host, port}, or null when the timeout expires.") { a =>
      val u = udpOf(a, 0, "udpReceive")
      val ms = if a.length > 1 && (a(1) ne VNull) then argIdx(a, 1, "udpReceive") else 0

      val readyRc = if ms > 0 then waitReady(u.fd, spoll.POLLIN, ms) else 1
      if readyRc == 0 then VNull // the timeout expired, as receive() maps it
      else if readyRc < 0 then Err.eval(s"udpReceive: ${strerrorText(cerr.errno)}")
      else
        // The largest possible datagram, so nothing is ever silently truncated.
        val buf = new Array[Byte](65536)
        val from = stackalloc[psock.sockaddr_storage]()
        val flen = stackalloc[psock.socklen_t]()
        var got = 0
        while
          !flen = sizeof[psock.sockaddr_storage].toUInt
          got = psock.recvfrom(u.fd, buf.at(0).asInstanceOf[CVoidPtr], buf.length.toUSize, 0,
            from.asInstanceOf[Ptr[psock.sockaddr]], flen).toInt
          got < 0 && cerr.errno == perr.EINTR
        do ()
        if got < 0 then Err.eval(s"udpReceive: ${strerrorText(cerr.errno)}")
        VObj.of(Seq(
          "data" -> VStr(new String(buf, 0, got, StandardCharsets.UTF_8)),
          "host" -> VStr(formatHost(from.asInstanceOf[Ptr[psock.sockaddr]])),
          "port" -> VInt.of(portOf(from.asInstanceOf[Ptr[psock.sockaddr]]).toLong)
        ))
    }

    define("closeUdp", 1, 1, "ipc", "closeUdp(u)", "Closes a UDP socket.") { a =>
      val u = handleOf[Udp](a, 0, "udp", "closeUdp")
      if u.fd >= 0 then // close() twice is a no-op
        closeFd(u.fd)
        u.fd = -1
      VNull
    }

  // -------------------------------------------------------------------------
  // Line-oriented file handles and named pipes
  // -------------------------------------------------------------------------

  private final class FileH(val path: String, val reading: Boolean, val in: LineIn, val out: LineOut):
    var closed = false

  private def fileOf(a: Array[Value], i: Int, fn: String): FileH =
    val f = handleOf[FileH](a, i, "file", fn)
    if f.closed then Err.eval(s"$fn: Stream closed")
    f

  private def files(): Unit =
    define("openFile", 1, 2, "ipc", "openFile(path, mode?)",
      "Opens a file for streaming: mode 'r' (default), 'w' or 'a'.") { a =>
      ignoreSigpipe()
      val path = argStr(a, 0, "openFile")
      val mode = if a.length > 1 then argStr(a, 1, "openFile") else "r"
      val reading = mode == "r"
      val flags = mode match
        case "r"   => fcntl.O_RDONLY
        case "w"   => fcntl.O_WRONLY | fcntl.O_CREAT | fcntl.O_TRUNC
        case "a"   => fcntl.O_WRONLY | fcntl.O_CREAT | fcntl.O_APPEND
        case other => Err.eval(s"openFile: unknown mode '$other', expected 'r', 'w' or 'a'")
      val (fd, openErr) = Zone.acquire { implicit z =>
        val cpath = toCString(path)
        var f = -1
        // Opening a FIFO blocks until the other end arrives, so this can be interrupted.
        while
          f = BlockingFcntl.open(cpath, flags, 0x1b6) // 0o666
          f < 0 && cerr.errno == perr.EINTR
        do ()
        (f, cerr.errno)
      }
      // FileNotFoundException prints as "<path> (<reason>)".
      if fd < 0 then Err.eval(s"openFile: $path (${strerrorText(openErr)})")
      if reading then
        val st = stackalloc[stat.stat]()
        if stat.fstat(fd, st) == 0 && stat.S_ISDIR(st.st_mode) != 0 then
          // open(2) will hand back a directory; FileInputStream will not.
          closeFd(fd)
          Err.eval(s"openFile: $path (Is a directory)")
      val h = new FileH(path, reading,
        new LineIn(if reading then fd else -1), new LineOut(if reading then -1 else fd))
      new VHandle("file", h, path)
    }

    define("fileReadLine", 1, 1, "ipc", "fileReadLine(f)",
      "Reads one line; null at end of file. On a FIFO this blocks until a writer sends one.") { a =>
      val f = fileOf(a, 0, "fileReadLine")
      if !f.reading then Err.eval(s"fileReadLine: '${f.path}' is open for writing")
      readOneLine(f.in, "fileReadLine", 0)
    }

    def fileWrite(a: Array[Value], fn: String, newline: Boolean): Value =
      val f = fileOf(a, 0, fn)
      if f.reading then Err.eval(s"$fn: '${f.path}' is open for reading")
      val text = a(1).display.getBytes(StandardCharsets.UTF_8)
      var ok = f.out.write(text)
      var saved = cerr.errno
      if ok && newline then
        ok = f.out.write(Newline) && f.out.flush()
        saved = cerr.errno
      if !ok then Err.eval(s"$fn: ${strerrorText(saved)}")
      VNull

    define("fileWrite", 2, 2, "ipc", "fileWrite(f, text)", "Writes text to an open file.") { a =>
      fileWrite(a, "fileWrite", newline = false)
    }

    define("fileWriteLine", 2, 2, "ipc", "fileWriteLine(f, text)",
      "Writes a line to an open file and flushes, so a reader on a FIFO sees it at once.") { a =>
      fileWrite(a, "fileWriteLine", newline = true)
    }

    define("fileFlush", 1, 1, "ipc", "fileFlush(f)", "Flushes buffered writes.") { a =>
      val f = fileOf(a, 0, "fileFlush")
      if !f.reading && !f.out.flush() then Err.eval(s"fileFlush: ${strerrorText(cerr.errno)}")
      VNull
    }

    define("fileClose", 1, 1, "ipc", "fileClose(f)", "Closes an open file.") { a =>
      val f = handleOf[FileH](a, 0, "file", "fileClose")
      if f.closed then VNull // close() on a closed reader or writer does nothing
      else
        var failed = false
        if !f.reading then failed = !f.out.flush() // close() flushes first
        val saved = cerr.errno
        closeFd(if f.reading then f.in.fd else f.out.fd)
        f.in.fd = -1
        f.out.fd = -1
        f.closed = true
        if failed then Err.eval(s"fileClose: ${strerrorText(saved)}")
        VNull
    }

    define("mkfifo", 1, 2, "ipc", "mkfifo(path, mode?)",
      "Creates a named pipe for communicating with another process; mode defaults to 0o600.") { a =>
      val path = argStr(a, 0, "mkfifo")
      val mode = if a.length > 1 then argIdx(a, 1, "mkfifo") else 0x180 // 0o600
      val rc = Zone.acquire { implicit z =>
        stat.mkfifo(toCString(path), mode.toUInt)
      }
      if rc != 0 then Err.eval(s"mkfifo: could not create '$path' (it may already exist)")
      VBool.True
    }

  // -------------------------------------------------------------------------
  // Readiness multiplexing
  // -------------------------------------------------------------------------

  /** poll's trailing timeout: absent or null blocks until something is ready, and
    * any negative value is that same "forever" spelled out — poll(2)'s own rule. */
  private def pollTimeout(a: Array[Value]): Long =
    if a.length > 1 && (a(1) ne VNull) then
      val ms = argInt(a, 1, "poll")
      if ms < 0 then -1L else ms
    else -1L

  private def readiness(): Unit =
    define("poll", 1, 2, "ipc", "poll(handles, timeoutMs?)",
      "Waits until some of the handles are readable (acceptable for servers); returns the ready subset in input order, empty on timeout.") { a =>
      val handles = argArr(a, 0, "poll").items
      val timeout = pollTimeout(a)
      val n = handles.length

      // One left-to-right pass settles every complaint before anything waits: the
      // wrong kind, and the handle whose read would already raise.
      var i = 0
      while i < n do
        handles(i) match
          case h: VHandle =>
            h.kind match
              case "connection" =>
                if h.payload.asInstanceOf[Conn].fd < 0 then Err.eval("poll: Socket closed")
              case "server" =>
                if h.payload.asInstanceOf[Server].fd < 0 then Err.eval("poll: Socket closed")
              case "udp" =>
                if h.payload.asInstanceOf[Udp].fd < 0 then Err.eval("poll: Socket closed")
              case "file" =>
                val f = h.payload.asInstanceOf[FileH]
                if f.closed then Err.eval("poll: Stream closed")
                if !f.reading then Err.eval(s"poll: '${f.path}' is open for writing")
              case "process" => ()
              case _ => Err.eval(s"poll: element ${i + 1} is ${h.kind}, not a pollable handle")
          case v => Err.eval(s"poll: element ${i + 1} is ${v.typeName}, not a pollable handle")
        i += 1
      // Nothing can ever become ready, and "wait forever" must not mean it literally.
      if n == 0 then VArr.empty
      else
        // Readiness the descriptor cannot see: bytes already buffered, a held
        // partial line, or recorded EOF — for those the wait collapses to a probe,
        // because the answer must describe NOW.
        val ins = new Array[LineIn](n)
        val fds = new Array[Int](n)
        val ready = new Array[Boolean](n)
        var anyBuffered = false
        i = 0
        while i < n do
          val h = handles(i).asInstanceOf[VHandle]
          h.kind match
            case "connection" =>
              val c = h.payload.asInstanceOf[Conn]
              ins(i) = c.in
              fds(i) = c.fd
            case "server" =>
              fds(i) = h.payload.asInstanceOf[Server].fd
            case "udp" =>
              fds(i) = h.payload.asInstanceOf[Udp].fd
            case "process" =>
              val c = h.payload.asInstanceOf[Child]
              ins(i) = c.out
              fds(i) = c.out.fd
            case _ =>
              val f = h.payload.asInstanceOf[FileH]
              ins(i) = f.in
              fds(i) = f.in.fd
          if ins(i) != null && ins(i).buffered then
            ready(i) = true
            anyBuffered = true
          i += 1

        val pfds = stackalloc[spoll.struct_pollfd](n)
        i = 0
        while i < n do
          val p = pfds + i
          p.fd = if ready(i) then -1 else fds(i) // poll(2) ignores negative fds
          p.events = spoll.POLLIN.toShort
          p.revents = 0
          i += 1

        val deadline = if timeout > 0 then System.currentTimeMillis() + timeout else 0L
        var rc = 0
        var polling = true
        while polling do
          val wait =
            if anyBuffered || timeout == 0 then 0
            else if timeout < 0 then -1
            else millisLeft(deadline) // EINTR recomputes what is left of the wait
          rc = spoll.poll(pfds, n.toUInt, wait)
          if rc >= 0 then polling = false
          else if cerr.errno != perr.EINTR then polling = false
        if rc < 0 then Err.eval(s"poll: ${strerrorText(cerr.errno)}")

        val out = VArr.sized(n)
        i = 0
        while i < n do
          val p = pfds + i
          if ready(i) || (p.revents & (spoll.POLLIN | spoll.POLLHUP | spoll.POLLERR)) != 0 then
            out.items += handles(i)
          i += 1
        out
    }

  // -------------------------------------------------------------------------
  // Process identity and signals
  // -------------------------------------------------------------------------

  private def signals(): Unit =
    define("pid", 0, 0, "ipc", "pid()", "This process's identifier.") { _ =>
      VInt.of(unistd.getpid().toLong)
    }

    define("signalProcess", 2, 2, "ipc", "signalProcess(pid, signal)",
      "Sends a signal to a process; see SIGTERM(), SIGKILL() and friends.") { a =>
      val target = argIdx(a, 0, "signalProcess")
      val sig = argIdx(a, 1, "signalProcess")
      val rc = psignal.kill(target, sig)
      if rc != 0 then Err.eval(s"signalProcess: could not signal process $target")
      VBool.True
    }

    def constant(name: String, value: Int, doc: String): Unit =
      define(name, 0, 0, "ipc", s"$name()", doc)(_ => VInt.of(value.toLong))

    constant("SIGINT", 2, "Interrupt, what Ctrl-C sends.")
    constant("SIGKILL", 9, "Kill outright; cannot be caught.")
    constant("SIGTERM", 15, "Polite request to terminate.")
    constant("SIGHUP", 1, "Hang up; often used to mean 'reload'.")
    constant("SIGUSR1", if System.getProperty("os.name", "").toLowerCase.contains("mac") then 30 else 10,
      "First user-defined signal.")
    constant("SIGUSR2", if System.getProperty("os.name", "").toLowerCase.contains("mac") then 31 else 12,
      "Second user-defined signal.")
