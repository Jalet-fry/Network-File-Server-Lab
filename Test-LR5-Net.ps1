$hosts = Get-Content "hosts.txt" | Where-Object { $_.Trim() -ne "" }
$winIp = ($hosts[0] -replace '[^\d\.]', '').Trim()
$fedoraIp = ($hosts[1] -replace '[^\d\.]', '').Trim()

Write-Host "=== [1/2] PARALLEL PING (Fedora: $fedoraIp, DNS: 8.8.8.8) ===" -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping $fedoraIp 8.8.8.8
Write-Host "`n=== [2/2] TRACEROUTE (8.8.8.8) ===" -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host "`n[!] Raw test: друг запускает на Fedora: sudo ./test_lr5_raw_net.sh" -ForegroundColor Yellow