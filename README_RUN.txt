=== NETWORK FILE SERVER: ПОЛНАЯ ШПАРГАЛКА (ЛР 1-4) ===

1. СБОРКА (обязательно после изменений в коде):
   - Windows: Запустите BUILD_PROJECT.bat
   - Linux/Termux: chmod +x *.sh gradlew && ./BUILD_PROJECT.sh

2. ЗАПУСК (через терминал):
   Windows: .\run.bat <mode> <protocol> [port] [host]
   Unix/Termux: ./run.sh <mode> <protocol> [port] [host]

   АРГУМЕНТЫ <mode>:
     1 = Sequential/Multiplexed Server (ЛР 1 и ЛР 3)
         - В рамках одного потока обслуживает всех клиентов (Selector).
     2 = Client (Клиент для всех ЛР)
     3 = Thread Pool Server (ЛР 4 - ВАРИАНТ 5)
         - Пул потоков: Min=2, Max=10. Динамическое расширение.
         - Защищенный accept (synchronized).

   АРГУМЕНТЫ <protocol>:
     1 = TCP (Использовать для ЛР 1, 3, 4)
     2 = UDP (Использовать для ЛР 2 - Reliable UDP с ACK и Окном)

3. ПРИМЕРЫ КОМАНД:
   - ЛР 1/3 (TCP Server): .\run.bat 1 1
   - ЛР 2 (UDP Server):   .\run.bat 1 2
   - ЛР 4 (Thread Pool):  .\run.bat 3 1
   - Клиент (к любому):   .\run.bat 2 1 (для TCP) или .\run.bat 2 2 (для UDP)

4. ФУНКЦИОНАЛ КЛИЕНТА:
   - Введите 'ls' для просмотра файлов на сервере.
   - Введите 'download <имя>' для скачивания (поддерживает докачку).
   - Введите 'upload <имя>' для загрузки на сервер.
   - Введите '?' для вывода списка всех команд.
   - Используйте TAB для автодополнения (работает в Windows Terminal, CMD, Linux Shell).

5. ТЕХНИЧЕСКИЕ ПАРАМЕТРЫ:
   - Порт по умолчанию: 9999
   - Хранилища: папки 'files-server' и 'files-client' (создаются автоматически).
   - UDP Оверхед: < 1% (лимит по заданию 30%).
   - UDP Скорость: выше TCP в ~1.5 раза за счет скользящего окна.
