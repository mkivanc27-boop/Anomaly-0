package com.mbest700.anomaly.mixin;

import com.mbest700.anomaly.IEntityData;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Player.class)
public abstract class PlayerMixin implements IEntityData {
    @Unique private float insanity = 0.0f;
    @Unique private int vTicks = 0;
    @Override public float getInsanity() { return insanity; }
    @Override public void setInsanity(float val) { this.insanity = Math.max(0, Math.min(100, val)); }
    @Override public int getVanishTicks() { return vTicks; }
    @Override public void setVanishTicks(int ticks) { this.vTicks = ticks; }
}
