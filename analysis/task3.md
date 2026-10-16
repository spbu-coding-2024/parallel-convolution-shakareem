# Task 3: Streaming Pipeline — Performance Analysis

## Setup

- **Machine**: Intel Core Ultra 5 225H, **14 physical cores** (4P + 8E + 2LP-E), no SMT, CachyOS
- **JVM**: Java 27 (OpenJDK), Kotlin coroutines
- **Dataset**: 7 curated large images (`airplaneU2, brickwall, carpet, man, Rainier, textureA, textureB`), **8.4 MP total**
- **Runs**: 1 warmup + 3 measured full-pass runs per configuration (a pass is one whole dataset; ≥ 0.1–0.8 s of work keeps OS noise small)
- **Benchmark**: `./gradlew benchmark -Ptask=pipeline`

## Pipeline architecture

```
┌────────────────┐  read ch    ┌──────────────────────┐  write ch   ┌────────────────┐
│ readers × r    │ ─[buffer]──►│ workers × w          │ ─[buffer]──►│ writers × x    │
│ Dispatchers.IO │             │ dedicated pool,      │             │ Dispatchers.IO │
│ (file+decode)  │             │ w pinned OS threads  │             │ (encode+file)  │
└────────────────┘             └──────────────────────┘             └────────────────┘
        ▲                    Semaphore(2×buffer) bounds resident images around the whole circuit
```

Key points (continuing the Task 2 conclusions):

- **Workers ≡ OS threads.** `w` coroutines on a `newFixedThreadPoolContext(w)` dispatcher — the
  benchmark knob is the number of OS threads the convolution stage may use. No coroutine is
  created per row/pixel anywhere on the hot path.
- **Default convolution inside a worker is SERIAL** — cross-image parallelism already saturates
  the CPUs; nested per-image coroutines are opt-in (`ROWS/COLUMNS/GRID` + `convThreads`) and are
  measured below.
- **I/O never blocks a compute thread and vice versa**: readers/writers live on `Dispatchers.IO`,
  convolution on its dedicated pool; nested convolutions use the *separate* cached tile pools,
  so a blocked worker can never deadlock its children.
- **Backpressure**: buffered channels + an in-flight semaphore (`2 × buffer` images) cap memory;
  a fast reader cannot flood memory when compute or write lags (Task 3.3).

## Stage budget (what must be overlapped)

Measured warm, single-threaded, outside the pipeline:

| Stage | Time per pass |
|-------|---------------|
| read + decode (grayscale) | 56.3 ms |
| encode + write | 24.4 ms |
| heavy conv (15×15, 8.4 MP, 1 thread) | ~790 ms |
| light conv (3×3, 8.4 MP, 1 thread) | ~31 ms |

So: with the heavy kernel I/O is ~10% of one serial pass — trivially hideable.
With the light kernel I/O **is** the pipeline (~81 ms vs 31 ms of convolution) — the
compute stage is irrelevant and only I/O parallelism helps.

## Scenario 1: heavy kernel — workers sweep (SERIAL per worker)

| workers | pass, ms | speedup | MP/s |
|---------|----------|---------|------|
| 1  | 796.1 | 1.00× | 10.5 |
| 2  | 425.4 | 1.87× | 19.7 |
| 4  | 260.9 | 3.05× | 32.1 |
| 8  | 246.4 | 3.23× | 34.0 |
| 12 | 240.2 | 3.31× | 34.8 |
| 14 | 238.9 | **3.33×** | 35.1 |

![Pass time vs workers](../docs/charts/task3_scaling_vs_workers.png)

Scaling stops at **3.3×, far below Task 2's 8.3×** — and this is not scheduling noise,
it is **work granularity**: the dataset has only **7 images**, so at most 7 workers can
ever be busy, and the pass makespan can never be shorter than the slowest single image
(Rainier: ~185 ms serial) plus its I/O. The flat tail from w=8 on is all consistent with
this bound: `makespan ≥ max(image time)`.

The w=1 number (796 ms) matches Task 1 throughput exactly (8.4 MP × 225 taps /
2.35 Gtap/s ≈ 800 ms), confirming the pipeline itself adds no measurable overhead.

## Scenario 2: light kernel — the I/O wall

| workers | pass, ms | speedup |
|---------|----------|---------|
| 1  | 113.7 | 1.00× |
| 2  | 81.2  | 1.40× |
| 4  | 78.9  | 1.44× |
| 8  | 75.1  | 1.51× |
| 12 | 71.9  | **1.58×** |
| 14 | 82.6  | 1.38× |

Adding CPU to the compute stage does nothing once pass time (~72–83 ms) approaches the
serial I/O sum (~81 ms): the pipeline becomes I/O-bound, exactly the failure mode Task 3
warns about (the w=14 number is within the run-to-run noise of the plateau — more workers
than there are images buys nothing when images aren't the constraint).

## Scenario 3: escaping the I/O bound — scale the I/O stages

| configuration | pass, ms | speedup |
|---------------|----------|---------|
| light, 14w, 2r/2w | 70.1 | 1.62× |
| light, 14w, **3r/3w** | **61.8** | **1.84×** |
| heavy, 14w, 2r/2w | 239.2 | 3.33× (no change) |

![I/O stages](../docs/charts/task3_io_stages.png)

Splitting the I/O stages across parallel readers/writers recovers what CPU workers
couldn't: 3 pairs bring the light pass to 61.8 ms (1.84×), pushing it below the
single-reader stage sum — the decode work of 7 files is now genuinely concurrent
(Task 3.3: the fast stage feeds the bottleneck one). With 2 pairs the effect is
smaller (70.1 ms): 7 files split 2 ways leaves a lumpy tail.
On the heavy kernel, extra readers change nothing (239.2 ≈ 238.9) — with buffer
prefetch the single reader already hides its 56 ms behind 800 ms of compute
(Task 3.1: one reader feeds many convolutions). This is the load-balancing answer
of Task 3.3: **scale the stage that is the bottleneck, leave the others at 1**.

## Scenario 4: nested per-image parallelism (Task 3.2) vs cross-image only

Heavy kernel, same dataset:

| configuration | pass, ms | speedup |
|---------------|----------|---------|
| 1w, ROWS convolution t=14 (one image at a time) | 135.1 | 5.89× |
| 4w, ROWS convolution t=4 (private pools)         | 249.3 | 3.19× |
| **14w, ROWS convolution t=14 (shared tile pool)** | **112.4** | **7.08×** |
| 14w, SERIAL (reference, Scenario 1)              | 236.8 | 3.36× |

![Nested vs cross](../docs/charts/task3_nested_vs_cross.png)

The winner is surprising and worth spelling out: **cross-image *and* per-image
parallelism together**. All nested tile tasks share one cached 14-thread convolution
pool, and tiles are distributed dynamically (Task 2), so the pool becomes a *global*
work queue across images: while 6 of the 7 images drain their small tiles, the
remaining threads keep chipping away at Rainier's big tiles. The `max(image)`
granularity bound from Scenario 1 disappears — 112.4 ms is close to the true
compute bound (8.4 MP of tiles / ~21 Gtap/s effective ≈ 90 ms) plus a small tail.

Note the ordering: naive oversubscription is **not** what makes this fast —
`4w × t=4` (16 threads for 14 cores, four private half-size pools) is *worse than
the serial-worker baseline* (249.3 vs 236.8 ms). What matters is one shared pool +
dynamic tiles, so total busy OS threads stay at 14 and every thread always has work.

The `1w ROWS t=14` row is the right choice when images must be processed strictly
one at a time (e.g. memory-limited): 5.89× with at most one image in the compute
stage.

## Scenario 5: buffer sizing (backpressure)

| buffer | heavy 14w, ms | light 14w, ms |
|--------|---------------|---------------|
| 1  | 497.1 | 126.9 |
| 2  | 294.0 | 84.2  |
| 4  | **235.0** | **70.8** |
| 8  | 242.1 | 70.5  |
| 16 | 235.8 | 79.6  |
| 32 | 236.6 | 70.4  |

![Buffer size](../docs/charts/task3_buffer.png)

`buffer=1` (2 resident images) serializes the stages — every stage waits for the next
slot and workers go idle: **~2× slowdown** on heavy. The knee is at buffer ≈ 4:
enough to keep the reader ahead and a couple of images in the write queue. Beyond the
knee everything is plateau + noise for both kernels; larger buffers buy nothing and
only enlarge the resident set.
Design bound: at most `2 × buffer` images resident (~8 MB per resident megapixel) —
this is what stops a fast reader from flooding memory when compute or write lags.

## Summary of recommendations (this machine)

| workload | configuration | pass, ms | vs serial pass |
|----------|---------------|----------|----------------|
| many small images, any kernel | 14w SERIAL, 1r/1w, buf=8 | (granularity fine) | ~7–8× expected |
| few large images, heavy kernel | **14w + ROWS t=14**, buf=8 | 112.4 | **7.1×** |
| light kernels (I/O bound) | 14w, **3r/3w**, buf=8 | 61.8 | 1.8× |
| memory-constrained | 1w + ROWS t=14, buf=8 | 135.1 | 5.9× |

Full data: [`docs/benchmark_task3.csv`](../docs/benchmark_task3.csv); charts regenerate
from the run itself into `docs/charts/`.

- The pipeline adds no measurable overhead: 1w light pass (113.7 ms) ≈ the serial sum
  of the three stages (~112 ms), and the 1w heavy pass (796 ms) is pure Task 1
  throughput.
- Task 2's lesson held and sharpened: the only knob worth sweeping is **OS threads**;
  coroutine counts stay equal to it everywhere (≤ `w` worker coroutines + ≤ `convThreads`
  tile loops per image), and dynamic tiles make even deliberate oversubscription harmless.
- The 3.3× plateau of Scenario 1 is the classic **load-balancing** trap of Task 3.3 —
  image-granular work units are too coarse for 14 threads when the dataset is 7 large
  images; the fix is tiling (Scenario 4), not more workers.
