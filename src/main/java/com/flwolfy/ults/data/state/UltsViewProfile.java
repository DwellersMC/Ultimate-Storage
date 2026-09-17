package com.flwolfy.ults.data.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record UltsViewProfile(String categoryId, int categoryPage, int itemPage) {

  public static final Codec<UltsViewProfile> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          Codec.STRING.optionalFieldOf("category", "").forGetter(UltsViewProfile::categoryId),
          Codec.INT.optionalFieldOf("categoryPage", 0).forGetter(UltsViewProfile::categoryPage),
          Codec.INT.optionalFieldOf("itemPage", 0).forGetter(UltsViewProfile::itemPage)
      ).apply(instance, UltsViewProfile::new)
  );

  public static final UltsViewProfile DEFAULT = new UltsViewProfile("", 0, 0);

  public UltsViewProfile {
    categoryId = categoryId == null ? "" : categoryId;
    categoryPage = Math.max(0, categoryPage);
    itemPage = Math.max(0, itemPage);
  }
}
