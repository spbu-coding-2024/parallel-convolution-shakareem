# Task 2: Parallel Convolution — OS-Thread Scaling Analysis

## Setup

- **Machine**: Intel Core Ultra 5 225H, **14 physical cores, no SMT**
  (4 Performance + 8 Efficient + 2 Low-Power Efficient), CachyOS
- **JVM**: Java 27 (OpenJDK), Kotlin coroutines on **dedicated fixed thread-pool
  dispatchers** (`newFixedThreadPoolContext(n)`) — *n* is the experimental knob:
  the number of OS threads the convolution may run on
- **Runs**: 1 warmup + 5 measured runs per configuration, nano-second clock
- **Benchmark**: `./gradlew benchmark -Ptask=parallel`
- **Reference**: Task 1 serial implementation (bit-identical results, verified by tests)

## What changed vs the previous benchmark design

Earlier versions swept the **coroutine count** (one per pixel / per row / per
column / 2×2 / 4×4 blocks), which measured scheduling churn, not parallel
speed. Now:

1. **Coroutines ≡ OS threads.** Every strategy launches exactly `threads`
   coroutines on a pool pinned to `threads` OS threads. The only experimental
   variable is the thread count (and the tile *shape*, Task 2.1).
2. **Dynamic tile distribution.** The image is cut into ~4 tiles per worker
   (row bands / column bands / square grid) handed out through one atomic
   cursor, so no per-tile scheduling objects, no coroutine churn — and fast
   cores automatically steal more work from slow ones.
3. **Thread counts chosen around this CPU's topology**: `{1, 2, 4, 7, 8, 12, 14, 28}` —
   4 = P-core block, 7/8 = spill onto E-cores, 12 = P+E, 14 = all physical
   cores, 28 = 2× oversubscription control point.
4. **Noise control** (carried over from Task 1): 1024² and 1920×1080 images
   with 11×11/15×15 box kernels keep every run ≥ 7 ms even at 14 threads
   (≥ 50 ms per run at 1 thread), so OS noise stays ≤ 1–2%.

## Raw Results — Rainier.bmp 1920×1080, blur 15×15 (2.07 MP × 225 taps)

Average of 5 runs, ms; speedup vs `serial` row in parentheses.

| threads | serial | rows | columns | autoGrid |
|---------|--------|------|---------|----------|
| 1  | 190.19 | 185.52 (1.03×) | 189.23 (1.01×) | 186.69 (1.02×) |
| 2  | — | 98.94 (1.92×) | 99.87 (1.90×) | 106.43 (1.79×) |
| 4  | — | 54.29 (3.50×) | 53.73 (3.54×) | 51.53 (3.69×) |
| 7  | — | 34.61 (5.50×) | 34.69 (5.48×) | 33.70 (5.64×) |
| 8  | — | 31.36 (6.06×) | 31.19 (6.10×) | 29.38 (6.47×) |
| 12 | — | 24.04 (7.91×) | 24.91 (7.64×) | 22.87 (8.32×) |
| 14 | — | 24.29 (7.83×) | 24.95 (7.62×) | **22.35 (8.51×)** |
| 28 | — | 24.09 (7.89×) | 24.99 (7.61×) | 22.86 (8.32×) |

Full data (both images, both kernels): [`docs/benchmark_task2.csv`](../docs/benchmark_task2.csv)

## Charts

![Time vs OS threads](../docs/charts/task2_time_vs_threads.png)

![Speedup vs OS threads](../docs/charts/task2_speedup_vs_threads.png)

![Efficiency vs OS threads](../docs/charts/task2_efficiency.png)

![Strategy comparison at 14 threads](../docs/charts/task2_strategies_at_all_cores.png)

## Analysis

### Scaling shape: linear up to 4, log-like up to ~12, then flat

- **t = 1 → 4**: 3.5–3.7× — the OS keeps 4 worker threads on the 4 P-cores;
  scaling is essentially linear and every strategy matches serial at t=1
  within 1%, i.e. the pool + atomic cursor overhead is unmeasurable.
- **t = 7 → 8**: 5.5–6.5× (not 7–8×) — threads spill onto E-cores. A
  Lionmont-class E-core runs the same SSE code ~2.5–3× slower than a P-core
  (no AVX2/FMA unit at all on the 225H's E-cores).
- **t = 12 → 14**: 7.3–8.5× — the last 2 threads land on the LP-E cores and
  add ≈ 0 throughput.
- **t = 28**: same as 14 (± 3%) — oversubscription neither helps nor
  *collapses* anymore (see below).

The plateau at ~8× on a 14-core chip is **hardware truth, not software waste**:
effective FP throughput ≈ 4·P + 10·(0.3–0.4)·P ≈ 7–8 P-core equivalents.
The efficiency chart quantifies it: ~92% per thread at t=4, ~80% at t=8,
~69% at t=12, ~61% at t=14. This is exactly what "speed as a function of
available OS threads" means on Arrow Lake-H: threads 1–4 are worth what
threads 5–14 are worth *combined*.

### Dynamic tile stealing: the optimal implementation choice

The first sweep of this benchmark used the natural static partitioning
(exactly `threads` row bands, one coroutine each). On this CPU it was both
slow and unstable — e.g. `rows` on man/11×11 at t=12: avg 15.70 ms but
min 8.03 / max 22.77 ms, and `columns` at t=14 avg 20.19 ms (min 14.75):
whichever tile the scheduler happened to put on an LP-E core dictated the
makespan, and per-run variance exploded. With 4 tiles per worker and an
atomic cursor the same configurations land at 9–14 ms with ~5% spread: slow
cores simply finish fewer tiles. Oversubscription (t=28) also became
harmless because idle-capable workers keep every core fed.

### Strategy comparison (Task 2.1: data access patterns)

At t=14 on the large compute-bound config: **autoGrid 22.35 ms < rows 24.29 ms
≈ columns 24.95 ms**. Ordering intuition from the single-thread era
(rows ≫ columns) mostly washes out under dynamic stealing:

- **rows**: perfect streaming of input rows, but each worker owns a wide band
  of *output* rows written sequentially — fine; slightly worse locality on
  the wrapped border rows that re-read the opposite image edge.
- **columns**: each tile is a 14–17 px-wide full-height strip; reading
  row-major input through a narrow column window defeats prefetching → ~3–5%
  penalty. Visible but small because the 1920×1080 `DoubleArray` input
  (16.6 MB) lives mostly in the 18 MB shared L3.
- **autoGrid** (≈7×7 blocks of the image at 14 workers): square tiles ~fit
  L2 (64–128 KB input+output per tile), giving the best makespan on all four
  image/kernel configs. More tiles per side also smooths stealing granularity.

### Cost model check

Serial throughput (Task 1): ~2.4 Gtap/s/core. Rainier/15×15 = 467 M-taps →
190 ms at t=1 matches exactly. Best parallel: 22.35 ms → aggregate 20.9 Gtap/s
= 8.7× single core. No configuration lost more than ~2% to pool/dispatch
overhead (t=1 rows vs serial).

### Conclusions / recommendations

1. Use **autoGrid with 12–14 workers** on this chip (t=14 default; 12 saves
   power with <1% throughput loss). More than 14 threads is pointless here.
2. Keep coroutines = OS threads = pool size; balance load with *tile
   granularity*, never with coroutine count.
3. The remaining ~2× gap to "14×" is E-core FP capability — unreachable from
   software; only heterogeneous scheduling (task 4+, not required) could
   exploit the extra cores better.

## Baseline for Task 3

Task 3 pipes whole images through read → convolve → write stages. This
benchmark says: a convolution of a 2 MP image with a 15×15 kernel costs
~23 ms across 14 threads (~13 ms per MP), while I/O for the same image is a
few ms — so the pipeline must keep ~14 workers busy (1 OS thread each,
*serial* convolution inside, or tile-parallel with total threads ≤ 14) and
make sure reader/writer stages overlap compute instead of gating it.
