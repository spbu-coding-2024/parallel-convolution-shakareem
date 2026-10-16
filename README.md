# Image Convolution

A command-line tool for applying 2D convolution filters to grayscale images —
serial, parallel (one image), and streaming pipeline (dataset).

## Available Filters

| Name | Description |
|------|-------------|
| `id` | Identity (no change) |
| `black` | Zero kernel (all black) |
| `blur` | 3×3 box blur |
| `sharpen` | 3×3 sharpening |
| `edge` | 3×3 edge detection |
| `left` / `right` | Shift filters |

## Implementation Details

- **Boundary conditions**: wrap-around (cyclic padding) — pixels outside the image are taken from the opposite edge
- **Kernel convention**: classical discrete convolution (kernel is reflected), not correlation
- **Pixel values**: not clamped during computation — only clamped to `[0, 255]` on write, preserving mathematical correctness for filter composition
- **Image I/O**: `javax.imageio` (standard JDK), grayscale via luminance formula `0.299·R + 0.587·G + 0.114·B`, bulk per-row pixel transfer
- **Inner loop**: kernel reflection hoisted, wrap-around (`%`) confined to border pixels, row references hoisted — ~1.28 Gtap/s ≈ 2.6 GFLOPS on one core at the benchmark's pinned 2.5 GHz

## Parallelization Strategies (Task 2)

Every strategy runs exactly `threads` coroutines on a fixed thread-pool
dispatcher of `threads` **OS threads** and differs only in *how the image is
split into tiles*. Tiles (~4 per worker) are handed out dynamically through an
atomic cursor, so fast cores finish more tiles — essential on heterogeneous
CPUs (the Ultra 5 225H's 4 P-cores + 8 E-cores + 2 LP-E-cores have very
different per-thread throughput).

| Mode | Split | Notes |
|------|-------|-------|
| `SERIAL` | whole image | Task 1 baseline, also the bit-exact reference |
| `ROWS` | horizontal row bands | cache-friendly (row-major access) |
| `COLUMNS` | vertical column bands | column-major access on row-major data — for observing cache effects |
| `GRID` | rectangular block grid (~square factors of `threads`) | Task 2.1.4 |
| `PIXELWISE` | one coroutine per pixel | Task 2.1.1 extreme, kept for tests only — never benchmarked |

## Pipeline Architecture (Task 3)

```
readers × r ──[buffer]──► workers × w ──[buffer]──► writers × x
 (Dispatchers.IO)          (dedicated OS-thread       (Dispatchers.IO)
                           pool of w threads)
```

- **Workers ≡ OS threads**: `w` convolution coroutines pinned to a dispatcher
  backed by exactly `w` OS threads; `SERIAL` convolution per worker is the
  default — cross-image parallelism saturates the CPU without nesting.
- **Nested tile-parallel modes** (`-m ROWS` etc. on a dataset) share one global
  dynamic tile pool — the fix for load imbalance when the dataset has few,
  large images (see analysis: 2.3× → 7.4×).
- **Backpressure**: buffered channels + an in-flight semaphore bound resident
  images to `2 × buffer`; a fast reader cannot flood memory.
- **I/O never occupies convolution threads** and vice versa.

## Usage

### Single image

```bash
./gradlew run --args="-i src/test/resources/cameraman.bmp -o out/ -f blur -m ROWS -t 14"
```

### Dataset (directory)

```bash
./gradlew run --args="-i images/ -o processed/ -f sharpen -w 14 -b 8"
```

### CLI Arguments

```
-i          Input file or directory (required)
-o          Output directory (default: .)
-f          Filter name: id, black, blur, sharpen, edge, left, right (required)
-m          Convolution strategy: SERIAL, ROWS, COLUMNS, GRID, PIXELWISE
            (default: ROWS for a single file, SERIAL for a dataset)
-t          OS threads for tile-parallel convolution of one image (default: all cores)
-w          Pipeline workers == dedicated OS threads (dataset mode, default: all cores)
--readers   Parallel file readers (dataset mode, default: 1)
--writers   Parallel file writers (dataset mode, default: 1)
-b          Buffer size for pipeline channels (default: 8)
```

## Running Tests

```bash
./gradlew test
```

All parallel strategies (every thread count and split, pipeline modes
included) are verified to produce results bit-identical to the serial
implementation.

## Benchmarking (v2 protocol)

The measurement protocol isolates the number of OS threads as the only
experimental variable, and makes noise measurable instead of hoped-away:

- **Only large inputs**: 1.0–5.5 MP images with 11×11/15×15/21×21 box kernels;
  every measured run carries ≥ ~10 ms (typically ≫) of useful convolution
  work. Tiny configs are excluded by design.
- **Raw nanoseconds, long format**: each invocation writes one CSV row per
  individual measurement (warmup + 7/9/5 interleaved reps for Task 1/2/3).
  No averaging happens inside the benchmark process.
- **Interleaved matrix**: one seeded shuffle of all configs, then rep 1 over
  every config, rep 2 over every config… — thermal/background drift cannot
  alias with a strategy or thread count.
- **Independent rounds**: `bench-all.sh` runs the suite R times (default 3)
  into separate `docs/bench/<stamp>/runK/` dirs; the cross-round spread of
  per-round medians (`benchmark_repeatability_taskN.csv`) is the measured
  reproducibility of the experiment.
- **Hardware controls** (root): all cores pinned to one common frequency
  (2.5 GHz = slowest core's max — no boost lottery, P/E compared at equal
  clocks), performance governor, device IRQs steered to housekeeping cores
  outside the benchmark cpuset (`--cpus 0-11`).
- **Offline analysis**: `./gradlew charts -Psrc=docs/bench/<stamp>` pools all
  rounds and writes median / IQR / bootstrap 95% CI per config to
  `docs/benchmark_stats_taskN.csv`, plus every PNG (CI error bars, box
  plots, repeatability) to `docs/charts/`.

### Full controlled run

```bash
sudo -E ./bench-all.sh                      # 3 rounds × {serial, parallel, pipeline} + charts
sudo -E ./bench-all.sh --cpus 0-11          # reserve 2 cores for IRQs/housekeeping
sudo -E ./bench-all.sh --rounds 5
./bench-all.sh --no-pin --no-irq            # rootless mode (taskset + rounds only)
```

### Individual commands

```bash
./gradlew benchmark -Ptask=serial    [-Pout=DIR] [-Preps=N]
./gradlew benchmark -Ptask=parallel  [-Pout=DIR] [-Preps=N]   # sweeps OS threads {1,2,4,7,8,12,14}
./gradlew benchmark -Ptask=pipeline  [-Pout=DIR] [-Preps=N]   # 13.8 MP dataset; worker/IO/nested/buffer sweeps
./gradlew charts -Psrc=docs/bench/<stamp>
```

## Performance Analysis (Russian)

- [analysis/task1.md](analysis/task1.md) — serial throughput, complexity
  confirmation, reproducibility of the protocol.
- [analysis/task2.md](analysis/task2.md) — speedup vs number of available OS
  threads on the 12-core pinned cpuset (best 10.2× = 85% of linear),
  P-vs-E-core measurements, tile-shape and quantization effects,
  oversubscription.
- [analysis/task3.md](analysis/task3.md) — pipeline scaling: the I/O wall and
  its cure, the work-granularity bound (quantitatively predicted), nested
  tile-parallelism on a shared pool (7.4×), backpressure sizing.
