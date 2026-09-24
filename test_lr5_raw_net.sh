#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
echo 'Sending raw ICMP packets: Source=10.220.155.14 -> Target=10.220.155.244...'
./run.sh 5 raw 10.220.155.14 10.220.155.244 10