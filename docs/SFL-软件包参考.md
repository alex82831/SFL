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
# 从源码目录装(项目本地,压过全局)
sfl pkg install packages/mongodb --local

# 或先预编译再装到全局
cd packages/mongodb && sfl pkg build --bin . && sfl pkg install mongodb-0.1.0.sflpkg

sfl pkg list        # 看装了什么
```

装好后按名字引用:

```sfl
import "mongodb"
import "datetime"
```

| 包 | 用途 | 模块 |
| --- | --- | --- |
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
