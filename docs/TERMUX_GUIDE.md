# 📱 Работа в Termux (Android)

## 📶 Настройка сети
1. **VPN:** Обязательно **выключи VPN** на телефоне и ноутбуке.
2. **Сеть:** Лучший способ — раздать Wi-Fi с телефона (точка доступа) на ноутбук.
3. **IP:** Узнай адрес в Termux командой `ip addr show` (обычно в блоке `wlan0`).

## 🛠 Сборка и запуск
```bash
# Обновить код из репозитория
git fetch origin
git reset --hard origin/termux-fix

# Собрать проект (нужна Java 17+)
chmod +x gradlew
./gradlew installDist

# Запуск клиента (Протокол 1=TCP, 2=UDP)
./run.sh 2 <протокол> 8888 <IP_СЕРВЕРА>
```

## ⚠️ Решение проблем
- **"Bad interpreter":** Если скрипт не запускается, введи `pkg install dos2unix && dos2unix run.sh`.
- **JLine Error:** Ошибки типа `stty` или `native library` в Termux — это нормально. Программа автоматически перейдет в "простой" режим ввода.
- **Логирование:** Чтобы сохранить лог теста, запускай так: 
  `./run.sh ... | tee my_test.txt`
