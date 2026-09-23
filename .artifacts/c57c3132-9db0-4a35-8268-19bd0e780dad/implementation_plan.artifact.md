# Implementation Plan - Labs 5-8 Finalization

Implement and verify Labs 5-8 in Kotlin, ensuring they meet the requirements and can be run on Windows and Linux.

## User Review Required

> [!IMPORTANT]
> **Lab 5 (ICMP)** requires raw sockets. On Windows, you must run the application as Administrator. On Linux, you must run it as root or grant `CAP_NET_RAW` to the Java executable.
> **Lab 8 (MPI)** requires shared file access (e.g., a network drive or the same directory) for all nodes to read `matrixA.bin` and `matrixB.bin`.

## Proposed Changes

### [Network Discovery & Chat (Lab 6)]

#### [MODIFY] [NetworkDiscovery.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr6/NetworkDiscovery.kt)
- Fix potential `N/A` broadcast address issue by providing a fallback or better detection.

#### [MODIFY] [P2PChat.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr6/P2PChat.kt)
- Add robust handling for missing broadcast addresses.

### [ICMP & Parallel Ping (Lab 5)]

#### [MODIFY] [IcmpService.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr5/IcmpService.kt)
- Improve `parallelPing` logic to avoid deadlocks when receiving unexpected ICMP packets.
- Add a "discard" mechanism for packets that are not Echo Replies.

### [MPI Matrix Multiplication (Labs 7 & 8)]

#### [MODIFY] [MatrixMultGroups.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/lr8/MatrixMultGroups.kt)
- Remove hardcoded `size = 1000` and use the command-line argument.
- Ensure result files are named correctly per group.

#### [MODIFY] [Mpi.kt](file:///C:/Univer/SEM_7/Network-File-Server-Lab/src/main/kotlin/sspoirs/mpi/Mpi.kt)
- Improve connection stability and handshake logic.

### [Scripts & Build]

#### [MODIFY] [run.bat](file:///C:/Univer/SEM_7/Network-File-Server-Lab/run.bat)
- Ensure correct arguments are passed for all labs.
- Add specific MPI run configurations.

#### [MODIFY] [run.sh](file:///C:/Univer/SEM_7/Network-File-Server-Lab/run.sh)
- Similar updates for Linux parity.

## Verification Plan

### Automated Tests
- None planned as these are network-intensive labs; verification will be manual.

### Manual Verification
- **Lab 5**: Run `run.bat 5 ping 8.8.8.8 1.1.1.1 google.com` (as Admin).
- **Lab 6**: Run `run.bat 6` in two instances, verify message exchange and peer listing.
- **Lab 7**: Run MPI matrix multiplication on 2+ ranks.
- **Lab 8**: Generate matrices with `run.bat gen size=500`, then run MPI groups multiplication.
