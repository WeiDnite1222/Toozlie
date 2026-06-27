# Toolize
## This plugin only tested on paper server. Not sure other Minecraft server core are support.

# Usage

## Installation

### 1. Download release from this repo and put jar file into your server's plugin folder.
### 2. Install necessary plugin(s) below:
* [Placeholder](https://github.com/PlaceholderAPI/PlaceholderAPI)
* [ConfigLib](https://github.com/Exlll/ConfigLib/releases)

#### Some plugins are required when specified option is enabled.
**Whitelist Server**
* [Geyser](https://geysermc.org/download/)
* [Floodgate](https://geysermc.org/download/)

**Custom join message (if you use EssentialX placeholder)**
* [EssentialX](https://essentialsx.net/)


## Configure

**Check 'plugins/Toozlie/config.yml'**

```
language: auto
showModifiedJoinMessage: true
welcomeMessage: Hello! %player_name%
joinMessage: Hello! %player_name%
enableRandomRespawn: true
radiusOfRandomRespawn: 1000000
randomRespawnCenterX: 0
randomRespawnCenterZ: 0
enableDeathSaver: true
enableBroadcaster: true
whitelistPortal:
enableWhitelistServer: true
host: 127.0.0.1
port: 1838
auth:
mode: token
token: YOUR-TOKEN-HERE
hmacSecret: YOUR-HMAC-SECRET-HERE
allowSkewSeconds: 30
security:
maxRequestsPerMinute: 120
maxBodyBytes: 120
requireMethod: POST
serverInfo:
serverName: Minecraft Server
description: Wow is a Minecraft server
```

