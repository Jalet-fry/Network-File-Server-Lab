$hosts = Get-Content "hosts.txt" | Where-Object { $_.Trim() -ne "" }
$winIp = ($hosts[0] -replace '[^\d\.]', '').Trim()
$fedoraIp = ($hosts[1] -replace '[^\d\.]', '').Trim()

Write-Host "Генерация матриц..." -ForegroundColor Cyan
cmd.exe /c run.bat gen size=600
Write-Host "Запуск параллельного вычисления групп и MPI-IO..." -ForegroundColor Cyan
cmd.exe /c run.bat 8 rank=0 hosts=$winIp,$fedoraIp groups=1 size=600
Get-ChildItem result_group_*.bin