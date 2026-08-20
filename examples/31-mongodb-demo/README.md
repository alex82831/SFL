# 31 — MongoDB

The **mongodb** package (pure-SFL OP_MSG driver, SCRAM auth): insertMany
with automatic ObjectIds, `find` with operators/sort/projection, the
streaming cursor (`findIter`), `aggregate` pipelines, `createIndex`, and
the BSON wrapper types (`{"$oid"}`, `mongodb.date()`, `mongodb.binary()`) for
shapes SFL doesn't have natively. The test exercises the pure BSON
encode/decode layer with no server anywhere near, and adds a live round
trip when one answers.

## Run

```bash
sfl build setup
docker run --rm -p 27017:27017 mongo:7
sfl build run
sfl build test    # BSON checks always; live checks when a server answers
```

`MONGO_URL` points it elsewhere (auth: `mongodb://user:pass@host/db`).
