# План реализации Лабораторной работы №5: ICMP и IP

В рамках данной работы будет реализована утилита для работы с протоколами сетевого уровня (L3) с использованием Raw-сокетов через JNA.

## User Review Required

> [!IMPORTANT]
> **Права администратора:** Работа с Raw-сокетами (`SOCK_RAW`) требует привилегий суперпользователя (Administrator в Windows).
> **Ограничения Windows:** В современных версиях Windows (начиная с XP SP2) ограничена возможность отправки Raw-пакетов с подменой IP-адреса (Smurf-атака). Для полноценной демонстрации может потребоваться Linux или специфические настройки реестра.

## Proposed Changes

### 1. Нативный слой (JNA)
Так как стандартная библиотека Java не поддерживает Raw-сокеты, мы будем использовать JNA для вызова функций системного API (Winsock2 для Windows).

#### [NEW] [Winsock2.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/native/Winsock2.kt)
Определение интерфейса для работы с сокетами: `socket`, `bind`, `sendto`, `recvfrom`, `setsockopt`, `getsockopt`, `htons`, `inet_addr`.

#### [NEW] [IcmpHeaders.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/native/IcmpHeaders.kt)
Определение структур (JNA `Structure`):
- `IPHeader` (версия, IHL, TOS, длина, ID, флаги, TTL, протокол, чексумма, адреса).
- `ICMPHeader` (тип, код, чексумма, ID, seq).

---

### 2. Ядро ICMP-клиента

#### [NEW] [IcmpClient.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/IcmpClient.kt)
Базовый класс для работы с Raw-сокетом:
- Подсчет контрольной суммы (RFC 1071).
- Создание сокета `AF_INET`, `SOCK_RAW`, `IPPROTO_ICMP`.
- Метод `sendEchoRequest` с возможностью передачи Timestamp в теле.
- Метод `receivePacket` с поддержкой `MSG_PEEK`.

---

### 3. Функциональные возможности

#### [NEW] [PingTask.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/PingTask.kt)
Логика для отдельного потока пинга:
1. Отправляет Echo Request.
2. В цикле вызывает `recvfrom` с `MSG_PEEK`.
3. Если ID пакета совпадает с ID потока — вызывает `recvfrom` без флагов (удаляет из буфера) и вычисляет RTT.
4. Обработка таймаутов и ошибок.

#### [NEW] [TracerouteService.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/TracerouteService.kt)
Реализация определения пути:
- Итеративное увеличение TTL (от 1 до 30).
- Обработка типа ICMP `11` (Time Exceeded).
- Вывод промежуточных узлов.

#### [NEW] [SmurfAttack.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/SmurfAttack.kt)
Специальный режим отправки:
- Включение `IP_HDRINCL`.
- Ручное формирование `IPHeader` с поддельным `srcAddress`.
- Отправка на Broadcast адрес сети.

---

### 4. Интеграция

#### [MODIFY] [Main.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/Main.kt)
Добавление новых команд в CLI:
- `ping <hosts...>` — параллельный запуск потоков.
- `traceroute <host>` — запуск трассировки.
- `smurf <victim_ip> <broadcast_ip>` — выполнение атаки.

## Verification Plan

### Automated Tests
- Модульные тесты для парсера ICMP-заголовков и функции `checksum`.
- Пинг `127.0.0.1` (если поддерживается Raw-сокетами ОС в петле).

### Manual Verification
1. Запуск `ping 8.8.8.8 1.1.1.1` — проверка параллельной работы и вывода RTT.
2. Запуск `traceroute google.com` — проверка вывода цепочки IP.
3. Запуск `smurf` — наблюдение в Wireshark за пакетами с подмененным IP.
