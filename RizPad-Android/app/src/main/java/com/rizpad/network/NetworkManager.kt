package com.rizpad.network

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue

/**
 * TCP Server (Android port of iOS NetworkManager).
 *
 * Listens on a fixed port (24864). Accepts one PC client at a time.
 * Sends binary key-state packets via a dedicated sender thread (avoids
 * NetworkOnMainThreadException on the UI/Choreographer thread).
 *
 * Packet format: [1 byte length N] [1 byte command 0x01] [N-1 bytes: ASCII key codes]
 */
class NetworkManager {

    companion object {
        const val PORT: Int = 24864
        private const val TAG = "RizPad.Net"
        private const val CMD_KEY_STATE: Byte = 0x01
    }

    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var running = false

    private val sendLock = Any()

    /** Lock-free queue so the UI thread never blocks on network I/O. */
    private val sendQueue = LinkedBlockingQueue<ByteArray>(4)

    /**
     * Start the TCP server and sender thread.
     * Accepts clients in a loop; only one client at a time.
     */
    fun start() {
        if (running) return
        running = true

        // Sender thread – drains the queue and writes to socket
        Thread({
            while (running) {
                try {
                    val packet = sendQueue.take() // blocks until available
                    synchronized(sendLock) {
                        val os = outputStream ?: return@synchronized
                        try {
                            os.write(packet)
                            os.flush()
                        } catch (e: IOException) {
                            Log.w(TAG, "Send error: ${e.message}")
                            disconnectClient()
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "RizPad-Sender").apply { isDaemon = true }.start()

        // Accept thread
        Thread({
            try {
                val server = ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(PORT))
                    serverSocket = it
                }
                Log.i(TAG, "Listening on port $PORT")

                while (running) {
                    try {
                        val socket = server.accept()
                        handleNewConnection(socket)
                    } catch (e: SocketException) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create server: ${e.message}")
            }
        }, "RizPad-TCPServer").apply { isDaemon = true }.start()
    }

    /** Stop the server and disconnect the current client. */
    fun stop() {
        running = false
        sendQueue.clear()
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        outputStream = null
        serverSocket = null
        setConnected(false)
    }

    /**
     * Enqueue a key-state packet. Safe to call from any thread (including UI).
     * Drops oldest packet if the queue is full (keeps latency low).
     */
    fun sendKeyState(pressedKeys: List<Byte>) {
        if (!isConnected) return

        // Build packet: [length] [CMD_KEY_STATE] [keys...]
        val payloadLen = 1 + pressedKeys.size
        val packet = ByteArray(1 + payloadLen)
        packet[0] = payloadLen.coerceAtMost(255).toByte()
        packet[1] = CMD_KEY_STATE
        for (i in pressedKeys.indices) {
            packet[i + 2] = pressedKeys[i]
        }

        // Non-blocking offer; drop old if full
        if (!sendQueue.offer(packet)) {
            sendQueue.poll()
            sendQueue.offer(packet)
        }
    }

    /** Get the server's listening port. */
    fun getPort(): Int = PORT

    private fun handleNewConnection(socket: Socket) {
        // Disconnect previous client
        disconnectClient()

        socket.tcpNoDelay = true
        synchronized(sendLock) {
            clientSocket = socket
            outputStream = socket.getOutputStream()
        }
        setConnected(true)
        Log.i(TAG, "Client connected: ${socket.remoteSocketAddress}")

        // Reader thread (detect disconnection)
        Thread({
            try {
                val input = socket.getInputStream()
                val buf = ByteArray(256)
                while (running && !socket.isClosed) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        Log.d(TAG, "Received $n bytes from PC")
                    }
                }
            } catch (_: IOException) {
                // Client disconnected
            } finally {
                disconnectClient()
            }
        }, "RizPad-ClientReader").apply { isDaemon = true }.start()
    }

    private fun disconnectClient() {
        synchronized(sendLock) {
            try { clientSocket?.close() } catch (_: Exception) {}
            clientSocket = null
            outputStream = null
        }
        sendQueue.clear()
        setConnected(false)
    }

    private fun setConnected(value: Boolean) {
        val changed = (isConnected != value)
        isConnected = value
        if (changed) {
            Log.i(TAG, if (value) "Client connected" else "Client disconnected")
            onConnectionChanged?.invoke(value)
        }
    }
}
