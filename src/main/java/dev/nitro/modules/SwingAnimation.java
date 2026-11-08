package dev.nitro.modules;

import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * Swing Animation: replaces the first-person item swing with a different style and lets you change how long the swing
 * takes. Purely visual (attack cooldown and damage are not affected). The work is done by HeldItemRendererMixin and
 * LivingEntityMixin, which call into the static methods below.
 */
public final class SwingAnimation extends Module {
    public enum Type {
        DEFAULT("Default"), SWIPE("Swipe"), DOWN("Down"), SMOOTH("Smooth"), SMOOTH_2("Smooth 2"),
        POWER("Power"), FEAST("Feast"), TWIST("Twist");
        private final String label;
        Type(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public static SwingAnimation INSTANCE;

    private final Setting.Choice<Type> type = new Setting.Choice<>("Animation", Type.SMOOTH, Type.class);
    private final Setting.Num strength = new Setting.Num("Strength", 1.0, 0.2, 2.0, 0.1);
    private final Setting.Num duration = new Setting.Num("Swing duration (x, lower = faster)", 1.0, 0.25, 4.0, 0.25);
    private final Setting.Bool offhand = new Setting.Bool("Also animate off hand", true);

    public SwingAnimation() {
        super("Swing Animation", "Changes how your held item swings and how long the swing takes.", Category.RENDER);
        add(type, strength, duration, offhand);
        INSTANCE = this;
    }

    private static boolean on() {
        return INSTANCE != null && INSTANCE.enabled;
    }

    /** True when the vanilla swing transform of this arm should be replaced. */
    public static boolean applies(Arm arm) {
        if (!on() || INSTANCE.type.value == Type.DEFAULT) return false;
        var pl = MinecraftClient.getInstance().player;
        return pl != null && (INSTANCE.offhand.on() || arm == pl.getMainArm());
    }

    /** Swing duration in ticks for the local player, or -1 to keep vanilla. */
    public static int duration() {
        if (!on()) return -1;
        return Math.max(1, Math.round(6f * INSTANCE.duration.f()));
    }

    /**
     * Replaces HeldItemRenderer.applySwingOffset. Vanilla also translates the item before the equip offset using the swing
     * progress; that translation is undone first so the styles start from the plain resting position.
     */
    public static void apply(MatrixStack m, Arm arm, float p) {
        final float PI = (float) Math.PI;
        int v = arm == Arm.RIGHT ? 1 : -1;
        float sq = MathHelper.sqrt(p);
        float s1 = MathHelper.sin(p * p * PI);
        float s2 = MathHelper.sin(sq * PI);
        float m1 = MathHelper.sin(p * PI);
        float k = INSTANCE.strength.f();

        // undo vanilla's swing translation (it is applied before the equip offset, translations commute)
        m.translate(-v * (-0.4f * s2), -(0.2f * MathHelper.sin(sq * PI * 2f)), -(-0.2f * m1));

        float a = 0, b = 0, c = 0, tx = 0, ty = 0, tz = 0;
        switch (INSTANCE.type.value) {
            case SWIPE -> {
                a = -60 * s2; b = -35 * s2; c = -25 * s1;
                ty = -0.04f * s2;
            }
            case DOWN -> {
                a = -10 * s1; c = -95 * s2;
                ty = -0.08f * s2; tz = -0.12f * s2;
            }
            case SMOOTH -> {
                a = -15 * m1; b = -15 * m1; c = -55 * m1;
                ty = -0.04f * m1; tz = -0.10f * m1;
            }
            case SMOOTH_2 -> {
                float e = m1 * m1 * (3f - 2f * m1);
                a = -25 * e; b = -25 * e; c = -40 * e;
                ty = -0.06f * e; tz = -0.20f * e;
            }
            case POWER -> {
                // wind up for the first third, then slam forward
                float wind = p < 0.35f ? MathHelper.sin(p / 0.35f * PI / 2f) : MathHelper.cos((p - 0.35f) / 0.65f * PI / 2f);
                float slam = p < 0.35f ? 0f : MathHelper.sin((p - 0.35f) / 0.65f * PI);
                c = 30 * wind - 110 * slam;
                a = -10 * slam;
                ty = 0.08f * wind - 0.12f * slam;
                tz = 0.10f * wind - 0.25f * slam;
            }
            case FEAST -> {
                a = 25 * s2; b = 40 * s2; c = -35 * s2;
                tx = -0.25f * s2; ty = 0.12f * s2; tz = -0.10f * s2;
            }
            case TWIST -> {
                a = -20 * s1; b = -110 * s2; c = -30 * s2;
                tz = -0.08f * s2;
            }
            default -> { }
        }
        a *= k; b *= k; c *= k; tx *= k; ty *= k; tz *= k;

        m.translate(v * tx, ty, tz);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(v * (45f + a)));
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(v * b));
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(c));
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(v * -45f));
    }
}
