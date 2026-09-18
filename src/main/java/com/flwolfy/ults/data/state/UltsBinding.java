package com.flwolfy.ults.data.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * One bound container of the server storage.
 *
 * <p>The server keeps a single storage, so a binding is only a position: the {@code #1 #2 ...}
 * numbering of the listings refers to the order of this list and the note is free text for humans.
 */
public record UltsBinding(
    String note,
    String dimension,
    int x,
    int y,
    int z,
    String creator
) {
  public static final Codec<UltsBinding> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          Codec.STRING.optionalFieldOf("note", "").forGetter(UltsBinding::note),
          Codec.STRING.fieldOf("dimension").forGetter(UltsBinding::dimension),
          Codec.INT.fieldOf("x").forGetter(UltsBinding::x),
          Codec.INT.fieldOf("y").forGetter(UltsBinding::y),
          Codec.INT.fieldOf("z").forGetter(UltsBinding::z),
          Codec.STRING.optionalFieldOf("creator", "").forGetter(UltsBinding::creator)
      ).apply(instance, UltsBinding::new)
  );

  public UltsBinding {
    note = note == null ? "" : note;
    creator = creator == null ? "" : creator;
  }

  public BlockPos pos() {
    return new BlockPos(x, y, z);
  }

  public boolean inDimension(String dimensionId) {
    return dimension.equals(dimensionId);
  }
}
