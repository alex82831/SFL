package com.fartech.sfl

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.sys.ioctl
import scala.scalanative.posix.termios
import scala.scalanative.posix.termiosOps.termiosStructOps
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** A decoded key press. */
enum Key:
  case Ch(cp: Int)
  case Enter, Backspace, Delete, Tab, Left, Right, Up, Down, Home, End
  case CtrlA, CtrlC, CtrlD, CtrlE, CtrlK, CtrlL, CtrlU, CtrlW
  case WordLeft, WordRight
  case Escape, Unknown, Eof

/**
 * Raw-mode terminal access.
 *
 * The REPL needs byte-at-a-time input with echo disabled to implement history,
 * cursor movement and live highlighting; Scala Native has no line-editing library, so
 * this drives termios directly and degrades to plain `readLine` when stdin is not a
 * terminal (a pipe, a here-doc, or CI).
 */
object Terminal:
  private var saved: Ptr[termios.termios] = null
  private var raw = false

  def isTty: Boolean = unistd.isatty(unistd.STDIN_FILENO) == 1 && unistd.isatty(unistd.STDOUT_FILENO) == 1

  def inRawMode: Boolean = raw

  /** Switches to raw mode. Returns false when that is not possible. */
  def enableRaw(): Boolean =
    if raw then return true
    if !isTty then return false
    // The saved copy outlives this call, so it cannot live on the stack.
    val backup = stdlibMalloc()
    if termios.tcgetattr(unistd.STDIN_FILENO, backup) != 0 then
      scala.scalanative.libc.stdlib.free(backup.asInstanceOf[Ptr[Byte]])
      return false
    saved = backup

    val tio = stackalloc[termios.termios]()
    if termios.tcgetattr(unistd.STDIN_FILENO, tio) != 0 then return false
    // Disable echo, line buffering, signal generation and extended input processing.
    // Ctrl-C and Ctrl-D are handled by the editor instead of the driver.
    val mask = (termios.ECHO | termios.ICANON | termios.ISIG | termios.IEXTEN).toUInt
    tio.c_lflag = tio.c_lflag & ~mask
    tio.c_cc(termios.VMIN) = 1.toUByte
    tio.c_cc(termios.VTIME) = 0.toUByte
    if termios.tcsetattr(unistd.STDIN_FILENO, termios.TCSANOW, tio) != 0 then return false
    raw = true
    true

  private def stdlibMalloc(): Ptr[termios.termios] =
    scala.scalanative.libc.stdlib
      .malloc(sizeof[termios.termios])
      .asInstanceOf[Ptr[termios.termios]]

  def restore(): Unit =
    if raw && saved != null then
      termios.tcsetattr(unistd.STDIN_FILENO, termios.TCSANOW, saved)
      raw = false

  /** Terminal width in columns, with sensible fallbacks. */
  def width: Int =
    val viaIoctl = winsizeCols()
    if viaIoctl > 0 then viaIoctl
    else
      sys.env.get("COLUMNS").flatMap(s => scala.util.Try(s.toInt).toOption).filter(_ > 0).getOrElse(80)

  // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
  private def winsizeCols(): Int =
    val TIOCGWINSZ: Long = if LinktimeInfo.isLinux then 0x5413L else 0x40087468L
    val ws = stackalloc[CUnsignedShort](4)
    val rc = ioctl.ioctl(unistd.STDOUT_FILENO, TIOCGWINSZ.toSize, ws.asInstanceOf[Ptr[Byte]])
    if rc != 0 then -1 else ws(1).toInt

  private def readByte(): Int =
    val buf = stackalloc[Byte](1)
    val n = unistd.read(unistd.STDIN_FILENO, buf.asInstanceOf[CVoidPtr], 1.toUSize)
    if n <= 0 then -1 else (!buf).toInt & 0xff

  /**
   * Reads one key, decoding UTF-8 sequences and the CSI escapes that arrow and
   * navigation keys produce.
   */
  def readKey(): Key =
    val b = readByte()
    if b < 0 then return Key.Eof
    b match
      case 1                 => Key.CtrlA
      case 3                 => Key.CtrlC
      case 4                 => Key.CtrlD
      case 5                 => Key.CtrlE
      case 9                 => Key.Tab
      case 10 | 13           => Key.Enter
      case 11                => Key.CtrlK
      case 12                => Key.CtrlL
      case 21                => Key.CtrlU
      case 23                => Key.CtrlW
      case 127 | 8           => Key.Backspace
      case 27                => escapeSequence()
      case c if c < 32       => Key.Unknown
      case c                 => Key.Ch(decodeUtf8(c))

  private def escapeSequence(): Key =
    val b1 = readByte()
    if b1 < 0 then return Key.Escape
    if b1 == '['.toInt then
      val b2 = readByte()
      b2 match
        case c if c == 'A'.toInt => Key.Up
        case c if c == 'B'.toInt => Key.Down
        case c if c == 'C'.toInt => Key.Right
        case c if c == 'D'.toInt => Key.Left
        case c if c == 'H'.toInt => Key.Home
        case c if c == 'F'.toInt => Key.End
        case c if c >= '0'.toInt && c <= '9'.toInt =>
          // Extended forms such as ESC [ 3 ~ (delete) and ESC [ 1 ; 5 C (ctrl-right).
          val sb = new StringBuilder
          sb.append(c.toChar)
          var last = readByte()
          while last >= 0 && last != '~'.toInt && !Character.isLetter(last.toChar) do
            sb.append(last.toChar)
            last = readByte()
          val params = sb.toString
          if last == '~'.toInt then
            params match
              case "3"          => Key.Delete
              case "1" | "7"    => Key.Home
              case "4" | "8"    => Key.End
              case _            => Key.Unknown
          else
            last match
              case c2 if c2 == 'C'.toInt => Key.WordRight
              case c2 if c2 == 'D'.toInt => Key.WordLeft
              case c2 if c2 == 'H'.toInt => Key.Home
              case c2 if c2 == 'F'.toInt => Key.End
              case _                     => Key.Unknown
        case _ => Key.Unknown
    else if b1 == 'O'.toInt then
      readByte() match
        case c if c == 'H'.toInt => Key.Home
        case c if c == 'F'.toInt => Key.End
        case _                   => Key.Unknown
    else if b1 == 'b'.toInt then Key.WordLeft
    else if b1 == 'f'.toInt then Key.WordRight
    else Key.Escape

  /** Assembles a code point from a leading byte plus its continuation bytes. */
  private def decodeUtf8(first: Int): Int =
    val extra =
      if (first & 0x80) == 0 then 0
      else if (first & 0xe0) == 0xc0 then 1
      else if (first & 0xf0) == 0xe0 then 2
      else if (first & 0xf8) == 0xf0 then 3
      else -1
    if extra <= 0 then first
    else
      var cp = first & (0x3f >> extra)
      var i = 0
      var ok = true
      while ok && i < extra do
        val b = readByte()
        if b < 0 || (b & 0xc0) != 0x80 then ok = false
        else cp = (cp << 6) | (b & 0x3f)
        i += 1
      if ok then cp else 0xfffd

  /**
   * Printable width of a code point. CJK and emoji occupy two columns, and getting
   * this wrong misplaces the cursor for anyone typing Chinese.
   */
  def charWidth(cp: Int): Int =
    if cp == 0 then 0
    else if cp < 32 || (cp >= 0x7f && cp < 0xa0) then 0
    else if isCombining(cp) then 0
    else if isWide(cp) then 2
    else 1

  private def isCombining(cp: Int): Boolean =
    (cp >= 0x0300 && cp <= 0x036f) || (cp >= 0x200b && cp <= 0x200f) ||
      (cp >= 0xfe00 && cp <= 0xfe0f)

  private def isWide(cp: Int): Boolean =
    (cp >= 0x1100 && cp <= 0x115f) || // Hangul Jamo
      (cp >= 0x2e80 && cp <= 0xa4cf && cp != 0x303f) || // CJK radicals .. Yi
      (cp >= 0xac00 && cp <= 0xd7a3) || // Hangul syllables
      (cp >= 0xf900 && cp <= 0xfaff) || // CJK compatibility ideographs
      (cp >= 0xfe30 && cp <= 0xfe6f) || // CJK compatibility forms
      (cp >= 0xff00 && cp <= 0xff60) || // Fullwidth forms
      (cp >= 0xffe0 && cp <= 0xffe6) ||
      (cp >= 0x1f300 && cp <= 0x1f64f) || // emoji
      (cp >= 0x1f900 && cp <= 0x1f9ff) ||
      (cp >= 0x20000 && cp <= 0x3fffd)

  /** Printable width of a whole string, ignoring ANSI escapes. */
  def displayWidth(s: String): Int =
    val plain = Ansi.strip(s)
    var w = 0
    var i = 0
    while i < plain.length do
      val cp = plain.codePointAt(i)
      w += charWidth(cp)
      i += Character.charCount(cp)
    w
