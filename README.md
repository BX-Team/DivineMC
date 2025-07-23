<img src="/public/logo.png" height="220" alt="DivineMC Face" align="right">
<div align="center">

# DivineMC

[![Github Releases](https://img.shields.io/badge/Download-Releases-blue?&style=for-the-badge)](https://github.com/BX-Team/DivineMC/releases)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/BX-Team/DivineMC/build-1218.yml?logo=GoogleAnalytics&logoColor=ffffff&style=for-the-badge)](https://github.com/BX-Team/DivineMC/actions)
[![Discord](https://img.shields.io/discord/931595732752953375?color=5865F2&label=discord&style=for-the-badge)](https://discord.gg/qNyybSSPm5)

DivineMC is a multi-functional fork of [Purpur](https://github.com/PurpurMC/Purpur), which focuses on the flexibility of your server and its optimization
</div>

## ⚙️ Features
- **Based on [Purpur](https://github.com/PurpurMC/Purpur)** - Adds a high customization level to your server
- **Regionized Chunk Ticking** - Tick chunks in parallel, similar to how Folia does it
- **Parallel World Ticking** - Leverage multiple CPU cores for world processing
- **Async Operations** - Pathfinding, entity tracker, mob spawning, joining and chunk sending
- **Secure Seed** - Enhanced 1024-bit seed system (vs standard 64-bit) for maximum security 
- **Linear Region File Format** - Optimize your world with the old V1/V2 linear format and the new Buffered format
- **Mod Protocols Support** - Compatible with Syncmatica, Apple Skin, Jade and Xaero's Map
- **Fully Compatible** - Works seamlessly with Bukkit, Spigot and Paper plugins
- **Bug Fixes** - Resolves various Minecraft issues (~10)
- **Sentry Integration** - Detailed error tracking and monitoring (original by [Pufferfish](https://github.com/pufferfish-gg/Pufferfish))

*...and much more!*

## 📥 Downloading & Installing
If you want to install DivineMC, you can read our [installation documentation](https://bxteam.org/docs/divinemc/getting-started/installation).

You can find the latest successful build in [Releases](https://github.com/BX-Team/DivineMC/releases) or you can use [MCJars](https://mcjars.app/DIVINEMC/versions) website.

## 📈 bStats
[![bStats](https://bstats.org/signatures/server-implementation/DivineMC.svg)](https://bstats.org/plugin/server-implementation/DivineMC)

## 📦 Building and setting up
Run the following commands in the root directory:

```bash
> ./gradlew applyAllPatches              # apply all patches
> ./gradlew createMojmapShuttleJar       # build the server jar
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
  <version>1.21.8-R0.1-SNAPSHOT</version>
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
    compileOnly("org.bxteam.divinemc:divinemc-api:1.21.8-R0.1-SNAPSHOT")
}
```

We also have a [Javadoc](https://repo.bxteam.org/javadoc/snapshots/org/bxteam/divinemc/divinemc-api/1.21.8-R0.1-SNAPSHOT/raw/index.html) for the API.

## ⚖️ License
DivineMC is licensed under the GNU General Public License v3.0. You can find the license [here](LICENSE).

## 📜 Credits
DivineMC includes patches from other projects, and without these projects, DivineMC wouldn't exist today. Here is a small list of projects that DivineMC takes patches from:

- [Purpur](https://github.com/PurpurMC/Purpur)
- [Petal](https://github.com/Bloom-host/Petal)
- [carpet-fixes](https://github.com/fxmorin/carpet-fixes)
- [Parchment](https://github.com/ProjectEdenGG/Parchment)
- [Leaves](https://github.com/LeavesMC/Leaves)
- [SparklyPaper](https://github.com/SparklyPower/SparklyPaper)
- [matter](https://github.com/plasmoapp/matter)
- [Leaf](https://github.com/Winds-Studio/Leaf)
- [C2ME](https://github.com/RelativityMC/C2ME-fabric)
- [VMP](https://github.com/RelativityMC/VMP-fabric)
- [EntityCulling](https://github.com/tr7zw/EntityCulling)
- ... and others

If you want to know more about other forks and see other Minecraft projects, you can go to our [list of different Minecraft server Software](https://gist.github.com/NONPLAYT/48742353af8ae36bcef5d1c36de9730a).
