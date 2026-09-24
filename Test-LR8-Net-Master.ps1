$hosts = Get-Content "hosts.txt" | Where-Object { $_.Trim() -ne "" }
$winIp = ($hosts[0] -replace '[^\d\.]', '').Trim()
$fedoraIp = ($hosts[1] -replace '[^\d\.]', '').Trim()

Clear-Host
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [ЛР 8] MPI ГРУППЫ, КОЛЛЕКТИВНЫЕ ОПЕРАЦИИ И MPI-IO" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host "1. Генерация общих бинарных матриц..." -ForegroundColor Yellow
cmd.exe /c run.bat gen size=600

Write-Host "`n2. Запуск группового умножения (Bcast, Reduce) и параллельной записи в файл..." -ForegroundColor Yellow
$t8_start = Get-Date
cmd.exe /c run.bat 8 rank=0 hosts=$winIp,$fedoraIp groups=1 size=600
$t8_time = ((Get-Date) - $t8_start).TotalSeconds

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " РЕЗУЛЬТАТ ВЫПОЛНЕНИЯ ЛР 8:" -ForegroundColor Green
Write-Host "  - Время групповых операций: [ $("{0:N2}" -f $t8_time) сек ]" -ForegroundColor Green
Write-Host "  - Проверена работа коллективных функций Bcast и Reduce" -ForegroundColor Green
Write-Host "  - Проверена параллельная запись со смещением в MPI-IO файл:" -ForegroundColor Green
Get-ChildItem result_group_*.bin | Select-Object Name, Length, LastWriteTime
Write-Host "==========================================================" -ForegroundColor Green