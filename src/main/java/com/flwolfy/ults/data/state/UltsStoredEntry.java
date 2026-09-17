package com.flwolfy.ults.data.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

record UltsStoredEntry(ItemStack template, long amount) {
  static final Codec<UltsStoredEntry> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          ItemStack.CODEC.fieldOf("stack").forGetter(UltsStoredEntry::template),
          Codec.LONG.fieldOf("amount").forGetter(UltsStoredEntry::amount)
      ).apply(instance, UltsStoredEntry::new)
  );

  UltsStoredEntry {
    template = template.copyWithCount(1);
  }
}
