package sspoirs.mpi

import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Communicator(
    val rank: Int,
    val hosts: List<String>,
    private val connections: Map<Int, Socket>,
    private val parentComm: Communicator? = null,
    private val groupGlobalRanks: List<Int>? = null
) {
    val size: Int = hosts.size
    private val allMessages = CopyOnWriteArrayList<MpiMessage>()
    private val streams = ConcurrentHashMap<Int, Pair<ObjectOutputStream, ObjectInputStream>>()
    private val outputLocks = ConcurrentHashMap<Int, Any>() // Unique lock for each destination
    private val isClosed = AtomicBoolean(false)
    private val receiveThreads = mutableListOf<Thread>()

    companion object {
        const val ANY_SOURCE = -1
        const val ANY_TAG = -1
        const val SOCKET_TIMEOUT_MS = 30000L
    }

    init {
        if (parentComm == null) {
            connections.forEach { (r, socket) ->
                try {
                    // Setup timeouts and performance flags
                    socket.soTimeout = SOCKET_TIMEOUT_MS.toInt()
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    
                    val out = ObjectOutputStream(socket.getOutputStream())
                    out.flush()
                    val inp = ObjectInputStream(socket.getInputStream())
                    streams[r] = out to inp
                    outputLocks[r] = Any()
                    
                    val thread = Thread({ receiveLoop(r, inp) }, "MpiReceiver-$r")
                    thread.isDaemon = true
                    thread.start()
                    receiveThreads.add(thread)
                } catch (e: Exception) {
                    println("[MPI] Error initializing connection to Rank $r: ${e.message}")
                }
            }
        }
    }

    private fun receiveLoop(fromRank: Int, input: ObjectInputStream) {
        try {
            while (!isClosed.get()) {
                try {
                    val msg = input.readObject() as? MpiMessage ?: break
                    synchronized(allMessages) {
                        allMessages.add(msg)
                        (allMessages as Object).notifyAll()
                    }
                } catch (e: EOFException) {
                    break
                } catch (e: SocketTimeoutException) {
                    if (isClosed.get()) break
                    continue
                } catch (e: Exception) {
                    if (!isClosed.get()) {
                        println("[MPI] Error receiving from $fromRank: ${e.message}")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            // Ignore during shutdown
        } finally {
            if (!isClosed.get()) {
                // Potential connection loss
            }
        }
    }

    /**
     * Safe send with per-destination locking and optimized reset.
     */
    fun send(dest: Int, tag: Int, data: Any) {
        if (parentComm != null && groupGlobalRanks != null) {
            val globalDest = groupGlobalRanks[dest]
            parentComm.send(globalDest, tag, data)
            return
        }

        if (isClosed.get()) {
            throw IllegalStateException("Communicator is closed")
        }
        
        if (dest == rank) {
            synchronized(allMessages) {
                allMessages.add(MpiMessage(rank, tag, data))
                (allMessages as Object).notifyAll()
            }
            return
        }
        
        val pair = streams[dest] ?: throw IllegalArgumentException("No connection to rank $dest")
        val out = pair.first
        val lock = outputLocks[dest] ?: throw IllegalStateException("No lock for rank $dest")
        
        synchronized(lock) {
            try {
                out.writeObject(MpiMessage(rank, tag, data))
                out.flush()
                
                // Optimized reset: don't reset for very large objects (like matrices)
                // as it might be expensive, but reset for small ones to prevent memory leaks
                if (data !is Array<*> || data.size <= 1000) {
                    out.reset()
                }
            } catch (e: IOException) {
                isClosed.set(true)
                throw RuntimeException("Failed to send to rank $dest: ${e.message}", e)
            }
        }
    }


    fun recv(source: Int = ANY_SOURCE, tag: Int = ANY_TAG): Any {
        return recvMessage(source, tag).data
    }

    fun recvMessage(source: Int = ANY_SOURCE, tag: Int = ANY_TAG): MpiMessage {
        if (parentComm != null && groupGlobalRanks != null) {
            val globalSource = if (source == ANY_SOURCE) -1 else groupGlobalRanks[source]
            val targetLock = parentComm.allMessages
            synchronized(targetLock) {
                while (true) {
                    if (parentComm.isClosed.get()) {
                        throw IllegalStateException("Parent communicator is closed")
                    }
                    val msg = parentComm.allMessages.find {
                        val sourceMatches = if (globalSource == -1) groupGlobalRanks.contains(it.source) else it.source == globalSource
                        sourceMatches && (tag == ANY_TAG || it.tag == tag)
                    }
                    if (msg != null) {
                        parentComm.allMessages.remove(msg)
                        val subSource = groupGlobalRanks.indexOf(msg.source)
                        return MpiMessage(subSource, msg.tag, msg.data)
                    }
                    try {
                        (targetLock as Object).wait(1000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RuntimeException("Interrupted while waiting for message", e)
                    }
                }
            }
        }

        synchronized(allMessages) {
            while (true) {
                if (isClosed.get()) {
                    throw IllegalStateException("Communicator is closed")
                }
                
                val msg = allMessages.find { 
                    (source == ANY_SOURCE || it.source == source) && 
                    (tag == ANY_TAG || it.tag == tag) 
                }
                if (msg != null) {
                    allMessages.remove(msg)
                    return msg
                }
                try {
                    (allMessages as Object).wait(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RuntimeException("Interrupted while waiting for message", e)
                }
            }
        }
    }

    fun isend(dest: Int, tag: Int, data: Any): MpiRequest {
        val req = MpiRequest()
        Thread {
            try {
                send(dest, tag, data)
                req.complete()
            } catch (e: Exception) {
                req.completeWithError(e)
            }
        }.start()
        return req
    }

    fun irecv(source: Int, tag: Int): MpiRequest {
        val req = MpiRequest()
        Thread {
            try {
                val data = recv(source, tag)
                req.complete(data)
            } catch (e: Exception) {
                req.completeWithError(e)
            }
        }.start()
        return req
    }

    fun barrier() {
        if (size == 1) return
        if (rank == 0) {
            for (i in 1 until size) recv(i, 999)
            for (i in 1 until size) send(i, 999, "GO")
        } else {
            send(0, 999, "READY")
            recv(0, 999)
        }
    }

    fun close() {
        if (isClosed.getAndSet(true)) return
        
        receiveThreads.forEach { it.interrupt() }
        
        connections.values.forEach { 
            try { it.close() } catch (e: Exception) { /* ignore */ }
        }
        streams.clear()
    }

    // --- Collective Operations (Lab 8) ---

    fun bcast(data: Any, root: Int): Any {
        if (rank == root) {
            for (i in 0 until size) {
                if (i != rank) send(i, 888, data)
            }
            return data
        } else {
            return recv(root, 888)
        }
    }

    fun gather(data: Any, root: Int): List<Any>? {
        if (rank == root) {
            val results = mutableListOf<Any>()
            for (i in 0 until size) {
                if (i == rank) results.add(data)
                else results.add(recv(i, 889))
            }
            return results
        } else {
            send(root, 889, data)
            return null
        }
    }

    fun reduce(data: Double, root: Int): Double {
        if (rank == root) {
            var sum = data
            for (i in 0 until size) {
                if (i != rank) sum += recv(i, 890) as Double
            }
            return sum
        } else {
            send(root, 890, data)
            return 0.0
        }
    }

    fun createGroup(ranks: List<Int>): Communicator? {
        if (!ranks.contains(this.rank)) return null
        
        val newRank = ranks.indexOf(this.rank)
        val newHosts = ranks.map { this.hosts[it] }
        
        return Communicator(newRank, newHosts, mapOf(), parentComm = this, groupGlobalRanks = ranks)
    }

    private data class MatrixMeta(val rows: Int, val cols: Int) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

data class MpiMessage(val source: Int, val tag: Int, val data: Any) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class MpiRequest {
    private val latch = CountDownLatch(1)
    private var data: Any? = null
    private var error: Throwable? = null

    fun complete(result: Any? = null) {
        data = result
        latch.countDown()
    }
    
    fun completeWithError(e: Throwable) {
        error = e
        latch.countDown()
    }

    fun wait(): Any? {
        latch.await()
        if (error != null) throw RuntimeException(error)
        return data
    }
    
    fun wait(timeout: Long, unit: TimeUnit): Any? {
        if (!latch.await(timeout, unit)) {
            throw RuntimeException("Request timeout")
        }
        if (error != null) throw RuntimeException(error)
        return data
    }
    
    fun isCompleted() = latch.count == 0L
}
