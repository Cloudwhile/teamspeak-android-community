## Summary / 变更概述

- 

## Verification Checklist / 验证清单

- [ ] `cargo test --manifest-path rust-core/Cargo.toml` passes
- [ ] `./gradlew :app:testDebugUnitTest :app:lintVitalRelease :app:assembleRelease` passes
- [ ] No private keys, signing keystores (`.jks` / `.keystore` / `keystore.properties`), or sensitive credentials included
