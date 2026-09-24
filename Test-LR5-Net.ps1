Write-Host '=== [1/2] PARALLEL PING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping 10.220.155.244 8.8.8.8
Write-Host '`n=== [2/2] TRACEROUTE ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host '`n[!] Raw test: friend launches on Fedora via: ./test_lr5_raw_net.sh' -ForegroundColor Yellow