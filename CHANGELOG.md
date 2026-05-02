# Changelog

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
