# Changelog

## [0.5.0](https://github.com/groundsgg/service-player/compare/v0.4.0...v0.5.0) (2026-07-24)


### Features

* **presence:** broadcast the network player count every five seconds ([#90](https://github.com/groundsgg/service-player/issues/90)) ([fab6923](https://github.com/groundsgg/service-player/commit/fab6923b5028498f6dc17d1f912e168ba3c4730c))

## [0.4.0](https://github.com/groundsgg/service-player/compare/v0.3.0...v0.4.0) (2026-07-24)


### Features

* **presence:** record a player's region and count players by proxy ([#88](https://github.com/groundsgg/service-player/issues/88)) ([91a39fb](https://github.com/groundsgg/service-player/commit/91a39fbad448fa9c2e0c76f3000799af68b7ff23))

## [0.3.0](https://github.com/groundsgg/service-player/compare/v0.2.1...v0.3.0) (2026-07-24)


### Features

* **grpc:** add CountPlayersByServer RPC for network-wide counts ([#77](https://github.com/groundsgg/service-player/issues/77)) ([2272ca0](https://github.com/groundsgg/service-player/commit/2272ca0cb20291ee16948370a0563c595733fed3))
* **persistence:** declare which reads may be stale ([#87](https://github.com/groundsgg/service-player/issues/87)) ([4027e9f](https://github.com/groundsgg/service-player/commit/4027e9fa55f8706fd06035d9835171229a980d9b))

## [0.2.1](https://github.com/groundsgg/service-player/compare/v0.2.0...v0.2.1) (2026-07-16)


### Bug Fixes

* **auth:** initialize JWT validation at startup ([#82](https://github.com/groundsgg/service-player/issues/82)) ([f467b33](https://github.com/groundsgg/service-player/commit/f467b33cb1d74230324bfc5baff7dbd5ac4c1348))

## [0.2.0](https://github.com/groundsgg/service-player/compare/v0.1.0...v0.2.0) (2026-07-16)


### Features

* add player hearbeat processing ([#21](https://github.com/groundsgg/service-player/issues/21)) ([0285e6c](https://github.com/groundsgg/service-player/commit/0285e6c44d2519941a6638ae651ef8ada40dc03c))
* **player:** add durable player-name index + LookupPlayerNames RPC ([#78](https://github.com/groundsgg/service-player/issues/78)) ([b3c9b04](https://github.com/groundsgg/service-player/commit/b3c9b047552e1a83a16c27bbd2c75751e04fc36d))
* **player:** server-side OpenTelemetry spans for incoming gRPC ([#59](https://github.com/groundsgg/service-player/issues/59)) ([ec98468](https://github.com/groundsgg/service-player/commit/ec984688dfa96d98d40a50f56db6d374aa2feed7))
* **player:** validate incoming gRPC JWTs (v2.2 service auth) ([#58](https://github.com/groundsgg/service-player/issues/58)) ([815fb92](https://github.com/groundsgg/service-player/commit/815fb926b0fe46c79f9e160add7970c663dbd827))
* **presence:** answer session lookups by name, id and prefix ([#75](https://github.com/groundsgg/service-player/issues/75)) ([5a1957d](https://github.com/groundsgg/service-player/commit/5a1957d546b1b4990252fc91f6bbd8319b499bb6))


### Bug Fixes

* **auth:** warm JWKS during startup ([#81](https://github.com/groundsgg/service-player/issues/81)) ([5f4a300](https://github.com/groundsgg/service-player/commit/5f4a300a439913a9485672be1e078214ba573ade))
* migrate gradle plugin to convention plugin ([#43](https://github.com/groundsgg/service-player/issues/43)) ([20f4b7a](https://github.com/groundsgg/service-player/commit/20f4b7a46dbf8676a26d3f1335179dc5f56713e7))

## [0.1.0](https://github.com/groundsgg/service-player/compare/v0.0.1...v0.1.0) (2026-01-18)


### Features

* add a simple github action to build and test the gradle project ([#1](https://github.com/groundsgg/service-player/issues/1)) ([821f445](https://github.com/groundsgg/service-player/commit/821f445ae355938916509e67a4b99290294cb774))
* add docker image and setup release pipeline ([#9](https://github.com/groundsgg/service-player/issues/9)) ([26dfd80](https://github.com/groundsgg/service-player/commit/26dfd80b69782cf3da5ea6b59e38bb5fd6f5891b))
* initial commit ([c7a3abf](https://github.com/groundsgg/service-player/commit/c7a3abfbd204c7c9e54f40a8ae623af19b7bceb1))
* migrate to kotlin ([#8](https://github.com/groundsgg/service-player/issues/8)) ([b52ae55](https://github.com/groundsgg/service-player/commit/b52ae5558e82b2f37689b035679bc4c23993c918))
* retrieve the grpc contract from a dependency ([e343153](https://github.com/groundsgg/service-player/commit/e34315323798475234f8e8ccef1b4880e333bce3))


### Bug Fixes

* devspace is now working again with the needed credentials from the gradle.properties ([#11](https://github.com/groundsgg/service-player/issues/11)) ([369f44f](https://github.com/groundsgg/service-player/commit/369f44f20c411483fdff3cf740fb6fddc8ba91f5))
