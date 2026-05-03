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

        // --- DELİLİK (INSANITY) MANTIĞI ---
        if (isNear) {
            data.setInsanity(data.getInsanity() + (nearList.get(0).distanceTo(p) < 30 ? 1.5f : 0.5f));
            
            // Psikolojik Korku: Arkadan gelen nefes sesleri
            if (data.getInsanity() >= 40 && time % 60 == 0) {
                l.playSound(null, p.blockPosition(), SoundEvents.HOSTILE_BREATH, SoundSource.AMBIENT, 0.8f, 0.1f);
            }
            
            // Dünya Bozulması (Netherrack'e dönüşme)
            if (data.getInsanity() >= 60 && time % 15 == 0) {
                BlockPos target = p.blockPosition().offset(l.random.nextInt(10)-5, -1, l.random.nextInt(10)-5);
                if (!l.getBlockState(target).isAir() && history.size() < 100) {
                    history.putIfAbsent(target.immutable(), l.getBlockState(target));
                    l.setBlockAndUpdate(target, Blocks.NETHERRACK.defaultBlockState());
                }
            }
        } else {
            data.setInsanity(Math.max(0, data.getInsanity() - 0.2f));
            // Otomatik Spawn
            if (data.getInsanity() > 50 && time > spawnCooldown && l.random.nextFloat() < 0.005) {
                BlockPos spawnPos = p.blockPosition().offset(20, 0, 20);
                AnomalyEntity e = new AnomalyEntity(ANOMALY, l);
                e.setPos(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
                l.addFreshEntity(e);
                spawnCooldown = time + 5000;
            }
            // Blokları eski haline döndür
            if (time % 40 == 0 && !history.isEmpty()) {
                BlockPos bp = history.keySet().iterator().next();
                l.setBlockAndUpdate(bp, history.remove(bp));
            }
        }

        // --- MULTIPLAYER SAHTE MESAJLAR ---
        if (s.getPlayerList().getPlayerCount() >= 2 && time % 300 == 0 && data.getInsanity() > 55) {
            s.getPlayerList().getPlayers().stream().filter(o -> o != p).findAny().ifPresent(friend -> 
                p.sendSystemMessage(Component.literal("<" + friend.getName().getString() + "> " + "Yardım et...")));
        }

        // --- UI VE KRİTİK EFEKTLER ---
        String color = data.getInsanity() > 80 ? "§4" : data.getInsanity() > 50 ? "§c" : "§a";
        p.displayClientMessage(Component.literal(color + "Zihinsel Durum: %" + (int)data.getInsanity()), true);

        if (data.getInsanity() >= 85 && l.random.nextFloat() < 0.05) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        }
    }

    // --- ANOMALY VARLIĞI ---
    public static class AnomalyEntity extends Zombie {
        public AnomalyEntity(EntityType<? extends Zombie> t, Level l) { super(t, l); }
        
        public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
            return Zombie.createAttributes().add(Attributes.MAX_HEALTH, 600).add(Attributes.MOVEMENT_SPEED, 0.35);
        }

        @Override
        public void aiStep() {
            super.aiStep();
            if (this.level().isClientSide) return;
            ServerPlayer target = (ServerPlayer) getTarget();
            if (target != null) {
                this.setCustomName(target.getName()); // İsmini taklit et
                
                // Bakış Kontrolü (Ağlayan Melek mekaniği)
                Vec3 look = target.getViewVector(1.0f).normalize();
                Vec3 toEnt = new Vec3(getX()-target.getX(), 0, getZ()-target.getZ()).normalize();
                boolean isLooking = look.dot(toEnt) > 0.5;
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(isLooking ? 0.05 : 0.9);
            }
        }
    }
    }
