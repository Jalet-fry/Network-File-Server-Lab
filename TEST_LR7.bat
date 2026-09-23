@echo off
echo ===================================================
echo [1/2] RUNNING LR7 BLOCKING MODE (3 processes)
echo ===================================================
call run.bat mpirun 3 7 size=600 mode=blocking
echo.

echo ===================================================
echo [2/2] RUNNING LR7 NON-BLOCKING MODE (3 processes)
echo ===================================================
call run.bat mpirun 3 7 size=600 mode=nonblocking
echo.
echo Check the difference in execution time above!
pause