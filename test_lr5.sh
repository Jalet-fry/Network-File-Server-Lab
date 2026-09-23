#!/bin/bash
echo "=== [LR5] ICMP Utils (Running as sudo for RAW sockets) ==="
echo "--- 1. Parallel Ping ---"
sudo ./run.sh 5 ping 8.8.8.8 1.1.1.1 77.88.8.8

echo -e "\n--- 2. Traceroute ---"
sudo ./run.sh 5 trace 8.8.8.8

echo -e "\n--- 3. Smurf Attack ---"
sudo ./run.sh 5 smurf 172.30.115.100 172.30.127.255 5