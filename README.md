# hyperscan-api

REST API around [Hyperscan](https://github.com/intel/hyperscan) (Ubuntu's `libhyperscan5`), called from Spring Boot through JNA.

## Run

```sh
docker build -t hyperscan-api .
docker run --rm -p 8080:8080 hyperscan-api
```

The image is `linux/amd64` only, because Hyperscan only supports x86-64 (SSSE3 or newer).

## API

There is a single search profile. Saving it compiles a new database that replaces the active one.
Each pattern has a numeric id that you choose; scans report these ids.
The optional `flags` are `CASELESS`, `DOTALL`, `MULTILINE`, `ALLOWEMPTY`, `UTF8` and `UCP`.

```sh
curl -X PUT localhost:8080/api/profile -H 'Content-Type: application/json' -d '{
  "patterns": [
    {"id": 3141592653, "expression": "secret-[0-9]{4}"},
    {"id": 27182818,   "expression": "password", "flags": ["CASELESS"]}
  ]}'
# {"version":1,"patterns":[...]}
```

Scan content. The request body is scanned as raw bytes, whatever its content type:

```sh
curl -X POST localhost:8080/api/scan --data-binary 'My PASSWORD is secret-1234'
# {"version":1,"matches":[27182818,3141592653]}
```

`GET /api/profile` returns the active profile.

Errors are returned as `application/problem+json`:

| Status | Cause |
|---|---|
| 400 | Invalid profile, such as a regex Hyperscan rejects, a duplicate id or no patterns |
| 404 | No profile has been saved yet |
| 413 | Content larger than `scan.max-content-bytes` |

## Concurrency

- Scans run on a dedicated pool with one thread per CPU core (`ScanExecutorService`). Requests are
  handled asynchronously (`CompletableFuture`). Additional scans wait in an unbounded queue until a
  thread is free.
- The active database is guarded by a fair `ReentrantReadWriteLock` (`ProfileHolder`).
  Scans hold the read lock. Saving the profile compiles the new database without holding any lock, then
  takes the write lock only to swap the reference. That waits until in-flight scans have finished
  with the old database, which is then freed.
- Each scan thread has one Hyperscan scratch space in a `ThreadLocal`. Before each scan,
  `hs_alloc_scratch` grows it if the database needs more, so one scratch space serves every database
  version. Threads free their scratch space when they exit.

## Configuration

| Property | Default |
|---|---|
| `scan.max-concurrency` | number of CPU cores |
| `scan.max-content-bytes` | `16MB` |
| `-Dhyperscan.library` | tries `hs`, then `libhs.so.5` |

## Tests

```sh
./mvnw test
```

The tests need libhs installed (`apt install libhyperscan5`).

`HyperscanApiStressTests` (about 100 s) drives the HTTP API from up to 48 concurrent clients. While the
clients scan, the tests swap profiles, save concurrently, submit patterns Hyperscan rejects and send
edge-case content. Every scan must report exactly the ids that the database version named in its
response produces. The rule sets in `RuleSets` are built to be hard on Hyperscan:

- literal-free character classes, which scan about 400 times slower than literal patterns;
- overlapping bounded repeats, which take seconds to compile;
- Unicode, NUL bytes, ReDoS shapes and ids beyond 2^53.

Surefire runs tests with `GLIBC_TUNABLES=glibc.malloc.perturb=165`. glibc then overwrites freed memory,
so a scan that uses an already freed database crashes instead of silently returning correct results.
