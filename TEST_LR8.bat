@echo off
echo ===================================================
echo RUNNING LR8 GROUPS & MPI-IO (4 processes, 2 groups)
echo ===================================================
call run.bat mpirun 4 8 size=600 groups=2
echo.
echo Results saved to result_group_0.bin and result_group_1.bin!
pause