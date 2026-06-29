package com.example.klientdesktopkontroller

import android.util.Log
import org.java_websocket.handshake.ServerHandshake
import java.lang.Exception
import java.net.URI
import org.json.JSONObject

class WebSocketClient(
    serverUri: URI,
    private val listener: Listener
) : org.java_websocket.client.WebSocketClient(serverUri) {

    interface Listener {
        fun  onMessage(message: String)
        //fun onBinaryMessage(data: ByteArray)
        fun onClosing(code: Int, reason: String?)
        fun onFailure(t: Throwable)
    }

    private var currentMonitor = 1
    private var TAG = "WebSocket"

    fun sendClick(absoluteX: Int, absoluteY: Int, button: Int, action: String, type: String) {
        try {
            val json = JSONObject().apply {
                put("type", "click")
                put("x", absoluteX)
                put("y", absoluteY)
                put("button", button)
                put("action", action)
                put("click_type", type)
                put("monitor", currentMonitor)
            }

            send(json.toString())
            Log.d(TAG, "Отправлен абсолютный клик: $action ($type) at $absoluteX,$absoluteY")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки клика", e)
        }
    }

    fun sendText(text: String) {
        try {
            val json = JSONObject()
                .put("type", "text")
                .put("text", text)
            send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки текста", e)
        }
    }

    fun sendBackspace(){
        try{
            val json = JSONObject()
                .put("type", "keyboard")
                .put("action", "backspace")
            send(json.toString())
            Log.d(TAG, "Отправлен backspace")
        } catch (e: Exception){
            Log.e(TAG, "Ошибка отправки backspace", e)
        }
    }

    fun switchDesktop(desktopNumber: Int) {
        try {
            val json = JSONObject()
                .put("type", "monitor")
                .put("data", desktopNumber)
            send(json.toString())
            currentMonitor=desktopNumber
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "❌ Ошибка переключения монитора", e)
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.d(TAG, "Подключение установлено")
    }

    override fun onMessage(message: String?) {
        message?.let { listener.onMessage(it) }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        listener.onClosing(code, reason)
    }

    override fun onError(ex: Exception?) {
        ex?.let { listener.onFailure(it) }
    }
}