#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
echo 'Отправка Smurf с подменой IP на 127.0.0.1...'
./run.sh 5 smurf 127.0.0.1 127.0.0.1 10