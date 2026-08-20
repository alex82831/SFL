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
| [`httpd`](#httpd) | HTTP 服务器框架(HTTP/1.1、HTTP/2、WebSocket、SSE、静态文件、TLS) | main, router, h1, h2, hpack, ws, mime |
| [`mongodb`](#mongodb) | MongoDB 数据库驱动(OP_MSG / BSON / SCRAM 认证) | main, bson, wire, scram, bytes |
| [`mysql`](#mysql) | MySQL / MariaDB 数据库驱动(文本协议、参数绑定、事务、TLS) | main, packets, auth |
| [`postgres`](#postgres) | PostgreSQL 数据库驱动(协议 3.0、$n 服务端参数、LISTEN/NOTIFY) | main, proto, sasl |
| [`redis`](#redis) | Redis 客户端(RESP2) | main, resp |
| [`datetime`](#datetime) | 历法运算、ISO 8601 解析与格式化、固定时区 | main |
| [`csv`](#csv) | RFC 4180 的 CSV 解析与生成 | main |
| [`toml`](#toml) | TOML 1.0 配置文件的解析与生成 | main |
| [`markdown`](#markdown) | Markdown 渲染为 HTML(GFM 表格、代码块、锚点) | main |
| [`template`](#template) | 逻辑无关的 {{mustache}} 模板 | main |
| [`jwt`](#jwt) | JSON Web Token(HS256 签发与校验) | main |
| [`smtp`](#smtp) | 发送邮件(STARTTLS、AUTH、MIME 构建) | main |
| [`uuid`](#uuid) | RFC 4122 标识符(v4 随机、v7 时间有序) | main |
| [`cli`](#cli) | 命令行参数解析,自动生成帮助 | main |
| [`log`](#log) | 分级、带时间戳的日志 | main |
| [`ansi`](#ansi) | 终端颜色与样式 | main |
| [`dotenv`](#dotenv) | .env 文件载入进程环境 | main |

配套的 [examples/](../examples/) 下有 32 个独立示例项目,每个包至少一个,
可用 IDEA / VSCode 直接打开运行。

字节即"0..255 整数数组"——`mongodb` 与 `redis` 的二进制协议就建立在
[SFL-使用手册.md](SFL-使用手册.md) §9 的字节内置函数
(`utf8Encode`/`socketReadBytes`/`digestBytes`/`hmacBytes`/`pbkdf2` 等)之上。

---

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

## mysql

MySQL / MariaDB 驱动,纯 SFL 说客户端/服务器协议(MySQL 5.7+ / 8、MariaDB)。
查询走文本协议,`?` 占位符在客户端按连接的转义规则绑定(字符串转义、数组展开成
IN 列表、`mysqlBinary(bytes)` 标记二进制),类型映射忠实:INT→整数、DECIMAL→
字符串(精确)、BLOB→字节数组、日期→字符串、NULL→null,无符号 BIGINT 溢出
64 位时以字符串返回。

```sfl
import "mysql"

val db = mysqlConnect({host: "127.0.0.1", user: "app", password: "secret", db: "shop"})
db.exec("INSERT INTO users (name) VALUES (?)", ["ada"])          // {affectedRows, lastInsertId, ...}
db.query("SELECT * FROM users WHERE id IN ? AND age > ?", [[1, 2, 3], 18])
db.transaction(() -> {
  db.exec("UPDATE accounts SET n = n - 1 WHERE id = ?", [1])
  db.exec("UPDATE accounts SET n = n + 1 WHERE id = ?", [2])
})
db.close()
```

**连接**:`mysqlConnect(target?, options?)`。`target` 是 `mysql://user:pass@host:3306/db`
URL、主机名或选项对象;选项认 `host` / `port` / `user` / `password` / `db` /
`timeoutMs` / `tls`(`true` 或 `{caFile: "..."}`,证书始终校验)/ `charset`
(整理序号,默认 45 = utf8mb4)。客户端由一把互斥量串行化,可跨线程共享
(事务期间独占连接)。

**认证**:`mysql_native_password` 与 `caching_sha2_password`(MySQL 8 默认)。
caching_sha2 的快路径走明文 TCP 即可;完整交换(服务器未缓存该账号时)需要
`tls`——本驱动不实现 RSA 密码交换,会以明确的错误提示。明文密码插件仅在 TLS
上应答。服务器的 `LOCAL INFILE` 请求一律拒绝。

**客户端对象**:

| 方法 | 说明 |
| --- | --- |
| `query(sql, params?)` | 行对象数组;列名作键 |
| `queryOne(sql, params?)` | 第一行或 `null` |
| `queryFull(sql, params?)` | `{columns: [{name, typeName, ...}], rows: [[...]]}`,列名冲突或要类型时用 |
| `queryMulti(sql, params?)` | 多结果集(CALL),每个 `{columns, rows}` 或 OK 信息 |
| `exec(sql, params?)` | `{affectedRows, lastInsertId, warnings, info}` |
| `begin/commit/rollback`、`transaction(fn)` | 事务;fn 抛错自动回滚并重抛 |
| `ping()` / `serverVersion()` / `threadId()` / `status()` | 往返毫秒、版本、线程号、`{inTransaction, autocommit}` |
| `selectDb(name)` / `escape(v)` / `close()` | 换库、手动转义一个字面量、关闭 |

## postgres

PostgreSQL 驱动,纯 SFL 说协议 3.0(PostgreSQL 10+)。`query()` 走扩展协议:
`$1..$n` 参数走带外,**任何东西都不会拼进 SQL 文本**;`exec()` 走简单协议,
一串可含多条语句(DDL 脚本)。类型映射:int2/4/8→整数、float→浮点、NUMERIC→
字符串(精确)、BYTEA→字节数组、BOOL→布尔、NULL→null,其余保持字符串。

```sfl
import "postgres"

val db = pgConnect({host: "127.0.0.1", user: "app", password: "secret", db: "shop"})
db.query("INSERT INTO users (name) VALUES ($1) RETURNING id", ["ada"])   // [{id: 1}]
db.query("SELECT * FROM users WHERE id = ANY($1::int[])", [pgArray([1, 2, 3])])
db.exec("BEGIN; UPDATE t SET n = n + 1; COMMIT")
db.listen("events")
db.waitNotification(5000)          // {pid, channel, payload} 或 null
db.close()
```

**连接**:`pgConnect(target?, options?)`。`target` 是 `postgres://` URL、主机名或
选项对象;选项认 `host` / `port` / `user` / `password` / `db` / `timeoutMs` /
`tls` / `appName` / `params`(额外启动参数)/ `allowCleartext`。

**认证**:SCRAM-SHA-256(带服务器签名校验)、MD5、明文(仅 TLS 上,或显式
`allowCleartext: true`)。TLS 经 SSLRequest 升级,证书始终校验(自签给
`{tls: {caFile: "..."}}`)。

**客户端对象**:`query` / `queryOne` / `queryFull`(带 `{columns, rows, tag,
affectedRows}`)/ `exec`(返回末条语句的 `{tag, affectedRows}`)/
`begin/commit/rollback` / `transaction(fn)` / `ping()` / `serverVersion()` /
`serverParams()` / `backendPid()` / `txStatus()`(I/T/E)/ `listen` /
`unlisten` / `notifications()` / `waitNotification(ms)` / `close()`。
数组参数用 `pgArray(values)` 造 `{...}` 字面量配 `ANY($1::int[])`;二进制用
`pgBinary(bytes)`。语句失败后连接照常可用(错误在 ReadyForQuery 之后才抛)。
COPY FROM STDIN 一律拒绝,COPY TO STDOUT 被排空。

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

## toml

TOML 1.0 配置文件:`tomlParse(text)` 与 `tomlStringify(obj)`。

```sfl
import "toml"

val cfg = tomlParse(readFile("app.toml"))
cfg.server.port                       // [server] 表的 port
writeFile("out.toml", tomlStringify({server: {port: 8080}}))
```

覆盖的语法:裸/引号/点分键,基本与字面字符串的单行、多行形态(完整转义集、
行尾反斜杠续行),整数(下划线、0x/0o/0b)、浮点(指数、inf、nan)、布尔,
数组(多行、尾逗号、内嵌注释)、内联表、`[表]` 与 `[[表数组]]`、`#` 注释。
日期时间按其字面量以**字符串**返回(SFL 没有日期类型;`dtParse` 能直接吃)。
重复键与重复定义表按规范报错,错误带行号。`tomlStringify` 先标量后子表
(TOML 要求的顺序),字符串按需转义,键按需加引号;TOML 没有 null——遇到即报错。

## markdown

Markdown 渲染为 HTML,实用子集。

```sfl
import "markdown"

mdToHtml("# Title\n\nSome *emphasis* and `code`.")
mdToHtml(untrusted)                    // 原始 HTML 默认转义,注入不进来
mdToHtml(trusted, {allowHtml: true})   // 信任的输入放行 HTML
mdInline("**加粗**")                    // 只做行内(标题、表格单元格用)
```

块级:`#`…`######` 标题(自动生成 slug 锚点 id)、段落(行尾两个空格硬换行)、
``` 围栏代码(语言名进 `class="language-x"`)、`>` 引用(可嵌套)、`-`/`*`/`+`
与 `1.` 列表(缩进嵌套、紧凑项)、`---` 分割线、GFM 表格(`:---:` 对齐)。
行内:`**strong**`、`*em*`、`` `code` ``、`~~del~~`、链接、图片、
`<https://autolink>`、反斜杠转义。不做:setext 标题、缩进代码块、引用式链接。

## template

逻辑无关的 {{mustache}} 模板。

```sfl
import "template"

tplRender("Hello, {{name}}!", {name: "ada"})
tplRender("{{#items}}{{n}} {{/items}}", {items: [{n: 1}, {n: 2}]})   // "1 2 "
val page = tplCompile(readFile("page.html"))   // 编译一次,渲染多次
page(data, {partials: {row: "<li>{{.}}</li>"}})
```

说 mustache 的:`{{name}}` 插值(HTML 转义)、`{{{raw}}}` / `{{& raw}}` 原样、
点分路径与 `{{.}}`、`{{#节}}`(数组迭代、真值压栈、函数先调用)、`{{^反节}}`、
`{{! 注释}}`、`{{> partial}}`(来自 opts.partials,循环有深度上限)。独占一行
的块标签连行一起消失,块状排版保持干净。名字查找沿上下文栈向外走;缺失的名字
渲染为空,不报错。`tplEscape(s)` 单独可用。不支持自定义分隔符。

## jwt

JSON Web Token(RFC 7519),HMAC-SHA-256 签名。

```sfl
import "jwt"

val token = jwtSign({sub: "user-1", exp: timeMillis() / 1000 + 3600}, "secret")
val claims = jwtVerify(token, "secret")    // 伪造、过期都抛错
jwtDecode(token)                            // 只解不验,{header, payload, signature}
```

只提供 HS256(运行时的摘要是 md5/sha1/sha256,非对称一族需要本包没有的
RSA/EC)。`jwtVerify` 先整体验签(恒定路径比较)再看时间声明:`exp` / `nbf`
(Unix 秒,`leewaySec` 给时钟偏差),可选 `issuer` / `audience`(`aud` 字符串
或数组皆可),`atSec` 指定"现在"(测试用)。`alg: "none"` 与一切非 HS256 直接
拒绝。密钥是字符串或字节数组;`opts.header` 可加 `kid` 等头字段。

## smtp

发送邮件:SMTP 客户端加 MIME 构建。

```sfl
import "smtp"

val m = smtpConnect("smtp.example.com", 587, {
  tls: "starttls", user: "app@example.com", password: "secret"
})
m.send({
  from: "App <app@example.com>", to: "ada@example.org",
  subject: "Hello 你好", text: "plain", html: "<p>rich</p>",
  attachments: [{name: "data.csv", contentType: "text/csv", text: "a,b\n"}]
})
m.quit()
```

TLS:`"starttls"`(587 的路数)在 EHLO 后升级,`"implicit"`(465)先包一层;
证书始终校验,自签给 `caFile`。AUTH(PLAIN / LOGIN,按服务器所报)在明文连接
上拒绝,除非 `allowInsecureAuth: true`;凭据不进错误文本。邮件对象认 `from` /
`to` / `cc` / `bcc`(bcc 只进信封不进信头)/ `replyTo` / `subject`(非 ASCII
自动编码词)/ `text` / `html`(两者都给出 multipart/alternative)/ `headers` /
`attachments`(`bytes` 或 `text`,base64 折行)。点填充、CRLF、Message-ID 都由
构建器负责;`smtpBuildMime(mail)` 单独可用(预览、测试)。地址里的换行一律拒收
(信头注入的路)。其余:`noop()`、`capabilities()`、`quit()`。

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

## ansi

终端颜色与样式,纯字符串函数。

```sfl
import "ansi"

println(ansiGreen("ok") + " " + ansiBold("42ms"))
println(ansiStyle("warning", "yellow", "bold", "underline"))
ansiRgb("orange", 255, 136, 0)      // 24 位;ansi256(text, n) 是 256 色
```

进程级开关:默认开,`NO_COLOR` 环境变量或 `TERM=dumb` 时自动关;
`ansiSetEnabled(false)` 让所有函数退化为恒等——库代码放心着色,调用方一处决定。
快捷函数 `ansiRed/Green/Yellow/Blue/Magenta/Cyan/Gray/Bold/Dim/Underline`,
全表见 `ansiCodes()`。`ansiStrip(s)` 剥掉任何来源的 ANSI 序列。

## dotenv

`.env` 文件:KEY=VALUE 进程环境。

```sfl
import "dotenv"

dotenvLoad()                        // 读 ./.env(不存在则安静返回 {})
getEnv("DATABASE_URL")
dotenvParse(text)                   // 只解析,不碰环境
```

格式取 dotenv 家族的公约数:每行一个 KEY=VALUE、`#` 注释(整行或未引值尾部)、
可选 `export ` 前缀、单引号字面量、双引号带 `\n \r \t \\ \"` 转义、未引值去
首尾空白。**真实环境变量默认压过文件**(部署覆盖开发者提交的默认值),
`{override: true}` 反过来。
