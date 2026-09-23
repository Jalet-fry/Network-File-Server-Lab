package sspoirs.lr6

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

object NetworkDiscovery {

    data class NetParams(
        val ip: String,
        val mask: String,
        val broadcast: String,
        val interfaceName: String
    )

    /**
     * Автоопределение с возможностью ручного выбора интерфейса
     */
    fun discover(manualIp: String? = null): NetParams? {
        // Если указан IP вручную, ищем интерфейс с этим IP
        if (manualIp != null) {
            return discoverByIp(manualIp)
        }
        
        // Автоопределение с фильтрацией виртуальных адаптеров
        val interfaces = NetworkInterface.getNetworkInterfaces()
        val candidates = mutableListOf<NetParams>()
        
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            
            // Пропускаем loopback и неактивные
            if (iface.isLoopback || !iface.isUp) continue
            
            // Фильтруем виртуальные адаптеры Windows
            val name = iface.displayName.lowercase(Locale.getDefault())
            if (isVirtualInterface(name, iface)) continue
            
            val params = extractParams(iface)
            if (params != null) {
                // Приоритет у адаптеров с реальными broadcast адресами
                if (params.broadcast != "N/A" && params.broadcast != "0.0.0.0") {
                    return params // Сразу возвращаем хороший вариант
                }
                candidates.add(params)
            }
        }
        
        // Если хороших нет, берем любой
        return candidates.firstOrNull()
    }

    private fun discoverByIp(ip: String): NetParams? {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            
            for (addr in iface.interfaceAddresses) {
                val address = addr.address
                if (address is Inet4Address && address.hostAddress == ip) {
                    return NetParams(
                        ip = ip,
                        mask = prefixToMask(addr.networkPrefixLength),
                        broadcast = addr.broadcast?.hostAddress ?: calculateBroadcast(ip, addr.networkPrefixLength),
                        interfaceName = iface.name
                    )
                }
            }
        }
        return null
    }

    private fun isVirtualInterface(name: String, iface: NetworkInterface): Boolean {
        // Проверяем по имени
        val virtualKeywords = listOf(
            "virtual", "vmware", "virtualbox", "vbox", 
            "hyper-v", "hyperv", "wsl", "docker",
            "ethernet adapter v", "bluetooth", "adapter for loopback"
        )
        
        if (virtualKeywords.any { name.contains(it) }) return true
        
        // Проверка по hardware address (MAC)
        try {
            val hw = iface.hardwareAddress
            if (hw != null && hw.isNotEmpty()) {
                // VMware: 00:50:56, 00:0C:29
                // VirtualBox: 08:00:27
                // Hyper-V: 00:15:5D
                val prefix = hw.take(3).joinToString("") { "%02X".format(it.toInt()) }
                val virtualMacs = listOf("005056", "000C29", "080027", "00155D")
                if (virtualMacs.any { prefix.startsWith(it) }) return true
            }
        } catch (e: Exception) { /* ignore */ }
        
        return false
    }

    private fun extractParams(iface: NetworkInterface): NetParams? {
        for (addr in iface.interfaceAddresses) {
            val ip = addr.address
            if (ip is Inet4Address && !ip.isLoopbackAddress) {
                // Проверяем, что IP не из диапазона виртуальных адаптеров
                val ipStr = ip.hostAddress
                if (ipStr.startsWith("169.254.")) continue // APIPA
                if (ipStr.startsWith("127.")) continue // Loopback
                
                return NetParams(
                    ip = ipStr,
                    mask = prefixToMask(addr.networkPrefixLength),
                    broadcast = addr.broadcast?.hostAddress 
                        ?: calculateBroadcast(ipStr, addr.networkPrefixLength),
                    interfaceName = iface.name
                )
            }
        }
        return null
    }

    private fun calculateBroadcast(ip: String, prefix: Short): String {
        try {
            val parts = ip.split(".").map { it.toInt() }
            val mask = -1 shl (32 - prefix.toInt())
            val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
            val broadcast = ipInt or mask.inv()
            return "${(broadcast shr 24) and 0xFF}.${(broadcast shr 16) and 0xFF}.${(broadcast shr 8) and 0xFF}.${broadcast and 0xFF}"
        } catch (e: Exception) {
            return "255.255.255.255"
        }
    }

    private fun prefixToMask(prefix: Short): String {
        val mask = -1 shl (32 - prefix.toInt())
        return listOf(
            (mask shr 24) and 0xFF,
            (mask shr 16) and 0xFF,
            (mask shr 8) and 0xFF,
            mask and 0xFF
        ).joinToString(".")
    }

    /**
     * Вывод всех доступных интерфейсов для ручного выбора
     */
    fun listInterfaces(): List<NetParams> {
        val result = mutableListOf<NetParams>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val params = extractParams(iface)
            if (params != null) result.add(params)
        }
        return result
    }
}
