# SentinelGuard 2.0.0 release requirements

Author: azicoo
Paper root command: /sg; aliases /sentinelguard, /sguard, /sentinelg. /sus remains independent.
No hard dependency on SentinelAC. All moderation functions must pass tests without SentinelAC installed.
Artifact names: SentinelGuard-2.0.0-paper<VERSION>.jar and SentinelGuard-2.0.0-velocity<API_VERSION>.jar.
Source lives on main. Build and release use GitHub Actions; no build branch.
Publish only after all supported Paper builds and Velocity integration tests pass.
Current status: development, network adapters and integration tests incomplete.
