package com.example.klientdesktopkontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace.isEnabled
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.abs
import com.example.klientdesktopkontroller.WebSocketClient as MyWebSocketClient

class MainActivity : AppCompatActivity() {

    private lateinit var button: Button

    private lateinit var menuButton: Button
    private lateinit var text: TextView
    private lateinit var imageView: ImageView

    private var websocketClient: MyWebSocketClient? = null
    private var websocketJob: Job? = null
    private var discoveryJob: Job? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var posX = 0f
    private var posY = 0f
    private var isDragging = false

    private lateinit var keyboardInputField: EditText

    private var longPressRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val LONG_PRESS_DELAY = 1000L // 1 секунда
    private var isLongPressTriggered = false

    private var lastTapTime = 0L
    private val DOUBLE_TAP_DELAY = 300L

    private var dClick = false
    private var lastFrameTime = 0L
    private val frameTimeout = 1000L // 1 секунда
    private val frameTimeoutCheckInterval = 500L // Проверяем каждые 500мс
    private var frameTimeoutJob: Job? = null

    // Матрица для трансформаций изображения
    private val matrix = Matrix()

    private var scrollThreshold = 5f
    private var lastScrollY = 0f

    private var currentMonitor = 1
    private var monitorsInfo: List<MonitorInfo> = listOf()

    data class MonitorInfo(
        val number: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    companion object {
        private const val TAG = "ScreenViewer"
        private val COMMON_IPS = listOf("192.168.0.120")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button = findViewById(R.id.button)
        text = findViewById(R.id.text)
        imageView = findViewById(R.id.imageView)
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        keyboardInputField = findViewById(R.id.TextKeyBoard)
        menuButton = findViewById<Button>(R.id.menuButton)
        menuButton.isEnabled = false
        menuButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        button.setOnClickListener {
            startAutoDiscovery()
        }
        setupZoomGestures()
        setupTouchListener()
        startFrameTimeoutChecker()
    }

    private fun startFrameTimeoutChecker() {
        frameTimeoutJob?.cancel()
        frameTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(frameTimeoutCheckInterval)

                val currentTime = System.currentTimeMillis()
                val timeSinceLastFrame = currentTime - lastFrameTime

                if (lastFrameTime > 0 && timeSinceLastFrame > frameTimeout) {
                    Log.w(TAG, "Таймаут кадров: $timeSinceLastFrame мс")
                    handleServerStopped()
                }
            }
        }
    }
    private fun Keyboard() {
        runOnUiThread {
            keyboardInputField.visibility = View.VISIBLE
            keyboardInputField.setText("")
            keyboardInputField.requestFocus()

            keyboardInputField.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_GO) {
                    sendTextToServer()
                    keyboardInputField.visibility = View.GONE
                    val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    inputMethodManager.hideSoftInputFromWindow(keyboardInputField.windowToken, 0)

                    keyboardInputField.visibility = View.GONE
                    keyboardInputField.setText("")
                    true
                } else {
                    false
                }
            }

            keyboardInputField.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                    sendTextToServer()
                    keyboardInputField.visibility = View.GONE
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun sendTextToServer() {
        val text = keyboardInputField.text.toString().trim()
        if (text.isNotEmpty()) {
            try {

                websocketClient?.sendText(text)
                Log.d(TAG, "Текст отправлен: $text")

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки текста", e)
            }
        }

    }

    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            onTouchEvent(event)
            true
        }
    }
    private fun setupZoomGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1f, 4.0f)
                Log.d(TAG, "Производится смена масштаба: $scaleFactor")
                applyImageTransform()
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                Log.d(TAG, "Начало смены масштаба")
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                Log.d(TAG, "Завершение смены масштаба")
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            scaleGestureDetector.isQuickScaleEnabled = false
        }
    }

    private fun applyImageTransform() {
        val drawable = imageView.drawable ?: return
        matrix.reset()

        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()

        val baseScale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight)
        val currentScale = baseScale * scaleFactor

        matrix.setScale(currentScale, currentScale)

        val scaledWidth = imageWidth * currentScale
        val scaledHeight = imageHeight * currentScale

        val maxX = Math.max(0f, (scaledWidth - viewWidth) / 2)
        val maxY = Math.max(0f, (scaledHeight - viewHeight) / 2)

        posX = posX.coerceIn(-maxX, maxX)
        posY = posY.coerceIn(-maxY, maxY)

        val centerX = (viewWidth - scaledWidth) / 2f
        val centerY = (viewHeight - scaledHeight) / 2f

        matrix.postTranslate(centerX + posX, centerY + posY)
        imageView.imageMatrix = matrix
    }


    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Для Android 11 и выше
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun startAutoDiscovery() {
        button.isEnabled = false

        discoveryJob = CoroutineScope(Dispatchers.IO).launch {
            var serverFound = false

            for (ip in COMMON_IPS) {
                if (!isActive) break

                try {
                    val uri = URI("ws://$ip:8765")
                    Log.d(TAG, "Пробуем подключиться к $uri")

                    withContext(Dispatchers.Main) {
                        text.text = "Идёт подключение к серверу..."
                    }

                    val testClient = MyWebSocketClient(uri, object : MyWebSocketClient.Listener {
                        override fun onMessage(message: String) {}
                        override fun onClosing(code: Int, reason: String?) {}
                        override fun onFailure(t: Throwable) {}
                    })

                    testClient.connect()
                    delay(1000)

                    if (testClient.isOpen) {
                        testClient.close()
                        serverFound = true
                        connectToWebSocket(uri)
                        break
                    }

                } catch (e: Exception) {
                    Log.d(TAG, "Не удалось подключиться к $ip: ${e.message}")
                    continue
                }
            }

            withContext(Dispatchers.Main) {
                if (!serverFound) {
                    text.text = "❌ Сервер не найден\n\nУбедитесь, что:\n1. Программа запущена на компьютере\n2. Оба устройства в одной сети WiFi"
                    button.isEnabled = true
                    button.text = "Попробовать снова"
                    menuButton.isEnabled = false
                    menuButton.visibility = View.GONE
                }
            }
        }
    }

    private fun connectToWebSocket(uri: URI) {
        lastFrameTime = System.currentTimeMillis()
        //startFrameTimeoutChecker()
        currentMonitor = 1
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {

                R.id.menu_desktop_1 -> {
                    websocketClient?.switchDesktop(1)
                    currentMonitor = 1
                }
                R.id.menu_desktop_2 -> {
                    websocketClient?.switchDesktop(2)
                    currentMonitor = 2
                }
                R.id.menu_desktop_3 -> {
                    websocketClient?.switchDesktop(3)
                    currentMonitor = 3
                }
                R.id.menu_disconnect -> disconnectFromWebSocket()
                R.id.Keyboard -> Keyboard()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        websocketJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Подключаюсь к $uri...")

                websocketClient = MyWebSocketClient(uri, object : MyWebSocketClient.Listener {
                    override fun onMessage(message: String) {
                        runOnUiThread {
                            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        handleWebSocketMessage(message)
                    }

                    override fun onClosing(code: Int, reason: String?) {
                        runOnUiThread {
                            text.text = "❌ Ошибка подключения\n"
                            button.text = "Подключиться"
                            button.isEnabled = true
                            menuButton.isEnabled = false
                            menuButton.visibility = View.GONE
                        }
                    }

                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "Ошибка подключения", t)
                        runOnUiThread {
                            text.text = "❌ Ошибка подключения\n${t.message}"
                            button.text = "Подключиться"
                            button.isEnabled = true
                            menuButton.isEnabled = false
                            menuButton.visibility = View.GONE
                        }
                    }
                })

                websocketClient?.connect()

                delay(2000)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при подключении", e)
                runOnUiThread {
                    text.text = "❌ Ошибка: ${e.message}"
                    button.text = "Подключиться"
                    button.isEnabled = true
                    menuButton.isEnabled = false
                    menuButton.visibility = View.GONE
                }
            }
        }
    }

    private fun handleWebSocketMessage(message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonObject = JSONObject(message)
                val type = jsonObject.getString("type")

                when (type) {
                    "frame" -> {
                        lastFrameTime = System.currentTimeMillis()
                        runOnUiThread {
                            menuButton.isEnabled = true
                            menuButton.visibility = View.VISIBLE
                        }
                        val frameData = jsonObject.getString("data")
                        val bitmap = decodeBase64ToBitmap(frameData)

                        bitmap?.let {
                            withContext(Dispatchers.Main) {
                                imageView.setImageBitmap(it)
                                imageView.visibility = ImageView.VISIBLE
                                applyImageTransform()
                            }
                        }
                    }
                    "monitors_info" -> {
                        val monitorsCount = jsonObject.getInt("monitors_count")
                        val monitorsArray = jsonObject.getJSONArray("monitors")

                        val monitorsList = mutableListOf<MonitorInfo>()
                        for (i in 0 until monitorsArray.length()) {
                            val monitorJson = monitorsArray.getJSONObject(i)
                            monitorsList.add(MonitorInfo(
                                number = monitorJson.getInt("number"),
                                left = monitorJson.getInt("left"),
                                top = monitorJson.getInt("top"),
                                width = monitorJson.getInt("width"),
                                height = monitorJson.getInt("height")
                            ))
                        }

                        monitorsInfo = monitorsList

                        withContext(Dispatchers.Main) {
                            updateMonitorsMenu(monitorsCount)
                        }

                        Log.d(TAG, "Получена информация о ${monitorsInfo.size} мониторах")
                        monitorsInfo.forEach { monitor ->
                            Log.d(TAG, "Монитор ${monitor.number}: ${monitor.width}x${monitor.height} at (${monitor.left}, ${monitor.top})")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки сообщения", e)
            }
        }
    }

    private fun handleServerStopped() {
        frameTimeoutJob?.cancel()
        Log.e(TAG, "Сервер остановился!")
        lastFrameTime = 0L
        runOnUiThread {
            imageView.setImageBitmap(null)
            imageView.visibility = View.GONE

            text.text = "Сервер остановлен, подождите пока его запустят!"
            button.text = "Попробовать подключиться"
            button.isEnabled = true
            menuButton.isEnabled = false
            menuButton.visibility = View.GONE

            scaleFactor = 1.0f
            posX = 0f
            posY = 0f
            matrix.reset()
            imageView.imageMatrix = matrix

            Toast.makeText(this@MainActivity, "Сервер отключен", Toast.LENGTH_LONG).show()

            keyboardInputField.visibility = View.GONE
            keyboardInputField.setText("")
        }
    }

    private fun updateMonitorsMenu(monitorsCount: Int) {
        Log.d(TAG, "Обновление меню мониторов: доступно $monitorsCount мониторов")
        val menu = navigationView.menu

        menu.findItem(R.id.menu_desktop_1).isVisible = false
        menu.findItem(R.id.menu_desktop_2).isVisible = false
        menu.findItem(R.id.menu_desktop_3).isVisible = false
        for (i in 1..monitorsCount) {
            when (i) {
                1 -> menu.findItem(R.id.menu_desktop_1).isVisible = true
                2 -> menu.findItem(R.id.menu_desktop_2).isVisible = true
                3 -> menu.findItem(R.id.menu_desktop_3).isVisible = true
            }
            Log.d(TAG, "Монитор $i доступен")
        }
        menu.findItem(R.id.menu_desktop_1).isChecked = true

    }

    private fun convertToAbsoluteCoords(normalizedX: Float, normalizedY: Float): Pair<Int, Int>? {
        val currentMonitorInfo = monitorsInfo.find { it.number == currentMonitor }

        if (currentMonitorInfo == null) {
            Log.e(TAG, "❌ convertToAbsoluteCoords: монитор $currentMonitor не найден в monitorsInfo: ${monitorsInfo.map { it.number }}")
            return null
        }

        val absoluteX = currentMonitorInfo.left + (normalizedX * currentMonitorInfo.width).toInt()
        val absoluteY = currentMonitorInfo.top + (normalizedY * currentMonitorInfo.height).toInt()

        Log.d(TAG, "📐 Преобразование координат: нормализованные ($normalizedX, $normalizedY) -> абсолютные ($absoluteX, $absoluteY)")
        Log.d(TAG, "📐 Монитор $currentMonitor: left=${currentMonitorInfo.left}, top=${currentMonitorInfo.top}, width=${currentMonitorInfo.width}, height=${currentMonitorInfo.height}")

        return Pair(absoluteX, absoluteY)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        if (scaleGestureDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            return true
        }

        if (event.pointerCount > 2) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                lastScrollY = event.y
                isDragging = true
                dClick = false

                val imageCoords = getImageCoordinates(event.x, event.y)
                imageCoords?.let { (x, y) ->
                    Log.d(TAG, "Касание на изображении: X=$x, Y=$y")

                    val currentTime = System.currentTimeMillis()

                    if (currentTime - lastTapTime < DOUBLE_TAP_DELAY) {
                        dClick = true
                        cancelLongPress()

                        val absoluteCoords = convertToAbsoluteCoords(x, y)
                        absoluteCoords?.let { (absX, absY) ->
                            websocketClient?.sendClick(absX, absY, 0, "down", "click")
                            Log.d(TAG, "Двойное нажатие - левый клик: X=$absX, Y=$absY down")
                        }
                    } else {
                        startLongPressTimer(x, y)
                    }

                    lastTapTime = currentTime
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !dClick) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    if (dx > 5 || dx < -5 || dy > 5 || dy < -5) {
                        cancelLongPress()
                    }

                    if (!dClick && (scaleFactor == 1f)) {
                        val scrollDelta = event.y - lastScrollY

                        if (abs(scrollDelta) > scrollThreshold) {
                            val scrollDirection = if (scrollDelta > 0) "down" else "up"

                            val imageCoords = getImageCoordinates(event.x, event.y)
                            imageCoords?.let { (x, y) ->
                                val absoluteCoords = convertToAbsoluteCoords(x, y)
                                absoluteCoords?.let { (absX, absY) ->
                                    websocketClient?.sendClick(absX, absY, 0, scrollDirection, "wheel")
                                    Log.d(TAG, "Скролл: $scrollDirection at $absX,$absY")
                                }
                            }

                            lastScrollY = event.y
                        }
                    }

                    posX += dx
                    posY += dy
                    applyImageTransform()

                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                isDragging = false

                if (!isLongPressTriggered && dClick) {
                    val imageCoords = getImageCoordinates(event.x, event.y)
                    imageCoords?.let { (x, y) ->
                        Log.d(TAG, "Отпускание на изображении: X=$x, Y=$y")
                        val absoluteCoords = convertToAbsoluteCoords(x, y)
                        absoluteCoords?.let { (absX, absY) ->
                            websocketClient?.sendClick(absX, absY, 0, "up", "click")
                        }
                    }
                }
                isLongPressTriggered = false
            }
        }

        return true
    }
    private fun startLongPressTimer(normalizedX: Float, normalizedY: Float) {
        cancelLongPress()

        longPressRunnable = Runnable {
            isLongPressTriggered = true

            val absoluteCoords = convertToAbsoluteCoords(normalizedX, normalizedY)

            if (absoluteCoords != null) {
                val (absX, absY) = absoluteCoords
                websocketClient?.sendClick(absX, absY, 1, "click", "click")
                Log.d(TAG, "Долгое нажатие - правый клик: X=$absX, Y=$absY")
            }
        }

        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DELAY)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let {
            handler.removeCallbacks(it)
            longPressRunnable = null
        }
    }
    private fun getImageCoordinates(screenX: Float, screenY: Float): Pair<Float, Float>? {
        val drawable = imageView.drawable ?: return null
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()

        if (imageWidth <= 0 || imageHeight <= 0) return null

        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()

        val baseScale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight)
        val currentScale = baseScale * scaleFactor

        val scaledWidth = imageWidth * currentScale
        val scaledHeight = imageHeight * currentScale

        val imageLeft = (viewWidth - scaledWidth) / 2f + posX
        val imageTop = (viewHeight - scaledHeight) / 2f + posY

        if (screenX < imageLeft || screenX > imageLeft + scaledWidth ||
            screenY < imageTop || screenY > imageTop + scaledHeight) {
            Log.d(TAG, "Касание на черной области вокруг изображения")
            return null
        }

        val imageX = (screenX - imageLeft) / currentScale
        val imageY = (screenY - imageTop) / currentScale

        val normalizedX = imageX / imageWidth
        val normalizedY = imageY / imageHeight

        return Pair(normalizedX, normalizedY)
    }
    private fun decodeBase64ToBitmap(base64String: String): Bitmap? {
        return try {
            val pureBase64 = base64String.substringAfterLast(",")
            val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка декодирования base64", e)
            null
        }
    }

    private fun disconnectFromWebSocket() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                websocketClient?.close()
                websocketJob?.cancel()
                discoveryJob?.cancel()
                frameTimeoutJob?.cancel()

                lastFrameTime = 0L
                delay(50)
                runOnUiThread {
                    menuButton.visibility = View.GONE
                    text.text = "Вы оключились"
                    imageView.visibility = ImageView.GONE
                    button.isEnabled = true
                    menuButton.isEnabled = false
                    button.text = "Подключиться"
                }
                Toast.makeText(this@MainActivity, "ПОДТВЕРЖДЕНИЕ", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при отключении", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromWebSocket()
        websocketJob?.cancel()
        discoveryJob?.cancel()
    }
}