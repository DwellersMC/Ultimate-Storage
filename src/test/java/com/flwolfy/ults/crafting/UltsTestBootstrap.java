package com.flwolfy.ults.crafting;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Brings the vanilla registries up once, so tests can use real items without a running server.
 *
 * <p>The item registry comes up without its component maps bound — a server fills those in while it
 * loads its data packs. A test only needs them to be there, and binding an empty map is what makes a
 * plain stack of a plain item behave the way it does in game: a slot matches it, and its patch is
 * empty, which are the only two things the crafting code looks at.
 */
final class UltsTestBootstrap {

  private static boolean booted;

  private UltsTestBootstrap() {}

  static synchronized void boot() {
    if (booted) {
      return;
    }
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
      bind(item);
    }
    booted = true;
  }

  /** One plain item of this kind, on the very holder the game registers it with. */
  @SuppressWarnings("deprecation")
  static ItemStack stack(Item item) {
    boot();
    bind(item);
    return new ItemStack(item);
  }

  @SuppressWarnings("deprecation")
  private static void bind(Item item) {
    Holder.Reference<Item> holder = item.builtInRegistryHolder();
    if (!holder.areComponentsBound()) {
      holder.bindComponents(DataComponentMap.EMPTY);
    }
  }
}
