# Task 2: Parallel Convolution — Performance Analysis

## Setup

- **Machine**: Apple M-series (ARM64), **12 CPU cores**
- **JVM**: Java 25 (OpenJDK), JVM target 24, Kotlin coroutines on `Dispatchers.Default`
- **Runs**: 5 per configuration, averaged
- **Benchmark**: `./gradlew benchmark`
- **Reference**: Task 1 serial implementation

## Strategies

| Strategy | Description | Coroutines | Cache behavior |
|----------|-------------|------------|----------------|
| `serial` | Single thread | 1 | Row-major, optimal |
| `rows` | One coroutine per row | H rows | Row-major, cache-friendly |
| `columns` | One coroutine per column | W columns | Column-major, **cache-unfriendly** |
| `grid_2x2` | 2×2 rectangular blocks | 4 | Row-major within block |
| `grid_4x4` | 4×4 rectangular blocks | 16 | Row-major within block |
| `allProcessors` | N chunks across N CPU cores | N=12 | Row-major, minimal overhead |
| `pixelwise` | One coroutine per pixel | W×H | Extreme overhead |

## Raw Results (man.bmp 1024×1024, blur 11×11)

| Strategy | Avg (ms) | Speedup vs serial |
|----------|----------|-------------------|
| serial | 182 | 1.0× |
| rows | 21 | **8.67×** |
| columns | 22 | **8.27×** |
| grid_2x2 | 46 | 3.96× |
| grid_4x4 | 25 | 7.28× |
| allProcessors | 27 | 6.74× |
| pixelwise | 181 | ~1.0× (no speedup) |

Full data: [`docs/benchmark_task2.csv`](../docs/benchmark_task2.csv)

## Analysis

### Speedup on Large Images

On 1024×1024 with 11×11 kernel (compute-heavy), the best strategies achieve **~8.5× speedup** on 12 cores.
This is ~70% parallel efficiency — reasonable given JVM coroutine overhead and memory bandwidth limits.

### Rows vs Columns: Cache Locality

`Array<DoubleArray>` is **row-major** in JVM memory:
- Each `DoubleArray` row is a contiguous block in heap
- Accessing `image[y][x]` for sequential `x` is cache-friendly (stride 1)
- Accessing `image[y][x]` for sequential `y` (fixed `x`) is cache-unfriendly (stride = row width)

**Expected**: `rows` should be faster than `columns`.

**Observed** (1024×1024, blur 11×11):
- `rows`: 21 ms
- `columns`: 22 ms

The difference is **minimal** (~5%). Why?

1. **Kernel access pattern dominates**: `convolvePixel` reads a `Kh×Kw` neighborhood for each pixel.
   For an 11×11 kernel, each pixel reads 121 values — the kernel access pattern itself is not purely sequential,
   so both strategies have similar cache miss rates in the kernel loop.
2. **JVM JIT**: The JIT compiler may reorder memory accesses or prefetch effectively.
3. **Apple M-series cache**: Large L2/L3 caches (up to 32 MB) reduce the penalty for non-sequential access.

For a **3×3 kernel** (less compute per pixel, more memory-bound):
- `rows` (1024×1024): 2 ms
- `columns` (1024×1024): 2 ms — still similar

The cache effect is present but masked by the M-series hardware prefetcher.

### Grid Strategy

`grid_2x2` (4 blocks) is consistently **slower** than `rows` or `allProcessors`:
- 1024×1024, blur 11×11: 46 ms vs 21 ms
- Only 4 coroutines → underutilizes 12 cores

`grid_4x4` (16 blocks) is better: 25 ms — closer to `rows` but still slightly worse due to:
- More coroutine creation overhead
- Block boundaries cause slightly more cache misses at block edges

**Optimal grid size** would be `numBlocksY = numCores, numBlocksX = 1` — equivalent to `allProcessors`.

### Pixelwise Strategy

`pixelwise` creates **W×H coroutines** (262K for 512×512, 1M for 1024×1024):
- 256×256: 210 ms vs 1 ms serial — **210× slower**
- 512×512: ~46 ms vs 4 ms serial — **~11× slower** (JVM reuses coroutine objects)
- 1024×1024: 181 ms vs 182 ms serial — **no speedup** (scheduler overhead ≈ compute time)

The coroutine scheduler overhead dominates for small work units.
This strategy is only useful for observing scheduler behavior, not for performance.

### Small Images (256×256)

For small images, parallel overhead often **exceeds** the computation time:
- `rows` on 256×256 blur 3×3: 9 ms vs 1 ms serial — **9× slower**
- `allProcessors` on 256×256 blur 3×3: 5 ms vs 1 ms serial — **5× slower**

**Conclusion**: Parallelization is only beneficial when computation time >> coroutine overhead (~1–2 ms).
For images smaller than ~512×512 with small kernels, serial is faster.

### Comparison with Task 1

| Image | Kernel | Serial (Task 1) | Best Parallel (Task 2) | Speedup |
|-------|--------|-----------------|------------------------|---------|
| lenna 512×512 | blur 11×11 | 47 ms | 6 ms (rows) | **7.8×** |
| man 1024×1024 | blur 11×11 | 191 ms | 21 ms (rows) | **9.1×** |
| man 1024×1024 | blur 3×3 | 17 ms | 2 ms (rows) | **8.5×** |

The parallel implementation achieves near-linear speedup on large, compute-heavy workloads.
