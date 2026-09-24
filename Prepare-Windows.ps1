Write-Host 'Открытие портов фаервола Windows для ЛР 5-8...' -ForegroundColor Cyan
netsh advfirewall firewall add rule name='MPI_TCP' dir=in action=allow protocol=TCP localport=10000-10020 profile=any | Out-Null
netsh advfirewall firewall add rule name='CHAT_UDP' dir=in action=allow protocol=UDP localport=9999 profile=any | Out-Null
netsh advfirewall firewall add rule name='ICMP_IN' dir=in action=allow protocol=icmpv4:8,any | Out-Null
Write-Host 'Готово! Фаервол настроен.' -ForegroundColor Green