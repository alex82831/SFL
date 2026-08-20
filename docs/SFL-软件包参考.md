# SFL 标准软件包参考

`packages/` 下是一批用纯 SFL 写成的常用软件包,覆盖数据库、日期、编解码、命令行等
开箱即用的场景。它们不是内置在二进制里的标准库,而是独立的**可安装包**——用
`sfl pkg install` 装,用 `import` 引用,和你自己写的包地位完全一样。

每个包都随源码分发,因此**解释器直接跑源码**;`sfl pkg build --bin` 还能为当前平台
预编译一份二进制归档随源码一起分发,让使用方 `sfl -c` 时免于重编包源码(细节见
[SFL-项目与构建指南.md](SFL-项目与构建指南.md) §9.2 与
[SFL-使用手册.md](SFL-使用手册.md) §5.13)。

## 安装

```bash
# 从 GitHub 直接装(registry 短名,解析到官方仓库对应子目录)
sfl install mongodb

# 或指定 git 源与子目录(任意仓库;私有仓库走你的 SSH key)
sfl install github.com/alex82831/SFL/packages/mongodb
sfl install git@github.com:alex82831/SFL.git#packages/datetime

# 从本地源码目录装(项目本地,压过全局)
sfl install packages/mongodb --local

# 或先预编译再装
cd packages/mongodb && sfl pkg build --bin . && sfl install mongodb-0.1.0.sflpkg

sfl pkg list        # 看装了什么
```

`sfl install`(即 `sfl pkg install`)的来源可以是本地目录、`.sflpkg`、git 仓库
(`github.com/owner/repo[/子目录][@ref]`,或 `#子目录` 片段)或 registry 短名;
传输走 `git clone`,详见 [SFL-使用手册.md](SFL-使用手册.md) §5.13「从 git 仓库或
registry 安装」。

装好后按名字引用:

```sfl
import "mongodb"
import "datetime"
```

| 包 | 用途 | 模块 |
| --- | --- | --- |
| [`gui`](#gui) | 跨平台异步 GUI 框架(响应式信号、完整部件库、增量渲染、原生感窗口) | main, _core, _widgets, _render, _app, _client, _demo |
| [`httpd`](#httpd) | HTTP 服务器框架(HTTP/1.1、HTTP/2、WebSocket、SSE、静态文件、TLS) | main, router, h1, h2, hpack, ws, mime |
| [`mongodb`](#mongodb) | MongoDB 数据库驱动(OP_MSG / BSON / SCRAM 认证) | main, bson, wire, scram, bytes |
| [`datetime`](#datetime) | 历法运算、ISO 8601 解析与格式化、固定时区 | main |
| [`csv`](#csv) | RFC 4180 的 CSV 解析与生成 | main |
| [`uuid`](#uuid) | RFC 4122 标识符(v4 随机、v7 时间有序) | main |
| [`cli`](#cli) | 命令行参数解析,自动生成帮助 | main |
| [`log`](#log) | 分级、带时间戳的日志 | main |
| [`redis`](#redis) | Redis 客户端(RESP2) | main, resp |

字节即"0..255 整数数组"——`mongodb` 与 `redis` 的二进制协议就建立在
[SFL-使用手册.md](SFL-使用手册.md) §9 的字节内置函数
(`utf8Encode`/`socketReadBytes`/`digestBytes`/`hmacBytes`/`pbkdf2` 等)之上。

---

## gui

跨平台的**异步 GUI 框架**,纯 SFL 写成,依赖 `httpd`。界面由本进程的一个嵌入式
Web 运行时渲染——`run()` 起一个本机回环服务,在 **macOS / Linux / Windows** 上
打开一个原生感觉的窗口(装有 Chrome/Edge/Chromium 时用其 app 模式,无边栏无地址
栏;否则退回默认浏览器),此后服务端保存真实的部件树与全部状态,浏览器只是一块
远端画布:事件上行、增量补丁下行,全部走一条 WebSocket。

```sfl
import "gui"

def counter(ctx) {
  val n = signal(0)
  column([
    heading(() -> "点了 ${n.get()} 次"),
    row([
      button("+1", {kind: "primary", onClick: () -> n.update(v -> v + 1)}),
      button("清零", {onClick: () -> n.set(0), disabled: () -> n.get() == 0})
    ], {gap: 8})
  ], {gap: 12})
}

guiApp({title: "计数器", root: counter}).run()
```

先睹为快:`sfl -e 'import "gui"; guiDemo()'` 打开一个六页的全功能演示。

### 响应式内核

状态是**信号**;凡是接受值的地方——属性或文本——都同样接受信号、`computed`
或零参函数,变化沿依赖图精确传播,只有真正变了的属性/文本会生成补丁:

```sfl
val name = signal("Ada")                    // .get() .set(v) .update(f)
val greet = computed(() -> "你好,${name.get()}")   // 惰性派生值
effect(() -> println(greet.get()))          // 立即运行,依赖变化时重跑
batch(() -> { a.set(1); b.set(2) })         // 合并成一次交付
text(greet)                                 // 信号当值用,自动订阅
textInput({bind: name})                     // 双向绑定:值下行、输入上行
```

`signal` 对原始值做去重(重复 set 免费);数组与对象**总是通知**,因此
`items.mutate(xs -> push(xs, x))` 原地改完即刷新。信号线程安全:后台线程
`set` 会经通道唤醒应用的 UI 循环,补丁照常发出——这就是 `spawn`/`ctx.task`
里直接改状态即可刷新界面的原因。

### 部件一览

布局 `row` `column` `grid` `card` `scrollArea` `spacer` `divider` `expansion` `tabs`;
文本与展示 `text` `heading` `link` `image` `icon` `badge` `markdownView` `htmlView`
`codeView` `progressBar` `spinner` `table`(可排序) `treeView` `canvasView`;
输入 `button` `textInput` `textArea` `numberInput` `checkbox` `toggle` `radioGroup`
`dropdown` `slider` `colorInput` `dateInput` `timeInput` `fileUpload`;
浮层 `dialog` `drawer` `menuButton`,右键菜单挂在任意部件的 `contextMenu` 属性上;
动态结构 `each`(带键调和) `when` `dyn`;逃生舱 `element(tag, props, children)`。

每个部件都吃一批通用属性:`id` `style` `class` `hidden` `tooltip` `gap` `pad`
`w` `h` `grow` `wrap` `align` `justify` `size` `color` `bg` `bold` `mono` `muted`
`round` `shadow` `border` `transition`(fade/slide-up/slide-down/zoom 出场动画)
以及 `onClick` 等事件。事件处理器写 `() -> …` 或 `e -> …` 都行。

列表用 `each`,靠键复用子树,增删移动都是最小补丁;行内状态直接把信号放进行
数据里即可跨刷新存活:

```sfl
each(todos, (it) -> row([checkbox("", {bind: it.done}), text(it.title)]),
     {key: (it) -> it.id})
```

### 应用、会话与页面

`guiApp(opts)` 的选项:`title` `root`(或 `pages: {"/": …, "/settings": …}`)
`port` `host` `theme`(CSS 变量覆盖,如 `{accent: "#e2596a", radius: "4px"}`)
`css`(附加样式) `dark`(true/false/"auto") `window: {width, height}` `assets`
(静态目录映射) `show` `keepAlive` `quiet` `onConnect` `onDisconnect`
`maxUploadBytes` `graceMs`。

`app.run()` 启动、开窗、阻塞到最后一个窗口关闭;`app.start()`/`app.stop()` 是
异步形态;还有 `app.url()` `app.port()` `app.post(fn)`(投递到 UI 循环)
`app.broadcast(fn)`(对每个活跃会话执行 `fn(ctx)`)与应用级 `app.timer` /
`app.after` / `app.cancel`。

**每个浏览器窗口是一个独立会话**:根构造函数按会话执行一次,里面创建的信号
互不相干;模块级信号则被所有会话共享,一处 `set`,处处刷新——多窗口协作
(聊天、看板)不需要任何额外机制。构造函数收到的 `ctx`:

| ctx 成员 | 作用 |
| --- | --- |
| `notify(msg, {kind, ms}?)` | 右上角 toast(info/ok/warn/error) |
| `interval(ms, fn)` `timeout(ms, fn)` `cancel(id)` | 会话级定时器,断开自动清理 |
| `task(work, onDone?)` | 后台线程跑 `work()`,完成后把结果送回 UI 循环 |
| `effect(fn)` | 在 UI 循环上重跑的响应式副作用,会话结束自动释放 |
| `download(name, content, mime?)` | 触发浏览器下载(字符串或字节数组) |
| `clipboard(text)` `setTitle(t)` `setDark(v)` | 剪贴板/标题/主题 |
| `navigate(path)` `openWindow(path?)` `close()` | 页内跳转/再开一窗/关窗 |
| `hotkey("mod+s", fn)` | 全局快捷键(mod = Ctrl/Cmd) |
| `focus(id)` `scrollTo(id)` `runJs(code)` | 按 `id` 属性定位/JS 逃生舱 |
| `session` `path` `query` `client` `app` | 会话元信息与 app 句柄 |

文件上行走 `fileUpload({onUpload: files -> …})`,`fileBytes(f)`/`fileText(f)`
解码内容;`canvasView({draw: () -> [["fillRect", …], …], onPointer: p -> …})`
是立即模式画布,绘制指令响应式重发,指针事件带坐标回传,足以做图表、绘图板
和小游戏(演示的 Canvas 页就是一只表盘和一块画板)。

### 安全与形态

服务默认只绑 `127.0.0.1`,页面带一次性令牌,WebSocket 握手校验令牌,防的是
恶意网页对本机端口的盲连。`SFL_GUI_BROWSER` 指定浏览器,`SFL_GUI_NO_WINDOW=1`
只服务不开窗;`show: false` + `keepAlive: true` 即是一个多用户 Web 应用——
同一份代码,本地窗口与远程浏览器通吃。模块:`main` 加下划线内部模块
`_core` `_widgets` `_render` `_app` `_client`(内嵌浏览器运行时) `_demo`
(下划线文件名保证运行目录里的同名文件永远不会遮蔽包的内部导入)。

与语言的其它部分一样,gui 程序解释执行与 `sfl -c` 编译执行行为一致;测试
`tests/gui/` 以真实端口 + 手写 WebSocket 客户端逐补丁断言(95 项),并对整个
会话做解释/编译逐字节差分。

## httpd

HTTP 服务器框架,稳定与性能优先,纯 SFL 建立在 socket/字节/TLS 原语之上。

```sfl
import "httpd"

val srv = httpServer({
  port: 8080,
  handler: routes([
    GET("/", (req) -> respondText(200, "hello")),
    GET("/users/:id", (req) -> respondJson(200, {id: req.params.id})),
    STATIC("/assets", "./public"),
    WS("/echo", {message: (sock, data, isText) -> sock.send(data)})
  ])
})
srv.listen()          // 同步:阻塞直到 srv.stop()
// 或 srv.start() 异步接入,srv.stop() 停止接入、放空在途连接后关闭
```

**协议**:HTTP/1.1(keep-alive、双向 chunked、`Expect: 100-continue`、静态文件的条件
与 Range 请求)、HTTP/2(明文 prior-knowledge / h2c 升级,TLS 下经 ALPN;多路复用、
HPACK、流量控制)、WebSocket(RFC 6455)、SSE(`respondEvents`)。TLS 配置
`{tls: {cert: "...", key: "..."}}`(PEM 文件;暂无 mTLS)。

**并发模型即语言的模型**:每连接一线程、每 HTTP/2 流一线程,handler 可自由阻塞,
与 `spawn`/channel/`await` 自然组合。模块:`main`(服务器与响应助手)、`router`、
`h1`、`h2`、`hpack`、`ws`、`mime`。测试:`tests/httpd.sfl` 40 项全协议驱动。

## mongodb

MongoDB 驱动,纯 SFL 说 OP_MSG 协议(MongoDB 3.6+)。值与 BSON 自然对应,
认证走 SCRAM-SHA-256 / SCRAM-SHA-1。

```sfl
import "mongodb"

val client = mongoConnect("mongodb://localhost:27017/app")
val users  = client.db("app").coll("users")

users.insertOne({name: "ada", age: 36, tags: ["math"]})
val grown = users.find({age: {"$gte": 18}}, {sort: {age: -1}, limit: 10})
users.updateOne({name: "ada"}, {"$set": {age: 37}})
println(users.countDocuments({}))
client.close()
```

**连接**:`mongoConnect(target?, options?)`。`target` 是 `mongodb://` URL 或主机名;
`options` 覆盖 URL 里的项,认得 `db` / `user` / `password` / `authSource` /
`authMechanism` / `appName` / `timeoutMs`。密码里的特殊字符在 URL 里按百分号转义。
客户端由一把互斥量串行化,可跨线程共享。

**客户端对象**:`db(name)`、`coll(name)`(默认库)、`runCommand(db, cmd)`、
`ping()`(往返毫秒)、`serverVersion()`、`listDatabases()`、`close()`。

**库对象**(`client.db(name)`):`coll(name)`、`runCommand(cmd)`、
`listCollections(filter?)`、`collectionNames()`、`createCollection(name, opts?)`、
`dropDatabase()`。

**集合对象**(`db.coll(name)`):

| 方法 | 说明 |
| --- | --- |
| `insertOne(doc, opts?)` / `insertMany(docs, opts?)` | 插入,缺 `_id` 自动补 ObjectId |
| `find(filter?, opts?)` | 取数组;`opts` 认 `sort`/`projection`/`skip`/`limit`/`batchSize` 等 |
| `findOne(filter?, opts?)` | 取一条或 `null` |
| `findIter(filter?, opts?)` | 流式游标:`hasNext()`/`next()`/`toArray()`/`forEach(f)`/`close()` |
| `updateOne` / `updateMany` / `replaceOne` | `{matchedCount, modifiedCount, upsertedId?}` |
| `deleteOne(filter)` / `deleteMany(filter)` | `{deletedCount}` |
| `countDocuments(filter?)` / `estimatedCount()` | 计数 |
| `aggregate(pipeline, opts?)` | 聚合,返回数组 |
| `distinct(key, filter?)` | 去重取值 |
| `createIndex(keySpec, opts?)` / `dropIndex(name)` / `listIndexes()` | 索引 |
| `drop()` | 删集合,不存在返回 `false` |

**BSON 扩展类型**(`import "mongodb"` 或 `import "mongodb/bson"`):SFL 没有原生形状
的类型走单键包装对象,可手写也可读回:

```sfl
{"$oid": "663d..."}                     // ObjectId,newObjectId() 造一个
{"$date": 1723972000000}                // UTC 毫秒,mongoDate() 造
{"$binary": [1,2,3], "$subtype": 0}     // 二进制,mongoBinary() 造
{"$regex": "^a", "$options": "i"}       // 正则,mongoRegex() 造
{"$timestamp": {"t": 秒, "i": 序号}}
{"$decimal128": "<32位十六进制>"}
{"$minKey": true} / {"$maxKey": true}
{"$int32": n} / {"$int64": n}           // 强制整数宽度
```

`bsonEncode(doc)` → 字节数组,`bsonDecode(bytes, at?)` → 对象;
`newObjectId()`、`oidTimestamp(oid)`、`mongoDate(ms?)`、`mongoBinary(bytes, sub?)`、
`mongoRegex(pat, opts?)`。整数按能否放进 int32 自动选 int32/int64,读回都是普通整数。

**尚不支持**:TLS(连本机、内网或走隧道)、副本集主机列表。

## datetime

历法运算全用纪元毫秒上的整数完成;时区是"东向 UTC 的分钟偏移"这种固定偏移
(没有夏令时库——机器本地时区用内置的 `formatTime`/`timeParts`)。

```sfl
import "datetime"

val t = dtParse("2026-08-19T15:30:00+08:00")   // → 纪元毫秒
dtIso(t)                                        // "2026-08-19T07:30:00Z"
dtIso(dtAddMonths(t, 6), 480)                   // 半年后,以 UTC+8 显示
dtDiffDays(dtParse("2026-12-25"), t)            // 到圣诞还有几天
```

- **造/拆**:`dtMake(y, mo, d, h?, mi?, s?, ms?, offset?)`、
  `dtParts(ms, offset?)` → `{year, month, day, hour, minute, second, ms, weekday(1=周一),
  yearday, offsetMinutes}`。
- **ISO 8601**:`dtIso(ms, offset?)`、`dtIsoDate(ms, offset?)`、`dtParse(text)`
  (认 `2026-08-19`、`...T15:30`、`...:00`、`...:00.250`,尾部可带 `Z` 或 `±hh:mm`)。
- **运算**:`dtAddDays/Hours/Minutes/Seconds`、`dtAddMonths`(月末自动钳位:1 月 31 日
  加一月是 2 月 28/29 日)、`dtAddYears`;`dtDiffDays/Hours/Minutes/Seconds`;
  `dtStartOfDay/Month/Year`。
- **历法**:`dtIsLeapYear(y)`、`dtDaysInMonth(y, mo)`、`dtDaysFromCivil`/`dtCivilFromDays`。

## csv

RFC 4180:含分隔符、引号或换行的字段自动加引号,引号内的引号翻倍。单元格一律解析
成字符串——数字是什么由调用方决定。

```sfl
import "csv"

csvParse("a,b\n1,2")               // [["a","b"],["1","2"]]
csvParseObjects("a,b\n1,2")        // [{a:"1", b:"2"}]
csvStringify([["x", "y,z"]])       // "x,\"y,z\"\n"
csvStringifyObjects([{a:1, b:2}])  // "a,b\n1,2\n"
```

`opts` 认 `{sep: ";"}` 换分隔符;`csvStringifyObjects` 认 `{columns: [...]}` 指定列序。

## uuid

RFC 4122 标识符。

```sfl
import "uuid"

uuid4()               // "8f14e45f-ceea-467f-a0e6-6ffbc0eafd6a" 随机
uuid7()               // 时间有序,适合做数据库主键
uuidValidate(s)       // 是否合法 UUID
uuidVersion(s)        // 4、7 ...
```

还有 `uuidNil()`、`uuidToBytes(s)`/`uuidFromBytes(b)`。v7 前 48 位是纪元毫秒,按创建
时间排序,对索引友好。

## cli

命令行参数解析,自动生成帮助。

```sfl
import "cli"

val spec = {
  name: "greet", doc: "Greets people.",
  flags:   {loud: {short: "l", doc: "大声点"}},
  options: {times: {short: "n", doc: "重复几次", default: "1"}},
  positionals: [{name: "who", required: true}, {name: "rest", variadic: true}]
}
val got = cliParse(spec)   // 解析 args();遇到 -h/--help 打印帮助并退出
// got = {flags: {loud: bool}, options: {times: "3"}, positionals: {who: "x", rest: [...]}}
```

认 `--flag`、`--opt v`、`--opt=v`、`-s`、`-s v`、成串短标志 `-ln5`,`--` 结束选项解析。
`cliParse(spec, argv?)` 带退出与打印;`cliParseArgs(spec, argv)` 是纯函数版(只抛错、
不打印,测试用);`cliHelp(spec)` 单出帮助文本。

## log

分级、带时间戳的日志,默认写 stderr(让程序的正常输出保持干净)。

```sfl
import "log"

logInfo("started", {port: 8080})   // 2026-08-19 15:30:00 INFO started {"port": 8080}
logSetLevel("warn")                // debug | info | warn | error | off
logWarn("careful"); logError("boom")
```

`logDebug/logInfo/logWarn/logError(...parts)`(多参照 println 渲染、空格连接);
`logSetLevel(name)`/`logLevel()`;`logSetFile(path)` 同时追加到文件;
`logSetSink(fn)` 把行交给函数(测试或转发用)。级别、文件、sink 是进程级的。

## redis

Redis 客户端,说 RESP2。回复自然映射:简单/批量字符串→字符串、整数→整数、
数组→数组、nil→null,服务器 `-ERR` 抛错。命令由一把互斥量串行化,可跨线程共享。

```sfl
import "redis"

val r = redisConnect("127.0.0.1", 6379)
r.set("greeting", "hello")
r.get("greeting")                    // "hello"
r.cmd("INCRBY", "counter", 5)        // 任意命令,一参一个
r.hGetAll("user:1")                  // → 对象
r.close()
```

常用封装:字符串 `get/set/setEx/del/incr/incrBy/decr/expire/ttl/keys/exists`;
哈希 `hSet/hGet/hDel/hGetAll`;列表 `lPush/rPush/lPop/rPop/lRange/lLen`;
集合 `sAdd/sRem/sMembers/sIsMember`;有序集合 `zAdd/zRange`;
`ping/flushDb/dbSize/publish`。还有 `cmd(...)` 发任意命令、`pipeline(commands)`
一趟往返、`subscribe(channels, handler)` 订阅。字节安全:值里有换行、二进制也能过。

`redisConnect(host?, port?, opts?)` 的 `opts` 认 `timeoutMs`/`password`/`user`/`db`。
