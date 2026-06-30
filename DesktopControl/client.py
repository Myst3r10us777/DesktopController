# client.py
import asyncio
import websockets
import cv2
import numpy as np
import base64
import json

class ScreenViewer:
    def __init__(self):
        self.websocket = None
        self.is_running = True
        self.window_name = "Remote Screen - Press Q to quit"
        
    async def connect(self, uri="ws://localhost:8765"):
        """Подключение к серверу и получение видео"""
        try:
            print(f"🔗 Подключаюсь к {uri}...")
            self.websocket = await websockets.connect(uri)
            print("✅ Подключено к серверу!")
            print("🎥 Начинается трансляция...")
            print("Нажмите 'q' для выхода")
            
            # Создаем окно один раз
            cv2.namedWindow(self.window_name, cv2.WINDOW_NORMAL)
            
            # Получаем и обрабатываем сообщения
            async for message in self.websocket:
                if not self.is_running:
                    break
                await self.handle_message(message)
                
        except websockets.exceptions.ConnectionClosed:
            print("📞 Соединение закрыто сервером")
        except Exception as e:
            print(f"❌ Ошибка: {e}")
        finally:
            await self.cleanup()
            
    async def handle_message(self, message):
        """Обработка входящих сообщений"""
        try:
            data = json.loads(message)
            
            if data["type"] == "frame":
                # Декодируем изображение из base64
                img_data = base64.b64decode(data["data"])
                nparr = np.frombuffer(img_data, np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                
                if frame is not None:
                    # Показываем кадр
                    cv2.imshow(self.window_name, frame)
                    
                    # Проверяем нажатие клавиши 'q' или закрытие окна
                    key = cv2.waitKey(1) & 0xFF
                    if key == ord('q') or cv2.getWindowProperty(self.window_name, cv2.WND_PROP_VISIBLE) < 1:
                        self.is_running = False
                        if self.websocket:
                            await self.websocket.close()
                        return 0
                
        except Exception as e:
            print(f"⚠️ Ошибка обработки кадра: {e}")
            
    async def cleanup(self):
        """Очистка ресурсов"""
        try:
            if self.websocket:
                await self.websocket.close()
        except:
            pass
        cv2.destroyAllWindows()
        print("👋 Программа завершена")

async def main():
    viewer = ScreenViewer()
    await viewer.connect()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n🛑 Программа остановлена")