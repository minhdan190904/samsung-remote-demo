package com.example.samsung_remote_test.samsung

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object SamsungWol {
    fun send(mac: String, broadcast: String = "255.255.255.255", port: Int = 9): Boolean {
        val clean = mac.replace(":", "").replace("-", "").trim()
        if (clean.length != 12) return false
        val macBytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val packetData = ByteArray(6 + 16 * macBytes.size)
        repeat(6) { packetData[it] = 0xFF.toByte() }
        var idx = 6
        repeat(16) {
            macBytes.copyInto(packetData, idx)
            idx += macBytes.size
        }

        return runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val addr = InetAddress.getByName(broadcast)
                val packet = DatagramPacket(packetData, packetData.size, addr, port)
                socket.send(packet)
            }
            true
        }.getOrDefault(false)
    }
}