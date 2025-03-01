<img src="/public/logo.png" height="220" alt="DivineMC Face" align="right">
<div align="center">

# DivineMC

[![Github Releases](https://img.shields.io/badge/Download-Releases-blue?&style=for-the-badge)](https://github.com/BX-Team/DivineMC/releases)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/BX-Team/DivineMC/build.yml?logo=GoogleAnalytics&logoColor=ffffff&style=for-the-badge)](https://github.com/BX-Team/DivineMC/actions)
[![Discord](https://img.shields.io/discord/931595732752953375?color=5865F2&label=discord&style=for-the-badge)](https://discord.gg/p7cxhw7E2M)

DivineMC is a high-performance [Purpur](https://github.com/PurpurMC/Purpur) fork focused on maximizing server performance while maintaining plugin compatibility.
  
</div>

> [!WARNING]  
> DivineMC is a performance-oriented fork. Make sure to take backups **before** switching to it. We also welcome a new contributor to help us improve the fork.

## ⚙️ Features
- All worlds **are ticked in parallel**, so the server can take full advantage of multicore processors.
- **Implemented Secure Seed mod** that changes default 64-bit seed to a 1024-bit seed, making it almost impossible to crack the seed.
- **Native math functions** using a new Java 22 feature, calling C functions directly from Java.
- **Optimized chunk generation** that can generate chunks up to 70% faster than vanilla.
- **Async pathfinding and entity tracker**
- **Fully compatible** with Bukkit, Spigot and Paper plugins
- **Fixes** some Minecraft bugs
- and more...

## 📥 Downloading & Installing
If you want to install DivineMC, you can read our [installation documentation](https://bxteam.org/docs/divinemc/getting-started/installation).

You can find the latest successful build in [GitHub Action](https://github.com/BX-Team/DivineMC/actions) or [Releases](https://github.com/BX-Team/DivineMC/releases)

## 📈 bStats
[![bStats](https://bstats.org/signatures/server-implementation/DivineMC.svg)](https://bstats.org/plugin/server-implementation/DivineMC)

## 📦 Building and setting up
Run the following commands in the root directory:

```bash
> ./gradlew applyAllPatches              # apply all patches
> ./gradlew createMojmapPaperclipJar     # build the server jar
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
  <version>1.21.4-R0.1-SNAPSHOT</version>
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
    compileOnly("org.bxteam.divinemc:divinemc-api:1.21.4-R0.1-SNAPSHOT")
}
```

We also have a [Javadoc](https://repo.bxteam.org/javadoc/snapshots/org/bxteam/divinemc/divinemc-api/1.21.4-R0.1-SNAPSHOT/raw/index.html) for the API.

## ⚖️ License
DivineMC is licensed under the GNU General Public License v3.0. You can find the license [here](LICENSE).

## 📜 Credits
DivineMC includes patches from other forks, and without these forks, DivineMC wouldn't exist today. Here are the list of these forks:

- [Purpur](https://github.com/PurpurMC/Purpur)
- <details>
    <summary>📜 Expand to see forks that DivineMC takes patches from.</summary>
    <p>
      • <a href="https://github.com/Bloom-host/Petal">Petal</a><br>
      • <a href="https://github.com/fxmorin/carpet-fixes">Carpet Fixes</a><br>
      • <a href="https://github.com/ProjectEdenGG/Parchment">Parchment</a><br>
      • <a href="https://github.com/LeavesMC/Leaves">Leaves</a><br>
      • <a href="https://github.com/KaiijuMC/Kaiiju">Kaiiju</a><br>
      • <a href="https://github.com/SparklyPower/SparklyPaper">SparklyPaper</a><br>
      • <a href="https://github.com/plasmoapp/matter">Matter</a><br>
      • <a href="https://github.com/CraftCanvasMC/Canvas">Canvas</a><br>
    </p>
</details>

If you want to know more about other forks and see other Minecraft projects, you can go to our [list of different Minecraft server Software](https://gist.github.com/NONPLAYT/48742353af8ae36bcef5d1c36de9730a).
