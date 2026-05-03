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
    public static final EntityType<AnomalyEntity> ANOMALY = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            new ResourceLocation("anomaly_0", "anomaly"),
            FabricEntityTypeBuilder.create(MobCategory.MONSTER, AnomalyEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.9f)).build());

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

        // Insanity Logic
        if (isNear) {
            data.setInsanity(data.getInsanity() + (nearList.get(0).distanceTo(p) < 35 ? 1.2f : 0.3f));
            if (data.getInsanity() >= 50 && time % 40 == 0) extinguishLights(p, l);
            if (data.getInsanity() >= 65 && time % (data.getInsanity() >= 75 ? 8 : 15) == 0) corrupt(p, l, data.getInsanity() >= 75 ? 2 : 1);
        } else {
            data.setInsanity(data.getInsanity() - 0.2f);
            if (data.getInsanity() > 50 && time > spawnCooldown && l.random.nextFloat() < 0.002) {
                spawnAnomaly(p, l); spawnCooldown = time + 6000;
            }
            if (time % 30 == 0) restore(l);
        }

        // Multiplayer Mimic & Decoy
        if (s.getPlayerList().getPlayerCount() >= 2 && time % 200 == 0 && data.getInsanity() > 50) simulateChat(p, s);
        if (data.getInsanity() > 40 && time % 300 == 0 && l.random.nextFloat() < 0.4) spawnDecoy(p, l);

        // UI & Effects
        updateUI(p, data.getInsanity(), isNear, time);
        if (data.getInsanity() >= 100) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0));
            p.hurt(l.damageSources().magic(), 10f);
            data.setInsanity(80);
        }
    }

    private void updateUI(ServerPlayer p, float ins, boolean near, long t) {
        String c = ins > 80 ? "§4" : ins > 60 ? "§c" : ins > 40 ? "§e" : "§a";
        String m = c + "Insanity: " + (int)ins + "/100" + (ins > 80 ? " §l⚠" : "");
        if (ins > 60 && near && (t / 10 % 2 == 0)) m = "§4§lERR_MIMIC_DETECTED";
        p.displayClientMessage(Component.literal(m), true);
    }

    private void simulateChat(ServerPlayer p, net.minecraft.server.MinecraftServer s) {
        s.getPlayerList().getPlayers().stream().filter(o -> o != p).findAny().ifPresent(f -> 
            p.sendSystemMessage(Component.literal("<" + f.getName().getString() + "> " + "Yardım et!")));
    }

    private void spawnAnomaly(ServerPlayer p, ServerLevel l) {
        BlockPos pos = p.blockPosition().offset(22 + l.random.nextInt(10), 0, 22 + l.random.nextInt(10));
        AnomalyEntity e = new AnomalyEntity(ANOMALY, l);
        e.setPos(pos.getX(), pos.getY(), pos.getZ());
        l.addFreshEntity(e);
    }

    private void spawnDecoy(ServerPlayer p, ServerLevel l) {
        BlockPos pos = p.blockPosition().offset(l.random.nextInt(15)+10, 0, l.random.nextInt(15)+10);
        Animal a = (Animal) EntityType.PIG.create(l);
        if (a != null) { a.setPos(pos.getX(), pos.getY(), pos.getZ()); a.setCustomName(Component.literal("§c?")); l.addFreshEntity(a); }
    }

    private void extinguishLights(ServerPlayer p, ServerLevel l) {
        BlockPos.betweenClosedStream(p.blockPosition().offset(-6,-6,-6), p.blockPosition().offset(6,6,6))
            .filter(pos -> l.getBlockState(pos).is(Blocks.TORCH)).forEach(pos -> l.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
    }

    private void corrupt(ServerPlayer p, ServerLevel l, int n) {
        for(int i=0; i<n; i++) {
            BlockPos target = p.blockPosition().offset(l.random.nextInt(10)-5, -1, l.random.nextInt(10)-5);
            if (!l.getBlockState(target).isAir() && history.size() < 200) {
                history.putIfAbsent(target.immutable(), l.getBlockState(target));
                if (l.random.nextFloat() < 0.2) permanent.add(target);
                l.setBlockAndUpdate(target, Blocks.NETHERRACK.defaultBlockState());
            }
        }
    }

    private void restore(ServerLevel l) {
        Iterator<Map.Entry<BlockPos, BlockState>> it = history.entrySet().iterator();
        for(int i=0; i<2 && it.hasNext(); i++) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            if (!permanent.contains(e.getKey())) l.setBlockAndUpdate(e.getKey(), e.getValue());
            it.remove();
        }
    }

    // --- ENTITY ---
    public static class AnomalyEntity extends Zombie {
        public AnomalyEntity(EntityType<? extends Zombie> t, Level l) { super(t, l); }
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
                if (this.tickCount % 60 == 0) this.setCustomName(target.getName());
                boolean look = isLook(target);
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(look ? 0.2 : 0.88);
                
                IEntityData d = (IEntityData) target;
                if (look && distanceTo(target) < 55 && distanceTo(target) > 10) {
                    d.setVanishTicks(d.getVanishTicks()+1);
                    if (d.getVanishTicks() >= 3) { vanish(target); }
                } else { d.setVanishTicks(0); }

                level().getEntitiesOfClass(Animal.class, getBoundingBox().inflate(4)).stream()
                    .filter(a -> a.getCustomName() != null && a.getCustomName().getString().equals("?")).forEach(a -> {
                        level().explode(this, a.getX(), a.getY(), a.getZ(), 0, Level.ExplosionInteraction.NONE);
                        a.discard(); target.sendSystemMessage(Component.literal("§4O hayvan değildi..."));
                        d.setInsanity(d.getInsanity()+15);
                    });
            }
        }
        private void vanish(ServerPlayer p) {
            ((ServerLevel)level()).sendParticles(ParticleTypes.END_ROD, getX(), getY()+1, getZ(), 20, 0.5, 0.5, 0.5, 0.1);
            ((IEntityData)p).setInsanity(((IEntityData)p).getInsanity()+5); this.discard();
        }
        private boolean isLook(ServerPlayer p) {
            return p.getViewVector(1f).dot(new Vec3(getX()-p.getX(), getY()-p.getY(), getZ()-p.getZ()).normalize()) > 0.6;
        }
        @Override public boolean fireImmune() { return true; }
    }
                                  }
                      
