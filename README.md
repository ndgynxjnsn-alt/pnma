# hyperscan-api

REST API around [Hyperscan](https://github.com/intel/hyperscan) (Ubuntu's `libhyperscan5`), called from Spring Boot through JNA.

## Run

```sh
docker build -t hyperscan-api .
docker run --rm -p 8080:8080 hyperscan-api
```

The image is `linux/amd64` only, because Hyperscan only supports x86-64 (SSSE3 or newer).

## API

Save (or replace) a search profile. Each pattern has a numeric id that you choose; scans report these ids.
The optional `flags` are `CASELESS`, `DOTALL`, `MULTILINE`, `ALLOWEMPTY`, `UTF8` and `UCP`.

```sh
curl -X PUT localhost:8080/api/profiles/secrets -H 'Content-Type: application/json' -d '{
  "patterns": [
    {"id": 3141592653, "expression": "secret-[0-9]{4}"},
    {"id": 27182818,   "expression": "password", "flags": ["CASELESS"]}
  ]}'
# {"name":"secrets","version":1,"patterns":[...]}
```

Scan content. The request body is scanned as raw bytes, whatever its content type:

```sh
curl -X POST localhost:8080/api/profiles/secrets/scan --data-binary 'My PASSWORD is secret-1234'
# {"profile":"secrets","version":1,"matches":[27182818,3141592653]}
```

`GET /api/profiles/{name}` returns a profile and `DELETE /api/profiles/{name}` removes it.

Errors are returned as `application/problem+json`:

| Status | Cause |
|---|---|
| 400 | Invalid profile, such as a regex Hyperscan rejects, a duplicate id or no patterns |
| 404 | Unknown profile |
| 413 | Content larger than `scan.max-content-bytes` |

## Concurrency

- Scans run on a dedicated pool of `min(CPU cores, 5)` threads (`ScanExecutorService`). Requests are
  handled asynchronously (`CompletableFuture`). Additional scans wait in an unbounded queue until a
  thread is free.
- Each profile's compiled database is guarded by a fair `ReentrantReadWriteLock` (`ProfileHolder`).
  Scans hold the read lock. Saving a profile compiles the new database without holding any lock, then
  takes the write lock only to swap the reference. That waits until in-flight scans have finished
  with the old database, which is then freed.
- Each database keeps a pool of Hyperscan scratch spaces, one per concurrent scan.

## Configuration

| Property | Default |
|---|---|
| `scan.max-concurrency` | `min(cores, 5)` |
| `scan.max-content-bytes` | `16MB` |
| `-Dhyperscan.library` | tries `hs`, then `libhs.so.5` |

## Tests

```sh
./mvnw test
```

The tests need libhs installed (`apt install libhyperscan5`).
