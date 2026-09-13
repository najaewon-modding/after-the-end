package net.njw.aftertheend.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.njw.aftertheend.network.CityRecallSoundPayload;

public final class CityRecallClientSound {
    private static final Map<UUID, RecallLoopSound> ACTIVE = new HashMap<>();

    private CityRecallClientSound() { }

    public static void handle(CityRecallSoundPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            RecallLoopSound existing = ACTIVE.remove(payload.playerId());
            if (existing != null) existing.stopNow();
            if (!payload.active() || minecraft.level == null) return;
            RecallLoopSound sound = new RecallLoopSound(payload.playerId());
            ACTIVE.put(payload.playerId(), sound);
            minecraft.getSoundManager().play(sound);
        });
    }

    private static final class RecallLoopSound extends AbstractTickableSoundInstance {
        private static final int MAX_TICKS = 180;
        private final UUID playerId;
        private int age;

        private RecallLoopSound(UUID playerId) {
            super(SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, RandomSource.create());
            this.playerId = playerId;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.72F;
            this.pitch = 1.0F;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
        }

        @Override
        public void tick() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || age++ >= MAX_TICKS) {
                stopNow();
                return;
            }
            Player player = minecraft.level.getPlayerByUUID(playerId);
            if (player == null || !player.isAlive()) {
                stopNow();
                return;
            }
            this.x = player.getX();
            this.y = player.getY() + 0.9D;
            this.z = player.getZ();
            float finalPhase = Math.clamp((age - 140.0F) / 20.0F, 0.0F, 1.0F);
            float emphasis = finalPhase * finalPhase;
            this.volume = 0.72F + emphasis * 0.28F;
            this.pitch = 1.0F;
        }

        private void stopNow() {
            stop();
            ACTIVE.remove(playerId, this);
        }
    }
}
