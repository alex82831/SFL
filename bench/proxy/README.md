# bench/proxy — httpd 反向代理性能评估装置

评估 SFL 的 `httpd` 包作为 HTTP 反向代理/负载均衡器的性能，并与 nginx
在相同拓扑下对比。完整结论见 `docs/评估-httpd-反向代理性能.md`。

## 组成

| 文件 | 作用 |
| --- | --- |
| `rproxy.sfl` | 被测对象：基于 httpd 的 HTTP/1.1 反向代理，轮询负载均衡 + 上游连接池 |
| `hello.sfl` | 最小 httpd 服务器，用作无转发的基线 |
| `nginx-backend.conf` | 上游后端：nginx 单 worker，端口 9001/9002 |
| `nginx-proxy.conf` | 对照组：nginx 反向代理（上游 keepalive 池），端口 8002 |
| `bench.sh` | 主压测矩阵：direct / sfl / ngx × 载荷 × 并发，CSV 输出 |
| `analyze.py` | 汇总 `results/results.csv`，输出对比表与比值 |
| `gclog-trial.sh` | 单点试跑并汇总 GC 停顿日志（需要插桩运行时） |
| `diag.sh` | 慢请求相位追踪 + 全进程停顿看门狗 |
| `syscalls.sh` | strace 每请求系统调用画像（sfl vs nginx） |
| `gc-instrument.patch` | 运行时 GC 观测插桩（env 门控，默认行为与原版一致） |

## 运行

需要 4 个 CPU、`nginx`、`wrk`、`sfl`（编译器）：

```sh
sfl pkg install /path/to/SFL/packages/httpd   # 在本目录安装 httpd 包
sfl -c rproxy.sfl -o rproxy
sfl -c hello.sfl -o hello
./bench.sh                    # 约 15 分钟，结果在 results/
python3 analyze.py            # 汇总表
```

CPU 绑定约定：后端 CPU0，被测代理 CPU1（或 1,2），wrk CPU3（direct 基线用 2,3）。

## GC 观测插桩

`gc-instrument.patch` 给运行时 `sfl_gc.c` 增加两个环境变量（都不设置时行为与
原版完全一致）：

- `SFL_GC_LOG=1` — 每次收集打一行 `GCLOG pause_us=… stop_us=… threads=…
  live_kb=… heap_kb=…` 到 stderr；
- `SFL_GC_MIN_THRESHOLD=<bytes>` — 抬高触发收集的分配阈值下限（默认 2MB）。

打法：对 `~/.cache/sfl/<ver>/src/sfl_gc.c` 应用补丁，
`clang -std=gnu11 -O2 -c sfl_gc.c -o sfl_gc.o && ar r ../libsflrt.a sfl_gc.o`，
再重新 `sfl -c` 编译被测程序。`rproxy` 另有 `RPROXY_TRACE=1` 打开慢请求
相位日志与停顿看门狗。
