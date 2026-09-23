package sspoirs.lr8

import sspoirs.mpi.Mpi
import sspoirs.mpi.MpiFile
import sspoirs.mpi.Communicator
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

class MatrixMultGroups {

    fun run(args: List<String>) {
        val numGroups = args.find { it.startsWith("groups=") }?.substringAfter("=")?.toIntOrNull() ?: 2
        val size = args.find { it.startsWith("size=") }?.substringAfter("=")?.toIntOrNull() ?: 600
        val fileA = args.find { it.startsWith("fileA=") }?.substringAfter("=") ?: "matrixA.bin"
        val fileB = args.find { it.startsWith("fileB=") }?.substringAfter("=") ?: "matrixB.bin"

        val world = Mpi.COMM_WORLD

        if (world.rank == 0 && (!File(fileA).exists() || !File(fileB).exists())) {
            println("[LR8] Generating $size x $size binary matrices...")
            createMatrixBinary(fileA, size)
            createMatrixBinary(fileB, size)
        }
        world.barrier()

        // Гарантированное распределение: сначала по 1 процессу в каждую группу, остаток - случайно
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

        println("[LR8] World Rank ${world.rank} -> Group $myGroupId (Local Rank ${groupComm.rank} of ${groupComm.size})")

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

        for (i in 0 until myRowsCount) {
            val offset = (myStartRow + i) * rowBytes
            fileA.readAt(offset, readBuffer)
            ByteBuffer.wrap(readBuffer).asDoubleBuffer().get(myA[i])
        }

        val fullB = if (comm.rank == 0) {
            val matrixB = Array(size) { DoubleArray(size) }
            for (i in 0 until size) {
                fileB.readAt(i * rowBytes, readBuffer)
                ByteBuffer.wrap(readBuffer).asDoubleBuffer().get(matrixB[i])
            }
            comm.bcast(matrixB, 0) as Array<DoubleArray>
        } else {
            comm.bcast("", 0) as Array<DoubleArray>
        }

        val myC = Array(myRowsCount) { DoubleArray(size) }
        for (i in 0 until myRowsCount) {
            for (j in 0 until size) {
                var sum = 0.0
                for (k in 0 until size) sum += myA[i][k] * fullB[k][j]
                myC[i][j] = sum
            }
        }

        val outPath = "result_group_$groupId.bin"
        val groupOutFile = MpiFile(outPath, "rw")
        val writeBuf = ByteBuffer.allocate(size * 8)

        for (i in 0 until myRowsCount) {
            writeBuf.clear()
            myC[i].forEach { writeBuf.putDouble(it) }
            val offset = (myStartRow + i) * rowBytes
            groupOutFile.writeAt(offset, writeBuf.array())
        }

        var localChecksum = 0.0
        myC.forEach { row -> row.forEach { localChecksum += it } }
        val totalChecksum = comm.reduce(localChecksum, 0)

        val duration = System.currentTimeMillis() - startTime
        println("[LR8 Group $groupId] Rank ${comm.rank} finished in ${duration}ms")

        if (comm.rank == 0) {
            println("[LR8 Group $groupId] Total Checksum: ${String.format("%.4f", totalChecksum)} -> Saved to $outPath")
        }

        fileA.close()
        fileB.close()
        groupOutFile.close()
    }

    private fun createMatrixBinary(path: String, size: Int) {
        val file = MpiFile(path, "rw")
        val buf = ByteBuffer.allocate(size * 8)
        for (i in 0 until size) {
            buf.clear()
            repeat(size) { buf.putDouble(Random.nextDouble(1.0, 10.0)) }
            file.writeAt(i * size * 8L, buf.array())
        }
        file.close()
    }
}