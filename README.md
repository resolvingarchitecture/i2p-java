# I2P Service
Invisible Internet Project (I2P) Service. Wraps an I2P Router as a service so that it can be easily managed
and used by 3rd party decentralized applications. Currently, only an embedded I2P router is supported which
may conflict with any already running I2P router due to port conflicts. Future support will include detection of and connecting
to a local instance of the I2P Router.

## Build Notes
- Required certificates from the following two directories in the i2p.i2p project (I2P Router core)
to be copied to resources/certificates keeping reseed and ssl as directories:
    - /installer/resources/certificates/reseed
    - /installer/resources/certificates/ssl

## Roadmap
* 1.0.0: Basic P2P communications using embedded I2P router and ElGamal-2048 / DSA-1024
* TBD
    * Verify ECDH-256 / ECDSA-256 works
    * Verify ECDH-521 / ECDSA-521 works
    * Verify NTRUEncrypt-1087 / GMSS-512 works
    * Provide the most granular means of supporting each algorithm set (down to each message if possible)
    * Provide means to use each algorithm set by request

## Integration

### Embedded
Current integration method. External support to come later.

### External
Control via: https://github.com/i2p/i2p.itoopie

## Installation
Currently, I2P Service embeds the I2P router. In the future,
external I2P router will be supported. When that happens, the following
installations are recommended:

Router with original UI:

https://geti2p.net/en/download

I2P Zero - zero dependency build of I2P router with minimal gui

https://github.com/i2p-zero/i2p-zero

* Ensure your firewall is opened to the random port assigned on startup for both tcp and udp.
* Open port 123 on udp for time sync if on (default)

## Removal

### Raspian

#### I2P External Router
Note: Not yet supported
```
sudo apt remove i2p
sudo apt remove i2prouter
sudo apt autoremove
sudo apt autoclean
```

## Attack Mitigation

- https://www.irongeek.com/i.php?page=security/i2p-identify-service-hosts-eepsites

## Version Notes

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

