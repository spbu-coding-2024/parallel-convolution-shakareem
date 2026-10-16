# Image Convolution

A command-line tool for applying 2D convolution filters to grayscale images.

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
- **Inner loop**: kernel reflection hoisted, wrap-around (`%`) confined to border pixels, row references hoisted — ~2.4 Gtap/s ≈ 4.8 GFLOPS on one core

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
| `GRID` | rectangular block grid (`autoGridConvolve` picks ~square factors) | Task 2.1.4 |
| *(pixelwise)* | one coroutine per pixel | Task 2.1.1 extreme, kept for tests only — never benchmarked |

## Usage

```bash
./gradlew run --args="-i input.bmp -o output.bmp -f blur -m ROWS -t 14"
```

### CLI Arguments

```
-i  Input image file (required)
-o  Output image file (default: output.bmp)
-f  Filter name: id, black, blur, sharpen, edge, left, right (required)
-m  Mode: SERIAL, ROWS, COLUMNS, GRID (default: ROWS)
-t  OS threads the convolution may use (default: all cores)
```

## Running Tests

```bash
./gradlew test
```

All parallel strategies are verified to produce results bit-identical to the
serial implementation for any thread count.

## Benchmarking

Benchmarks use 1 warmup + 5 measured runs on large images with 11×11/15×15
box-blur kernels, so each run carries ≥ 50 ms of useful convolution work and
OS noise stays below a few percent. Result tables (`docs/benchmark_taskN.csv`)
drive PNG charts regenerated into `docs/charts/`.

### Serial

```bash
./gradlew benchmark -Ptask=serial
```
Results: `docs/benchmark_task1.csv` + `task1_*.png` charts.

### Parallel (OS thread scaling)

```bash
./gradlew benchmark -Ptask=parallel
```
Sweeps OS threads `{1, 2, 4, 7, 8, 12, 14, 28}` per strategy (28 = oversubscription demo).
Results: `docs/benchmark_task2.csv` + `task2_*.png` charts.

## Performance Analysis

- [analysis/task1.md](analysis/task1.md) - serial benchmark results and analysis.
- [analysis/task2.md](analysis/task2.md) - parallel benchmark results: OS thread
  scaling on a 14-core heterogeneous CPU, strategy (cache locality) comparison,
  oversubscription, and comparison with the Task 1 serial baseline.
