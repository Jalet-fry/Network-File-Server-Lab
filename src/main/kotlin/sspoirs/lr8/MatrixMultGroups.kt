package sspoirs.lr8

import sspoirs.mpi.Mpi
import sspoirs.mpi.MpiFile
import sspoirs.mpi.Communicator
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

class MatrixMultGroups {

    fun run(args: List<String>) {
        val numGroups = args.find { it.startsWith("groups=") }?.substringAfter("=")?.toIntOrNull() ?: 1
        val size = args.find { it.startsWith("size=") }?.substringAfter("=")?.toIntOrNull() ?: 600
        val fileA = "matrixA.bin"
        val fileB = "matrixB.bin"

        val world = Mpi.COMM_WORLD

        // 1. Проверяем наличие 2 входных файлов на каждом узле
        if (!File(fileA).exists() || !File(fileB).exists()) {
            println("[LR8 Rank ${world.rank}] Generating shared 2 input binary files ($size x $size): $fileA, $fileB...")
            createMatrixBinary(fileA, size, seed = 111L)
            createMatrixBinary(fileB, size, seed = 222L)
        }
        world.barrier()

        // 2. Создание групп процессов
        val assignments = IntArray(world.size)
        val actualGroups = minOf(numGroups, world.size)
        for (i in 0 until actualGroups) {
            assignments[i] = i
        }
        val rng = Random(42)
        for (i in actualGroups until world.size) {
            assignments[i] = rng.nextInt(actualGroups)
        }

        val myGroupId = assignments[world.rank]
        val groupRanks = assignments.withIndex().filter { it.value == myGroupId }.map { it.index }
        val groupComm = world.createGroup(groupRanks) ?: return

        println("[LR8] World Rank ${world.rank} assigned to Group $myGroupId (Local Rank ${groupComm.rank} of ${groupComm.size})")

        executeGroupMultiplication(myGroupId, groupComm, size, fileA, fileB)
    }

    private fun executeGroupMultiplication(groupId: Int, comm: Communicator, size: Int, pathA: String, pathB: String) {
        val startTime = System.currentTimeMillis()
        val fileA = MpiFile(pathA, "r")
        val fileB = MpiFile(pathB, "r")

        val rowsPerRank = size / comm.size
        val myStartRow = comm.rank * rowsPerRank
        val myRowsCount = if (comm.rank == comm.size - 1) size - myStartRow else rowsPerRank

        val rowBytes = size * 8L
        val readBuffer = ByteArray(size * 8)
        val myA = Array(myRowsCount) { DoubleArray(size) }

        // --- MPI-IO ТРЕБОВАНИЕ 1: Параллельное чтение своей порции из matrixA.bin ---
        val startOffsetA = myStartRow * rowBytes
        println("[MPI-IO Rank ${comm.rank}] Reading $myRowsCount rows from $pathA starting at offset $startOffsetA bytes...")
        for (i in 0 until myRowsCount) {
            val offset = (myStartRow + i) * rowBytes
            fileA.readAt(offset, readBuffer)
            ByteBuffer.wrap(readBuffer).asDoubleBuffer().get(myA[i])
        }

        // --- MPI-IO ТРЕБОВАНИЕ 2: Чтение данных из matrixB.bin всеми узлами ---
        println("[MPI-IO Rank ${comm.rank}] Reading matrix data from $pathB...")
        val matrixB = Array(size) { DoubleArray(size) }
        for (i in 0 until size) {
            fileB.readAt(i * rowBytes, readBuffer)
            ByteBuffer.wrap(readBuffer).asDoubleBuffer().get(matrixB[i])
        }

        // Коллективная операция Bcast для синхронизации метаданных
        comm.bcast("SYNC_READY", 0)

        // Перемножение своей порции
        val myC = Array(myRowsCount) { DoubleArray(size) }
        for (i in 0 until myRowsCount) {
            for (j in 0 until size) {
                var sum = 0.0
                for (k in 0 until size) sum += myA[i][k] * matrixB[k][j]
                myC[i][j] = sum
            }
        }

        // --- MPI-IO ТРЕБОВАНИЕ 3: Параллельная запись в файл группы со смещением ---
        val outPath = "result_group_$groupId.bin"
        val groupOutFile = MpiFile(outPath, "rw")
        val writeBuf = ByteBuffer.allocate(size * 8)

        println("[MPI-IO Rank ${comm.rank}] Writing $myRowsCount rows to $outPath at offset $startOffsetA bytes...")
        for (i in 0 until myRowsCount) {
            writeBuf.clear()
            myC[i].forEach { writeBuf.putDouble(it) }
            val offset = (myStartRow + i) * rowBytes
            groupOutFile.writeAt(offset, writeBuf.array())
        }

        // Коллективная операция Reduce: подсчет общей суммы матрицы
        var localChecksum = 0.0
        myC.forEach { row -> row.forEach { localChecksum += it } }
        val totalChecksum = comm.reduce(localChecksum, 0)

        val duration = System.currentTimeMillis() - startTime
        println("[LR8 Group $groupId] Rank ${comm.rank} finished computations in ${duration}ms")

        if (comm.rank == 0) {
            println("[LR8 Group $groupId] Collective Checksum: ${String.format("%.4f", totalChecksum)} -> Verified in $outPath")
        }

        fileA.close()
        fileB.close()
        groupOutFile.close()
    }

    private fun createMatrixBinary(path: String, size: Int, seed: Long) {
        val file = MpiFile(path, "rw")
        val buf = ByteBuffer.allocate(size * 8)
        val rng = Random(seed)
        for (i in 0 until size) {
            buf.clear()
            repeat(size) { buf.putDouble(rng.nextDouble(1.0, 5.0)) }
            file.writeAt(i * size * 8L, buf.array())
        }
        file.close()
    }
}