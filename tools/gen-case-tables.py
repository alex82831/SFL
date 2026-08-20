#!/usr/bin/env python3
"""Regenerates runtime/sfl_case_tables.h from the interpreter's own behavior.

The compiled runtime's toUpper/toLower must agree with the interpreter down to
the last code unit, and the interpreter's mapping is whatever its String
implementation does — not any particular Unicode data file. So instead of
transcribing UnicodeData.txt, this script asks the interpreter itself: it runs a
probe program through the given sfl binary that prints, for every code point,
the full toUpper/toLower mapping plus the char's role in the final-sigma
context rules, and turns the answers into C tables.

Usage:  python3 tools/gen-case-tables.py target/scala-3.8.4/sfl > runtime/sfl_case_tables.h

The probe measures four behaviors per code point X (Σ is U+03A3, ς U+03C2):
  cased      toLower("XΣ") ends in ς — the backward scan saw X as cased
  ignorable  toLower("ΑXΣ") ends in ς while the above does not — X is skipped
  and the same two on the far side of the sigma, which is how we know the
  engine never treats a supplementary letter as cased when scanning forward.
"""

import subprocess
import sys
import tempfile
import os

PROBE = r"""
val SIGMA = chr(0x3A3)
val FINAL = 0x3C2
val ALPHA = chr(0x391)

def hexUnits(s) {
  var out = ""
  var i = 0
  while (i < length(s)) {
    if (i > 0) { out += "," }
    out += format("%04x", ord(charAt(s, i)))
    i += 1
  }
  out
}

def lastUnit(s) = ord(charAt(s, length(s) - 1))

for (cp in range(0, 1114112)) {
  if (cp < 0xD800 || cp > 0xDFFF) {
    val s = chr(cp)
    val u = toUpper(s)
    val l = toLower(s)
    val cased = lastUnit(toLower(s + SIGMA)) == FINAL
    val igOrCased = lastUnit(toLower(ALPHA + s + SIGMA)) == FINAL
    val ig = igOrCased && !cased
    val fs3 = toLower(ALPHA + SIGMA + s)
    val casedFwd = ord(charAt(fs3, 1)) != FINAL
    val fs4 = toLower(ALPHA + SIGMA + s + chr(0x3B1))
    val igFwd = !casedFwd && ord(charAt(fs4, 1)) != FINAL
    if (u != s || l != s || cased || ig || casedFwd || igFwd) {
      val us = if (u == s) then "=" else hexUnits(u)
      val ls = if (l == s) then "=" else hexUnits(l)
      println(format("%x %s %s %d%d%d%d", cp, us, ls,
        (if (cased) then 1 else 0), (if (ig) then 1 else 0),
        (if (casedFwd) then 1 else 0), (if (igFwd) then 1 else 0)))
    }
  }
}
"""


def probe(sfl: str) -> list[str]:
    with tempfile.NamedTemporaryFile("w", suffix=".sfl", delete=False) as f:
        f.write(PROBE)
        path = f.name
    try:
        out = subprocess.run([sfl, path], check=True, capture_output=True, text=True)
        return out.stdout.splitlines()
    finally:
        os.unlink(path)


def parse_units(field: str) -> list[int]:
    return [int(u, 16) for u in field.split(",")]


def units_to_cp(units: list[int]) -> int:
    """A supplementary mapping must be exactly one code point."""
    assert len(units) == 2, units
    hi, lo = units
    assert 0xD800 <= hi < 0xDC00 and 0xDC00 <= lo < 0xE000, units
    return 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)


def to_ranges(points: set[int]) -> list[tuple[int, int]]:
    out = []
    for cp in sorted(points):
        if out and cp == out[-1][1] + 1:
            out[-1] = (out[-1][0], cp)
        else:
            out.append((cp, cp))
    return out


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: gen-case-tables.py <path-to-sfl-binary>")
    lines = probe(sys.argv[1])

    map1 = {"upper": {}, "lower": {}}    # BMP, one unit -> one unit
    mapn = {"upper": {}, "lower": {}}    # BMP, one unit -> 2..3 units
    supp = {"upper": {}, "lower": {}}    # code point -> code point, both >= 0x10000
    cased: set[int] = set()
    ignorable: set[int] = set()

    for line in lines:
        cph, us, ls, flags = line.split()
        cp = int(cph, 16)
        for kind, field in (("upper", us), ("lower", ls)):
            if field == "=":
                continue
            units = parse_units(field)
            if cp <= 0xFFFF:
                assert all(u < 0xD800 or u > 0xDFFF for u in units), line
                assert 1 <= len(units) <= 3, line
                if len(units) == 1:
                    map1[kind][cp] = units[0]
                else:
                    mapn[kind][cp] = units
            else:
                supp[kind][cp] = units_to_cp(units)
        b_cased, b_ig, f_cased, f_ig = (c == "1" for c in flags)
        # The whole point of probing both directions: one shared ignorable set,
        # one cased set, and a forward scan that stops at the BMP boundary.
        assert b_ig == f_ig, line
        assert f_cased == (b_cased and cp <= 0xFFFF), line
        assert not (b_cased and b_ig), line
        if b_cased:
            cased.add(cp)
        if b_ig:
            ignorable.add(cp)

    w = sys.stdout.write
    w("/*\n")
    w(" * Case-mapping tables measured from the interpreter, which is the language's\n")
    w(" * specification. Generated — do not edit. To regenerate after a change to the\n")
    w(" * interpreter's String implementation:\n")
    w(" *\n")
    w(" *   python3 tools/gen-case-tables.py target/scala-3.8.4/sfl > runtime/sfl_case_tables.h\n")
    w(" *\n")
    w(" * sfl_string.c interprets these: toUpper/toLower look a unit up in the *_1\n")
    w(" * table, then the *_n table, and decode a surrogate pair for the *_supp table;\n")
    w(" * the final-sigma scan skips sfl_case_ignorable code points and then asks\n")
    w(" * whether it stopped on an sfl_cased one (scanning forward, a supplementary\n")
    w(" * letter never counts as cased — measured, like everything else here).\n")
    w(" */\n\n")
    w("typedef struct { uint16_t cp, to; } SflCase1;\n")
    w("typedef struct { uint16_t cp, n, out[3]; } SflCaseN;\n")
    w("typedef struct { uint32_t cp, to; } SflCaseSupp;\n")
    w("typedef struct { uint32_t lo, hi; } SflCaseRange;\n")

    for kind in ("upper", "lower"):
        w(f"\nstatic const SflCase1 sfl_{kind}_1[] = {{\n")
        items = [f"{{0x{cp:04X}, 0x{to:04X}}}" for cp, to in sorted(map1[kind].items())]
        for i in range(0, len(items), 6):
            w("  " + ", ".join(items[i:i + 6]) + ",\n")
        w("};\n")
        w(f"\nstatic const SflCaseN sfl_{kind}_n[] = {{\n")
        for cp, units in sorted(mapn[kind].items()):
            out = ", ".join(f"0x{u:04X}" for u in units + [0] * (3 - len(units)))
            w(f"  {{0x{cp:04X}, {len(units)}, {{{out}}}}},\n")
        w("};\n")
        w(f"\nstatic const SflCaseSupp sfl_{kind}_supp[] = {{\n")
        items = [f"{{0x{cp:05X}, 0x{to:05X}}}" for cp, to in sorted(supp[kind].items())]
        for i in range(0, len(items), 4):
            w("  " + ", ".join(items[i:i + 4]) + ",\n")
        w("};\n")

    for name, points in (("sfl_cased", cased), ("sfl_case_ignorable", ignorable)):
        ranges = to_ranges(points)
        w(f"\nstatic const SflCaseRange {name}[] = {{\n")
        items = [f"{{0x{lo:04X}, 0x{hi:04X}}}" for lo, hi in ranges]
        for i in range(0, len(items), 6):
            w("  " + ", ".join(items[i:i + 6]) + ",\n")
        w("};\n")

    counts = (f"{len(map1['upper'])}+{len(mapn['upper'])} upper, "
              f"{len(map1['lower'])}+{len(mapn['lower'])} lower, "
              f"{len(supp['upper'])}/{len(supp['lower'])} supplementary, "
              f"{len(to_ranges(cased))} cased ranges, "
              f"{len(to_ranges(ignorable))} ignorable ranges")
    print(f"generated: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
