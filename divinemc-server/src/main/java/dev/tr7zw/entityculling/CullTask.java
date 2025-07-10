package dev.tr7zw.entityculling;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.util.Vec3d;
import dev.tr7zw.entityculling.versionless.access.Cullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CullTask implements Runnable {
    private volatile boolean requestCull = false;
    private volatile boolean scheduleNext = true;
    private volatile boolean inited = false;

    private final OcclusionCullingInstance culling;
    private final Player checkTarget;

    private final int hitboxLimit;

    public long lastCheckedTime = 0;

    private final Vec3d lastPos = new Vec3d(0, 0, 0);
    private final Vec3d aabbMin = new Vec3d(0, 0, 0);
    private final Vec3d aabbMax = new Vec3d(0, 0, 0);

    private static final Executor backgroundWorker = Executors.newCachedThreadPool(task -> {
        final TickThread worker = new TickThread("Raytrace Entity Tracker Thread") {
            @Override
            public void run() {
                task.run();
            }
        };

        worker.setDaemon(true);
        ThreadDumperRegistry.REGISTRY.add(worker.getName());

        return worker;
    });

    private final Executor worker;

    public CullTask(
        OcclusionCullingInstance culling,
        Player checkTarget,
        int hitboxLimit,
        long checkIntervalMs
    ) {
        this.culling = culling;
        this.checkTarget = checkTarget;
        this.hitboxLimit = hitboxLimit;
        this.worker = CompletableFuture.delayedExecutor(checkIntervalMs, TimeUnit.MILLISECONDS, backgroundWorker);
    }

    public void requestCullSignal() {
        this.requestCull = true;
    }

    public void signalStop() {
        this.scheduleNext = false;
    }

    public void setup() {
        if (!this.inited)
            this.inited = true;
        else
            return;
        this.worker.execute(this);
    }

    @Override
    public void run() {
        try {
            if (this.checkTarget.tickCount > 10) {
                Vec3 cameraMC = this.checkTarget.getEyePosition(0);
                if (requestCull || !(cameraMC.x == lastPos.x && cameraMC.y == lastPos.y && cameraMC.z == lastPos.z)) {
                    long start = System.currentTimeMillis();

                    requestCull = false;

                    lastPos.set(cameraMC.x, cameraMC.y, cameraMC.z);
                    culling.resetCache();

                    cullEntities(cameraMC, lastPos);

                    lastCheckedTime = (System.currentTimeMillis() - start);
                }
            }
        } finally {
            if (this.scheduleNext) {
                this.worker.execute(this);
            }
        }
    }

    private void cullEntities(Vec3 cameraMC, Vec3d camera) {
        for (Entity entity : this.checkTarget.level().getEntities().getAll()) {
            if (!(entity instanceof Cullable cullable)) {
                continue;
            }

            if (entity.getType().skipRaytracingCheck) {
                continue;
            }

            if (!cullable.isForcedVisible()) {
                if (entity.isCurrentlyGlowing() || isSkippableArmorstand(entity)) {
                    cullable.setCulled(false);
                    continue;
                }

                if (!entity.position().closerThan(cameraMC, DivineConfig.MiscCategory.retTracingDistance)) {
                    cullable.setCulled(false);
                    continue;
                }

                AABB boundingBox = entity.getBoundingBox();
                if (boundingBox.getXsize() > hitboxLimit || boundingBox.getYsize() > hitboxLimit
                    || boundingBox.getZsize() > hitboxLimit) {
                    cullable.setCulled(false);
                    continue;
                }

                aabbMin.set(boundingBox.minX, boundingBox.minY, boundingBox.minZ);
                aabbMax.set(boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ);

                boolean visible = culling.isAABBVisible(aabbMin, aabbMax, camera);

                cullable.setCulled(!visible);
            }
        }
    }

    private boolean isSkippableArmorstand(Entity entity) {
        if (!DivineConfig.MiscCategory.retSkipMarkerArmorStands) return false;

        return entity instanceof ArmorStand && entity.isInvisible();
    }
}
