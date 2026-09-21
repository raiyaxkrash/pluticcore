package net.pocketodds.client;

import net.minecraftforge.common.ForgeConfigSpec;

public class PocketOddsClientConfig {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CLIENT = new Client(builder);
        CLIENT_SPEC = builder.build();
    }

    public static class Client {
        public final ForgeConfigSpec.BooleanValue enableAnimations;
        public final ForgeConfigSpec.DoubleValue animationSpeed;
        public final ForgeConfigSpec.BooleanValue reduceFlashes;
        public final ForgeConfigSpec.DoubleValue soundVolume;
        public final ForgeConfigSpec.BooleanValue autoSkipReplay;

        public Client(ForgeConfigSpec.Builder builder) {
            builder.push("accessibility_and_performance");

            enableAnimations = builder
                    .comment("Whether visual casino animations (reels, roulette, dice, deck) are enabled")
                    .define("enableAnimations", true);

            animationSpeed = builder
                    .comment("Speed multiplier for animations (1.0 = normal, 2.0 = double speed, 0.5 = slow)")
                    .defineInRange("animationSpeed", 1.0, 0.25, 4.0);

            reduceFlashes = builder
                    .comment("Reduce bright flashes and intense visual effects for photosensitive accessibility")
                    .define("reduceFlashes", false);

            soundVolume = builder
                    .comment("Volume multiplier for casino sound effects (clicks, spins, plings)")
                    .defineInRange("soundVolume", 1.0, 0.0, 1.0);

            autoSkipReplay = builder
                    .comment("Automatically skip animation if reopening an already determined game session")
                    .define("autoSkipReplay", true);

            builder.pop();
        }
    }

    public static boolean areAnimationsEnabled() {
        return CLIENT_SPEC != null && CLIENT_SPEC.isLoaded() ? CLIENT.enableAnimations.get() : true;
    }

    public static double getAnimationSpeed() {
        return CLIENT_SPEC != null && CLIENT_SPEC.isLoaded() ? CLIENT.animationSpeed.get() : 1.0;
    }

    public static boolean isReduceFlashes() {
        return CLIENT_SPEC != null && CLIENT_SPEC.isLoaded() ? CLIENT.reduceFlashes.get() : false;
    }

    public static float getSoundVolume() {
        return CLIENT_SPEC != null && CLIENT_SPEC.isLoaded() ? CLIENT.soundVolume.get().floatValue() : 1.0F;
    }

    public static boolean isAutoSkipReplay() {
        return CLIENT_SPEC != null && CLIENT_SPEC.isLoaded() ? CLIENT.autoSkipReplay.get() : true;
    }
}
