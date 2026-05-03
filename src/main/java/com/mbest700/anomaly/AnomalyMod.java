package com.mbest700.anomaly;

import com.mbest700.anomaly.mixin.IEntityData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class AnomalyMod implements ModInitializer {
    public static final String MOD_ID = "anomaly_0";

    public static final EntityType<AnomalyEntity> ANOMALY = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            new ResourceLocation(MOD_ID, "anomaly"),
            FabricEntityTypeBuilder.create(MobCategory.MONSTER, AnomalyEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.9f)).build());

    private static final Map<BlockPos, BlockState> history = new LinkedHashMap<>();
    private static final Set<BlockPos> permanent = new HashSet<>();
    private static long spawnCooldown = 0;

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(ANOMALY, AnomalyEntity.createAttributes());
        
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                updateSystem(player, (ServerLevel) player.level(), server);
            }
        });
    }

    private void updateSystem(ServerPlayer p, ServerLevel l, net.minecraft.server.MinecraftServer s) {
        IEntityData data = (IEntityData) p;
        long time = l.getGameTime();
        List<AnomalyEntity> nearList = l.getEntitiesOfClass(AnomalyEntity.class, p.getBoundingBox().inflate(64));
        boolean isNear = !nearList.isEmpty();

        if (isNear) {
            data.setInsanity(data.getInsanity() + (nearList.get(0).distanceTo(p) < 35 ? 1.4f : 0.4f));
            if (data.getInsanity() >= 40 && time % 55 == 0) {
                p.playNotifySound(SoundEvents.HOSTILE_BREATH, SoundSource.AMBIENT, 0.5f, 0.1f);
            }
            if (data.getInsanity() >= 65 && time % 12 == 0) corrupt(p, l);
        } else {
            data.setInsanity(data.getInsanity() - 0.2f);
            if (data.getInsanity() > 50 && time > spawnCooldown && l.random.nextFloat() < 0.002) {
                spawnAnomaly(p, l);
                spawnCooldown = time + 5000;
            }
            if (time % 40 == 0) restore(l);
        }

        if (s.getPlayerList().getPlayerCount() >= 2 && time % 180 == 0 && data.getInsanity() > 50) {
            s.getPlayerList().getPlayers().stream().filter(o -> o != p).findAny().ifPresent(f -> 
                p.sendSystemMessage(Component.literal("<" + f.getName().getString() + "> " + "Arkana bakma...")));
        }

        updateUI(p, data.getInsanity(), isNear, time);
    }

    private void updateUI(ServerPlayer p, float ins, boolean near, long t) {
        String c = ins > 80 ? "§4" : ins > 60 ? "§c" : ins > 40 ? "§e" : "§a";
        String m = c + "Insanity: " + (int)ins + "/100";
        if (ins >= 90 && (t / 2 % 2 == 0)) m = "§4§k||||||||||||||";
        p.displayClientMessage(Component.literal(m), true);
    }

    private void corrupt(ServerPlayer p, ServerLevel l) {
        BlockPos target = p.blockPosition().offset(l.random.nextInt(10)-5, -1, l.random.nextInt(10)-5);
        if (!l.getBlockState(target).isAir() && history.size() < 150) {
            history.putIfAbsent(target.immutable(), l.getBlockState(target));
            l.setBlockAndUpdate(target, Blocks.NETHERRACK.defaultBlockState());
        }
    }

    private void restore(ServerLevel l) {
        Iterator<Map.Entry<BlockPos, BlockState>> it = history.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            l.setBlockAndUpdate(e.getKey(), e.getValue());
            it.remove();
        }
    }

    private void spawnAnomaly(ServerPlayer p, ServerLevel l) {
        BlockPos pos = p.blockPosition().offset(20, 0, 20);
        AnomalyEntity e = new AnomalyEntity(ANOMALY, l);
        e.setPos(pos.getX(), pos.getY(), pos.getZ());
        l.addFreshEntity(e);
    }

    public static class AnomalyEntity extends Zombie {
        public AnomalyEntity(EntityType<? extends Zombie> t, Level l) { super(t, l); }
        public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
            return Zombie.createAttributes().add(Attributes.MAX_HEALTH, 600).add(Attributes.MOVEMENT_SPEED, 0.4);
        }

        @Override
        public void aiStep() {
            super.aiStep();
            if (this.level().isClientSide) return;
            ServerPlayer target = (ServerPlayer) getTarget();
            if (target != null) {
                // Skini buradan çeker (Glitcli skin)
                this.setCustomName(target.getName());
                boolean looking = target.getViewVector(1.0f).dot(new Vec3(getX()-target.getX(), 0, getZ()-target.getZ()).normalize()) > 0.5;
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(looking ? 0.1 : 0.9);
            }
        }
    }
}
