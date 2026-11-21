<div align="center">

<img src=".github/branding/logo-128.png" width="128" height="128" alt="DivineMC Logo" />

# DivineMC

DivineMC is a multi-functional fork of [Purpur](https://github.com/PurpurMC/Purpur), which focuses on the flexibility of your server and its optimization

[![website](https://raw.githubusercontent.com/NONPLAYT/badges/refs/heads/master/available-on-our-website.svg)](https://bxteam.org/downloads/divinemc)
[![mcjars](https://raw.githubusercontent.com/NONPLAYT/badges/refs/heads/master/available-on-mcjars.svg)](https://mcjars.app/DIVINEMC/versions)
[![bstats](https://raw.githubusercontent.com/NONPLAYT/badges/refs/heads/master/available-on-bstats.svg)](https://bstats.org/plugin/server-implementation/DivineMC)

[![discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/social/discord-plural_vector.svg)](https://discord.gg/qNyybSSPm5)
[![documentation](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/documentation/generic_vector.svg)](https://bxteam.org/docs/divinemc)

</div>

## ⚙️ Features
- **Based on [Purpur](https://github.com/PurpurMC/Purpur)** - Adds a high customization level to your server
- **Regionized Chunk Ticking** - Tick chunks in parallel, similar to how Folia does it
- **Parallel World Ticking** - Leverage multiple CPU cores for world processing
- **Parallel Sensor Phase** - Run the expensive read-only part of mob AI (entity scans, line-of-sight checks) on a thread pool
- **Async Operations** - Pathfinding, entity tracker, mob spawning, joining, chunk sending and portal pre-loading
- **Raytrace Entity Culling** - Stops sending entities a player provably cannot see, saving bandwidth and blinding entity-ESP cheats
- **Secure Seed** - Enhanced 1024-bit seed system (vs standard 64-bit) for maximum security
- **Linear Region File Format** - Worlds about half the size of Anvil, an append-only log that writes less than Anvil and loses at most the last flush window (10 s by default) of saved chunks in a crash ([spec](linear-format/SPEC.md))
- **Mod Protocols Support** - Compatible with Syncmatica, Apple Skin, Jade and Xaero's Map
- **Fully Compatible** - Works seamlessly with Bukkit, Spigot and Paper plugins
- **Bug Fixes** - Resolves various Minecraft issues (~10)
- **Sentry Integration** - Detailed error tracking and monitoring (original by [Pufferfish](https://github.com/pufferfish-gg/Pufferfish))

*...and much more!*

## 📥 Downloading & Installing
If you want to install DivineMC, you can read our [installation documentation](https://bxteam.org/docs/divinemc/getting-started/installation).

You can find the latest successful builds on our [website](https://bxteam.org/downloads/divinemc) or on [MCJars](https://mcjars.app/DIVINEMC/versions).

## 📦 Building and setting up
Run the following commands in the root directory:

```bash
> ./gradlew applyAllPatches        # apply all patches
> ./gradlew createPaperclipJar     # build the server jar
```

For anything else you can refer to our [contribution guide](https://bxteam.org/docs/divinemc/development/contributing).

## 🧪 API

### Maven
```xml
<repository>
  <id>bx-team</id>
  <url>https://repo.bxteam.org/snapshots</url>
</repository>
```
```xml
<dependency>
  <groupId>org.bxteam.divinemc</groupId>
  <artifactId>divinemc-api</artifactId>
  <version>[26.3.build,)</version>
  <scope>provided</scope>
</dependency>
```

### Gradle
```kotlin
repositories {
    maven("https://repo.bxteam.org/snapshots")
}
```
```kotlin
dependencies {
    compileOnly("org.bxteam.divinemc:divinemc-api:26.3.build.+")
}
```

## ⚖️ License
DivineMC is licensed under the GNU General Public License v3.0. You can find the license [here](LICENSE).

## 📜 Credits
DivineMC includes patches from other projects, and without these projects, DivineMC wouldn't exist today. The full list of projects DivineMC takes code and ideas from, and what it takes from each, is in [CREDITS.md](.github/CREDITS.md).
