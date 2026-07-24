<div align="center">

# DivineMC
DivineMC is a multi-functional fork of [Purpur](https://github.com/PurpurMC/Purpur), which focuses on the flexibility of your server and its optimization

[![website](https://raw.githubusercontent.com/NONPLAYT/badges/refs/heads/master/available-on-our-website.svg)](https://bxteam.org/downloads/divinemc)
[![mcjars](https://raw.githubusercontent.com/NONPLAYT/badges/refs/heads/master/available-on-mcjars.svg)](https://mcjars.app/DIVINEMC/versions)

[![Chat on Discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/social/discord-plural_vector.svg)](https://discord.gg/qNyybSSPm5)
[![Read the Docs](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/documentation/generic_vector.svg)](https://bxteam.org/docs/divinemc)
[![Available on BStats](https://raw.githubusercontent.com/NONPLAYT/badges/refs/heads/master/available-on-bstats.svg)](https://bstats.org/plugin/server-implementation/DivineMC)
</div>

## ⚙️ Features
- **Based on [Purpur](https://github.com/PurpurMC/Purpur)** - Adds a high customization level to your server
- **Regionized Chunk Ticking** - Tick chunks in parallel, similar to how Folia does it
- **Parallel World Ticking** - Leverage multiple CPU cores for world processing
- **Parallel Sensor Phase** - Run the expensive read-only part of mob AI (entity scans, line-of-sight checks) on a thread pool
- **Async Operations** - Pathfinding, entity tracker, mob spawning, joining, chunk sending and portal pre-loading
- **Secure Seed** - Enhanced 1024-bit seed system (vs standard 64-bit) for maximum security 
- **Linear Region File Format** - Optimize your world with the old V1/V2 linear format and the new Buffered format
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
  <version>[26.1.2.build,)</version>
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
    compileOnly("org.bxteam.divinemc:divinemc-api:26.1.2.build.+")
}
```

## ⚖️ License
DivineMC is licensed under the GNU General Public License v3.0. You can find the license [here](LICENSE).

## 📜 Credits
DivineMC includes patches from other projects, and without these projects, DivineMC wouldn't exist today. Here is a small list of projects that DivineMC takes patches from:

- [Purpur](https://github.com/PurpurMC/Purpur)
- [Petal](https://github.com/Bloom-host/Petal)
- [Parchment](https://github.com/ProjectEdenGG/Parchment)
- [Leaves](https://github.com/LeavesMC/Leaves)
- [SparklyPaper](https://github.com/SparklyPower/SparklyPaper)
- [matter](https://github.com/plasmoapp/matter)
- [C2ME](https://github.com/RelativityMC/C2ME-fabric)
- [VMP](https://github.com/RelativityMC/VMP-fabric)
- [EntityCulling](https://github.com/tr7zw/EntityCulling)
- [carpet-fixes](https://github.com/fxmorin/carpet-fixes)
- ... and others

If you want to know more about other forks and see other Minecraft projects, you can go to our [list of different Minecraft server Software](https://gist.github.com/NONPLAYT/48742353af8ae36bcef5d1c36de9730a).
