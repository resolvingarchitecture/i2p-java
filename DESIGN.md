# i2p-java — Design

Wraps I2P as a `ra.common.network.NetworkService` so 1M5 (and any RA app) can
route envelopes over the Invisible Internet Project without touching the I2P
router API directly.

## Where it sits

    1m5-core  ──registers──►  ra.i2p.I2PService   (a NetworkService == a "protocol service")
                                     │
                     ┌───────────────┴────────────────┐
              embedded router                  local router (I2CP client)
              net.i2p:router  (RouterLaunch)   127.0.0.1:7654

`I2PService extends ra.common.network.NetworkService extends BaseService`, so it
plugs into `service-bus` / `1m5-core` exactly like any other service: one channel
keyed by class name, `OPERATION_SEND` for outbound, `updateNetworkStatus(...)` for
its `NetworkState`. `1m5-core`'s `RoutingService` discovers it by type and pushes
`SimpleExternalRoute` hops carrying the destination `NetworkPeer` (I2P base64
address).

## Components

    I2PService              the NetworkService: lifecycle, operation dispatch,
                            router mode selection, status reporting, peer cache
    I2PServiceSession       one I2P client session: destination key management,
                            datagram send (I2PDatagramMaker) and receive
                            (I2PSessionMuxedListener) -> Envelope <-> JSON
    CheckRouterStatus       a TaskRunner task polling router status every 30s
    LocalRouterDetector     probes I2CP 127.0.0.1:7654 for an already-running router

## Router modes  (`ra.i2p.mode`)

| mode       | behaviour |
|------------|-----------|
| `embedded` (default) | launch `net.i2p:router` in-process (`RouterLaunch.main`). First start reseeds and takes a few minutes. May conflict with another local router's ports. |
| `local`    | attach to a router already running on this host as an external I2CP client (`I2PClient.PROP_TCP_HOST`/`PORT` -> `LocalRouterDetector` host/port). No `RouterContext` - status is inferred from session liveness. |
| `auto`     | `local` if `LocalRouterDetector` finds a router, else `embedded`. |

Supplying `-Di2p.dir.base=...` also forces `local` (something else owns the router).

The embedded/local split follows `1m5-android`'s `I2P` / `I2PEmbedded` / `I2PLocal`
structure (Android's `I2PLocal` binds to the I2P Android app's router service; on
the JVM "local" means an I2CP socket on loopback).

## Message flow

**Outbound** — `1m5-core` routes an `Envelope` with a `SimpleExternalRoute`
(`service = ra.i2p.I2PService`, `operation = SEND`, `destination = NetworkPeer` with
the peer's I2P base64 address). `I2PService.sendOut` -> `I2PServiceSession.send`:
look up the `Destination`, wrap `envelope.toJSON()` in an I2P datagram, send.

**Inbound** — `I2PServiceSession.messageAvailable` dissects the datagram, parses the
JSON back into an `Envelope`, records the sender's address/fingerprint on the
origination `NetworkPeer`, and hands it to the bus via `service.send(envelope)`.
`NetOpReq` / `NetOpRes` markers drive peer-list exchange with
`ra.networkmanager.NetworkManagerService`.

## Identity

On first session open, `I2PServiceSession` creates an ECDSA-SHA512-P521 I2P
destination, stores the private key in `<i2pDir>/<alias>`, and populates the local
`NetworkPeer`'s `DID` public key with the base64 destination (address) and hash
(fingerprint). This is the node's I2P identity; it is separate from the 1M5 node
identity.

## Status

`CheckRouterStatus` polls every 30s. Embedded: maps `CommSystemFacade.Status`
(OK / REJECT_UNSOLICITED / DISCONNECTED / HOSED / ...) onto `NetworkStatus`
(CONNECTED / BLOCKED / PORT_CONFLICT / ...). Local: infers CONNECTED /
CONNECTING from whether a client session is live (the router's CommSystem is not
reachable from an external I2CP client).

## Not here

- I2P eepsite (HTTP-over-I2P) requests — `NotImplementedException` in the Android
  reference; not attempted here.
- Streaming (`I2PSocketManager` streaming API) — only repliable datagrams are used.
- The adaptive/tuning knobs beyond hidden mode / share % / GeoIP.
