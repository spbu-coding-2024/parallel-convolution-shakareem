# Task 3: Pipeline Processing — Performance Analysis

## Setup

- **Machine**: Apple M-series (ARM64), **12 CPU cores**
- **JVM**: Java 25 (OpenJDK), JVM target 24
- **Dataset**: 67 BMP images, sample size 576×787 = 3.5 MB per image
- **Runs**: 3 per configuration + 1 warmup, averaged
- **Benchmark**: `./gradlew benchmark`

## Pipeline Architecture

```
┌──────────┐  readChannel   ┌──────────┐  writeChannel  ┌──────────┐
│ Readers  │ ─[bufferSize]─►│ Workers  │ ─[bufferSize]─►│ Writers  │
│ (IO)     │                │ (CPU)    │                │ (IO)     │
└──────────┘                └──────────┘                └──────────┘
```

- **Readers** (`Dispatchers.IO`): parallel file readers, each handles a slice of the file list
- **Workers** (`Dispatchers.Default`): parallel convolution workers, each pulls one image at a time
- **Writers** (`Dispatchers.IO`): parallel file writers
- **Backpressure**: `Semaphore(bufferSize * 2)` limits total images in-flight across all stages,
  preventing readers from flooding memory when workers or writers are slow

## Results

### Scenario 1: Light kernel (3×3 blur) — I/O bound

| Configuration | Avg (ms) | Speedup |
|---------------|----------|---------|
| serial 1r/1w/1worker buf=8 | 1602 | 1.0× |
| parallel 1r/1w/4workers buf=8 | 1506 | 1.06× |
| parallel 2r/2w/4workers buf=8 | 1201 | **1.33×** |
| parallel 2r/2w/8workers buf=8 | 1116 | **1.44×** |
| 1r/1w/1worker buf=1 (tight) | 2093 | 0.77× |
| 1r/1w/1worker buf=32 (loose) | 1417 | 1.13× |

**Observation**: With a light kernel, I/O dominates. Adding parallel readers+writers gives 1.44× speedup.
Adding more workers beyond 4 gives diminishing returns — the bottleneck is disk, not CPU.
Buffer=1 is harmful (stalls pipeline), buffer=32 helps slightly (reader can prefetch).

### Scenario 2: Heavy kernel (11×11 blur) — compute bound

| Configuration | Avg (ms) | Speedup |
|---------------|----------|---------|
| serial 1r/1w/1worker buf=8 | 8093 | 1.0× |
| parallel 1r/1w/4workers buf=8 | 4661 | **1.74×** |
| parallel 1r/1w/8workers buf=8 | 4364 | **1.86×** |
| parallel 1r/1w/12workers buf=8 | 4348 | **1.86×** |
| parallel 2r/2w/8workers buf=8 | 4332 | **1.87×** |
| parallel 2r/2w/12workers buf=16 | 4157 | **1.95×** |

**Observation**: With a heavy kernel, compute dominates. More workers give clear speedup.
Plateau at ~8 workers — beyond that, CPU cores are saturated by the workers themselves.
Adding parallel readers/writers gives marginal additional benefit (~5%).

### Scenario 3: Heavy kernel + parallel convolution per image

| Configuration | Avg (ms) | Speedup vs serial |
|---------------|----------|-------------------|
| 1r/1w/1worker ALLPROCESSORS buf=8 | 1934 | **4.18×** |
| 1r/1w/4workers ALLPROCESSORS buf=8 | 1994 | 4.06× |
| 1r/1w/8workers ALLPROCESSORS buf=8 | 1937 | 4.18× |

**Key insight**: Using `ALLPROCESSORS` mode (parallel convolution within each image) with a single pipeline worker
achieves **4.18× speedup** — better than 12 serial workers (1.86×).

This is because `ALLPROCESSORS` uses all 12 cores for each image, while 12 serial workers compete for the same 12 cores.
The optimal strategy depends on image size vs dataset size:
- **Few large images** → parallelize within each image (`ALLPROCESSORS`)
- **Many small images** → parallelize across images (many workers, serial convolution)

### Scenario 4: Buffer size effect (heavy kernel, 4 workers)

| Buffer | Avg (ms) | Speedup vs buf=1 |
|--------|----------|------------------|
| 1 | 6472 | 1.0× |
| 2 | 5111 | 1.27× |
| 4 | 4742 | 1.36× |
| 8 | 4715 | 1.37× |
| 16 | 4615 | 1.40× |
| 32 | 4569 | 1.42× |

**Observation**: Buffer=1 causes severe pipeline stalls — each stage must wait for the next to consume before producing.
Buffer=4–8 captures most of the benefit. Beyond 8, diminishing returns.

**Memory cost**: buffer=8 → ~55 MB; buffer=32 → ~221 MB. For large images, keep buffer ≤ 8.

## Summary

| Scenario | Baseline | Best | Speedup |
|----------|----------|------|---------|
| Light kernel (I/O bound) | 1602 ms | 1116 ms (2r/2w/8workers) | **1.44×** |
| Heavy kernel (compute bound) | 8093 ms | 4157 ms (2r/2w/12workers) | **1.95×** |
| Heavy kernel + parallel conv | 8093 ms | 1934 ms (1worker ALLPROCESSORS) | **4.18×** |

## Comparison with Task 1 and Task 2

| Task | Strategy | Time for 67 images (11×11) | Speedup |
|------|----------|---------------------------|---------|
| Task 1 | Serial, no pipeline | ~8093 ms | 1.0× |
| Task 2 | Parallel conv, no pipeline | ~1934 ms (ALLPROCESSORS) | 4.18× |
| Task 3 | Pipeline, serial conv, 12 workers | ~4348 ms | 1.86× |
| Task 3 | Pipeline, ALLPROCESSORS, 1 worker | ~1934 ms | 4.18× |
| Task 3 | Pipeline, ALLPROCESSORS, 4 workers | ~1994 ms | 4.06× |

## Conclusions

1. **Bottleneck identification**: Use a heavy kernel to make compute the bottleneck; otherwise I/O dominates
2. **Reader feeds multiple workers**: 1 reader can feed 4–12 workers without becoming a bottleneck (for heavy kernels)
3. **Backpressure works**: buffer=1 causes 37% slowdown vs buffer=8; semaphore prevents memory exhaustion
4. **Parallel conv vs parallel pipeline**: For large images, parallelizing within each image is more efficient
5. **Optimal config**: 1–2 readers, `ALLPROCESSORS` mode, 1 worker, buffer=8 — best balance of speed and memory
