package com.mbest700.anomaly.mixin;

import com.mbest700.anomaly.AnomalyMod;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Player.class)
public abstract class PlayerEntityMixin {
    @Unique
    private float anomaly_insanity = 0.0f;

    public float getInsanity() { return anomaly_insanity; }
    public void setInsanity(float val) { this.anomaly_insanity = Math.max(0, Math.min(100, val)); }
}

