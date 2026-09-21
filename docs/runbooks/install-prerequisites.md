# Installation Prerequisites

## Local credential verification capacity

Local credentials use Argon2id with 384 MiB memory, four iterations, and parallelism one. Each hash uses a
16-byte salt and produces a 32-byte hash. Do not configure a setting below the absolute floor of 19 MiB memory,
two iterations, and parallelism one.

The runtime permits four concurrent credential hash operations, including hashing and verification. Saturation is
refused immediately; it is not queued. At the selected settings, four simultaneous verifications measured about
1.5 GiB of transient memory. Configure a
minimum 2 GiB JVM heap before enabling local credential login. A 1 GiB heap does not provide sufficient headroom.

Before go-live, rerun the commands in
[`006-argon2id-benchmark.md`](../plans/backend/evidence/006-argon2id-benchmark.md) section 2 on the target host and
tune based on those measurements. The benchmark host was an Apple Silicon development laptop, so its timings do not
establish production latency. The measured lower-memory alternative is 96 MiB memory, 12 iterations, and parallelism
one, at about 650 ms; selecting it is an owner posture decision and it must remain above the absolute floor.
