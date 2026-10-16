package convolution

import images.Bitmap
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Parallel convolution for one image (Task 2).
 *
 * Design goal: measure and maximize speed for a given number of OS threads,
 * not coroutine creation overhead. Therefore every strategy:
 *
 * 1. creates exactly `workers` coroutines — one per OS thread — on a fixed
 *    thread-pool dispatcher of the same size (no per-pixel/per-row churn);
 * 2. splits the image into `workers x CHUNKS_PER_WORKER` tiles shaped by the
 *    strategy (row bands / column bands / rectangular grid) and hands them
 *    out dynamically via an atomic cursor;
 * 3. computes each tile through the shared serial hot loop [convolveRegion],
 *    so results are bit-for-bit identical to the serial reference.
 *
 * Dynamic tile distribution matters on this machine: the Ultra 5 225H has
 * 4 performance, 8 efficient and 2 low-power efficient cores with no SMT.
 * Static "one band per thread" partitioning would make the slowest core on
 * the largest band dictate the makespan; dynamic stealing lets fast cores
 * finish more bands and levels the load automatically.
 *
 * [pixelwiseConvolve] (one coroutine per pixel) is kept as the educational
 * extreme required by Task 2.1.1 — to observe what per-item scheduling
 * overhead looks like. It is intentionally never benchmarked: the benchmark
 * axis is OS thread count, not coroutine count.
 */

val defaultThreads: Int = Runtime.getRuntime().availableProcessors()

/** Tiles per worker: enough for dynamic load-leveling, few enough to keep cache reuse. */
private const val CHUNKS_PER_WORKER = 4

/** Row bands, one coroutine per OS thread pulling tiles dynamically. */
suspend fun rowsConvolve(
    image: Bitmap,
    kernel: Bitmap,
    threads: Int = defaultThreads,
): Bitmap {
    val workers = workersFor(threads, image.size)
    val bands = balancedBands(image.size, workers * CHUNKS_PER_WORKER)
    val tiles = bands.map { (y0, y1) -> Tile(y0, y1, 0, image[0].size) }
    return convolveTiles(image, kernel, tiles, workers)
}

/**
 * Column bands: same partitioning along x. Kept because Task 2.1.3 asks to
 * observe the (cache-unfriendly) column-major access pattern: each tile is a
 * narrow full-height strip, so tiles are distributed across all workers.
 */
suspend fun columnsConvolve(
    image: Bitmap,
    kernel: Bitmap,
    threads: Int = defaultThreads,
): Bitmap {
    val workers = workersFor(threads, image[0].size)
    val bands = balancedBands(image[0].size, workers * CHUNKS_PER_WORKER)
    val tiles = bands.map { (x0, x1) -> Tile(0, image.size, x0, x1) }
    return convolveTiles(image, kernel, tiles, workers)
}

/** Rectangular grid of as-close-to-square blocks as possible, ~threads blocks per side scaled by [CHUNKS_PER_WORKER]. */
suspend fun autoGridConvolve(
    image: Bitmap,
    kernel: Bitmap,
    threads: Int = defaultThreads,
): Bitmap {
    val workers = workersFor(threads, min(image.size, image[0].size))
    val totalTiles = workers * CHUNKS_PER_WORKER
    val side = sqrt(totalTiles.toDouble()).roundToInt().coerceAtLeast(1)
    return gridConvolve(image, kernel, numBlocksY = side, numBlocksX = side, threads = threads)
}

/** Arbitrary rectangular grid (Task 2.1.4): [numBlocksY] x [numBlocksX] blocks, [threads] workers. */
suspend fun gridConvolve(
    image: Bitmap,
    kernel: Bitmap,
    numBlocksY: Int = defaultBlocks(),
    numBlocksX: Int = defaultBlocks(),
    threads: Int = defaultThreads,
): Bitmap {
    val workers = workersFor(threads, numBlocksY * numBlocksX)
    val rowBands = balancedBands(image.size, numBlocksY)
    val colBands = balancedBands(image[0].size, numBlocksX)
    val tiles =
        rowBands.flatMap { (y0, y1) ->
            colBands.map { (x0, x1) -> Tile(y0, y1, x0, x1) }
        }
    return convolveTiles(image, kernel, tiles, workers)
}

/**
 * One coroutine per pixel on the shared [Dispatchers.Default]. Kept for Task
 * 2.1.1 completeness and result-equivalence tests only — never benchmarked.
 */
suspend fun pixelwiseConvolve(
    image: Bitmap,
    kernel: Bitmap,
): Bitmap {
    require(kernel.size % 2 == 1 && kernel[0].size % 2 == 1) { "Kernel dimensions must be odd" }
    val output = Array(image.size) { DoubleArray(image[0].size) }
    coroutineScope {
        for (y in image.indices) {
            for (x in image[0].indices) {
                launch(Dispatchers.Default) {
                    output[y][x] = convolvePixel(image, kernel, y, x)
                }
            }
        }
    }
    return output
}

/** Back-compatible alias: row bands across all available cores. */
suspend fun allProcessorsConvolve(
    image: Bitmap,
    kernel: Bitmap,
): Bitmap = rowsConvolve(image, kernel, defaultThreads)

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

private data class Tile(val yFrom: Int, val yTo: Int, val xFrom: Int, val xTo: Int)

private fun workersFor(
    threads: Int,
    maxUseful: Int,
): Int = max(1, min(threads, maxUseful))

private fun defaultBlocks(): Int {
    val n = defaultThreads
    return max(1, sqrt(n.toDouble()).roundToInt())
}

/**
 * Split `0 until total` into `parts` ranges, the first `total % parts` of them
 * one element longer. Returns at most [total] non-empty ranges.
 */
private fun balancedBands(
    total: Int,
    parts: Int,
): List<Pair<Int, Int>> {
    val p = max(1, min(parts, total))
    val base = total / p
    val extra = total % p
    val bands = ArrayList<Pair<Int, Int>>(p)
    var start = 0
    for (i in 0 until p) {
        val end = start + base + if (i < extra) 1 else 0
        bands.add(start to end)
        start = end
    }
    return bands
}

/**
 * Lazily created, cached fixed thread-pool dispatchers keyed by pool size.
 *
 * Threads in `newFixedThreadPoolContext` pools are daemon threads, so cached
 * pools never keep the JVM alive. Caching matters because pool creation would
 * otherwise repeat on every convolution and would skew runs shorter than ~1 s.
 *
 * Note for callers that themselves run on a bounded pool (the Task 3
 * pipeline): convolution must use these pools (or its own dedicated pool),
 * never the caller's pool — otherwise blocked workers and queued child tasks
 * share the same threads and deadlock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private object Pools {
    private val pools = ConcurrentHashMap<Int, CloseableCoroutineDispatcher>()

    fun of(threads: Int): CloseableCoroutineDispatcher =
        pools.computeIfAbsent(max(1, threads)) {
            newFixedThreadPoolContext(it, "convolution-pool-$it")
        }

    fun shutdown() {
        pools.values.forEach { it.close() }
        pools.clear()
    }
}

/** Releases cached thread pools (call at the end of a process/benchmark run). */
fun shutdownConvolutionPools() = Pools.shutdown()

/**
 * Runs [workers] coroutines on a dedicated fixed pool of the same size; each
 * coroutine pulls the next unfinished tile from [tiles] via an atomic cursor
 * (dynamic work stealing — fast cores finish more tiles).
 */
private suspend fun convolveTiles(
    image: Bitmap,
    kernel: Bitmap,
    tiles: List<Tile>,
    workers: Int,
): Bitmap {
    require(kernel.size % 2 == 1 && kernel[0].size % 2 == 1) { "Kernel dimensions must be odd" }

    val output = Array(image.size) { DoubleArray(image[0].size) }
    if (tiles.isEmpty()) return output

    val flipped = flipped(kernel)
    val cursor = AtomicInteger(0)
    val effectiveWorkers = min(workers, tiles.size)
    val dispatcher = Pools.of(effectiveWorkers)
    coroutineScope {
        repeat(effectiveWorkers) {
            launch(dispatcher) {
                while (true) {
                    val tile = cursor.getAndIncrement()
                    if (tile >= tiles.size) break
                    convolveRegion(
                        image,
                        flipped,
                        output,
                        tiles[tile].yFrom,
                        tiles[tile].yTo,
                        tiles[tile].xFrom,
                        tiles[tile].xTo,
                    )
                }
            }
        }
    }
    return output
}
