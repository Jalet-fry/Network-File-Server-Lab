#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
systemctl stop firewalld 2>/dev/null
sysctl -w net.ipv4.icmp_echo_ignore_all=0 >/dev/null 2>&1
sysctl -w net.ipv4.icmp_echo_ignore_broadcasts=0 >/dev/null 2>&1
chmod +x *.sh gradlew build/install/*/bin/* 2>/dev/null
echo 'Linux готов к сдаче ЛР 5-8!'