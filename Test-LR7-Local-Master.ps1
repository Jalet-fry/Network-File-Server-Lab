Write-Host '=== MPI NON-BLOCKING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 7 rank=0 hosts=127.0.0.1,127.0.0.1 mode=nonblocking size=800
Write-Host '
=== MPI BLOCKING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 7 rank=0 hosts=127.0.0.1,127.0.0.1 mode=blocking size=800