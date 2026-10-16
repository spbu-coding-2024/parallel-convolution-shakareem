# Image Convolution

A command-line tool for applying 2D convolution filters to grayscale images.

## Implementation Details

- **Boundary conditions**: wrap-around (cyclic padding) — pixels outside the image are taken from the opposite edge
- **Kernel convention**: classical discrete convolution (kernel is reflected), not correlation
- **Pixel values**: not clamped during computation — only clamped to `[0, 255]` on write, preserving mathematical correctness for filter composition
- **Image I/O**: `javax.imageio` (standard JDK), grayscale via luminance formula `0.299·R + 0.587·G + 0.114·B`, bulk per-row pixel transfer
- **Inner loop**: kernel reflection hoisted, wrap-around (`%`) confined to border pixels, row references hoisted — ~2.4 Gtap/s ≈ 4.8 GFLOPS on one core

## Available Filters

| Name | Description |
|------|-------------|
| `id` | Identity (no change) |
| `black` | Zero kernel (all black) |
| `blur` | 3×3 box blur |
| `sharpen` | 3×3 sharpening |
| `edge` | 3×3 edge detection |
| `left` / `right` | Shift filters |

## Usage

```bash
./gradlew run --args="-i input.bmp -o output.bmp -f blur"
```

### CLI Arguments

```
-i  Input image file (required)
-o  Output image file (default: output.bmp)
-f  Filter name: id, black, blur, sharpen, edge, left, right (required)
```

## Running Tests

```bash
./gradlew test
```

## Benchmarking

The measurement protocol (full description in
[analysis/task1.md](analysis/task1.md)): only large images (1.0–5.5 MP) and
heavy box kernels (11×11/15×15/21×21), raw nanosecond samples, one warmup plus
7 interleaved reps per config, three independent invocations pooled offline.

The controlled-environment runner (`bench-all.sh`: CPU isolation, pinned
frequency, IRQ steering, N independent rounds) lives on the `pipeline` branch,
which continues this one.

```bash
./gradlew benchmark -Ptask=serial                     # -> default out dir
./gradlew benchmark -Ptask=serial -Pout=docs/bench/e1/run1 -Preps=7
./gradlew charts -Psrc=docs/bench/e1                  # stats CSVs + PNGs from pooled runs
```

Outputs: raw data `docs/benchmark_task1.csv` (long format, one row per
measurement), aggregates `docs/benchmark_stats_task1.csv` (median, IQR,
bootstrap 95% CI), reproducibility `docs/benchmark_repeatability_task1.csv`,
charts `docs/charts/task1_*.png` (CI error bars + box plots).

## Performance Analysis

See [analysis/task1.md](analysis/task1.md) for full benchmark results and analysis.
