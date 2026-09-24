$hosts = Get-Content "hosts.txt" | Where-Object { $_.Trim() -ne "" }
$winIp = ($hosts[0] -replace '[^\d\.]', '').Trim()
$fedoraIp = ($hosts[1] -replace '[^\d\.]', '').Trim()

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [ЛР 7] MPI MATRIX MULTIPLICATION (Master: $winIp)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Запуск в НЕБЛОКИРУЮЩЕМ режиме (быстрый, с перекрытием вычислений)..." -ForegroundColor Yellow
cmd.exe /c run.bat 7 rank=0 hosts=$winIp,$fedoraIp mode=nonblocking size=800