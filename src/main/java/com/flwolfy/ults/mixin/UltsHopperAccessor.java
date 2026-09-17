package com.flwolfy.ults.mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(HopperBlockEntity.class)
public interface UltsHopperAccessor {

  @Invoker("setCooldown")
  void ults$setCooldown(int cooldown);
}
