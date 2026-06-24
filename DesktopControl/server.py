import asyncio
import os
import websockets
import cv2
import numpy as np
import base64
import json
import pyautogui
import pyperclip
import tkinter as tk
from tkinter import ttk
import threading
import socket
from mss import mss
import ctypes

class DiscoveryServer:
    def __init__(self):
        self.running = False
        self.sock = None
        self.thread = None
        self.DISCOVERY_PORT = 8766
        self.DISCOVERY_MESSAGE = "DISCOVER_DESKTOP_CONTROLLER"
        self.server_ip = None

    def start(self):
        if self.running:
            print("Discovery сервер уже запущен")
            return

        self.running = True
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()
        print(f"UDP Discovery запущен на порту {self.DISCOVERY_PORT}")

    def _listen(self):
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

            self.sock.bind(('', self.DISCOVERY_PORT))
            print(f"Слушаю порт {self.DISCOVERY_PORT} для discovery запросов")

            self.server_ip = self._get_local_ip()
            print(f"IP сервера: {self.server_ip}")

            self.sock.settimeout(1)

            while self.running:
                try:
                    data, addr = self.sock.recvfrom(1024)
                    message = data.decode('utf-8')

                    if message == self.DISCOVERY_MESSAGE:
                        print(f"Получен discovery запрос от {addr[0]}:{addr[1]}")

                        response = {
                            "type": "discovery_response",
                            "ip": self.server_ip,
                            "port": 8765,
                            "name": socket.gethostname(),
                            "version": "1.0"
                        }

                        response_data = json.dumps(response).encode('utf-8')
                        self.sock.sendto(response_data, addr)
                        print(f"Отправлен ответ сервера: {self.server_ip}:8765")

                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        print(f"Ошибка в discovery: {e}")

        except Exception as e:
            print(f"Критическая ошибка discovery: {e}")
        finally:
            if self.sock:
                self.sock.close()
                print("Discovery сокет закрыт")

    def _get_local_ip(self):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("8.8.8.8", 80))
                return s.getsockname()[0]
        except:
            return socket.gethostbyname(socket.gethostname())

    def stop(self):
        print("Остановка Discovery сервера...")
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except:
                pass
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=2)
        print("Discovery сервер выключен")

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
            print("Сервер запущен на порту 8765")
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
            print("Сервер остановлен")

    async def handle_client(self, websocket):
        if self.client and self.client.open:
            await websocket.close(reason="Разрешен только один клиент")
            return
        self.client = websocket
        print("Клиент подключен")

        try:
            await self.send_monitors_info(websocket)

            stream_task = asyncio.create_task(self.stream_screen(websocket))
            message_task = asyncio.create_task(self.handle_messages(websocket))

            await asyncio.wait(
                [stream_task, message_task],
                return_when=asyncio.FIRST_COMPLETED
            )

        except Exception as e:
            print(f"Ошибка: {e}")
            
        finally:
            if 'stream_task' in locals():
                stream_task.cancel()
            if 'message_task' in locals():
                message_task.cancel()
            if self.client == websocket:
                self.client = None
                print("Клиент отключен")

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
            print(f"Отправлена информация о {len(monitors) - 1} мониторах")

        except Exception as e:
            print(f"Ошибка отправки информации о мониторах: {e}")

    async def handle_messages(self, websocket):
        async for message in websocket:
            asyncio.create_task(self.process_message(message))

    async def process_message(self, message):
        try:
            data = json.loads(message)
            message_type = data.get("type")

            if message_type == "click":
                await self.handle_click(data)
            elif message_type == "text":
                await self.handle_text(data)
            elif message_type == "monitor":
                await self.getMonitor(data)
            elif message_type == "keyboard":
                await self.handle_keyboard(data)
            else:
                print(f"Неизвестный тип сообщения: {message_type}")

        except Exception as e:
            print(f"Ошибка обработки сообщения: {e}")

    async def handle_keyboard(self, data):
        try:
            action = data.get("action")
            
            if action == "backspace":
                print("Backspace нажат")
                await asyncio.to_thread(pyautogui.click)
                await asyncio.sleep(0.05)
                await asyncio.to_thread(self._press_backspace)
            else:
                print(f"Неизвестное действие клавиатуры: {action}")
                
        except Exception as e:
            print(f"Ошибка обработки клавиатурного события: {e}")

    async def handle_text(self, data):
        try:
            text = data.get("text", "")
            if not text:
                print("Пустой текст")
                return

            print(f"Получен текст для вставки: '{text}'")
            await asyncio.to_thread(self._paste_text, text)

        except Exception as e:
            print(f"Ошибка при вставке текста: {e}")

    def _paste_text(self, text):
        pyperclip.copy(text)
        ctypes.windll.user32.keybd_event(0x11, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0x56, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0x56, 0, 2, 0)
        ctypes.windll.user32.keybd_event(0x11, 0, 2, 0)

    def _press_backspace(self):
        ctypes.windll.user32.keybd_event(0x08, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0x08, 0, 2, 0)

    async def handle_click(self, data):
        try:
            x = data.get("x")
            y = data.get("y")
            button = data.get("button", 0)
            action = data.get("action")
            click_type = data.get("click_type", "click")

            if x is None or y is None:
                print(f"Неверные координаты: x={x}, y={y}")
                return

            absolute_x = int(x)
            absolute_y = int(y)
            print(f"Абсолютные координаты: ({absolute_x}, {absolute_y})")

            if button == 0:
                button_str = 'left'
            elif button == 1:
                button_str = 'right'
            else:
                button_str = 'middle'

            if click_type == "click":
                if action == "down":
                    await asyncio.to_thread(pyautogui.mouseDown, absolute_x, absolute_y, button=button_str)
                elif action == "up":
                    await asyncio.to_thread(pyautogui.mouseUp, absolute_x, absolute_y, button=button_str)
                elif action == "click":
                    await asyncio.to_thread(pyautogui.click, absolute_x, absolute_y, button=button_str)
                else:
                    print(f"Неизвестное действие мыши: {action}")

            elif click_type == "wheel":
                scroll_amount = -20 if action == "up" else 20
                await asyncio.to_thread(pyautogui.scroll, scroll_amount, absolute_x, absolute_y)
            else:
                print(f"Неизвестный тип клика: {click_type}")

        except Exception as e:
            print(f"Ошибка обработки клика: {e}")

    async def getMonitor(self, data):
        try:
            monitor = data.get("data", 1)
            self.current_monitor = monitor
            print(f"Переключен на монитор {monitor}")

        except Exception as e:
            print(f"Ошибка при переключении монитора: {e}")

    async def stream_screen(self, websocket):
        try:
            sct = self.init_mss()
            
            while True:
                
                if self.client is None:
                    break
                
                monitor = sct.monitors[self.current_monitor]
                screenshot = sct.grab(monitor)
                frame = np.array(screenshot)
                
                _, buffer = cv2.imencode('.jpg', frame, [
                    cv2.IMWRITE_JPEG_QUALITY, 70
                ])
                
                message = json.dumps({
                    "type": "frame",
                    "data": base64.b64encode(buffer).decode('utf-8')
                })
                
                try:
                    await websocket.send(message)
                except:
                    break

            # await asyncio.sleep(0.01)
            
        except Exception as e:
            print(f"❌ Ошибка захвата: {e}")

class StreamerApp:
    def __init__(self):
        try:
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
                'DesktopController'
            )
        except Exception as e:
            print(f"Не удалось установить AppUserModelID (имя программы): {e}")

        self.root = tk.Tk()
        self.root.title("Desktop Controller")
        self.root.geometry("650x500")
        self.root.resizable(False, False)
        self.root.configure(bg='#131418')

        try:
            if os.path.exists("ico.ico"):
                self.root.iconbitmap("ico.ico")
            else:
                print("Файл ico.ico не найден")
        except Exception as e:
            print(f"Не удалось загрузить иконку: {e}")

        self.streamer = ScreenStreamer()
        self.asyncio_thread = None
        self.is_running = False

        self.setup_ui()

    def setup_ui(self):
        style = ttk.Style()
        style.configure("Background.TFrame", background="#131418")
        style.configure("Title.TLabel", background="#131418", foreground="#f3f3f3", font=("Segoe UI", 28, "bold"))
        style.configure("Subtitle.TLabel", background="#131418", foreground="#0ea3fc", font=("Segoe UI", 13))
        style.configure("Info.TLabel", background="#131418", foreground="#888888", font=("Segoe UI", 11))
        style.configure("StatusOn.TLabel", background="#131418", foreground="#4CAF50", font=("Segoe UI", 13, "bold"))
        style.configure("StatusOff.TLabel", background="#131418", foreground="#ff6b6b", font=("Segoe UI", 13, "bold"))

        main_frame = ttk.Frame(self.root, padding="30", style="Background.TFrame")
        main_frame.pack(fill=tk.BOTH)

        title_label = ttk.Label(main_frame, text="Desktop Controller", style="Title.TLabel")
        title_label.pack()

        subtitle_label = ttk.Label(
            main_frame,
            text="Управление компьютером с телефона",
            style="Subtitle.TLabel"
        )
        subtitle_label.pack(pady=(0, 30))

        self.btn_server = RoundedButton(
            main_frame,
            text="▶ ВКЛЮЧИТЬ СЕРВЕР",
            command=self.toggle_streaming
        )
        self.btn_server.pack(pady=10)

        self.status_label = ttk.Label(
            main_frame,
            text="Сервер остановлен",
            style="StatusOff.TLabel"
        )
        self.status_label.pack(pady=(10, 25))

        info_frame = ttk.Frame(main_frame, style="Background.TFrame")
        info_frame.pack(fill=tk.X)

        info_lines = [
            "◆ Как использовать:",
            "  • Убедитесь что сервер и клиент в одной сети Wi-Fi",
            "",
            "◆ В меню можно:",
            "  • Переключить просматриваемый монитор",
            "  • Вставить текст"
        ]

        for line in info_lines:
            color = "#0ea3fc" if line.startswith("◆") else "#888888"
            if line.startswith("  "):
                label = ttk.Label(info_frame, text=line, foreground=color, background="#131418", font=("Segoe UI", 11))
                label.pack(anchor=tk.W, padx=(15, 0))
            else:
                label = ttk.Label(info_frame, text=line, foreground=color, background="#131418", font=("Segoe UI", 12))
                label.pack(anchor=tk.W)

    def toggle_streaming(self):
        if not self.is_running:
            self.start_streaming()
        else:
            self.stop_streaming()

    def start_streaming(self):
        self.is_running = True
        self.asyncio_thread = threading.Thread(target=self.run_async, daemon=True)
        self.asyncio_thread.start()
        print("Стриминг запущен")
        self.root.after(0, self._update_ui_started)

    def _update_ui_started(self):
        self.btn_server.itemconfig(self.btn_server.text_id, text="■ ВЫКЛЮЧИТЬ СЕРВЕР")
        self.status_label.config(text="Сервер запущен", style="StatusOn.TLabel")

    def stop_streaming(self):
        self.is_running = False
        self.streamer.current_monitor = 1
        if hasattr(self.streamer, 'loop') and self.streamer.loop:
            asyncio.run_coroutine_threadsafe(self.streamer.toggle_server(), self.streamer.loop)
        print("Стриминг остановлен")
        self.root.after(0, self._update_ui_stopped)

    def _update_ui_stopped(self):
        self.btn_server.itemconfig(self.btn_server.text_id, text="▶ ВКЛЮЧИТЬ СЕРВЕР")
        self.status_label.config(text="Сервер остановлен", style="StatusOff.TLabel")

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

class RoundedButton(tk.Canvas):
    def __init__(self, master, text="Button", width=280, height=60,
                 radius=50, bg_color="#0ea3fc", hover_color="#1a8bc4",
                 text_color="white", font=("Segoe UI", 16, "bold"),
                 command=None, **kwargs):

        super().__init__(master, width=width, height=height,
                        highlightthickness=0, bg='#131418', **kwargs)

        self.width = width
        self.height = height
        self.radius = radius
        self.bg_color = bg_color
        self.hover_color = hover_color
        self.text_color = text_color
        self.font = font
        self.darker_color = "#187cad"
        self.command = command
        self.is_hovered = False
        self.is_pressed = False

        self.bg_rect = self.create_rounded_rect(
            radius,
            0, 0, width, height,
            fill=bg_color,
            outline=bg_color
        )

        self.text_id = self.create_text(
            width // 2, height // 2,
            text=text,
            fill=text_color,
            font=font,
            anchor="center"
        )

        self.bind("<Enter>", self.on_enter)
        self.bind("<Leave>", self.on_leave)
        self.bind("<Button-1>", self.on_press)
        self.bind("<ButtonRelease-1>", self.on_release)

    def create_rounded_rect(self, radius, x1, y1, x2, y2, **kwargs):

        points = [
            x1 + radius, y1,
            x2 - radius, y1,
            x2, y1,
            x2, y1 + radius,
            x2, y2 - radius,
            x2, y2,
            x2 - radius, y2,
            x1 + radius, y2,
            x1, y2,
            x1, y2 - radius,
            x1, y1 + radius,
            x1, y1
        ]
        return self.create_polygon(points, smooth=True, **kwargs)

    def on_enter(self, event):
        self.is_hovered = True
        if not self.is_pressed:
            self.itemconfig(self.bg_rect, fill=self.hover_color, outline=self.hover_color)

    def on_leave(self, event):
        self.is_hovered = False
        if not self.is_pressed:
            self.itemconfig(self.bg_rect, fill=self.bg_color, outline=self.bg_color)

    def on_press(self, event):
        self.is_pressed = True
        self.itemconfig(self.bg_rect, fill=self.darker_color, outline=self.darker_color)

    def on_release(self, event):
        self.is_pressed = False
        self.itemconfig(self.bg_rect, fill=self.hover_color, outline=self.hover_color)

        self.command()

if __name__ == "__main__":
    app = StreamerApp()
    app.run()