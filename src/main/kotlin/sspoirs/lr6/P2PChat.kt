package sspoirs.lr6

import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

class P2PChat(val params: NetworkDiscovery.NetParams) {
    private val port = 9999
    private val multicastGroup = "239.0.0.1"
    val instanceId: String = UUID.randomUUID().toString().take(6) // Уникальный ID для работы на одной машине!

    private val socket: MulticastSocket
    private val groupAddr = InetAddress.getByName(multicastGroup)

    val peers = ConcurrentHashMap<String, PeerInfo>()
    val ignoredPeers = CopyOnWriteArraySet<String>()

    private var isRunning = true
    var mode = ChatMode.BROADCAST

    enum class ChatMode { BROADCAST, MULTICAST }

    init {
        socket = MulticastSocket(port).apply {
            reuseAddress = true
            broadcast = true
            try {
                // Включаем прием локальных пакетов (loopback), чтобы 2 окна на 1 ПК видели друг друга
                setLoopbackMode(false)
            } catch (e: Exception) {}
            soTimeout = 1000
        }

        try {
            val ni = NetworkInterface.getByName(params.interfaceName)
            if (ni != null) {
                socket.networkInterface = ni
            }
            socket.joinGroup(groupAddr)
            println("[$instanceId] Joined Multicast Group $multicastGroup on ${params.interfaceName}")
        } catch (e: Exception) {
            println("[$instanceId] Multicast join fallback: ${e.message}")
        }

        startReceiver()
        startDiscoveryBeacon()
    }

    private fun startReceiver() {
        thread(isDaemon = true, name = "ChatReceiver") {
            val buffer = ByteArray(2048)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    handleIncoming(packet)
                } catch (e: SocketTimeoutException) {
                    // Таймаут сокета для проверки флага isRunning
                } catch (e: Exception) {
                    if (isRunning) println("Receive error: ${e.message}")
                }
            }
        }
    }

    private fun handleIncoming(packet: DatagramPacket) {
        val raw = String(packet.data, 0, packet.length)
        val parts = raw.split("|", limit = 3)
        if (parts.size < 2) return

        val senderId = parts[0]
        val type = parts[1]
        val payload = if (parts.size > 2) parts[2] else ""

        // Игнорируем только самого себя по instanceId! Другие окна на этом же ПК будут приняты!
        if (senderId == instanceId) return

        val senderIp = packet.address.hostAddress
        if (ignoredPeers.contains(senderId) || ignoredPeers.contains(senderIp)) return

        when (type) {
            "HELLO" -> {
                peers.getOrPut(senderId) { PeerInfo("$senderIp:$senderId") }.lastSeen = System.currentTimeMillis()
            }
            "MSG" -> {
                println("\n[$senderIp ($senderId)]: $payload")
                print("> ")
                System.out.flush()
            }
            "EXIT" -> {
                peers.remove(senderId)
                println("\n[INFO] Peer $senderId left the chat.")
                print("> ")
                System.out.flush()
            }
        }
    }

    private fun startDiscoveryBeacon() {
        thread(isDaemon = true, name = "DiscoveryBeacon") {
            while (isRunning) {
                sendDiscovery()
                cleanOldPeers()
                Thread.sleep(3000)
            }
        }
    }

    private fun sendDiscovery() {
        sendPacket("HELLO", "")
    }

    private fun cleanOldPeers() {
        val now = System.currentTimeMillis()
        peers.values.removeIf { now - it.lastSeen > 10000 }
    }

    fun sendMessage(text: String) {
        sendPacket("MSG", text)
        println("[You]: $text")
    }

    private fun sendPacket(type: String, payload: String) {
        try {
            val data = "$instanceId|$type|$payload".toByteArray()
            val targetHost = if (mode == ChatMode.BROADCAST) params.broadcast else multicastGroup
            val addr = InetAddress.getByName(targetHost)
            val packet = DatagramPacket(data, data.size, addr, port)
            socket.send(packet)

            // Дублируем на 127.0.0.1 для 100% надежности при тесте на одной машине
            val loopbackAddr = InetAddress.getByName("127.0.0.1")
            socket.send(DatagramPacket(data, data.size, loopbackAddr, port))
        } catch (e: Exception) {
            // ignore
        }
    }

    fun leaveMulticast() {
        try {
            socket.leaveGroup(groupAddr)
            println("Left multicast group.")
        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }

    fun close() {
        isRunning = false
        sendPacket("EXIT", "")
        try { socket.close() } catch (e: Exception) {}
    }
}