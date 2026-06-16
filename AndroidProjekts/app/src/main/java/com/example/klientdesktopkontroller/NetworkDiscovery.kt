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

    /**
     * Поиск сервера в локальной сети
     * @param onServerFound - вызывается при обнаружении сервера
     * @param onError - вызывается при ошибке или таймауте
     * @param timeoutSeconds - сколько секунд искать
     * @param attempts - сколько раз отправлять broadcast
     */
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
                // 1. Создаем UDP сокет
                socket = DatagramSocket()
                socket?.soTimeout = TIMEOUT_MS
                socket?.broadcast = true

                Log.d(TAG, "🚀 Начинаем поиск сервера в сети...")

                // 2. Формируем broadcast сообщение
                val messageBytes = DISCOVERY_MESSAGE.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(
                    messageBytes,
                    messageBytes.size,
                    broadcastAddress,
                    DISCOVERY_PORT
                )

                var serverFound = false

                // 3. Отправляем несколько раз (на случай потери пакетов)
                for (attempt in 1..attempts) {
                    if (!isRunning) break

                    Log.d(TAG, "📤 Отправка broadcast-запроса #$attempt...")

                    try {
                        socket?.send(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка отправки broadcast", e)
                        continue
                    }

                    // 4. Ждем ответ
                    val waitStartTime = System.currentTimeMillis()
                    val waitDuration = (timeoutSeconds * 1000L) / attempts

                    while (System.currentTimeMillis() - waitStartTime < waitDuration) {
                        if (!isRunning) break

                        try {
                            // Пытаемся получить ответ
                            val receiveBuffer = ByteArray(BUFFER_SIZE)
                            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                            socket?.receive(receivePacket)

                            // 5. Разбираем ответ
                            val responseData = String(
                                receivePacket.data,
                                0,
                                receivePacket.length
                            )

                            Log.d(TAG, "📨 Получен ответ от ${receivePacket.address.hostAddress}")

                            val json = JSONObject(responseData)

                            // Проверяем, что это наш сервер
                            if (json.getString("type") == "discovery_response") {
                                val ip = json.getString("ip")
                                val port = json.getInt("port")
                                val name = json.optString("name", "Desktop Controller")

                                Log.d(TAG, "✅ Найден сервер: $name @ $ip:$port")

                                serverFound = true
                                isRunning = false

                                // Вызываем колбэк в UI потоке
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onServerFound(ip, port, name)
                                }

                                socket?.close()
                                return@Thread
                            }

                        } catch (e: SocketTimeoutException) {
                            // Таймаут - продолжаем ждать или следующую попытку
                            Log.d(TAG, "⏰ Таймаут ожидания ответа")
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Ошибка получения ответа", e)
                        }
                    }
                }

                // 6. Если сервер не найден
                if (!serverFound && isRunning) {
                    isRunning = false
                    Log.d(TAG, "❌ Сервер не найден за отведенное время")

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onError("Сервер не найден в сети.\nПроверьте, что сервер запущен и устройства в одной сети.")
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
                    // Игнорируем ошибки закрытия
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
            // Игнорируем
        }
    }
}