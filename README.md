# I2P Service
Invisible Internet Project (I2P) Service. Wraps an I2P router as a
`ra.common.network.NetworkService` so it can be managed and used by decentralized
applications - in particular as the I2P **protocol service** for `1m5-core-java`
(`1m5-core` registers `ra.i2p.I2PService`, its `RoutingService` discovers it by
type and routes external hops to it).

Two router modes (`ra.i2p.mode` = `embedded` | `local` | `auto`): launch an
**embedded** `net.i2p:router` in-process (default), or attach to a router already
running on this host over I2CP (`127.0.0.1:7654`). The local/auto path mirrors
`1m5-android`'s embedded-vs-local split and is experimental on the JVM - see
[`TODO.md`](TODO.md).

See [`DESIGN.md`](DESIGN.md) for the model and message flow.

## Build Notes
- Required certificates from the following two directories in the i2p.i2p project (I2P Router core)
to be copied to resources/certificates keeping reseed and ssl as directories:
    - /installer/resources/certificates/reseed
    - /installer/resources/certificates/ssl
update

## Control
I2P Router Control via: https://github.com/i2p/i2p.itoopie when in local mode.

## Roadmap
* 0.9.50.0: Basic P2P communications using embedded I2P router and ElGamal-2048 / DSA-1024
* Add local router identification and use if available over embedded.
* TBD
    * Verify ECDH-256 / ECDSA-256 works
    * Verify ECDH-521 / ECDSA-521 works
    * Verify NTRUEncrypt-1087 / GMSS-512 works
    * Provide the most granular means of supporting each algorithm set (down to each message if possible)
    * Provide means to use each algorithm set by request

## Attack Mitigation

- https://www.irongeek.com/i.php?page=security/i2p-identify-service-hosts-eepsites

## Version Notes

### 1.7.1
- Router-mode selection: `ra.i2p.mode` = `embedded` (default) | `local` | `auto`,
  plus `LocalRouterDetector` (I2CP loopback probe). Structure follows
  `1m5-android`'s `I2P` / `I2PEmbedded` / `I2PLocal`. Embedded path unchanged;
  `local` needs field testing (see `TODO.md`).
- `getNetwork()` convenience; `routerContext` / `router` uses guarded for local mode.
- Added `DESIGN.md`, `TODO.md`.

### 0.9.50.1
- upgraded to 0.9.50 moving versioning to reflect I2P version

### 0.6.5
- upgraded to 0.9.47

### 0.6.4
- upgraded to 0.9.45

### 0.6.3
- upgraded to 0.9.43

### 0.6.2
- upgraded to 0.9.41
- updated reseed and ssl certificates
- added host.txt for reference
- added blocklist.txt for reference

Note: I believe built-in-peers.txt is no longer used; couldn't find an update

### 0.6.1
- upgraded to 0.9.39

### 0.6.0
- upgraded to 0.9.37

