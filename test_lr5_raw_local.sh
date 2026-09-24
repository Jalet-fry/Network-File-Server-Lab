#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
echo 'Sending raw ICMP packets with Source IP 127.0.0.1...'
./run.sh 5 raw 127.0.0.1 127.0.0.1 10