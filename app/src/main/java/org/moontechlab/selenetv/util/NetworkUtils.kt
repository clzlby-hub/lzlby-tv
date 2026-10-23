package org.moontechlab.selenetv.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * 获取设备所有可用的 IPv4 地址（排除回环与 link-local）。
     * 优先返回物理网卡（wlan/eth），并按常用局域网网段（192.168.x.x, 10.x.x.x, 172.16-31.x.x）排序。
     */
    fun getAllIpAddresses(): List<String> {
        val physicalIps = mutableListOf<String>()
        val otherIps = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                // 排除常见虚拟网卡
                if (name.startsWith("dummy") || name.startsWith("p2p") || name.startsWith("tun") || name.startsWith("sit")) {
                    continue
                }
                val isPhysical = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")

                for (addr in ni.inetAddresses.toList()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.") || host.startsWith("169.254.")) continue
                        if (isPhysical) {
                            physicalIps.add(host)
                        } else {
                            otherIps.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 合并并去重
        val all = (physicalIps + otherIps).distinct()
        // 排序优先级：192.168.x.x -> 10.x.x.x -> 172.16-31.x.x -> 其余
        return all.sortedWith { ip1, ip2 ->
            ipScore(ip2).compareTo(ipScore(ip1))
        }
    }

    private fun ipScore(ip: String): Int {
        return when {
            ip.startsWith("192.168.") -> 100
            ip.startsWith("10.") -> 90
            is172Private(ip) -> 80
            else -> 10
        }
    }

    private fun is172Private(ip: String): Boolean {
        if (!ip.startsWith("172.")) return false
        val secondOctet = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return secondOctet in 16..31
    }

    /**
     * 获取最佳首选 IPv4 地址
     */
    fun getPreferredIpAddress(): String? {
        val ips = getAllIpAddresses()
        return ips.firstOrNull()
    }
}
