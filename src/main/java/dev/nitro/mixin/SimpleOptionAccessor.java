package dev.nitro.mixin;

import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Fullbright write a gamma value outside the normal 0..1 range (SimpleOption.setValue would reject it). */
@Mixin(SimpleOption.class)
public interface SimpleOptionAccessor<T> {
    @Mutable
    @Accessor("value")
    void nitro$setValue(T value);
}
