package com.flwolfy.ults.data.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

/**
 * One kind of item the storage holds.
 *
 * @param template one item of the kind, with every component it carries
 * @param amount how many of it are stored
 * @param updatedAt when this kind was last put in, as a wall clock time; {@code 0} for a save that
 *     predates the stamp. A stack that is put in again counts as new, which is what the special
 *     category sorts and trims by.
 */
record UltsStoredEntry(ItemStack template, long amount, long updatedAt) {

  static final Codec<UltsStoredEntry> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          ItemStack.CODEC.fieldOf("stack").forGetter(UltsStoredEntry::template),
          // Read as optional: a file that lost the field is a stack of no size rather than a file that
          // cannot be read, and the storage drops what holds nothing on the way in.
          Codec.LONG.optionalFieldOf("amount", 0L).forGetter(UltsStoredEntry::amount),
          Codec.LONG.optionalFieldOf("updatedAt", 0L).forGetter(UltsStoredEntry::updatedAt)
      ).apply(instance, UltsStoredEntry::new)
  );

  UltsStoredEntry {
    template = template.copyWithCount(1);
  }
}
