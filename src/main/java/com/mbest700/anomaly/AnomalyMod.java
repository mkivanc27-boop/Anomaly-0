package com.mbest700.anomaly;

import com.mbest700.anomaly.IEntityData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class AnomalyMod implements ModInitializer {
    public static final String MOD_ID = "anomaly_0";

    private static final String[] FAKE_MESSAGES = {
        "Neredesin?", "O geliyor...", "Kaç!", "Arkana bakma",
        "Yardım et...", "Duyuyor musun?", "Buradayım", "Gel buraya", "Sessiz ol"
    };

    public static final EntityType<AnomalyEntity> ANOMALY = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "anomaly"),
            EntityType.Builder.of(AnomalyEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.9f).build());

    private static final Map<BlockPos, BlockState> history = new LinkedHashMap<>();
    private static long spawnCooldown = 0;

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(ANOMALY, AnomalyEntity.createAttributes());

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                updateAnomalySystem(player, (ServerLevel) player.level(), server);
            }
        });
    }

    private void updateAnomalySystem(ServerPlayer p, ServerLevel l, net.minecraft.server.MinecraftServer s) {
        IEntityData data = (IEntityData) p;
        long time = l.getGameTime();
        List<AnomalyEntity> nearList = l.getEntitiesOfClass(AnomalyEntity.class, p.getBoundingBox().inflate(64));
        boolean isNear = !nearList.isEmpty();

        boolean isNight = !l.isDay();
        float insanityRate = isNight ? 2.0f : 1.0f;

        if (isNear) {
            float dist = nearList.get(0).distanceTo(p);
            data.setInsanity(data.getInsanity() + (dist < 30 ? 1.5f : 0.5f) * insanityRate);

            if (data.getInsanity() >= 40 && time % 60 == 0) {
                l.playSound(null, p.blockPosition(), SoundEvents.WARDEN_AMBIENT, SoundSource.AMBIENT, 0.8f, 0.1f);
            }

            if (data.getInsanity() >= 60 && time % 15 == 0) {
                BlockPos target = p.blockPosition().offset(
                        l.random.nextInt(10) - 5, -1, l.random.nextInt(10) - 5);
                if (!l.getBlockState(target).isAir() && history.size() < 100) {
                    history.putIfAbsent(target.immutable(), l.getBlockState(target));
                    l.setBlockAndUpdate(target, Blocks.NETHERRACK.defaultBlockState());
                }
            }

            if (data.getInsanity() >= 80 && time % 100 == 0) {
                AnomalyEntity anomaly = nearList.get(0);
                Vec3 look = p.getViewVector(1.0f).normalize();
                double tpX = p.getX() + look.x * 5;
                double tpY = p.getY();
                double tpZ = p.getZ() + look.z * 5;
                anomaly.teleportTo(tpX, tpY, tpZ);
                l.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.5f);
            }

            if (l.isDay()) {
                AnomalyEntity anomaly = nearList.get(0);
                anomaly.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.05);
                if (time % 200 == 0) {
                    BlockPos safePos = anomaly.blockPosition();
                    for (int i = 1; i <= 10; i++) {
                        BlockPos check = safePos.below(i);
                        if (!l.getBlockState(check).isAir() && l.getBlockState(check.above()).isAir()) {
                            anomaly.teleportTo(check.getX(), check.above().getY(), check.getZ());
                            break;
                        }
                    }
                }
            }

        } else {
            data.setInsanity(Math.max(0, data.getInsanity() - 0.2f));

            if (data.getInsanity() > 50 && time > spawnCooldown && l.random.nextFloat() < 0.005) {
                BlockPos spawnPos = p.blockPosition().offset(20, 0, 20);
                AnomalyEntity e = new AnomalyEntity(ANOMALY, l);
                e.setPos(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
                l.addFreshEntity(e);
                spawnCooldown = time + 5000;
            }

            if (time % 40 == 0 && !history.isEmpty()) {
                BlockPos bp = history.keySet().iterator().next();
                BlockState original = history.remove(bp);
                if (l.random.nextFloat() > 0.20f) {
                    l.setBlockAndUpdate(bp, original);
                }
            }
        }

        if (s.getPlayerList().getPlayerCount() >= 2 && time % 300 == 0 && data.getInsanity() > 55) {
            String randomMsg = FAKE_MESSAGES[l.random.nextInt(FAKE_MESSAGES.length)];
            s.getPlayerList().getPlayers().stream()
                    .filter(o -> o != p)
                    .findAny()
                    .ifPresent(friend ->
                            p.sendSystemMessage(Component.literal(
                                    "<" + friend.getName().getString() + "> " + randomMsg)));
        }

        String color = data.getInsanity() > 80 ? "§4" : data.getInsanity() > 50 ? "§c" : "§a";
        p.displayClientMessage(Component.literal(color + "Zihinsel Durum: %" + (int) data.getInsanity()), true);

        if (data.getInsanity() >= 85 && l.random.nextFloat() < 0.05) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        }
    }

    public static class AnomalyEntity extends Zombie {
        public AnomalyEntity(EntityType<? extends Zombie> t, Level l) { super(t, l); }

        public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
            return Zombie.createAttributes()
                    .add(Attributes.MAX_HEALTH, 600)
                    .add(Attributes.MOVEMENT_SPEED, 0.35);
        }

        @Override
        public void aiStep() {
            super.aiStep();
            if (this.level().isClientSide) return;

            ServerPlayer target = (ServerPlayer) getTarget();
            if (target != null) {
                this.setCustomName(target.getName());

                Vec3 look = target.getViewVector(1.0f).normalize();
                Vec3 toEnt = new Vec3(getX() - target.getX(), 0, getZ() - target.getZ()).normalize();
                boolean isLooking = look.dot(toEnt) > 0.5;
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(isLooking ? 0.05 : 0.9);
            }
        }

        @Override
        public boolean isCustomNameVisible() { return false; }
    }
                }
