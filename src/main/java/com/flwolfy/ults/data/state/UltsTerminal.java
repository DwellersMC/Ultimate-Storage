package com.flwolfy.ults.data.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

public record UltsTerminal(
    String name,
    String dimension,
    int x,
    int y,
    int z,
    String creator
) {
  public static final Codec<UltsTerminal> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          Codec.STRING.fieldOf("name").forGetter(UltsTerminal::name),
          Codec.STRING.fieldOf("dimension").forGetter(UltsTerminal::dimension),
          Codec.INT.fieldOf("x").forGetter(UltsTerminal::x),
          Codec.INT.fieldOf("y").forGetter(UltsTerminal::y),
          Codec.INT.fieldOf("z").forGetter(UltsTerminal::z),
          Codec.STRING.optionalFieldOf("creator", "").forGetter(UltsTerminal::creator)
      ).apply(instance, UltsTerminal::new)
  );

  public BlockPos basePos() {
    return new BlockPos(x, y, z);
  }

  public BlockPos barrelPos() {
    return basePos().above();
  }
}
