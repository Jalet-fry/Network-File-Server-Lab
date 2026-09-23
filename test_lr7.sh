#!/bin/bash
echo "==================================================="
echo "[1/2] RUNNING LR7 BLOCKING MODE (3 processes)"
echo "==================================================="
./run.sh mpirun 3 7 size=600 mode=blocking

echo -e "\n==================================================="
echo "[2/2] RUNNING LR7 NON-BLOCKING MODE (3 processes)"
echo "==================================================="
./run.sh mpirun 3 7 size=600 mode=nonblocking