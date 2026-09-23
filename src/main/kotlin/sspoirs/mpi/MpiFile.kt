package sspoirs.mpi

import java.io.RandomAccessFile

class MpiFile(val path: String, mode: String = "r") {
    private val raf = RandomAccessFile(path, mode)

    fun readAt(offset: Long, buffer: ByteArray): Int {
        synchronized(raf) {
            raf.seek(offset)
            return raf.read(buffer)
        }
    }

    fun writeAt(offset: Long, buffer: ByteArray) {
        synchronized(raf) {
            raf.seek(offset)
            raf.write(buffer)
        }
    }

    fun length(): Long = raf.length()

    fun close() {
        raf.close()
    }
}
