Write-Host '=== MPI NON-BLOCKING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 7 rank=0 hosts=10.220.155.14,10.220.155.244 mode=nonblocking size=800
Write-Host '
=== MPI BLOCKING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 7 rank=0 hosts=10.220.155.14,10.220.155.244 mode=blocking size=800