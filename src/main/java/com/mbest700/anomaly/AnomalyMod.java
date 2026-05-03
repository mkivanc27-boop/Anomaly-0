package com.mbest700.anomaly;

import com.mbest700.anomaly.mixin.PlayerEntityMixin;
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
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class AnomalyMod implements ModInitializer {
    public static final String MOD_ID = "anomaly_0";
    public static final EntityType<AnomalyEntity> ANOMALY = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            new ResourceLocation(MOD_ID, "anomaly"),
            FabricEntityTypeBuilder.create(MobCategory.MONSTER, AnomalyEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.9f)).build());

    private static final Map<BlockPos, BlockState> history = new LinkedHashMap<>();
    private static final Set<BlockPos> permanent = new HashSet<>();
    private static long spawnCooldown = 0;

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(ANOMALY, AnomalyEntity.createAttributes());
        ServerTickEvents.START_SERVER_TICK.register(this::onTick);
    }

    private void onTick(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerEntityMixin pData = (PlayerEntityMixin)(Object)player;
            ServerLevel level = (ServerLevel) player.level();
            long time = level.getGameTime();

            // 1. Yakınlık ve Insanity Artışı
            List<AnomalyEntity> anomalies = level.getEntitiesOfClass(AnomalyEntity.class, player.getBoundingBox().inflate(64));
            boolean near = !anomalies.isEmpty();
            
            if (near) {
                float dist = (float) anomalies.get(0).distanceTo(player);
                pData.setInsanity(pData.getInsanity() + (dist < 35 ? 1.2f : 0.3f));
            } else {
                pData.setInsanity(pData.getInsanity() - 0.2f);
                // Rastgele Spawn
                if (pData.getInsanity() > 50 && time > spawnCooldown && (level.random.nextFloat() < 0.002 || time % 100 == 0 && level.random.nextFloat() < 0.015)) {
                    spawnAnomaly(player, level);
                    spawnCooldown = time + 6000;
                }
            }

            // 2. Action Bar
            updateActionBar(player, pData.getInsanity(), near);

            // 3. Insanity Efektleri & Decoy
            handleInsanityEffects(player, level, pData.getInsanity(), time, near);
            
            // 4. Mimic Sistemi (Multiplayer)
            if (server.getPlayerList().getPlayerCount() >= 2) {
                handleMimic(player, server, pData.getInsanity(), time);
            }

            // 5. Blok Restorasyonu
            if (!near && time % 30 == 0) restoreBlocks(level);
        }
    }

    private void spawnAnomaly(ServerPlayer player, ServerLevel level) {
        BlockPos pos = player.blockPosition().offset(level.random.nextInt(10)+22, 0, level.random.nextInt(10)+22);
        AnomalyEntity e = new AnomalyEntity(ANOMALY, level);
        e.setPos(pos.getX(), pos.getY(), pos.getZ());
        level.addFreshEntity(e);
    }

    private void updateActionBar(ServerPlayer player, float ins, boolean near) {
        String color = ins > 80 ? "§4" : ins > 60 ? "§c" : ins > 40 ? "§e" : "§a";
        String bar = color + "Insanity: " + (int)ins + "/100" + (ins > 80 ? " §l⚠" : "");
        if (ins > 60 && near && (System.currentTimeMillis() / 500 % 2 == 0)) bar = "§4§lERR_MIMIC_DETECTED";
        player.displayClientMessage(Component.literal(bar), true);
    }

    private void handleInsanityEffects(ServerPlayer player, ServerLevel level, float ins, long time, boolean near) {
        if (!near) return;
        if (ins >= 40 && time % 60 == 0) level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_STARE, SoundSource.AMBIENT, 1f, 0.5f);
        if (ins >= 50 && time % 40 == 0) extinguishLights(player, level);
        if (ins >= 65 && time % (ins >= 75 ? 8 : 15) == 0) corruptWorld(player, level, ins >= 75 ? 2 : 1);
        if (ins >= 100) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0));
            player.hurt(level.damageSources().magic(), 10f);
            ((PlayerEntityMixin)(Object)player).setInsanity(80);
        }
    }

    private void handleMimic(ServerPlayer player, net.minecraft.server.MinecraftServer server, float ins, long time) {
        ServerPlayer targetFriend = server.getPlayerList().getPlayers().stream().filter(p -> p != player).findAny().orElse(null);
        if (targetFriend == null) return;

        if (ins > 50 && time % 200 == 0) player.sendSystemMessage(Component.literal("<" + targetFriend.getName().getString() + "> Yardım et!"));
        if (ins > 40 && time % 340 == 0) player.sendSystemMessage(Component.literal("§e" + targetFriend.getName().getString() + " left the game"));
    }

    private void corruptWorld(ServerPlayer p, ServerLevel l, int count) {
        for(int i=0; i<count; i++) {
            BlockPos pos = p.blockPosition().offset(l.random.nextInt(10)-5, -1, l.random.nextInt(10)-5);
            if (!l.getBlockState(pos).isAir() && history.size() < 200) {
                history.putIfAbsent(pos.immutable(), l.getBlockState(pos));
                if (l.random.nextFloat() < 0.2) permanent.add(pos);
                l.setBlockAndUpdate(pos, Blocks.NETHERRACK.defaultBlockState());
            }
        }
    }

    private void restoreBlocks(ServerLevel l) {
        Iterator<Map.Entry<BlockPos, BlockState>> it = history.entrySet().iterator();
        for(int i=0; i<2 && it.hasNext(); i++) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            if (!permanent.contains(e.getKey())) l.setBlockAndUpdate(e.getKey(), e.getValue());
            it.remove();
        }
    }

    private void extinguishLights(ServerPlayer p, ServerLevel l) {
        BlockPos.betweenClosedStream(p.blockPosition().offset(-6,-6,-6), p.blockPosition().offset(6,6,6))
            .filter(pos -> l.getBlockState(pos).is(Blocks.TORCH)).forEach(pos -> l.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
    }

    // --- ENTITY ---
    public static class AnomalyEntity extends Zombie {
        private int lookTicks = 0;
        public AnomalyEntity(EntityType<? extends Zombie> type, Level level) { super(type, level); }
        public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
            return Zombie.createAttributes().add(Attributes.MAX_HEALTH, 600).add(Attributes.ATTACK_DAMAGE, 8).add(Attributes.MOVEMENT_SPEED, 0.4).add(Attributes.KNOCKBACK_RESISTANCE, 1);
        }
        @Override protected void registerGoals() {
            this.goalSelector.addGoal(1, new FloatGoal(this));
            this.goalSelector.addGoal(2, new BreakDoorGoal(this, d -> true));
            this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, false));
            this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, ServerPlayer.class, true));
        }
        @Override public void aiStep() {
            super.aiStep();
            if (this.level().isClientSide) return;
            ServerPlayer target = (ServerPlayer) getTarget();
            if (target != null) {
                boolean looking = isLooking(target);
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(looking ? 0.2 : 0.88);
                if (looking && distanceTo(target) < 55 && ++lookTicks > 3) {
                    ((ServerLevel)level()).sendParticles(ParticleTypes.END_ROD, getX(), getY()+1, getZ(), 20, 0.5, 0.5, 0.5, 0.1);
                    ((PlayerEntityMixin)(Object)target).setInsanity(((PlayerEntityMixin)(Object)target).getInsanity()+5);
                    this.discard();
                }
            }
        }
        private boolean isLooking(ServerPlayer p) {
            return p.getViewVector(1.0f).dot(new net.minecraft.world.phys.Vec3(getX()-p.getX(), getY()-p.getY(), getZ()-p.getZ()).normalize()) > 0.6;
        }
        @Override public boolean fireImmune() { return true; }
    }
}

