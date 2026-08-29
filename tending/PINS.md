# Toolchain pins

- Temurin JDK 21.0.12.1+1 (Eclipse Adoptium), macos aarch64
  - url: https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz
  - sha256: 3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998
  - unpacked at: dist/jdk-21.0.12.1/ (gitignored; re-fetch by url+sha)
  - invoke: dist/jdk-21.0.12.1/Contents/Home/bin/java (and javac)
  - library targets Java 17 language level (javac --release 17); built and tested on 21
