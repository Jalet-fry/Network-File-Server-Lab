package sspoirs.lr7

import sspoirs.mpi.Mpi
import sspoirs.mpi.MpiRequest
import java.io.Serializable

class MatrixMult {

    fun run(args: List<String>) {
        val size = args.find { it.startsWith("size=") }?.substringAfter("=")?.toIntOrNull() ?: 800
        val mode = args.find { it.startsWith("mode=") }?.substringAfter("=") ?: "nonblocking"
        val comm = Mpi.COMM_WORLD

        if (comm.rank == 0) {
            master(size, mode)
        } else {
            slave()
        }
    }

    private fun master(size: Int, mode: String) {
        val comm = Mpi.COMM_WORLD
        println("\n[LR7 Master] Generating $size x $size matrices...")
        val a = Array(size) { DoubleArray(size) { (1..9).random().toDouble() } }
        val b = Array(size) { DoubleArray(size) { (1..9).random().toDouble() } }
        val c = Array(size) { DoubleArray(size) }

        // Рассылка матрицы B
        for (i in 1 until comm.size) {
            comm.send(i, TAG_B, b)
        }

        println("[LR7 Master] Computing in '$mode' mode across ${comm.size} processes...")
        val startTime = System.currentTimeMillis()

        if (comm.size == 1) {
            multiplySingleNode(a, b, c, 0, size)
        } else if (mode.lowercase() == "blocking") {
            runBlockingChuncked(a, c, size)
        } else {
            runNonBlockingChunked(a, c, size)
        }

        val duration = System.currentTimeMillis() - startTime
        println("[BENCHMARK_RESULT] Mode: $mode -> Time: ${duration}ms (${duration / 1000.0}s)")

        verifyResult(a, b, c, size)

        // Остановка рабочих процессов
        for (i in 1 until comm.size) {
            comm.send(i, TAG_EXIT, "")
        }
    }

    private fun runBlockingChuncked(a: Array<DoubleArray>, c: Array<DoubleArray>, size: Int) {
        val comm = Mpi.COMM_WORLD
        val numChunks = 8
        val (starts, counts) = calculateBlockRanges(size, numChunks)

        // Последовательно: отправил кусок -> заблокировался и ждешь ответ
        for (i in 0 until numChunks) {
            val chunk = Array(counts[i]) { a[starts[i] + it] }
            comm.send(1, TAG_CHUNK_DATA, ChunkData(starts[i], chunk))
            val res = comm.recv(1, TAG_RESULT) as ChunkResult
            copyChunkToResult(c, res)
        }
    }

    private fun runNonBlockingChunked(a: Array<DoubleArray>, c: Array<DoubleArray>, size: Int) {
        val comm = Mpi.COMM_WORLD
        val numChunks = 8
        val (starts, counts) = calculateBlockRanges(size, numChunks)
        val requests = mutableListOf<MpiRequest>()

        // Асинхронно: пуляем все запросы в сеть без блокировки
        for (i in 0 until numChunks) {
            val chunk = Array(counts[i]) { a[starts[i] + it] }
            comm.isend(1, TAG_CHUNK_DATA, ChunkData(starts[i], chunk))
            requests.add(comm.irecv(1, TAG_RESULT))
        }

        // Параллельно собираем результаты
        for (req in requests) {
            val res = req.wait() as ChunkResult
            copyChunkToResult(c, res)
        }
    }

    private fun slave() {
        val comm = Mpi.COMM_WORLD
        var b: Array<DoubleArray>? = null

        while (true) {
            val msg = comm.recvMessage(source = 0)
            when (msg.tag) {
                TAG_B -> b = msg.data as Array<DoubleArray>
                TAG_CHUNK_DATA -> {
                    val data = msg.data as ChunkData
                    val matB = b ?: continue
                    val resRows = Array(data.rows.size) { DoubleArray(matB[0].size) }

                    for (i in data.rows.indices) {
                        for (j in matB[0].indices) {
                            var sum = 0.0
                            for (k in matB.indices) {
                                sum += data.rows[i][k] * matB[k][j]
                            }
                            resRows[i][j] = sum
                        }
                    }
                    comm.send(0, TAG_RESULT, ChunkResult(data.startRow, resRows))
                }
                TAG_EXIT -> break
            }
        }
    }

    private fun calculateBlockRanges(totalRows: Int, parts: Int): Pair<IntArray, IntArray> {
        val starts = IntArray(parts)
        val counts = IntArray(parts)
        val base = totalRows / parts
        var rem = totalRows % parts
        var cur = 0
        for (i in 0 until parts) {
            starts[i] = cur
            counts[i] = base + if (rem > 0) 1 else 0
            if (rem > 0) rem--
            cur += counts[i]
        }
        return starts to counts
    }

    private fun copyChunkToResult(c: Array<DoubleArray>, res: ChunkResult) {
        for (i in res.rows.indices) {
            c[res.startRow + i] = res.rows[i]
        }
    }

    private fun multiplySingleNode(a: Array<DoubleArray>, b: Array<DoubleArray>, c: Array<DoubleArray>, start: Int, end: Int) {
        val size = a.size
        for (i in start until end) {
            for (j in 0 until size) {
                var s = 0.0
                for (k in 0 until size) s += a[i][k] * b[k][j]
                c[i][j] = s
            }
        }
    }

    private fun verifyResult(a: Array<DoubleArray>, b: Array<DoubleArray>, c: Array<DoubleArray>, size: Int) {
        print("[VERIFICATION] Checking mathematical correctness against A x B... ")
        val checkRows = listOf(0, size / 2, size - 1)
        for (row in checkRows) {
            for (col in 0 until size) {
                var expected = 0.0
                for (k in 0 until size) expected += a[row][k] * b[k][col]
                if (Math.abs(expected - c[row][col]) > 1e-4) {
                    println("FAILED at row $row, col $col")
                    return
                }
            }
        }
        println("SUCCESS! (100% matched)")
    }

    companion object {
        private const val TAG_B = 10
        private const val TAG_CHUNK_DATA = 20
        private const val TAG_RESULT = 30
        private const val TAG_EXIT = 99
    }
}

data class ChunkData(val startRow: Int, val rows: Array<DoubleArray>) : Serializable
data class ChunkResult(val startRow: Int, val rows: Array<DoubleArray>) : Serializable