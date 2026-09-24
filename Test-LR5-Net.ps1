$hostsFile = Join-Path $PSScriptRoot "hosts.txt"
if (-not (Test-Path $hostsFile)) { $hostsFile = "hosts.txt" }
$hosts = Get-Content $hostsFile | Where-Object { $_.Trim() -ne "" }
$winIp = $hosts[0].Trim()
$fedoraIp = $hosts[1].Trim()

Write-Host "=== [1/2] PARALLEL PING (Fedora: $fedoraIp, Google: 8.8.8.8) ===" -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping $fedoraIp 8.8.8.8
Write-Host "`n=== [2/2] TRACEROUTE (Google: 8.8.8.8) ===" -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host "`n[!] Raw test: friend launches on Fedora via: ./test_lr5_raw_net.sh" -ForegroundColor Yellow