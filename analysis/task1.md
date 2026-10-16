# Task 1: Serial Convolution — Performance Analysis

## Setup

- **Machine**: Intel Core Ultra 5 225H, **14 physical cores** (4P + 8E + 2LP-E, no SMT), 30 GB RAM, CachyOS
- **JVM**: Java 27 (OpenJDK), JVM target 24
- **Runs**: 1 warmup (JIT + page cache) + 5 measured runs per configuration, averaged
- **Benchmark**: `./gradlew benchmark -Ptask=serial`

## Implementation

The serial core is deliberately written for maximum single-thread throughput, so that
every later comparison (Task 2, Task 3) measures *parallelism*, not implementation waste:

1. **Kernel reflection hoisted.** A discrete convolution is a correlation with the
   180°-rotated kernel; the rotation is computed once (`flipped()`) instead of doing two
   array lookups and two index subtractions *per tap*.
2. **Wrap-around moved out of the hot loop.** Pixels whose whole kernel window fits in
   the image use a straight-line inner loop (`convolvePixelFast`: one row reference per
   kernel row, one load per tap, no modulo). Only the ≤ `2·K` border rows/columns take
   the `%`-based path. For a 1024×1024 image with a 15×15 kernel this is ~94% of pixels.
3. **Row-major tiling API.** `convolveRegion()` accepts an arbitrary `[y0,y1) × [x0,x1)`
   tile; Task 2 splits images into tiles and reuses the exact same hot loop, so parallel
   versions add only dispatch, not extra per-pixel work.

Accumulation order per pixel is identical to the naive formulation, so results are
bit-for-bit equal and the serial implementation stays a valid reference for the
parallel ones (checked in tests).

Measured single-core throughput: **~2.4 Gtap/s ≈ 4.9 GFLOPS** (blur 15×15 on man.bmp:
97.0 ms for 1.05 Mpx × 225 taps). This is the per-thread ceiling that Task 2's
thread-scaling measurements are compared against.

## Benchmark design — minimizing OS noise

Absolute scheduling/interrupt noise is roughly constant per run (~1–3 ms). To make it
irrelevant, each measured configuration must do enough useful work:

* **Images**: a size ladder `cameraman 256²` → `lenna 512²` → `man 1024²` →
  `Rainier 1920×1080`, up to 2 MP.
* **Kernels**: `blur 3×3`, `edge 3×3`, `blur 11×11`, `blur 15×15` (225 taps) — the heavy
  box blurs push large-image runs to 50–190 ms, where noise is ≲1%.
* **1 warmup + 5 runs** per configuration: warmup absorbs JIT tier-up and page faults;
  after that the spread is tiny (see the stability chart).
* Everything is computed in-memory from a pre-loaded `DoubleArray`; no I/O inside a
  timed region.

![Relative run-to-run spread](../docs/charts/task1_run_spread.png)

The spread chart shows the design working: 3×3 kernels on small images still have
tens–hundreds of percent relative spread (they are *not* used for scaling conclusions —
they only take ~1 ms of useful work), while every configuration doing ≥ 50 ms of work
varies by < 4%, and the two heaviest ones (1024² and 1920×1080 with the 15×15 kernel)
by ~2% and ~0.5%.

## Raw Results

Timing uses a nanosecond-resolution clock (`System.nanoTime`), so sub-millisecond
runs are not quantization artifacts.

| Image | Size | Kernel | Avg (ms) | Min (ms) | Max (ms) |
|-------|------|--------|----------|----------|----------|
| cameraman | 256×256 | blur 3×3 | 1.30 | 0.85 | 2.38 |
| cameraman | 256×256 | edge 3×3 | 1.07 | 0.84 | 1.96 |
| cameraman | 256×256 | blur 11×11 | 3.83 | 3.71 | 4.05 |
| cameraman | 256×256 | blur 15×15 | 7.10 | 7.03 | 7.27 |
| lenna | 512×512 | blur 3×3 | 2.63 | 1.85 | 5.16 |
| lenna | 512×512 | edge 3×3 | 1.79 | 1.77 | 1.80 |
| lenna | 512×512 | blur 11×11 | 13.98 | 13.55 | 15.36 |
| lenna | 512×512 | blur 15×15 | 25.36 | 25.33 | 25.41 |
| man | 1024×1024 | blur 3×3 | 7.93 | 7.23 | 9.09 |
| man | 1024×1024 | edge 3×3 | 7.54 | 7.14 | 8.59 |
| man | 1024×1024 | blur 11×11 | 52.51 | 51.89 | 53.96 |
| man | 1024×1024 | blur 15×15 | 97.02 | 96.60 | 98.25 |
| Rainier | 1920×1080 | blur 3×3 | 15.02 | 14.54 | 15.99 |
| Rainier | 1920×1080 | edge 3×3 | 14.77 | 14.32 | 16.04 |
| Rainier | 1920×1080 | blur 11×11 | 103.13 | 102.02 | 105.64 |
| Rainier | 1920×1080 | blur 15×15 | 186.61 | 186.21 | 187.06 |

Full data: [`docs/benchmark_task1.csv`](../docs/benchmark_task1.csv)
(charts are regenerated from this table by the benchmark itself into `docs/charts/`).

## Analysis

### Scaling with image size

![Time vs image size (log-log)](../docs/charts/task1_scaling.png)

Straight lines on log-log axes: time grows linearly with pixel count for every kernel
(e.g. man → Rainier ≈ 2× pixels → 1.9× time on all kernels). Confirms O(W·H·Kw·Kh).

### Scaling with kernel size & constant throughput

![ns per pixel per tap](../docs/charts/task1_ns_per_tap.png)

Time per (pixel × tap) collapses to a stable ~0.4 ns for all kernels from 11×11 up,
while the 3×3 cases pay 0.8–2.2 ns: one tap costs the same everywhere, only the
small-kernel runs are dominated by the fixed per-pixel loop overhead.
This is the evidence that the hot loop is running at constant hardware throughput
(~2.4 Gtap/s single-core), i.e. measurements above are throughput, not noise.

### Wrap-around vs zero-padding

Wrap-around (cyclic) boundary was chosen because:

1. Mathematically correct for filter composition tests (shift-left ∘ shift-right = identity)
2. No border artifacts from zero-padding
3. Consistent behavior regardless of kernel size

Downside: introduces a discontinuity at image edges for natural images (visible with large kernels).

### Baseline for Task 2

The serial implementation serves as the **reference** for all parallel strategies:
any parallel result must match it bit-for-bit (tested). Its ~2.4 Gtap/s per core sets the
expected ceiling for Task 2 on the 14-core Ultra 5 225H; the Task 2 benchmark therefore
sweeps the number of OS threads the convolution may use (1…14) and measures how close
chunked parallelism gets to `threads × serial`.
