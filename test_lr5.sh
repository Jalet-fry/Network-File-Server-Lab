#!/bin/bash
# Автоматически отключаем фаервол Fedora и разрешаем ICMP
sudo systemctl stop firewalld 2>/dev/null
sudo sysctl -w net.ipv4.icmp_echo_ignore_all=0 >/dev/null 2>&1

echo "=== [1/2] TEST PARALLEL PING ==="
sudo ./run.sh 5 ping 10.220.155.14 8.8.8.8

echo -e "\n=== [2/2] TEST TRACEROUTE ==="
sudo ./run.sh 5 trace 8.8.8.8