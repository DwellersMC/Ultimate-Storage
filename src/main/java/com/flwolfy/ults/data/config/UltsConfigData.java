package com.flwolfy.ults.data.config;

import com.flwolfy.ults.data.lang.UltsLangManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public record UltsConfigData(General general, Input input) {

  public record General(String language) {}

  public record Input(String baseBlock, int createPermissionLevel, int maxTerminals) {}

  public static final UltsConfigData DEFAULT = new UltsConfigData(
      new General("en_us"),
      new Input("minecraft:lodestone", 2, 0)
  );

  public List<String> validate() {
    List<String> invalid = new ArrayList<>();
    if (general == null || general.language() == null
        || !UltsLangManager.getInstance().availableLocales().contains(
            general.language().trim().toLowerCase(Locale.ROOT))) {
      invalid.add("general.language");
    }
    if (input == null || !validBlock(input.baseBlock())) {
      invalid.add("input.baseBlock");
    }
    if (input == null || input.createPermissionLevel() < 0
        || input.createPermissionLevel() > 4) {
      invalid.add("input.createPermissionLevel");
    }
    if (input == null || input.maxTerminals() < 0) {
      invalid.add("input.maxTerminals");
    }
    return List.copyOf(invalid);
  }

  public UltsConfigData canonicalize() {
    return new UltsConfigData(
        new General(general.language().trim().toLowerCase(Locale.ROOT)),
        new Input(
            input.baseBlock().trim().toLowerCase(Locale.ROOT),
            input.createPermissionLevel(),
            input.maxTerminals()
        )
    );
  }

  private static boolean validBlock(String value) {
    Identifier id = value == null ? null : Identifier.tryParse(value.trim());
    return id != null && BuiltInRegistries.BLOCK.getOptional(id).isPresent();
  }
}
