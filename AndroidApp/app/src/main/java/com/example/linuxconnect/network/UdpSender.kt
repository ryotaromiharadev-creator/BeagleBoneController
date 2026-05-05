package com.example.linuxconnect.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 2-byte RC control パケットを UDP で送受信するクラス。
 *
 * 送信: [throttle: Int8][-128..127] [steering: Int8][-128..127]
 * 受信: サーバーからの heartbeat (1 byte = 0x01) を同一ソケットで受け取る。
 *       send() と awaitHeartbeat() は別スレッドから同時に呼んでよい
 *       (DatagramSocket の send/receive は内部的に独立した syscall)。
 */
class UdpSender(host: String, val port: Int) : AutoCloseable {

    private val socket  = DatagramSocket()
    private val address = InetAddress.getByName(host)
    private val sendBuf = ByteArray(2)
    private val sendPkt = DatagramPacket(sendBuf, 2, address, port)

    fun send(throttle: Int, steering: Int) {
        sendBuf[0] = throttle.coerceIn(-128, 127).toByte()
        sendBuf[1] = steering.coerceIn(-128, 127).toByte()
        socket.send(sendPkt)
    }

    /**
     * サーバーからの heartbeat (0x01) を最大 [timeoutMs] ms 待つ。
     * - heartbeat 受信 → true
     * - タイムアウト   → false (サーバーがダウンしている)
     * - ソケット閉鎖   → false (disconnect() 後)
     *
     * ブロッキング呼び出し — IO スレッドから呼ぶこと。
     */
    fun awaitHeartbeat(timeoutMs: Int = 3000): Boolean {
        return try {
            socket.soTimeout = timeoutMs
            val buf = ByteArray(1)
            socket.receive(DatagramPacket(buf, 1))
            buf[0] == 0x01.toByte()
        } catch (_: SocketTimeoutException) { false }
          catch (_: SocketException)        { false }
    }

    override fun close() = socket.close()
}
