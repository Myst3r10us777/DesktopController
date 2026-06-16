import asyncio
import os
import websockets
import cv2
import numpy as np
import base64
import json
from mss import mss
import pyautogui
import pyperclip
import tkinter as tk
from tkinter import ttk
import threading

import socket
import json
import threading
import time

class DiscoveryServer:
    """UDP сервер для обнаружения в локальной сети"""
    
    def __init__(self):
        self.running = False
        self.sock = None
        self.thread = None
        self.DISCOVERY_PORT = 8766
        self.DISCOVERY_MESSAGE = "DISCOVER_DESKTOP_CONTROLLER"
        self.server_ip = None
        
    def start(self):
        """Запускает UDP сервер в отдельном потоке"""
        if self.running:
            print("⚠️ Discovery сервер уже запущен")
            return
            
        self.running = True
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()
        print(f"🔍 UDP Discovery запущен на порту {self.DISCOVERY_PORT}")
    
    def _listen(self):
        """Основной цикл прослушивания discovery запросов"""
        try:
            # Создаем UDP сокет
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            
            # Привязываемся ко всем интерфейсам
            self.sock.bind(('', self.DISCOVERY_PORT))
            self.sock.settimeout(1.0)  # Таймаут для неблокирующего режима
            
            print(f"📡 Слушаю порт {self.DISCOVERY_PORT} для discovery запросов")
            
            # Получаем IP сервера один раз при старте
            self.server_ip = self._get_local_ip()
            print(f"🖥️ IP сервера: {self.server_ip}")
            
            while self.running:
                try:
                    # Ждем данные
                    data, addr = self.sock.recvfrom(1024)
                    message = data.decode('utf-8')
                    
                    # Проверяем, что это наш запрос
                    if message == self.DISCOVERY_MESSAGE:
                        print(f"🔍 Получен discovery запрос от {addr[0]}:{addr[1]}")
                        
                        # Формируем ответ
                        response = {
                            "type": "discovery_response",
                            "ip": self.server_ip,
                            "port": 8765,
                            "name": socket.gethostname(),
                            "version": "1.0"
                        }
                        
                        response_data = json.dumps(response).encode('utf-8')
                        
                        # Отправляем ответ клиенту (на тот же порт, с которого пришел запрос)
                        self.sock.sendto(response_data, addr)
                        print(f"✅ Отправлен ответ сервера: {self.server_ip}:8765")
                        
                except socket.timeout:
                    # Таймаут - просто продолжаем цикл
                    continue
                except Exception as e:
                    if self.running:  # Если не остановлено специально
                        print(f"❌ Ошибка в discovery: {e}")
                        
        except Exception as e:
            print(f"❌ Критическая ошибка discovery: {e}")
        finally:
            if self.sock:
                self.sock.close()
                print("🔌 Discovery сокет закрыт")
    
    def _get_local_ip(self):
        """Получает реальный IP адрес в локальной сети"""
        try:
            # Подключаемся к внешнему серверу, чтобы узнать наш IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            try:
                # Альтернативный метод
                return socket.gethostbyname(socket.gethostname())
            except:
                return "127.0.0.1"
    
    def stop(self):
        """Останавливает discovery сервер"""
        print("⏹️ Остановка Discovery сервера...")
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except:
                pass
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=2)
        print("✅ Discovery сервер остановлен")


class ScreenStreamer:
    def __init__(self):
        self.client = None
        self.screen_width, self.screen_height = pyautogui.size()
        self.sct = None
        self.current_monitor = 1
        self.paused = True
        self.server = None
        self.loop = None
        self.discovery_server = None

    def init_mss(self):
        if self.sct is None:
            self.sct = mss()
        return self.sct

    async def toggle_server(self):
        if self.paused:
            self.server = await websockets.serve(
                self.handle_client,
                "0.0.0.0",
                8765
            )

            if not self.discovery_server:
                self.discovery_server = DiscoveryServer()
                self.discovery_server.start()

            self.paused = False
            print("🚀 Сервер запущен на порту 8765")
        else:
            if self.server:
                self.server.close()
                await self.server.wait_closed()
                self.server = None

            if self.discovery_server:
                self.discovery_server.stop()
                self.discovery_server = None
                
            if self.client is not None:
                await self.client.close()
            self.client = None

            self.paused = True
            print("⏹️ Сервер остановлен")

    async def handle_client(self, websocket):
        # Если уже есть клиент - проверяем, жив ли он
        if self.client is not None:
            try:
                if self.client.open:
                    print("❌ Клиент уже подключен. Отказ в соединении.")
                    await websocket.close(reason="Only one client allowed")
                    return
                else:
                    self.client = None
                    print("🔄 Обнаружен мёртвый клиент, очищаем...")
            except:
                self.client = None
                print("🔄 Очищаем мёртвого клиента")
        
        self.client = websocket
        print("✅ Клиент подключен")
        
        try:
            # Отправляем информацию о мониторах
            await self.send_monitors_info(websocket)
            
            # Запускаем трансляцию и обработку сообщений
            stream_task = asyncio.create_task(self.stream_screen(websocket))
            message_task = asyncio.create_task(self.handle_messages(websocket))
            
            await asyncio.wait(
                [stream_task, message_task],
                return_when=asyncio.FIRST_COMPLETED
            )
            
        except Exception as e:
            print(f"❌ Ошибка: {e}")
        finally:
            if 'stream_task' in locals():
                stream_task.cancel()
            if 'message_task' in locals():
                message_task.cancel()
            if self.client == websocket:
                self.client = None
                print("👋 Клиент отключен")

    async def send_monitors_info(self, websocket):
        try:
            sct = self.init_mss()
            monitors = sct.monitors
            
            monitors_info = {
                "type": "monitors_info",
                "monitors_count": len(monitors) - 1,
                "monitors": []
            }
            
            for i, monitor in enumerate(monitors):
                if i == 0:
                    continue
                monitors_info["monitors"].append({
                    "number": i,
                    "left": monitor["left"],
                    "top": monitor["top"],
                    "width": monitor["width"],
                    "height": monitor["height"]
                })
            
            await websocket.send(json.dumps(monitors_info))
            print(f"📊 Отправлена информация о {len(monitors) - 1} мониторах")
            
        except Exception as e:
            print(f"❌ Ошибка отправки информации о мониторах: {e}")

    async def handle_messages(self, websocket):
        """Обработка входящих сообщений от клиента"""
        async for message in websocket:
            # ✅ ИСПРАВЛЕНО: передаём только message
            asyncio.create_task(self.process_message(message))

    async def process_message(self, message):  # ✅ ТОЛЬКО ОДИН ПАРАМЕТР
        """Асинхронная обработка сообщения"""
        try:
            data = json.loads(message)
            message_type = data.get("type")

            if message_type == "click":
                await self.handle_click(data)
            elif message_type == "text":
                await self.handle_text(data)
            elif message_type == "monitor":
                await self.GetMonitor(data)
            else:
                print(f"📨 Неизвестный тип сообщения: {message_type}")

        except Exception as e:
            print(f"❌ Ошибка обработки сообщения: {e}")

    async def handle_text(self, data):
        try:
            text = data.get("text", "")
            if not text:
                print("❌ Пустой текст")
                return

            print(f"📝 Получен текст для вставки: '{text}'")
            await asyncio.to_thread(self._paste_text, text)

        except Exception as e:
            print(f"❌ Ошибка при вставке текста: {e}")

    def _paste_text(self, text):
        import ctypes
        pyperclip.copy(text)
        ctypes.windll.user32.keybd_event(0x11, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0x56, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0x56, 0, 2, 0)
        ctypes.windll.user32.keybd_event(0x11, 0, 2, 0)

    async def handle_click(self, data):
        try:
            x = data.get("x")
            y = data.get("y")
            button = data.get("button", 0)
            action = data.get("action")
            click_type = data.get("click_type", "click")

            if x is None or y is None:
                print(f"❌ Неверные координаты: x={x}, y={y}")
                return

            if isinstance(x, float) and 0 <= x <= 1 and isinstance(y, float) and 0 <= y <= 1:
                absolute_x = int(x * self.screen_width)
                absolute_y = int(y * self.screen_height)
                print(f"  📏 Нормализованные -> абсолютные: ({x}, {y}) -> ({absolute_x}, {absolute_y})")
            else:
                absolute_x = int(x)
                absolute_y = int(y)
                print(f"  📏 Абсолютные координаты: ({absolute_x}, {absolute_y})")

            button_str = 'left' if button == 0 else 'right' if button == 1 else 'middle'

            if click_type == "click":
                if action == "down":
                    await asyncio.to_thread(pyautogui.mouseDown, absolute_x, absolute_y, button=button_str)
                elif action == "up":
                    await asyncio.to_thread(pyautogui.mouseUp, absolute_x, absolute_y, button=button_str)
                elif action == "click":
                    await asyncio.to_thread(pyautogui.click, absolute_x, absolute_y, button=button_str)
                else:
                    print(f"❌ Неизвестное действие мыши: {action}")

            elif click_type == "wheel":
                scroll_amount = -20 if action == "up" else 20
                await asyncio.to_thread(pyautogui.scroll, scroll_amount, absolute_x, absolute_y)
            else:
                print(f"❌ Неизвестный тип клика: {click_type}")

        except Exception as e:
            print(f"❌ Ошибка обработки клика: {e}")

    async def GetMonitor(self, data):
        try:
            monitor = data.get("data", 1)
            sct = self.init_mss()

            if monitor < 1 or monitor >= len(sct.monitors):
                print(f"❌ Монитор {monitor} не существует. Доступно: {len(sct.monitors)-1}")
                return

            self.current_monitor = monitor
            print(f"🖥️ Переключен на монитор {monitor}")

        except Exception as e:
            print(f"❌ Ошибка при переключении монитора: {e}")

    async def stream_screen(self, websocket):
        try:
            sct = self.init_mss()
            
            while True:
                if self.paused:
                    await asyncio.sleep(0.1)
                    continue
                
                if self.client is None:
                    break
                
                monitor = sct.monitors[self.current_monitor]
                screenshot = sct.grab(monitor)
                frame = np.array(screenshot)
                frame = cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)
                
                _, buffer = cv2.imencode('.jpg', frame, [
                    cv2.IMWRITE_JPEG_QUALITY, 60
                ])
                
                message = json.dumps({
                    "type": "frame",
                    "data": base64.b64encode(buffer).decode('utf-8')
                })
                
                try:
                    await websocket.send(message)
                except:
                    break
                
                await asyncio.sleep(0.05)  # 20 FPS
                
        except Exception as e:
            print(f"❌ Ошибка захвата: {e}")

class StreamerApp:
    def __init__(self):
        if os.name == 'nt':
            try:
                import ctypes
                ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID('com.yourcompany.desktopcontroller.1.0')
                print("✅ AppUserModelID установлен")
            except Exception as e:
                print(f"⚠️ Не удалось установить AppUserModelID: {e}")

        self.root = tk.Tk()
        self.root.title("Desktop Controller")
        self.root.geometry("650x500")
        self.root.resizable(False, False)

        try:
            if os.path.exists("ico.ico"):
                self.root.iconbitmap("ico.ico")
                print("✅ Иконка установлена")
            else:
                print("⚠️ Файл ico.ico не найден")
        except Exception as e:
            print(f"⚠️ Не удалось загрузить иконку: {e}")

        self.streamer = ScreenStreamer()
        self.asyncio_thread = None
        self.is_running = False

        self.setup_ui()

    def setup_ui(self):
        style = ttk.Style()
        style.configure("Blue.TFrame", background="#131418")
        style.configure("Title.TLabel", background="#131418", foreground="#f3f3f3", font=("Segoe UI", 25, "bold"))
        style.configure("Orange.TLabel", background="#131418", foreground="#0ea3fc", font=("Segoe UI", 15))
        style.configure("Status.TLabel", background="#131418", foreground="red", font=("Segoe UI", 15))
        style.configure("Large.TButton", font=("Arial", 15))

        main_frame = ttk.Frame(self.root, padding="20", style="Blue.TFrame")
        main_frame.pack(fill=tk.BOTH, expand=True)

        ttk.Label(main_frame, text="Desktop Controller", style="Title.TLabel").pack(pady=(0, 20))

        ttk.Label(main_frame, 
                 text="◆ Как использовать:\nУбедитесь что сервер и клиент находятся в одной сети Wi-Fi\n"
                      "\n◆ В меню можно:\n• Переключить просматриваемый монитор\n"
                      "• Вставить текст",
                 style="Orange.TLabel").pack(pady=5)

        self.toggle_button = ttk.Button(
            main_frame,
            text="ВКЛЮЧИТЬ СЕРВЕР",
            command=self.toggle_streaming,
            style="Large.TButton",
            padding=(20, 7)
        )
        self.toggle_button.pack(pady=10)

        self.status_label = ttk.Label(main_frame, text="Статус: Остановлен", style="Status.TLabel")
        self.status_label.pack(pady=5)

    def toggle_streaming(self):
        if not self.is_running:
            self.start_streaming()
        else:
            self.stop_streaming()

    def start_streaming(self):
        self.is_running = True
        self.asyncio_thread = threading.Thread(target=self.run_async, daemon=True)
        self.asyncio_thread.start()
        print("🚀 Стриминг запущен")
        self.root.after(0, self._update_ui_started)

    def _update_ui_started(self):
        self.toggle_button.config(text="ВЫКЛЮЧИТЬ СЕРВЕР")
        self.status_label.config(text="Статус: Запущен", foreground="green")
        style = ttk.Style()
        style.configure("Stop.TButton", font=("Arial", 15), foreground="black")
        self.toggle_button.configure(style="Stop.TButton")

    def stop_streaming(self):
        self.is_running = False
        self.streamer.current_monitor = 1
        if hasattr(self.streamer, 'loop') and self.streamer.loop:
            asyncio.run_coroutine_threadsafe(self.streamer.toggle_server(), self.streamer.loop)
        print("⏸️ Стриминг остановлен")
        self.root.after(0, self._update_ui_stopped)

    def _update_ui_stopped(self):
        self.toggle_button.config(text="ВКЛЮЧИТЬ СЕРВЕР")
        self.status_label.config(text="Статус: Остановлен", foreground="red")
        style = ttk.Style()
        style.configure("Large.TButton", font=("Arial", 15))
        self.toggle_button.configure(style="Large.TButton")

    def run_async(self):
        self.streamer.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.streamer.loop)
        self.streamer.loop.run_until_complete(self.streamer.toggle_server())
        self.streamer.loop.run_forever()

    def run(self):
        try:
            self.root.mainloop()
        except KeyboardInterrupt:
            self.stop_streaming()
        finally:
            if self.is_running:
                self.stop_streaming()

if __name__ == "__main__":
    app = StreamerApp()
    app.run()