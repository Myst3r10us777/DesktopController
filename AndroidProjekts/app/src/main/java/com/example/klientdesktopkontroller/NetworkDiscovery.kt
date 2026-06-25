package com.example.klientdesktopkontroller

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class NetworkDiscovery {
    companion object {
        private const val TAG = "NetworkDiscovery"
        private const val DISCOVERY_PORT = 8766
        private const val DISCOVERY_MESSAGE = "DISCOVER_DESKTOP_CONTROLLER"
        private const val BUFFER_SIZE = 1024
        private const val TIMEOUT_MS = 2000
    }

    private var isRunning = false
    private var discoveryThread: Thread? = null
    private var socket: DatagramSocket? = null

    fun discoverServers(
        onServerFound: (ip: String, port: Int, name: String) -> Unit,
        onError: (String) -> Unit,
        timeoutSeconds: Int = 5,
        attempts: Int = 3
    ) {
        if (isRunning) {
            Log.w(TAG, "⚠️ Поиск уже выполняется")
            return
        }

        isRunning = true

        discoveryThread = Thread {
            try {
                socket = DatagramSocket()
                socket?.soTimeout = TIMEOUT_MS
                socket?.broadcast = true

                Log.d(TAG, "Начинаем поиск сервера в сети...")

                val messageBytes = DISCOVERY_MESSAGE.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(
                    messageBytes,
                    messageBytes.size,
                    broadcastAddress,
                    DISCOVERY_PORT
                )

                var serverFound = false

                for (attempt in 1..attempts) {
                    if (!isRunning) break

                    Log.d(TAG, "Отправка broadcast-запроса #$attempt...")

                    try {
                        socket?.send(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка отправки broadcast", e)
                        continue
                    }

                    val waitStartTime = System.currentTimeMillis()
                    val waitDuration = (timeoutSeconds * 1000L) / attempts

                    while (System.currentTimeMillis() - waitStartTime < waitDuration) {
                        if (!isRunning) break

                        try {
                            val receiveBuffer = ByteArray(BUFFER_SIZE)
                            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                            socket?.receive(receivePacket)

                            val responseData = String(
                                receivePacket.data,
                                0,
                                receivePacket.length
                            )

                            Log.d(TAG, "Получен ответ от ${receivePacket.address.hostAddress}")

                            val json = JSONObject(responseData)

                            if (json.getString("type") == "discovery_response") {
                                val ip = json.getString("ip")
                                val port = json.getInt("port")
                                val name = json.optString("name", "Desktop Controller")

                                Log.d(TAG, "Найден сервер: $name @ $ip:$port")

                                serverFound = true
                                isRunning = false

                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onServerFound(ip, port, name)
                                }

                                socket?.close()
                                return@Thread
                            }

                        } catch (e: SocketTimeoutException) {
                            Log.d(TAG, "Таймаут ожидания ответа")
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка получения ответа", e)
                        }
                    }
                }

                if (!serverFound && isRunning) {
                    isRunning = false
                    Log.d(TAG, "❌ Сервер не найден за отведенное время")

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onError("Сервер не найден в сети.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Критическая ошибка discovery", e)
                isRunning = false

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onError("Ошибка поиска: ${e.message}")
                }
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                }
                isRunning = false
                Log.d(TAG, "🔌 Discovery завершен")
            }
        }

        discoveryThread?.start()
    }

    fun stopDiscovery() {
        Log.d(TAG, "⏹️ Остановка discovery")
        isRunning = false
        discoveryThread?.interrupt()
        discoveryThread = null
        try {
            socket?.close()
        } catch (e: Exception) {
        }
    }
}