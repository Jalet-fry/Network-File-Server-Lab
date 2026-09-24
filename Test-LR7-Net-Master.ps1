$hosts = Get-Content "hosts.txt" | Where-Object { $_.Trim() -ne "" }
$winIp = ($hosts[0] -replace '[^\d\.]', '').Trim()
$fedoraIp = ($hosts[1] -replace '[^\d\.]', '').Trim()

Write-Host "=== MPI NON-BLOCKING (Быстрый режим) ===" -ForegroundColor Cyan
cmd.exe /c run.bat 7 rank=0 hosts=$winIp,$fedoraIp mode=nonblocking size=800
Write-Host "`n=== MPI BLOCKING (Медленный режим) ===" -ForegroundColor Cyan
cmd.exe /c run.bat 7 rank=0 hosts=$winIp,$fedoraIp mode=blocking size=800