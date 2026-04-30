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

## Pipeline Architecture

```
readDataset ──[buffer]──► processImages ──[buffer]──► writeDataset
 (producer)                (processor)                (consumer)
```

- **readDataset**: reads files sequentially, sends to buffered channel
- **processImages**: processes each image in a separate coroutine on `Dispatchers.Default`
- **writeDataset**: writes results sequentially
- **Backpressure**: buffered channels prevent unbounded memory growth

## Usage

### Single image

```bash
./gradlew run --args="-i input.bmp -o output/ -f blur -m ALLPROCESSORS"
```

### Dataset (directory)

```bash
./gradlew run --args="-i images/ -o processed/ -f sharpen -m ROWS -b 8"
```

### CLI Arguments

```
-i  Input file or directory (required)
-o  Output directory (default: .)
-f  Filter name: id, black, blur, sharpen, edge, left, right (required)
-m  Mode: SERIAL, ROWS, COLUMNS, ALLPROCESSORS, GRID (default: ALLPROCESSORS)
-b  Buffer size for pipeline channels (default: 8)
```

## Running Tests

```bash
./gradlew test
```

## Benchmarking

### Serial

```bash
./gradlew benchmark -Ptask=serial
```
Results are saved to `docs/benchmark_task1.csv`.

### Parallel

```bash
./gradlew benchmark -Ptask=parallel
```
Results are saved to `docs/benchmark_task2.csv`.

### Pipeline

```bash
./gradlew benchmark -Ptask=pipeline
```
Results are saved to `docs/benchmark_task3.csv`.

## Performance Analysis
- [analysis/task1.md](analysis/task1.md) - serial benchmark results and analysis.
- [analysis/task2.md](analysis/task2.md) - parallel benchmark results, cache locality analysis,
and comparison with Task 1 serial baseline.
- [analysis/task3.md](analysis/task3.md) async pipeline benchmark results, backpressure analysis,
memory pressure estimates, and comparison with Task 1 and Task 2.
