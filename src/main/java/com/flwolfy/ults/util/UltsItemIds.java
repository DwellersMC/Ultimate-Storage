package com.flwolfy.ults.util;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Resolves the item ids the configuration names.
 *
 * <p>The runtime and the configuration screen share this lookup, so a value the screen accepts is
 * exactly a value the server can use. Ids are read the way the configuration file stores them, with
 * the namespace optional and the case folded.
 */
public final class UltsItemIds {

  private UltsItemIds() {}

  /**
   * Resolves one configured item id.
   *
   * @param value configured id, with or without a namespace
   * @return the item, or empty when the id is missing, malformed or names no item of this game
   */
  public static Optional<Item> resolve(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    Identifier id = Identifier.tryParse(normalized);
    return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
  }
}
