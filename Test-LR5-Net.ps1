Write-Host '=== [1/2] PARALLEL PING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping 10.220.155.244 8.8.8.8
Write-Host '
=== [2/2] TRACEROUTE ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host '
[!] Smurf запускает друг на Fedora через: ./test_lr5_smurf_net.sh' -ForegroundColor Yellow