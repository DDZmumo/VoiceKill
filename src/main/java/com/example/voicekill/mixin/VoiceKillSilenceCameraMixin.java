package com.example.voicekill.mixin;

import com.example.voicekill.SilenceDamage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A directionless health penalty must not reuse the previous attack's camera angle. */
@Mixin(GameRenderer.class)
public class VoiceKillSilenceCameraMixin {
    @Inject(method="bobHurt",at=@At("HEAD"),cancellable=true,require=1)
    private void voicekill$quietPenalty(PoseStack pose,float partialTick,CallbackInfo ci) {
        if(Minecraft.getInstance().getCameraEntity() instanceof LivingEntity living
                &&SilenceDamage.hideHurtTilt(living.getLastDamageSource(),living.isDeadOrDying()))ci.cancel();
    }
}
