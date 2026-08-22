#!/usr/bin/env python3
"""Summarize results/results.csv: best-of-repeats per case, SFL/nginx ratios."""
import csv, sys, collections

path = sys.argv[1] if len(sys.argv) > 1 else "results/results.csv"
rows = list(csv.DictReader(open(path)))

def num(v):
    try: return float(v)
    except (ValueError, TypeError): return None

# Keep the best repeat (highest rps) of each (target,cpus,payload,conns) case;
# remember rps spread across repeats to report variance.
best, spread = {}, collections.defaultdict(list)
for r in rows:
    churn = "close" in r.get("payload", "") or r.get("churn", "")
    key = (r["target"], r["cpus"], r["payload"], r["conns"])
    rps = num(r["rps"]) or 0
    spread[key].append(rps)
    if key not in best or rps > (num(best[key]["rps"]) or 0):
        best[key] = r

def fmt_us(v):
    v = num(v)
    if v is None: return "-"
    return f"{v/1000:.1f}ms" if v >= 1000 else f"{v:.0f}us"

print(f"{'case':<28}{'target':<10}{'rps':>10}{'p50':>9}{'p99':>9}{'p99.9':>9}{'cpu%':>6}{'rss':>8}  spread")
order = ["direct", "sfl", "ngx"]
cases = sorted({(k[2], int(k[3])) for k in best}, key=lambda c: (c[0], c[1]))
for payload, conns in cases:
    for tgt in order:
        for cpus in ["-", "1", "2"]:
            k = (tgt, cpus, payload, str(conns))
            if k not in best: continue
            r = best[k]
            sp = spread[k]
            spr = f"±{(max(sp)-min(sp))/2/max(sp)*100:.0f}%" if len(sp) > 1 and max(sp) else ""
            name = f"{payload} c={conns}"
            label = tgt + (f"-{cpus}cpu" if cpus != "-" else "")
            print(f"{name:<28}{label:<10}{num(r['rps']) or 0:>10.0f}{fmt_us(r['p50_us']):>9}"
                  f"{fmt_us(r['p99_us']):>9}{fmt_us(r['p999_us']):>9}"
                  f"{r['proxy_cpu_pct']:>6}{r['proxy_rss_mb']:>7}M  {spr}")
    print()

# Ratios: sfl vs ngx at matching cpu counts.
print("== SFL rproxy as a fraction of nginx (same CPUs, best-of-repeats) ==")
for payload, conns in cases:
    for cpus in ["1", "2"]:
        s = best.get(("sfl", cpus, payload, str(conns)))
        n = best.get(("ngx", cpus, payload, str(conns)))
        if not s or not n: continue
        sr, nr = num(s["rps"]) or 0, num(n["rps"]) or 1
        print(f"  {payload} c={conns} {cpus}cpu: sfl {sr:>8.0f} / ngx {nr:>8.0f} = {sr/nr*100:5.1f}%"
              f"   p99 {fmt_us(s['p99_us'])} vs {fmt_us(n['p99_us'])}")
