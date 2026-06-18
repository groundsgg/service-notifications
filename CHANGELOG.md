# Changelog

## [0.6.1](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.6.0...service-notifications-v0.6.1) (2026-06-18)


### Bug Fixes

* support nats token files ([#18](https://github.com/groundsgg/service-notifications/issues/18)) ([36113e8](https://github.com/groundsgg/service-notifications/commit/36113e8b578ea2910cf1520c9e42ebe2e46de6a2))

## [0.6.0](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.5.0...service-notifications-v0.6.0) (2026-06-18)


### Features

* make notifications live fanout HA with NATS ([#16](https://github.com/groundsgg/service-notifications/issues/16)) ([5dfb398](https://github.com/groundsgg/service-notifications/commit/5dfb398b59a6141c036194686c96437aa2d13072))

## [0.5.0](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.4.1...service-notifications-v0.5.0) (2026-06-04)


### Features

* support cluster resume notification actions ([#13](https://github.com/groundsgg/service-notifications/issues/13)) ([661c522](https://github.com/groundsgg/service-notifications/commit/661c5222fed0303f21fa7a2735bf5080b636ef07))

## [0.4.1](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.4.0...service-notifications-v0.4.1) (2026-06-02)


### Bug Fixes

* apply spotless formatting ([#11](https://github.com/groundsgg/service-notifications/issues/11)) ([3dd9d00](https://github.com/groundsgg/service-notifications/commit/3dd9d0029305783ee716315bd59ca1d40890291d))

## [0.4.0](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.3.1...service-notifications-v0.4.0) (2026-06-02)


### Features

* add notification live stream ([#9](https://github.com/groundsgg/service-notifications/issues/9)) ([f5bfdcb](https://github.com/groundsgg/service-notifications/commit/f5bfdcb45c82f68e6153939311969901fa0386ef))

## [0.3.1](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.3.0...service-notifications-v0.3.1) (2026-06-02)


### Bug Fixes

* authorize notification admins via forge access ([2026879](https://github.com/groundsgg/service-notifications/commit/202687949e30d1d2fc7c63c4ce82ac1ffc0e243a))

## [0.3.0](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.2.0...service-notifications-v0.3.0) (2026-06-02)


### Features

* add notification admin endpoints ([#6](https://github.com/groundsgg/service-notifications/issues/6)) ([5060334](https://github.com/groundsgg/service-notifications/commit/50603348cbb00608c1323c99a71d176e4c6761d5))


### Bug Fixes

* align notification logging conventions ([#7](https://github.com/groundsgg/service-notifications/issues/7)) ([6cce15a](https://github.com/groundsgg/service-notifications/commit/6cce15aa994bbd481882cd93e6d02119fc27bf74))
* hide completed notification actions ([119d48d](https://github.com/groundsgg/service-notifications/commit/119d48d43608657fe52b6c4acd88f449c64026b5))

## [0.2.0](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.1.1...service-notifications-v0.2.0) (2026-06-01)


### Features

* add notification read state endpoints ([ac38e03](https://github.com/groundsgg/service-notifications/commit/ac38e0324542036a2b7fe3701cc8f20638823753))


### Bug Fixes

* mark successful notification actions read ([893c87e](https://github.com/groundsgg/service-notifications/commit/893c87e3f092f466aa4e2cc98b5c3f4433455a63))

## [0.1.1](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.1.0...service-notifications-v0.1.1) (2026-05-31)


### Bug Fixes

* **docker:** use distroless runtime image ([47e0f33](https://github.com/groundsgg/service-notifications/commit/47e0f335a66b3cef8edc46a00f4c40584c9633f1))

## [0.1.0](https://github.com/groundsgg/service-notifications/compare/service-notifications-v0.0.1...service-notifications-v0.1.0) (2026-05-31)


### Features

* add minecraft notification action endpoint ([6f887d7](https://github.com/groundsgg/service-notifications/commit/6f887d767d813888347e708e6550145f80e7da5a))
* bootstrap notification service ([e1561bd](https://github.com/groundsgg/service-notifications/commit/e1561bd2fb1752fcff2b9d77cde6d8b07b3e31fa))
* **notifications:** add core notification APIs ([c2fff9b](https://github.com/groundsgg/service-notifications/commit/c2fff9bd36b5f75bdb4e0394d7e6db573f1f3f9e))


### Bug Fixes

* harden notification service bootstrap release ([19d2d45](https://github.com/groundsgg/service-notifications/commit/19d2d451c3f92c2a39b45d8c604342c1c07ab382))
* **notifications:** enforce channel and action boundaries ([9219baf](https://github.com/groundsgg/service-notifications/commit/9219baf4f524079e47978caa39ada4a10fbd5d05))
* **notifications:** enforce jwt subject identity ([71abc3b](https://github.com/groundsgg/service-notifications/commit/71abc3bfdc16264d4b46e551f6657672d9ac575c))
* **notifications:** enforce producer scope limits ([0d65a12](https://github.com/groundsgg/service-notifications/commit/0d65a12dfd5f93d0494c615493acbfa95dd28cbc))
* return unread minecraft notifications ([1e47129](https://github.com/groundsgg/service-notifications/commit/1e47129d03a013988f7c74a0ae911936374d7d47))
