package com.example.voicekill;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.damagesource.DamageType;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import static org.junit.jupiter.api.Assertions.*;

class SilenceDamageTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private net.minecraft.world.damagesource.DamageSource source(String id) {
        var key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,net.minecraft.resources.Identifier.parse(id));
        var holder=net.minecraft.core.Holder.Reference.createStandAlone(new net.minecraft.core.HolderOwner<DamageType>(){},key);
        return new net.minecraft.world.damagesource.DamageSource(holder);
    }
    @Test void onlySilenceDamageSuppressesDirectionalCameraAndDeathRemainsVisible() {
        var silence=source("voicekill:silence");
        assertTrue(SilenceDamage.hideHurtTilt(silence,false));
        assertFalse(SilenceDamage.hideHurtTilt(source("minecraft:mob_attack"),false));
        assertFalse(SilenceDamage.hideHurtTilt(source("minecraft:fall"),false));
        assertFalse(SilenceDamage.hideHurtTilt(silence,true));assertFalse(SilenceDamage.hideHurtTilt(null,false));
    }
    @Test void cameraHookIsRegisteredAgainstTheActualSupportedMinecraftMethod() throws Exception {
        assertNotNull(net.minecraft.client.renderer.GameRenderer.class.getDeclaredMethod("bobHurt",com.mojang.blaze3d.vertex.PoseStack.class,float.class));
        try(var in=getClass().getResourceAsStream("/voicekill.mixins.json")) {
            assertNotNull(in);var json=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(json.getAsJsonArray("client").asList().stream().anyMatch(v->v.getAsString().equals("VoiceKillSilenceCameraMixin")));
        }
    }
    @Test void damageDefinitionUsesTheActualMinecraftCodec() throws Exception {
        try(var in=getClass().getResourceAsStream("/data/voicekill/damage_type/silence.json")){
            assertNotNull(in);var json=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8));
            var decoded=DamageType.DIRECT_CODEC.parse(JsonOps.INSTANCE,json);
            assertTrue(decoded.result().isPresent(),decoded.toString());
        }
        for(String tag:new String[]{"bypasses_armor","bypasses_effects","bypasses_cooldown","no_knockback","no_impact"})
            try(var in=getClass().getResourceAsStream("/data/minecraft/tags/damage_type/"+tag+".json")){
                assertNotNull(in);var json=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                assertFalse(json.get("replace").getAsBoolean());assertEquals("voicekill:silence",json.getAsJsonArray("values").get(0).getAsString());
            }
    }
}
