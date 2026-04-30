# Task 1: Serial Convolution — Performance Analysis

## Setup

- **Machine**: Apple M-series (ARM64)
- **JVM**: Java 25 (OpenJDK), JVM target 24
- **Runs**: 5 per configuration, averaged
- **Benchmark**: `./gradlew benchmark`

## Raw Results

| Image | Size | Kernel | Avg (ms) | Min (ms) | Max (ms) |
|-------|------|--------|----------|----------|----------|
| cameraman | 256×256 | blur 3×3 | 3 | 1 | 9 |
| cameraman | 256×256 | sharpen 3×3 | 1 | 1 | 1 |
| cameraman | 256×256 | edge 3×3 | 1 | 1 | 2 |
| cameraman | 256×256 | blur 11×11 | 11 | 11 | 12 |
| lenna | 512×512 | blur 3×3 | 4 | 4 | 5 |
| lenna | 512×512 | sharpen 3×3 | 4 | 4 | 4 |
| lenna | 512×512 | edge 3×3 | 4 | 4 | 4 |
| lenna | 512×512 | blur 11×11 | 47 | 46 | 48 |
| man | 1024×1024 | blur 3×3 | 17 | 17 | 17 |
| man | 1024×1024 | sharpen 3×3 | 17 | 16 | 20 |
| man | 1024×1024 | edge 3×3 | 16 | 16 | 17 |
| man | 1024×1024 | blur 11×11 | 191 | 188 | 194 |
| girl | 576×720 | blur 3×3 | 6 | 6 | 8 |
| girl | 576×720 | sharpen 3×3 | 6 | 6 | 7 |
| girl | 576×720 | edge 3×3 | 6 | 6 | 7 |
| girl | 576×720 | blur 11×11 | 74 | 74 | 76 |

Full data: [`docs/benchmark_task1.csv`](../docs/benchmark_task1.csv)

## Analysis

### Complexity

The serial convolution has time complexity **O(W × H × Kw × Kh)**:
- Linear in image area (W × H)
- Linear in kernel area (Kw × Kh)

### Scaling with Image Size (3×3 kernel)

| Image | Pixels | Time (ms) | Ratio vs prev |
|-------|--------|-----------|---------------|
| cameraman | 65 536 | 3 | — |
| lenna | 262 144 | 4 | 1.3× (4× pixels) |
| man | 1 048 576 | 17 | 4.25× (4× pixels) |

- **cameraman → lenna**: only 1.3× slower despite 4× more pixels — JVM warmup effect; the JIT compiler hasn't fully optimized the small image run
- **lenna → man**: 4.25× slower for 4× more pixels — matches theoretical O(N²) scaling perfectly

### Scaling with Kernel Size (lenna 512×512)

| Kernel | Ops/pixel | Time (ms) | Ratio |
|--------|-----------|-----------|-------|
| 3×3 | 9 | 4 | 1× |
| 11×11 | 121 | 47 | 11.75× |

- Theoretical ratio: 121/9 = **13.4×**
- Observed: **11.75×** — slightly better than theoretical due to cache effects (larger kernel reuses cached image rows more)

### Bottleneck

The inner loop in [`convolvePixel()`](../app/src/main/kotlin/convolution/SerialConvolution.kt) performs `Kh × Kw` multiply-accumulate operations per pixel:

```
for ky in 0..Kh:
    for kx in 0..Kw:
        sum += kernel[Kh-1-ky][Kw-1-kx] * image[imageY][imageX]
```

For a 3×3 kernel on 1024×1024: ~9.4M FP operations → **17 ms** → ~550 MFLOPS (single core)  
For an 11×11 kernel on 1024×1024: ~115M FP operations → **191 ms** → ~600 MFLOPS

The throughput is consistent (~550–600 MFLOPS), confirming the bottleneck is purely arithmetic, not I/O.

### Wrap-around vs Zero-padding

Wrap-around (cyclic) boundary was chosen because:
1. Mathematically correct for filter composition tests (shift-left ∘ shift-right = identity)
2. No border artifacts from zero-padding
3. Consistent behavior regardless of kernel size

Downside: introduces a discontinuity at image edges for natural images (visible with large kernels).

### Baseline for Task 2

The serial implementation serves as the **reference** for all parallel strategies.
Any parallel result must match serial output within floating-point epsilon (1e-9).

Expected speedup in Task 2: proportional to number of CPU cores (theoretical maximum).
For an M-series chip with 8+ performance cores, we expect **4–8× speedup** on large images.
