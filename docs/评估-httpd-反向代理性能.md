# httpd 能否撑起一个反向代理

**性能评估报告** · 针对 SFL 0.8.3 的 `httpd` 包

本文回答一个问题：用 `httpd` 包开发一个做 HTTP 流量负载均衡的反向代理，
性能上能否与 nginx 一类的实现相提并论。

所有数字都是实测的，测试装置在 `bench/proxy/` 里，命令随文给出，可复现。
凡是估算，都标明是估算。

---

## 结论

**当前版本（0.8.3）不能，而且首先不是输在快慢上：运行时存在一个多线程负载下
必然触发的 GC 死锁，代理在持续压力下几秒到几十秒内整体挂死，进程还会把每个
核烧到 100%。** 这是发布阻断级缺陷，在它修复之前谈吞吐没有意义。

打上本文附带的死锁修复补丁之后，可以谈快慢了。结论分三层：

| 问题 | 回答 |
| --- | --- |
| 能不能和 nginx 可比？ | **不可比。** 单核吞吐约为 nginx 的 ⟨RATIO⟩，p99 尾延迟差约两个数量级 |
| 差距在 httpd 包吗？ | **基本不在。** httpd 的 I/O 写法是对的；差距的 ~90% 在语言运行时：全局堆分配锁、每秒上百次的全局停顿、UTF-16 字符串 |
| 那 httpd 能用来干什么？ | 内部工具、低并发服务、开发环境——凡是 QPS 在几千以下、尾延迟不敏感的场景，修复死锁后都够用，而且代码量只有 nginx 配置的量级 |

一句话：**httpd 把一个正确、完整的 HTTP 栈写得很像样，但"稳定与性能优先"的
承诺目前被运行时挡住了——先修死锁，再谈把分配路径做便宜，此后才轮到讨论
事件驱动。**

---

## 一、测的是什么

### 被测对象

`bench/proxy/rproxy.sfl`（约 250 行）：基于 httpd 的 HTTP/1.1 反向代理。
按 httpd 与语言的推荐写法实现，不做奇技淫巧：

- `httpd.server` 收前端连接，处理函数里轮询（round-robin）选后端；
- 上游用 `connect` 客户端 socket，**带连接池**（每后端最多 128 条空闲复用，
  与 nginx 对照组的 `keepalive 256` 匹配）；
- 剥 hop-by-hop 头、追加 `x-forwarded-for`、支持 content-length / chunked /
  read-to-close 三种上游 framing；池里拿到的死连接换新连接重试一次。

### 对照组

- **nginx 1.24.0**（Ubuntu 包）做同样的事：`proxy_pass` 到同两个后端、
  上游 keepalive 池、`access_log off`。
- **direct**：wrk 直压后端，给出测试装置本身的天花板。

### 拓扑

4 核 Linux 6.18（本仓库的 CI 容器），全部走环回：

```
CPU 0      后端 nginx（1 worker，:9001/:9002，返回 19B / 4KB / 64KB）
CPU 1[,2]  被测代理（SFL 或 nginx，绑 1 核或 2 核）
CPU 3      wrk -t2（direct 基线借用 CPU 2,3）
```

矩阵：3 种载荷 × 并发 16/64/256/1024（小载荷）+ 64/256（4K/64K）+
每用例 10 秒、`/` 用例各跑两遍取高者；另测 `Connection: close` 连接风暴。
被测代理**每个用例重启**——原因见下一节。

---

## 二、首要发现：GC 停世界 vs malloc 的死锁（发布阻断）

### 现象

持续压测原版 rproxy，几秒内吞吐归零，进程占满所在核不再应答；
`bench/proxy/soak.sh` 30 秒实测：

| 构建 | 30s @ c=64 结果 | GC 次数 | 之后进程 |
| --- | --- | --- | --- |
| 原版运行时 | 平均 1009 rps（前几秒 ~8k，随后挂死） | 445 | **死，100% CPU** |
| 修复后 | 稳定 7251 rps | 3204 | 活 |

### 机制

gdb 抓到的现场（完整 18 线程栈：`bench/proxy/logs/deadlock-backtrace.txt`）：

```
Thread 13:  sfl_alloc → sfl_gc_collect → sweep → finalize → free()
              └─ futex_wait: 等 glibc malloc arena 锁 0x7fcc1c000030
Thread 16:  sfl_raw_alloc → malloc → sysmalloc(av=0x7fcc1c000030)   ← 正持有该锁
              └─ 被 SIGUSR2 停世界信号打断 → stop_handler 自旋等收集结束
其余 15 线程:  stop_handler 里 sched_yield() 自旋
```

环形等待成立：收集器在停住的世界里调 `free()`，要的正是某个被停线程手里的
malloc arena 锁。两处代码各自都对——`sweep` 释放死对象的边缘缓冲、
停世界用信号抢占——组合起来就是死锁。触发窗口是"信号恰好落在别的线程
malloc 临界区内"，单次概率不大，但压测下 GC 每秒 ~110 次、66 个线程全在
频繁 malloc，几秒内必中。**这不是 httpd 的 bug，任何多线程分配的 SFL
程序都会中**；`mark_push` 里停世界期间的 `realloc` 是同一个隐患。
挂死后所有被停线程在 `sched_yield()` 里自旋，故表现为烧满 CPU 的假死。

### 修复原型（已实测）

`bench/proxy/runtime-fix.patch`（约 150 行，对 `runtime/sfl_gc.c`）：

1. **停住的世界里不碰 C 分配器**：`finalize` 的释放推迟到 `start_the_world()`
   之后统一执行（延迟表用 mmap 增长，不经过 malloc）；`mark_push` 的标记栈
   改用 mmap 增长；
2. **被停线程改为 futex 停靠**，不再 `sched_yield()` 自旋——顺带解决了
   "每次 GC 期间每个停住的线程烧掉一整个核"的问题。

补丁后 30 秒/120 秒 soak 均存活，正式矩阵中 `sflfix` 全部用例 0 次挂死
（原版 `sfl` 行的 `wedged` 列如实记录挂死次数）。

---

## 三、架构对比：差距会出现在哪（读代码所得，实测印证在第四节）

### httpd 包本身：写得对的地方

| 设计点 | 现状 | 评价 |
| --- | --- | --- |
| 行读取 | 运行时 8KB 缓冲 `LineIn`，非逐字节 | ✓ 正确 |
| 小响应写出 | 头+体拼一串，**一次 write 系统调用** | ✓ 正确 |
| Date 头 | 每秒重建一次的缓存 | ✓ 正确 |
| 静态文件 | `socketSendFile`（64KB 栈缓冲 pread+write 循环，避开 GC 堆与编码转换；非内核 sendfile）+ 条件/Range | ✓ 基本正确 |
| TCP_NODELAY | 每连接开启 | ✓ 正确 |
| 解析 | 拒绝 smuggling 形状、上限齐全（头/URL/体） | ✓ 稳健 |

httpd 作为一段 SFL 代码，几乎没有留下明显的浪费；它的性能上限就是
"SFL 运行时处理字符串与线程的成本"本身。

### 与 nginx 的结构性差异

| 维度 | httpd (SFL) | nginx | 后果 |
| --- | --- | --- | --- |
| 并发模型 | **每连接一线程**（pthread，8MB 栈），HTTP/2 再加每流一线程 | 每 worker 一个 epoll 事件环 | c=1k 时 SFL 有 1k+ 线程在 4 核上轮转；nginx 仍是 2 个进程 |
| 值与内存 | 一切动态值都是 GC 堆单元，**每次分配拿全局互斥锁** | 每请求内存池，请求结束整池释放 | 实测每个代理请求 ~30KB 堆分配 ≈ 每秒数百万次全局加锁 |
| GC | 全局停世界 mark-sweep，阈值 max(live, 2MB)，sweep 扫全堆 | 无 GC | 实测负载下每秒 ~110 次、每次 ~1.4ms 的全局停顿（约 15% 墙钟时间） |
| 字符串 | UTF-16 堆对象；socket 读入转 UTF-16、写出转回 UTF-8 | 字节指针 + 长度，零拷贝引用 | 正文在代理路径上复制 ~5 次，其中 2 次带编码转换 |
| 头处理 | 每行 `indexOf/trim/toLower/substring` 各生成新字符串 | 指向缓冲区的 slice，惰性小写化 | 每请求几十个小对象进 GC 堆 |
| accept | 单线程 poll+accept，每连接 `pthread_create` | 各 worker `accept`/`SO_REUSEPORT` | 连接风暴时建立成本高一个量级 |

### 定量印证（插桩运行时，`SFL_GC_LOG=1`）

- hello（19B 响应，无转发）：每请求 **~15KB** GC 堆分配；
- rproxy（转发 19B）：每请求 **~30KB**；
- c=64 时 GC 每秒 ~110-126 次、单次全停 1.4-1.8ms、其间原版让 65 个停住的
  线程一起 `sched_yield` 自旋；
- 把阈值拉到 512MB（等效关 GC）反而更慢：堆膨胀到 130MB 后局部性劣化，
  且最终一次 sweep 全停 **118ms**——sweep 代价是 O(堆容量) 而非 O(活对象)；
- `RPROXY_TRACE=1` 的看门狗显示：一个只做 `sleep(100)`+比较的线程在原版
  负载下可被饿 **1-2 秒**拿不到堆锁/CPU——p99 的 100ms+ 台阶就是它。

---

## 四、实测数据

⟨MATRIX_SECTION⟩

---

## 五、能不能与 nginx 可比——按场景回答

⟨VERDICT_SECTION⟩

---

## 六、改进路线（按性价比排序）

| 优先级 | 改动 | 层次 | 预期收益 | 依据 |
| --- | --- | --- | --- | --- |
| **P0** | 合入死锁修复（附补丁） | 运行时 | 从"几秒挂死"到可持续运行 | 第二节 A/B 实测 |
| **P1** | 每线程分配缓存（每线程小自由链，批量向全局堆要页） | 运行时 | 消除全局堆锁竞争——当前最大的吞吐与尾延迟来源 | 看门狗饿死 1-2s；每请求 ~1000 次加锁（估算） |
| **P2** | sweep 只扫有分配的页 + 阈值上调/自适应（如 max(live×2, 16MB)） | 运行时 | 停顿频率减半以上；避免 O(堆容量) 的大停顿 | gc-sweep 实测 ⟨SWEEP_NOTE⟩ |
| **P3** | 字符串/字节直通：`socketReadToBuf`→`respond` 允许携带 buf 正文，写出免 UTF-16 往返；头解析用字节 slice | 运行时+httpd | 正文拷贝从 ~5 次降到 ~2 次；转发 4K/64K 收益最大 | 第三节字符串路径分析 |
| **P4** | `accept` 后连接交给线程池而非每连接 `pthread_create`；或 listener 侧 `SO_REUSEPORT` 多 accept 线程 | httpd | 连接风暴（`Connection: close`）差距收窄 | 矩阵 churn 行 ⟨CHURN_NOTE⟩ |
| **P5** | 事件驱动 I/O + M:N 调度 | 语言级 | 接近 nginx 的并发扩展性 | 与"线程即并发模型"的语言承诺冲突，属长期方向，不建议为 httpd 单独做 |

P1-P3 做完后的合理预期（估算）：单核吞吐到 nginx 的 40-60%，p99 进入
10ms 以内。要摸到 nginx 同一量级，需要 P5 级别的架构变更——那已经不是
httpd 包的问题，而是语言并发模型的取舍。

---

## 七、威胁与边界（什么没测、结论到哪为止）

- **环回、单机、4 核**：没有真实网卡、跨机延迟；相对比较成立，绝对数字
  不代表生产环境。
- **未测 TLS 终结**：nginx 的 TLS 栈优化（会话复用、内核 offload 配合）
  是另一个维度的差距，此处只测明文 HTTP/1.1。
- **未测 HTTP/2 前端**：httpd 支持 h2，但其每流一线程 + 帧级 SFL 数组
  组包的成本读代码即知更高，且 nginx 对照配置为 h1，故统一 h1。
- **wrk 单核**：direct 行给出装置天花板（⟨CEILING⟩），被测行离它足够远，
  wrk 不构成瓶颈；个别 direct 行接近时已标注。
- 修复补丁是**原型**：正确性论证在第二节，但未过上游测试套件；合入前
  应补 `bench/` 与 `tests/` 的回归。

---

## 附：复现

```sh
cd bench/proxy
sfl pkg install ../../packages/httpd
sfl -c rproxy.sfl -o rproxy && sfl -c hello.sfl -o hello
# 插桩与修复补丁的打法见 README.md；修复版编译为 rproxy-fixed
./run-all.sh          # 主矩阵 + GC 扫描 + 系统调用画像 + 长跑，约 35 分钟
python3 analyze.py    # 汇总表
./soak.sh rproxy 30 64        # 复现死锁（通常 10 秒内）
./soak.sh rproxy-fixed 120 256
```
