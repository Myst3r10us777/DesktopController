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
import android.view.GestureDetector
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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.example.klientdesktopkontroller.WebSocketClient
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
import kotlin.time.Duration.Companion.milliseconds
import com.example.klientdesktopkontroller.WebSocketClient as MyWebSocketClient

class MainActivity : AppCompatActivity() {

    private lateinit var button: Button

    private lateinit var menuButton: Button
    private lateinit var keyboardButton: Button
    private lateinit var backspaceButton: Button
    private lateinit var text: TextView
    private lateinit var imageView: ImageView

    private var websocketClient: MyWebSocketClient? = null
    private var websocketJob: Job? = null
    private var discoveryJob: Job? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private lateinit var gestureDetector: GestureDetector
    private var scaleFactor = 1.0f
    private var posX = 0f
    private var posY = 0f
    private lateinit var keyboardInputField: EditText

    private var lastFrameTime = 0L
    private val frameTimeout = 3000L
    private val frameTimeoutCheckInterval = 500L
    private var frameTimeoutJob: Job? = null
    private val matrix = Matrix()

    private var currentMonitor = 1
    private var monitorsInfo: List<MonitorInfo> = listOf()

    private var discovery: NetworkDiscovery? = null
    data class MonitorInfo(
        val number: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    companion object {
        private const val TAG = "ScreenViewer"
    }

    fun View.enable() {
        isEnabled = true
        visibility = View.VISIBLE
    }

    fun View.disable() {
        isEnabled = false
        visibility = View.GONE
    }

    //определение кнопок и т.п
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
        keyboardButton = findViewById<Button>(R.id.Keyboard)
        backspaceButton = findViewById<Button>(R.id.btn_backspace)
        menuButton.disable()
        keyboardButton.disable()
        menuButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        keyboardButton.setOnClickListener {
            Keyboard()
        }

        backspaceButton.setOnClickListener {
            sendBackspace()
        }

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        button.setOnClickListener {
            startAutoDiscovery()
        }
        setupZoomGestures()
        setupGestureDetector()
        setupTouchListener()
        startFrameTimeoutChecker()
    }

    //проверка работоспособности сервера по счету кадров
    private fun startFrameTimeoutChecker() {
        frameTimeoutJob?.cancel()
        frameTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(frameTimeoutCheckInterval.milliseconds)

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
            if (keyboardInputField.visibility == View.VISIBLE){
                keyboardInputField.visibility = View.GONE
                backspaceButton.disable()
                return@runOnUiThread
            }
            keyboardInputField.visibility = View.VISIBLE
            backspaceButton.enable()
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
                    backspaceButton.disable()
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

    //отправка текста с клавы на сервер
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

    private fun sendBackspace(){
        runOnUiThread {
            try {
                websocketClient?.sendBackspace()
                Log.d(TAG, "Backspace нажат")
            } catch (e: Exception){
                Log.e(TAG, "Ошибка нажатия backspace", e)
            }

        }
    }

    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    //зум
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

        scaleGestureDetector.isQuickScaleEnabled = false
    }

    private fun handleSingleTap(e: MotionEvent) {
        val imageCoords = getImageCoordinates(e.x, e.y)

        imageCoords?.let { (normalizedX, normalizedY) ->
            val absoluteCoords = convertToAbsoluteCoords(normalizedX, normalizedY)

            absoluteCoords?.let { (absX, absY) ->
                websocketClient?.sendClick(absX, absY, 0, "down", "click")
                websocketClient?.sendClick(absX, absY, 0, "up", "click")
                Log.d(TAG, "Клик: ($absX, $absY)")
            }
        }
    }
    private fun setupGestureDetector(){
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    handleSingleTap(e)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleDoubleTap(e)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    handleLongPress(e)
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (scaleFactor > 1.0f) {
                        posX -= distanceX
                        posY -= distanceY
                        applyImageTransform()
                        return true
                    }

                    handleScroll(e2, distanceY)
                    return true
                }
            }
        )
    }

    private fun handleDoubleTap(e: MotionEvent) {
        val imageCoords = getImageCoordinates(e.x, e.y)

        imageCoords?.let { (normalizedX, normalizedY) ->
            val absoluteCoords = convertToAbsoluteCoords(normalizedX, normalizedY)

            absoluteCoords?.let { (absX, absY) ->
                websocketClient?.sendClick(absX, absY, 0, "down", "click")
                websocketClient?.sendClick(absX, absY, 0, "up", "click")
                Log.d(TAG, "Двойной клик: ($absX, $absY)")
            }
        }
    }

    private fun handleLongPress(e: MotionEvent) {
        val imageCoords = getImageCoordinates(e.x, e.y)

        imageCoords?.let { (normalizedX, normalizedY) ->
            val absoluteCoords = convertToAbsoluteCoords(normalizedX, normalizedY)

            absoluteCoords?.let { (absX, absY) ->
                websocketClient?.sendClick(absX, absY, 1, "click", "click")
                Log.d(TAG, "Правый клик (долгое нажатие): ($absX, $absY)")
            }
        }
    }

    private fun handleScroll(e: MotionEvent, distanceY: Float) {
        val imageCoords = getImageCoordinates(e.x, e.y)
        val scrollAmount = if (distanceY > 0) "up" else "down"

        imageCoords?.let { (normalizedX, normalizedY) ->
            val absoluteCoords = convertToAbsoluteCoords(normalizedX, normalizedY)

            absoluteCoords?.let { (absX, absY) ->
                websocketClient?.sendClick(absX, absY, 0, scrollAmount, "wheel")
                Log.d(TAG, "Скролл: $scrollAmount на ($absX, $absY)")
            }
        }
    }

    //подогнать масштаб под телефон
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
            enableEdgeToEdge()
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    //подключение к серверу
    private fun startAutoDiscovery() {
        text.text = "🔍 Поиск сервера..."
        button.isEnabled = false
        button.text = "Поиск..."
        text.text = "Поиск сервера в сети..."

        discovery?.stopDiscovery()
        discovery = NetworkDiscovery()

        discovery?.discoverServers(
            onServerFound = { ip, port, name ->
                Log.d(TAG, "Сервер найден: $name @ $ip:$port")
                runOnUiThread {
                    connectToWebSocket(URI("ws://$ip:$port"))
                    menuButton.enable()
                    keyboardButton.enable()
                    button.disable()
                    text.text = ""
                }
            },
            onError = { error ->
                Log.e(TAG, "Ошибка поиска: $error")
                runOnUiThread {
                    text.text = "❌ $error"
                    button.enable()
                    button.text = "Найти снова"
                    menuButton.disable()
                    keyboardButton.disable()
                }
            },
            timeoutSeconds = 5,
            attempts = 3
        )
    }

    private fun handleWebSocketMessage(message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonObject = JSONObject(message)
                val type = jsonObject.getString("type")

                when (type) {
//                    "frame" -> {
//                        lastFrameTime = System.currentTimeMillis()
//                        runOnUiThread {
//                            menuButton.isEnabled = true
//                            menuButton.visibility = View.VISIBLE
//                        }
//                        val frameData = jsonObject.getString("data")
//                        val bitmap = decodeBase64ToBitmap(frameData)
//
//                        bitmap?.let {
//                            withContext(Dispatchers.Main) {
//                                imageView.setImageBitmap(it)
//                                imageView.visibility = ImageView.VISIBLE
//                                applyImageTransform()
//                            }
//                        }
//                    }
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

    private fun connectToWebSocket(uri: URI) {
        lastFrameTime = System.currentTimeMillis()
        startFrameTimeoutChecker()
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

                websocketClient = WebSocketClient(uri, object : WebSocketClient.Listener {
                    override fun onMessage(message: String) {
                        handleWebSocketMessage(message)
                    }

                    override fun onBinaryMessage(data: ByteArray) {
                        lastFrameTime = System.currentTimeMillis()
                        runOnUiThread {
//                            text.text = ""
//                            text.visibility = View.GONE
                            button.disable()
                            menuButton.enable()
                            keyboardButton.enable()
                        }
                        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)

                        bitmap?.let {
                            runOnUiThread {
                                imageView.setImageBitmap(it)
                                imageView.visibility = ImageView.VISIBLE
                                applyImageTransform()
                            }
                        }
                    }

                    override fun onClosing(code: Int, reason: String?) {
                    }

                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "Ошибка подключения", t)
                        runOnUiThread {
                            text.text = "❌ Ошибка подключения\n${t.message}"
                            button.text = "Подключиться"
                            button.isEnabled = true
                            menuButton.disable()
                            keyboardButton.disable()
                            backspaceButton.disable()
                            keyboardInputField.visibility = View.GONE
                            keyboardInputField.setText("")
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
                    menuButton.disable()
                    keyboardButton.disable()
                }
            }
        }
    }

    //сообщение при оставновке сервера
    private fun handleServerStopped() {
        frameTimeoutJob?.cancel()
        Log.e(TAG, "Сервер остановился!")
        lastFrameTime = 0L

        runOnUiThread {
            imageView.setImageBitmap(null)
            imageView.visibility = View.GONE

            text.text = "Связь с сервером потеряна. Повторное подключение..."
            button.disable()
            menuButton.disable()

            scaleFactor = 1.0f
            posX = 0f
            posY = 0f
            matrix.reset()
            imageView.imageMatrix = matrix

            Toast.makeText(this@MainActivity, "Сервер отключен", Toast.LENGTH_LONG).show()

            keyboardInputField.visibility = View.GONE
            keyboardInputField.setText("")

            Handler(Looper.getMainLooper()).postDelayed({
                if(!isDestroyed){
                    startAutoDiscovery()
                }
            }, 4000)
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
            Log.e(TAG, "convertToAbsoluteCoords: монитор $currentMonitor не найден в monitorsInfo: ${monitorsInfo.map { it.number }}")

            return null
        }

        val absoluteX = currentMonitorInfo.left + (normalizedX * currentMonitorInfo.width).toInt()
        val absoluteY = currentMonitorInfo.top + (normalizedY * currentMonitorInfo.height).toInt()

        Log.d(TAG, "Преобразование координат: нормализованные ($normalizedX, $normalizedY) -> абсолютные ($absoluteX, $absoluteY)")
        Log.d(TAG, "Монитор $currentMonitor: left=${currentMonitorInfo.left}, top=${currentMonitorInfo.top}, width=${currentMonitorInfo.width}, height=${currentMonitorInfo.height}")

        return Pair(absoluteX, absoluteY)
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
//    private fun decodeBase64ToBitmap(base64String: String): Bitmap? {
//        return try {
//            val pureBase64 = base64String.substringAfterLast(",")
//            val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
//            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
//        } catch (e: Exception) {
//            Log.e(TAG, "Ошибка декодирования base64", e)
//            null
//        }
//    }

    private fun disconnectFromWebSocket() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                websocketClient?.close()
                websocketJob?.cancel()
                discoveryJob?.cancel()
                frameTimeoutJob?.cancel()

                lastFrameTime = 0L
                delay(50.milliseconds)
                runOnUiThread {
                    menuButton.disable()
                    keyboardButton.disable()
                    backspaceButton.disable()
                    text.text = "Вы оключились"
                    imageView.visibility = ImageView.GONE
                    button.enable()
                    keyboardInputField.visibility = View.GONE
                    keyboardInputField.setText("")
                }
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