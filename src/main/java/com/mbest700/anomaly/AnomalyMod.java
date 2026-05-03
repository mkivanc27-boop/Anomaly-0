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

        // --- INSANITY MEKANİĞİ ---
        if (isNear) {
            data.setInsanity(data.getInsanity() + (nearList.get(0).distanceTo(p) < 35 ? 1.2f : 0.3f));
            
            // Psikolojik Olaylar
            if (data.getInsanity() >= 40 && time % 60 == 0) playParanoiaSound(p, l);
            if (data.getInsanity() >= 50 && time % 40 == 0) extinguishLights(p, l);
            if (data.getInsanity() >= 65 && time % (data.getInsanity() >= 75 ? 8 : 15) == 0) corrupt(p, l, (int)data.getInsanity());
        } else {
            data.setInsanity(data.getInsanity() - 0.2f);
            if (data.getInsanity() > 50 && time > spawnCooldown && l.random.nextFloat() < 0.002) {
                spawnAnomaly(p, l); 
                spawnCooldown = time + 6000;
            }
            if (time % 30 == 0) restore(l);
        }

        // --- MULTIPLAYER MANİPÜLASYON ---
        if (s.getPlayerList().getPlayerCount() >= 2 && time % 200 == 0 && data.getInsanity() > 50) simulateChat(p, s);
        if (data.getInsanity() > 40 && time % 300 == 0 && l.random.nextFloat() < 0.4) spawnDecoy(p, l);

        // --- GÖRSEL EFEKTLER VE KRİTİK DURUM ---
        updateUI(p, data.getInsanity(), isNear, time);
        
        if (data.getInsanity() >= 80 && l.random.nextFloat() < 0.05) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        }

        if (data.getInsanity() >= 100) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0));
            p.hurt(l.damageSources().magic(), 6f);
            l.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_SCREAM, SoundSource.PLAYERS, 1.0f, 0.5f);
            data.setInsanity(80);
        }
    }

    private void updateUI(ServerPlayer p, float ins, boolean near, long t) {
        String c = ins > 80 ? "§4" : ins > 60 ? "§c" : ins > 40 ? "§e" : "§a";
        String m = c + "Insanity: " + (int)ins + "/100" + (ins > 80 ? " §l⚠" : "");
        if (ins > 60 && near && (t / 10 % 2 == 0)) m = "§4§lERR_MIMIC_DETECTED";
        if (ins >= 90 && (t / 2 % 2 == 0)) m = "§4§k||||||||||||||"; 
        p.displayClientMessage(Component.literal(m), true);
    }

    private void playParanoiaSound(ServerPlayer p, ServerLevel l) {
        SoundEvent[] sounds = {SoundEvents.ENDERMAN_STARE, SoundEvents.WITHER_AMBIENT, SoundEvents.HOSTILE_BREATH, SoundEvents.GLASS_BREAK};
        l.playSound(null, p.blockPosition(), sounds[l.random.nextInt(sounds.length)], SoundSource.AMBIENT, 0.6f, 0.4f);
    }

    private void simulateChat(ServerPlayer p, net.minecraft.server.MinecraftServer s) {
        String[] msgs = {"Arkana bakma...", "Onu görüyor musun?", "Neden kaçıyorsun?", "Gerçek değil...", "Buradayım!"};
        s.getPlayerList().getPlayers().stream().filter(o -> o != p).findAny().ifPresent(f -> 
            p.sendSystemMessage(Component.literal("<" + f.getName().getString() + "> " + msgs[s.getRandom().nextInt(msgs.length)])));
    }

    private void spawnAnomaly(ServerPlayer p, ServerLevel l) {
        BlockPos pos = p.blockPosition().offset(25 + l.random.nextInt(10), 0, 25 + l.random.nextInt(10));
        AnomalyEntity e = new AnomalyEntity(ANOMALY, l);
        e.setPos(pos.getX(), pos.getY(), pos.getZ());
        l.addFreshEntity(e);
    }

    private void spawnDecoy(ServerPlayer p, ServerLevel l) {
        BlockPos pos = p.blockPosition().offset(l.random.nextInt(20)-10, 0, l.random.nextInt(20)-10);
        Animal a = (Animal) EntityType.PIG.create(l);
        if (a != null) { 
            a.setPos(pos.getX(), pos.getY(), pos.getZ()); 
            a.setCustomName(Component.literal("§c?")); 
            l.addFreshEntity(a); 
        }
    }

    private void extinguishLights(ServerPlayer p, ServerLevel l) {
        BlockPos.betweenClosedStream(p.blockPosition().offset(-5,-5,-5), p.blockPosition().offset(5,5,5))
            .filter(pos -> l.getBlockState(pos).is(Blocks.TORCH)).forEach(pos -> l.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
    }

    private void corrupt(ServerPlayer p, ServerLevel l, int ins) {
        int amount = ins >= 75 ? 2 : 1;
        for(int i=0; i<amount; i++) {
            BlockPos target = p.blockPosition().offset(l.random.nextInt(10)-5, -1, l.random.nextInt(10)-5);
            if (!l.getBlockState(target).isAir() && history.size() < 200) {
                history.putIfAbsent(target.immutable(), l.getBlockState(target));
                if (l.random.nextFloat() < 0.15) permanent.add(target);
                l.setBlockAndUpdate(target, (ins >= 85 && l.random.nextBoolean()) ? Blocks.NETHER_WART_BLOCK.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState());
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

    // --- ANOMALY VARLIĞI SINIFI ---
    public static class AnomalyEntity extends Zombie {
        public AnomalyEntity(EntityType<? extends Zombie> t, Level l) { super(t, l); }

        public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
            return Zombie.createAttributes()
                    .add(Attributes.MAX_HEALTH, 600.0)
                    .add(Attributes.ATTACK_DAMAGE, 8.0)
                    .add(Attributes.MOVEMENT_SPEED, 0.35)
                    .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
        }

        @Override
        protected void registerGoals() {
            this.goalSelector.addGoal(1, new FloatGoal(this));
            this.goalSelector.addGoal(2, new BreakDoorGoal(this, d -> true));
            this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, false));
            this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, ServerPlayer.class, true));
        }

        @Override
        public void aiStep() {
            super.aiStep();
            if (this.level().isClientSide) return;
            
            ServerPlayer target = (ServerPlayer) getTarget();
            if (target != null) {
                if (this.tickCount % 60 == 0) this.setCustomName(target.getName());
                
                boolean isLooking = isPlayerLooking(target);
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(isLooking ? 0.15 : 0.85);
                
                IEntityData d = (IEntityData) target;
                if (isLooking && distanceTo(target) < 50 && distanceTo(target) > 8) {
                    d.setVanishTicks(d.getVanishTicks() + 1);
                    if (d.setVanishTicks(d.getVanishTicks()) >= 4) {
                        vanish();
                        d.setInsanity(d.getInsanity() + 5);
                    }
                }

                // Tuzak Hayvan Kontrolü
                level().getEntitiesOfClass(Animal.class, getBoundingBox().inflate(3)).stream()
                    .filter(a -> a.getCustomName() != null && a.getCustomName().getString().equals("?")).forEach(a -> {
                        level().explode(this, a.getX(), a.getY(), a.getZ(), 0, Level.ExplosionInteraction.NONE);
                        a.discard();
                        target.sendSystemMessage(Component.literal("§4Gerçek değildi."));
                        d.setInsanity(d.getInsanity() + 15);
                    });
            }
        }

        private void vanish() {
            ((ServerLevel)level()).sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY()+1, getZ(), 15, 0.3, 0.3, 0.3, 0.05);
            this.discard();
        }

        private boolean isPlayerLooking(ServerPlayer p) {
            Vec3 view = p.getViewVector(1.0f).normalize();
            Vec3 toEntity = new Vec3(getX() - p.getX(), getY() - p.getY(), getZ() - p.getZ()).normalize();
            return view.dot(toEntity) > 0.5;
        }

        @Override public boolean fireImmune() { return true; }
    }
                    }
                                     
