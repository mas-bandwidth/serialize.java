# Toolchain pins

- Temurin JDK 21.0.12.1+1 (Eclipse Adoptium), macos aarch64
  - url: https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz
  - sha256: 3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998
  - unpacked at: dist/jdk-21.0.12.1/ (gitignored; re-fetch by url+sha)
  - invoke: dist/jdk-21.0.12.1/Contents/Home/bin/java (and javac)
  - library targets Java 17 language level (javac --release 17); built and tested on 21

- Jazzer 0.30.0 (Code Intelligence, Apache-2.0), the standalone release: the fuzzing engine of `make fuzz`
  - fetched and checked by the Makefile on first use (the `$(JAZZER_JAR)` rule): curl, `shasum -a 256 -c`, then unpack
  - url: https://github.com/CodeIntelligenceTesting/jazzer/releases/download/v0.30.0/<asset>
  - jazzer-linux-x86-64.tar.gz sha256: 6eeaf0026d75599b07527d93b576593ce847cfca8b337a579971a5a9cdf792d0
  - jazzer-linux-arm64.tar.gz  sha256: 16636a4d3e98f1d3a7fcf59d4ab37f5be1d1ad6df9d94d5c3ca8de644838e369
  - jazzer-macos.tar.gz        sha256: 6239e2b2b8d626c287fd8a50376315f00ef4310e3f29e96315cd6e60911563c8
  - unpacked at: dist/jazzer-0.30.0/jazzer_standalone.jar (gitignored); run on the pinned JDK
  - the harness has no compile time dependency on it: targets are plain fuzzerTestOneInput( byte[] ) classes
