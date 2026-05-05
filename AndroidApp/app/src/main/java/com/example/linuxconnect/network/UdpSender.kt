package com.example.linuxconnect.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends 2-byte RC control packets via UDP.
 * Packet layout: [throttle: Int8][-128..127] [steering: Int8][-128..127]
 *
 * Must be created and used on a background thread (socket I/O).
 */
class UdpSender(host: String, val port: Int) : AutoCloseable {

    private val socket  = DatagramSocket()
    private val address = InetAddress.getByName(host)
    private val buf     = ByteArray(2)
    private val packet  = DatagramPacket(buf, 2, address, port)

    /** Values clamped to -128..127 before encoding. */
    fun send(throttle: Int, steering: Int) {
        buf[0] = throttle.coerceIn(-128, 127).toByte()
        buf[1] = steering.coerceIn(-128, 127).toByte()
        socket.send(packet)
    }

    override fun close() = socket.close()
}
