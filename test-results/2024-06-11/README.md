Benchmarks run on AWS EC2 c6i.2xlarge instance (8 cores, 16 GB RAM).

Version information (`java -version`):

- `zulu-22`: `OpenJDK 64-Bit Server VM Zulu22.30+13-CA (build 22.0.1+8, mixed mode, sharing)`
- `graalvm-oracle-22`: `Java HotSpot(TM) 64-Bit Server VM Oracle GraalVM 22.0.1+8.1 (build 22.0.1+8-jvmci-b01, mixed mode, sharing)`
- `graalvm-ce-22`: `OpenJDK 64-Bit Server VM GraalVM CE 22.0.1+8.1 (build 22.0.1+8-jvmci-b01, mixed mode, sharing)`

All tests run with `java -Xmx8G -jar bug-report-graal-compiler-warmup-1.0.0-SNAPSHOT-jar-with-dependencies.jar 1000 120 netty-warmup-results-<JVM name>.csv` (1000 connections, 120 seconds).
