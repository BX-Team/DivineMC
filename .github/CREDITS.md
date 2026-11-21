# Credits

DivineMC is built on the work of many other projects. This page lists every project DivineMC takes code or ideas from, and what it takes. Where a patch names its source in its header or in its `// DivineMC - <Project>: ...` markers, it is listed here.

## Upstream

| Project | What DivineMC uses |
|---------|--------------------|
| [Paper](https://github.com/PaperMC/Paper) | The server DivineMC is built on, through Purpur. Unmerged pull requests: Add FillBottleEvents for player and dispenser, Throttle failed spawn attempts, Add ticket on player join to avoid chunk load-unload-load cycle, Optimise non-flush packet sending, Fire ServerListPingEvent for secondary motd send, Player standing on position API |
| [Purpur](https://github.com/PurpurMC/Purpur) | The direct upstream of DivineMC |

## Server software

| Project | What DivineMC uses | License |
|---------|--------------------|---------|
| [Pufferfish](https://github.com/pufferfish-gg/Pufferfish) | Optimize suffocation, Reduce chunk loading lookups, Optimize mob spawning, Simpler ShapelessRecipes comparison for Vanilla, Dynamic Activation of Brain, Sentry integration | GPL-3.0 |
| [Airplane](https://github.com/TECHNOVE/Airplane) | Make EntityCollisionContext a live representation, Optimize random calls in chunk ticking, Reduce projectile chunk loading (by way of Pufferfish) | GPL-3.0 |
| [SparklyPaper](https://github.com/SparklyPower/SparklyPaper) | The original idea of Parallel World Ticking and its thread checks, per-world MSPT tracking, Skip distanceToSqr call in ServerEntity#sendChanges, Optimize "canSee" checks, throttling hopper checks when the target container is full, the player stats and map dirty flag fixes | |
| [Fish](https://github.com/Biquaternions/Fish) | The Parallel World Ticking API (`Server#isParallelWorldTickingEnabled`, `World#getTickTimes`, `World#getAverageTickTime`) | MIT |
| [Petal](https://github.com/Bloom-host/Petal) | Async Pathfinding; the multithreaded tracker the Parallel Entity Tracker was derived from | GPL-3.0 |
| [CanvasMC](https://github.com/CraftCanvasMC/Canvas) | Regionized Chunk Ticking, originally an idea by Dueris, later reworked in DivineMC (heavily optimized by dan28000) | |
| [Leaves](https://github.com/LeavesMC/Leaves) | Protocol core with the AppleSkin, Jade, Xaero's Map and Syncmatica protocols, Copper Bulb and Crafter 1 gt delay, catching update suppression crashes, snowball and egg knockback, spectators not getting advancements | GPL-3.0 |
| [Gale](https://github.com/Dreeam-qwq/Gale) | Virtual thread support | GPL-3.0 |
| [Parchment](https://github.com/ProjectEdenGG/Parchment) | Make `/paper fixlight` use the action bar | |
| [EmpireCraft](https://github.com/starlis/empirecraft) | Don't use snapshots for acquiring block states | MIT |
| [matter](https://github.com/plasmoapp/matter) | Secure Seed, based on the [SecureSeed](https://github.com/Earthcomputer/SecureSeed) mod by Earthcomputer | GPLv3 |
| [LinearPaper](https://github.com/xymb-endcrystalme/LinearPaper) | The idea of the Linear region file format; DivineMC's Linear format is its own (`linear-format/SPEC.md`) | |
| [Velocity](https://github.com/PaperMC/Velocity) | Optimized VarInt and VarLong writing | GPL-3.0 |

## Fabric mods

| Project | What DivineMC uses | License |
|---------|--------------------|---------|
| [Lithium](https://github.com/CaffeineMC/lithium) | `fast_util`, `brain`, `long_jump_weighted_choice`, `goat_item_sensor`, `combined_heightmap_update`, `sleeping_block_entity`, `equipment_tracking`, `moving_block_shapes`, `non_poi_block_search`, `replace_streams`, `nitwit_job_search`, `baby_specific_sensors`, `cached_iterate_outwards`, `entity.fast_elytra_check` | LGPL-3.0 |
| [C2ME](https://github.com/RelativityMC/C2ME-fabric) | Optimize world gen math, Limit NBT cache, Optimize Aquifer and Beardifier, The End biome cache, native math acceleration, reduced allocations; `com.ishland.flowsched` utilities | MIT |
| [VMP](https://github.com/RelativityMC/VMP-fabric) | Store mob counts in an array | MIT |
| [carpet-fixes](https://github.com/fxmorin/carpet-fixes) | RecipeManager optimization, optimized `getBiome`, sheep offspring color optimization, slime block bounce fix | MIT |
| [ModernFix](https://github.com/embeddedt/ModernFix) | `compact_bit_storage` | GPL-3.0 |
| [EntityCulling](https://github.com/tr7zw/EntityCulling) | Raytrace Entity Culling is a server-side port of this mod, with the [OcclusionCulling](https://github.com/LogisticsCraft/OcclusionCulling) library (MIT) | |
| [No Chat Reports](https://github.com/Aizistral-Studios/No-Chat-Reports) | The No Chat Reports feature and its options | WTFPL |

## Libraries and integrations

| Project | What DivineMC uses | License |
|---------|--------------------|---------|
| [Raytrace AntiXray SDK](https://github.com/Imanity-Software/raytrace-antixray-spigot-sdk) | Integration with Imanity Software's Raytrace AntiXray | |
| [FastNoiseLite](https://github.com/Auburn/FastNoiseLite) | Noise functions in the native math library | MIT |
| [musl](https://musl.libc.org/) | libc math functions in the native math library | MIT |
