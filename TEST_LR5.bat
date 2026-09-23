@echo off
call run_admin.bat 5 ping 10.220.155.244 8.8.8.8
pause
call run_admin.bat 5 smurf 10.220.155.244 10.220.155.255 10
pause