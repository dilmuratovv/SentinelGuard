SentinelGuard 2.2 for Paper: the moderation hub of the Sentinel suite.

- **SentinelAD and SentinelAX-ray flags go to `/sus`.** Guard connects automatically to SentinelAD (anti-dupe) and SentinelAX-ray (anti-xray) when they are installed, and adds their findings to `/sus` with the plugin name, finding type and description (`SentinelAD: ledger-mismatch - ...`, `SentinelAX-ray: xray-mining - ...`). Test flags (`/sad testflag`, `/sxray testflag`) never count. Guard only observes: it never changes what the other plugins block or log.
- **New config section** (defaults shown):
  ```yaml
  integrations:
    sentinelad:
      enabled: true
      min-confidence: 60
      sus-threshold: 1
    sentinelaxray:          # statistical, so a higher bar
      enabled: true
      min-confidence: 70
      sus-threshold: 1
  ```
  `sus-threshold` is the number of findings of one type within 10 minutes before the player is added.
- **`/sg status`** shows whether SentinelAC, SentinelAD and SentinelAX-ray are connected, and the live protection state of SentinelAX-ray and SentinelAE.
- Hooks survive `/sg reload` and plugins enabled or disabled later; with none of them installed Guard works exactly as before.

One JAR for Paper 1.21 through 26.3 (Java 21; Java 25 on 26.x). The Velocity plugin is a separate release (`velocity-v*`); upgrading it is optional (no functional change).

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs SentinelGuard-2.2.0-paper.jar`. Checksums are in `SHA256SUMS.txt`.
