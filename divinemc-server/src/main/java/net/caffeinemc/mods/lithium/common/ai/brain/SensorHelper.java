package net.caffeinemc.mods.lithium.common.ai.brain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;

public class SensorHelper {
    public static void disableSensor(LivingEntity brainedEntity, SensorType<?> sensorType) {
        if (brainedEntity.level().isClientSide()) {
            return;
        }
        Brain<?> brain = brainedEntity.getBrain();
        Sensor<?> sensor = (brain).sensors.get(sensorType);
        if (sensor != null) {
            long lastSenseTime = sensor.timeToTick;
            int senseInterval = sensor.scanRate;

            long maxMultipleOfSenseInterval = Long.MAX_VALUE - (Long.MAX_VALUE % senseInterval);
            maxMultipleOfSenseInterval -= senseInterval;
            maxMultipleOfSenseInterval += lastSenseTime;

            sensor.timeToTick = (maxMultipleOfSenseInterval);
        }
    }

    public static <T extends LivingEntity, U extends Sensor<T>> void enableSensor(T brainedEntity, SensorType<U> sensorType) {
        enableSensor(brainedEntity, sensorType, false);
    }

    public static <T extends LivingEntity, U extends Sensor<T>> void enableSensor(T brainedEntity, SensorType<U> sensorType, boolean extraTick) {
        if (brainedEntity.level().isClientSide()) {
            return;
        }

        Brain<?> brain = brainedEntity.getBrain();
        U sensor = (U) (brain).sensors.get(sensorType);
        if (sensor != null) {
            long lastSenseTime = sensor.timeToTick;
            int senseInterval = sensor.scanRate;

            if (lastSenseTime > senseInterval) {
                lastSenseTime = lastSenseTime % senseInterval;
                if (extraTick) {
                    (sensor).timeToTick = (0L);
                    sensor.tick((ServerLevel) brainedEntity.level(), brainedEntity);
                }
            }
            sensor.timeToTick = (lastSenseTime);
        }
    }
}
