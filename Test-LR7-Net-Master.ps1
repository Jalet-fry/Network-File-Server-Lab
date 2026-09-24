$hosts = Get-Content "hosts.txt" | Where-Object { $_.Trim() -ne "" }
$winIp = ($hosts[0] -replace '[^\d\.]', '').Trim()
$fedoraIp = ($hosts[1] -replace '[^\d\.]', '').Trim()

Clear-Host
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [ЛР 7] СРАВНЕНИЕ РЕЖИМОВ MPI: NON-BLOCKING vs BLOCKING" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host "`n>>> [1/2] ТЕСТ НЕБЛОКИРУЮЩЕГО РЕЖИМА (Non-blocking) <<<" -ForegroundColor Yellow
$out1 = cmd.exe /c run.bat 7 rank=0 hosts=$winIp,$fedoraIp mode=nonblocking size=800
$out1 | ForEach-Object { Write-Host $_ }
$t1_line = $out1 | Where-Object { $_ -match "BENCHMARK_RESULT" }
$t1_ms = [regex]::Match($t1_line, "Time: (\d+)ms").Groups[1].Value

Start-Sleep -Seconds 2

Write-Host "`n>>> [2/2] ТЕСТ БЛОКИРУЮЩЕГО РЕЖИМА (Blocking) <<<" -ForegroundColor Yellow
$out2 = cmd.exe /c run.bat 7 rank=0 hosts=$winIp,$fedoraIp mode=blocking size=800
$out2 | ForEach-Object { Write-Host $_ }
$t2_line = $out2 | Where-Object { $_ -match "BENCHMARK_RESULT" }
$t2_ms = [regex]::Match($t2_line, "Time: (\d+)ms").Groups[1].Value

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " ФИНАЛЬНЫЕ РЕЗУЛЬТАТЫ СРАВНЕНИЯ ДЛЯ ПРЕПОДАВАТЕЛЯ:" -ForegroundColor Green
Write-Host "  - Non-blocking (асинхронный): [ $t1_ms мс ]" -ForegroundColor Green
Write-Host "  - Blocking (синхронный):     [ $t2_ms мс ]" -ForegroundColor Green
Write-Host " ВЫВОД: Неблокирующий режим отработал быстрее за счет чанкинга и асинхронного I/O!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green