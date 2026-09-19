package com.flwolfy.ults.data.state;

import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * What one player last looked at, plus the item visibility they chose for themselves.
 *
 * <p>A missing or unknown visibility means the player never chose one, so the configured default stays
 * in effect for them.
 */
public record UltsViewProfile(
    String categoryId,
    int categoryPage,
    int itemPage,
    String filter,
    @Nullable UltsItemVisibility visibility
) {

  public static final Codec<UltsViewProfile> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          Codec.STRING.optionalFieldOf("category", "").forGetter(UltsViewProfile::categoryId),
          Codec.INT.optionalFieldOf("categoryPage", 0).forGetter(UltsViewProfile::categoryPage),
          Codec.INT.optionalFieldOf("itemPage", 0).forGetter(UltsViewProfile::itemPage),
          Codec.STRING.optionalFieldOf("filter", "").forGetter(UltsViewProfile::filter),
          Codec.STRING.optionalFieldOf("visibility", "").forGetter(UltsViewProfile::visibilityName)
      ).apply(instance, UltsViewProfile::from)
  );

  public static final UltsViewProfile DEFAULT = new UltsViewProfile("", 0, 0, "", null);

  public UltsViewProfile {
    categoryId = categoryId == null ? "" : categoryId;
    categoryPage = Math.max(0, categoryPage);
    itemPage = Math.max(0, itemPage);
    filter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
  }

  /** The stored name of the chosen visibility, or an empty string while nobody chose one. */
  private String visibilityName() {
    return visibility == null ? "" : visibility.name().toLowerCase(Locale.ROOT);
  }

  private static UltsViewProfile from(
      String categoryId,
      int categoryPage,
      int itemPage,
      String filter,
      String visibility
  ) {
    return new UltsViewProfile(
        categoryId, categoryPage, itemPage, filter, parseVisibility(visibility));
  }

  /** Reads a stored visibility, answering {@code null} for a missing or unknown mode. */
  @Nullable
  private static UltsItemVisibility parseVisibility(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      return UltsItemVisibility.valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
