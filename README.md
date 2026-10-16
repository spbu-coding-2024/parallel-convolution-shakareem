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

```bash
./gradlew benchmark -Ptask=serial
```

Results are saved to `docs/benchmark_task1.csv`; charts are regenerated from the
results table into `docs/charts/` (`task1_scaling.png`, `task1_ns_per_tap.png`,
`task1_run_spread.png`).

## Performance Analysis

See [analysis/task1.md](analysis/task1.md) for full benchmark results and analysis.
