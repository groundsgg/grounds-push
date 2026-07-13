# Changelog

## [0.12.2](https://github.com/groundsgg/grounds-push/compare/v0.12.1...v0.12.2) (2026-07-13)


### Bug Fixes

* **client:** don't kill a large upload at ten seconds ([#45](https://github.com/groundsgg/grounds-push/issues/45)) ([1ea2155](https://github.com/groundsgg/grounds-push/commit/1ea2155ac494e8867f343ce93cc14081d5e69758))

## [0.12.1](https://github.com/groundsgg/grounds-push/compare/v0.12.0...v0.12.1) (2026-07-07)


### Bug Fixes

* scope push requests to project ([#42](https://github.com/groundsgg/grounds-push/issues/42)) ([76870ff](https://github.com/groundsgg/grounds-push/commit/76870ffe9f06ba2cc082ec88ae952d23fd9a9b70))

## [0.12.0](https://github.com/groundsgg/grounds-push/compare/v0.11.0...v0.12.0) (2026-06-06)


### Features

* **manifest:** forward services declarations to forge ([#39](https://github.com/groundsgg/grounds-push/issues/39)) ([34cf74f](https://github.com/groundsgg/grounds-push/commit/34cf74f74e0d6193834da1d64024aca4e6cc78ff))

## [0.11.0](https://github.com/groundsgg/grounds-push/compare/v0.10.1...v0.11.0) (2026-06-03)


### Features

* transport an app's events: block to forge ([#37](https://github.com/groundsgg/grounds-push/issues/37)) ([5e359aa](https://github.com/groundsgg/grounds-push/commit/5e359aa3b1cc041bab69ede798c9dc7604dab260))

## [0.10.1](https://github.com/groundsgg/grounds-push/compare/v0.10.0...v0.10.1) (2026-05-21)


### Bug Fixes

* **push:** upload full flavor manifest ([#34](https://github.com/groundsgg/grounds-push/issues/34)) ([91629e7](https://github.com/groundsgg/grounds-push/commit/91629e79769894c1861dfe96087d794dd00f24eb))

## [0.10.0](https://github.com/groundsgg/grounds-push/compare/v0.9.1...v0.10.0) (2026-05-20)


### Features

* **push:** support app flavor selection ([#32](https://github.com/groundsgg/grounds-push/issues/32)) ([6af92ad](https://github.com/groundsgg/grounds-push/commit/6af92adafe48812146a9915c8fd40dc72020f639))


### Bug Fixes

* raise push artifact cap to 150mb ([#30](https://github.com/groundsgg/grounds-push/issues/30)) ([0d51644](https://github.com/groundsgg/grounds-push/commit/0d51644d3b335e281797e80ca3cb728bb3e95146))

## [0.9.1](https://github.com/groundsgg/grounds-push/compare/v0.9.0...v0.9.1) (2026-05-14)


### Bug Fixes

* raise upload cap to 100mb ([#28](https://github.com/groundsgg/grounds-push/issues/28)) ([1817053](https://github.com/groundsgg/grounds-push/commit/18170534ab166ee8bd63fba48368e29bb45d8475))

## [0.9.0](https://github.com/groundsgg/grounds-push/compare/v0.8.0...v0.9.0) (2026-05-14)


### Features

* upload effective plugin sources ([#26](https://github.com/groundsgg/grounds-push/issues/26)) ([1b9809c](https://github.com/groundsgg/grounds-push/commit/1b9809cd1c052edf856042a8193b4f05b843fb55))

## [0.8.0](https://github.com/groundsgg/grounds-push/compare/v0.7.0...v0.8.0) (2026-05-09)


### Features

* **push:** validate base images from catalog ([#24](https://github.com/groundsgg/grounds-push/issues/24)) ([c830292](https://github.com/groundsgg/grounds-push/commit/c8302929a2d2a2f6eb5a9756cf66392d56299f87))

## [0.7.0](https://github.com/groundsgg/grounds-push/compare/v0.6.0...v0.7.0) (2026-05-07)


### Features

* **plugin:** plugins[] supports Gradle projects + GitHub releases ([#21](https://github.com/groundsgg/grounds-push/issues/21)) ([1654296](https://github.com/groundsgg/grounds-push/commit/16542965b41e9409a721afc3458350328a5b8d6f))

## [0.6.0](https://github.com/groundsgg/grounds-push/compare/v0.5.1...v0.6.0) (2026-05-07)


### Features

* **plugin:** multi-plugin bundles via plugins[] manifest field ([#19](https://github.com/groundsgg/grounds-push/issues/19)) ([f375a17](https://github.com/groundsgg/grounds-push/commit/f375a17b1a464470181863b6c43004dbcd0cb101))

## [0.5.1](https://github.com/groundsgg/grounds-push/compare/v0.5.0...v0.5.1) (2026-05-06)


### Bug Fixes

* **gradle:** remove execution-time project access ([#17](https://github.com/groundsgg/grounds-push/issues/17)) ([6970748](https://github.com/groundsgg/grounds-push/commit/6970748f75d72e6146a7f1450c998041f1d7f57c))

## [0.5.0](https://github.com/groundsgg/grounds-push/compare/v0.4.0...v0.5.0) (2026-05-06)


### Features

* add grounds push build link ([#16](https://github.com/groundsgg/grounds-push/issues/16)) ([9ef7878](https://github.com/groundsgg/grounds-push/commit/9ef7878f80391c0f2d76778f0a729c219f274d14))
* **test-local:** groundsTestLocal Gradle task — offline dev loop ([#3](https://github.com/groundsgg/grounds-push/issues/3)) ([6532422](https://github.com/groundsgg/grounds-push/commit/6532422cc52fc5ddde550924534c2edbf0689ddd))

## [0.4.0](https://github.com/groundsgg/grounds-push/compare/v0.3.2...v0.4.0) (2026-05-05)


### Features

* **plugin:** --force flag to skip forge contentHash dedup ([#13](https://github.com/groundsgg/grounds-push/issues/13)) ([e3f27e0](https://github.com/groundsgg/grounds-push/commit/e3f27e0e1dce83d64158620a3c89bb50b43cbe83))

## [0.3.2](https://github.com/groundsgg/grounds-push/compare/v0.3.1...v0.3.2) (2026-05-02)


### Bug Fixes

* improve grounds-push developer UX ([#11](https://github.com/groundsgg/grounds-push/issues/11)) ([0ac0cfe](https://github.com/groundsgg/grounds-push/commit/0ac0cfeb120c861252e505c85c0cf9e70ac29fc8))

## [0.3.1](https://github.com/groundsgg/grounds-push/compare/v0.3.0...v0.3.1) (2026-05-02)


### Bug Fixes

* name release-please package ([53d3469](https://github.com/groundsgg/grounds-push/commit/53d34693785d98a93dc179d0d52536e1355756c6))

## [0.3.0](https://github.com/groundsgg/grounds-push/compare/v0.2.0...v0.3.0) (2026-05-02)


### Features

* **client:** CredentialResolver with env + file + platform-aware paths ([a513b02](https://github.com/groundsgg/grounds-push/commit/a513b02dc2d765a412b7f6af96cc2d504dee829c))
* **client:** GroundsForgeClient with multipart POST, GET, retry, SSE stream ([d8ee89e](https://github.com/groundsgg/grounds-push/commit/d8ee89ebae77d0848ba84909cf7b74db2b7eb867))
* **client:** JAR size pre-upload guard ([07e6dd0](https://github.com/groundsgg/grounds-push/commit/07e6dd0300439f0a0c6bdbc26fbdfb31b522e036))
* **client:** SSE event-frame decoder ([3e32b49](https://github.com/groundsgg/grounds-push/commit/3e32b491ae9fd290476b7749039cf1e964844f8d))
* **manifest:** GroundsYaml parser with SnakeYAML + test coverage ([f443d27](https://github.com/groundsgg/grounds-push/commit/f443d27794de3da8ec90269ec96da621dfb9282e))
* **plugin:** GroundsPushPlugin + Extension + tasks wiring end-to-end flow ([86c1747](https://github.com/groundsgg/grounds-push/commit/86c1747100c37ec495d3b48b22209ce70c258197))
* rework release pipeline ([#7](https://github.com/groundsgg/grounds-push/issues/7)) ([fa732cc](https://github.com/groundsgg/grounds-push/commit/fa732cc9066d38775a5910443190d7b36ba4b8f9))
* **sample:** Paper plugin sample with grounds.yaml for E2E testing ([9afa7cd](https://github.com/groundsgg/grounds-push/commit/9afa7cd47e5eab204e82c70e66332baae10b9a4e))


### Bug Fixes

* **build:** read release-please manifest with eager File I/O ([#6](https://github.com/groundsgg/grounds-push/issues/6)) ([31620c6](https://github.com/groundsgg/grounds-push/commit/31620c6625ceb86e457efd34552a355ff8108037))
* **build:** remove hardcoded macOS java.home from gradle.properties ([fc40831](https://github.com/groundsgg/grounds-push/commit/fc40831592485a8a7a17e3fc7540eede4b927365))
* **build:** resolve release-please manifest from outer composite build root ([61af96c](https://github.com/groundsgg/grounds-push/commit/61af96c96dc86a7122687582b53d898f332b6235))
* **plugin:** show real bytes for sub-1KB jars in upload log ([#4](https://github.com/groundsgg/grounds-push/issues/4)) ([e940a81](https://github.com/groundsgg/grounds-push/commit/e940a8113c03f1f220b61a7deeb33db2f32564ea))
* reset release-please component config ([541e783](https://github.com/groundsgg/grounds-push/commit/541e78301fe6a9ee886aec47aa1e567bbeba3cbc))


### Documentation

* readme stub with install snippet, auth and local dev instructions ([c1e20ab](https://github.com/groundsgg/grounds-push/commit/c1e20ab7cf2e27e2907bdb7f53111c80bec84b08))

## [0.2.0](https://github.com/groundsgg/grounds-push/compare/v0.1.0...v0.2.0) (2026-04-24)


### Features

* **client:** CredentialResolver with env + file + platform-aware paths ([a513b02](https://github.com/groundsgg/grounds-push/commit/a513b02dc2d765a412b7f6af96cc2d504dee829c))
* **client:** GroundsForgeClient with multipart POST, GET, retry, SSE stream ([d8ee89e](https://github.com/groundsgg/grounds-push/commit/d8ee89ebae77d0848ba84909cf7b74db2b7eb867))
* **client:** JAR size pre-upload guard ([07e6dd0](https://github.com/groundsgg/grounds-push/commit/07e6dd0300439f0a0c6bdbc26fbdfb31b522e036))
* **client:** SSE event-frame decoder ([3e32b49](https://github.com/groundsgg/grounds-push/commit/3e32b491ae9fd290476b7749039cf1e964844f8d))
* **manifest:** GroundsYaml parser with SnakeYAML + test coverage ([f443d27](https://github.com/groundsgg/grounds-push/commit/f443d27794de3da8ec90269ec96da621dfb9282e))
* **plugin:** GroundsPushPlugin + Extension + tasks wiring end-to-end flow ([86c1747](https://github.com/groundsgg/grounds-push/commit/86c1747100c37ec495d3b48b22209ce70c258197))
* **sample:** Paper plugin sample with grounds.yaml for E2E testing ([9afa7cd](https://github.com/groundsgg/grounds-push/commit/9afa7cd47e5eab204e82c70e66332baae10b9a4e))


### Bug Fixes

* **build:** remove hardcoded macOS java.home from gradle.properties ([fc40831](https://github.com/groundsgg/grounds-push/commit/fc40831592485a8a7a17e3fc7540eede4b927365))


### Documentation

* readme stub with install snippet, auth and local dev instructions ([c1e20ab](https://github.com/groundsgg/grounds-push/commit/c1e20ab7cf2e27e2907bdb7f53111c80bec84b08))
