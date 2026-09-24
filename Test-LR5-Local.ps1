Write-Host '=== [1/2] PARALLEL PING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping 8.8.8.8 1.1.1.1 127.0.0.1
Write-Host '
=== [2/2] TRACEROUTE ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host '
[!] Smurf запускай в WSL через: ./test_lr5_smurf_local.sh' -ForegroundColor Yellow