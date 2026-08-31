# i2p-java — TODO

## Done

- [x] Embedded I2P router as a `NetworkService` (`I2PService`), datagram
      send/receive, destination-key identity, status polling.
- [x] `1.7.1` — router-mode selection (`ra.i2p.mode` = `embedded` | `local` | `auto`)
      + `LocalRouterDetector` (I2CP loopback probe). Embedded path unchanged;
      `routerContext`/`router` uses guarded for the local path. `getNetwork()`
      convenience. `DESIGN.md` / `TODO.md`.

## Local router mode — needs field testing

The `local` / `auto` path compiles and the wiring is in place, but it has **not**
been run against a real local I2P router. Before relying on it:

- [ ] Verify `I2PSocketManagerFactory.createDisconnectedManager` + `i2pSession.connect()`
      actually talk to a router listening on I2CP `127.0.0.1:7654`.
- [ ] Confirm `i2cp.tcp.host` / `i2cp.tcp.port` (vs the older `I2PClient.PROP_TCP_*`)
      are the keys the current `net.i2p` client honours.
- [ ] Local status: replace the session-liveness heuristic in `checkRouterStats`
      with I2PControl (`https://127.0.0.1:7650`, JSON-RPC) — as `1m5-android`'s
      `I2PLocal.checkRouterStats` does.
- [ ] `restart()` in local mode currently just re-establishes sessions; decide
      whether to also nudge the local router.
- [ ] Handle the local router going away (I2CP connection drop) -> `BLOCKED` /
      re-detect.
- [ ] A test that starts a throwaway embedded router in one process and attaches to
      it in `local` mode from another (integration, opt-in / slow).

## Other

- [ ] Split `I2PService` into `EmbeddedRouter` / `LocalRouter` strategy classes
      (mirrors `1m5-android`'s `I2PEmbedded` / `I2PLocal`) instead of the `embedded`
      boolean + guards.
- [ ] The real test suite is commented out (needs a ~2-minute reseed). Add a
      `@Category(Slow.class)` embedded round-trip test run only in a dedicated CI job.
- [ ] Message chunking for envelopes > ~31.5 kB (`I2PServiceSession.send` only warns).
- [ ] Streaming transport option for large / ordered payloads.
- [ ] I2P eepsite (HTTP-over-I2P) request support.
- [ ] Move the hard-coded `Log.STR_INFO` / 100 MB log size to config.
- [ ] Align Java target with the rest of the stack (currently Java 8; bus libs are 11).
- [ ] Publish to a real repository (local / jitpack only).
