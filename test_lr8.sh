#!/bin/bash
echo "==================================================="
echo "RUNNING LR8 GROUPS & MPI-IO (4 processes, 2 groups)"
echo "==================================================="
./run.sh mpirun 4 8 size=600 groups=2