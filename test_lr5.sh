#!/bin/bash
echo "=== [1/3] TEST PARALLEL PING ==="
sudo ./run.sh 5 ping 10.220.155.14 8.8.8.8

echo -e "\n=== [2/3] TEST TRACEROUTE ==="
sudo ./run.sh 5 trace 8.8.8.8

echo -e "\n=== [3/3] TEST SMURF ATTACK ==="
sudo ./run.sh 5 smurf 10.220.155.14 10.220.155.255 10
echo "Done!"