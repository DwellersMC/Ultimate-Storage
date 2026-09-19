package com.flwolfy.ults.data.config;

import com.flwolfy.ults.data.lang.UltsLangManager;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record UltsConfigData(General general, Input input) {

  /** The supported range of {@code input.drainInterval}. */
  public static final int MIN_DRAIN_INTERVAL = 1;
  public static final int MAX_DRAIN_INTERVAL = 20;

  public record General(
      String language,
      UltsItemVisibility itemVisibility,
      UltsStorageMode storageMode
  ) {}

  public record Input(
      int permissionLevel,
      int maxBindings,
      int drainInterval,
      List<String> multiBlockContainers,
      UltsCraftingMode crafting
  ) {}

  public static final UltsConfigData DEFAULT = new UltsConfigData(
      new General("en_us", UltsItemVisibility.AVAILABLE, UltsStorageMode.VOID),
      new Input(2, 0, 2, List.of(), UltsCraftingMode.DISABLED)
  );

  public List<String> validate() {
    List<String> invalid = new ArrayList<>();
    if (general == null || general.language() == null
        || !UltsLangManager.getInstance().availableLocales().contains(
            general.language().trim().toLowerCase(Locale.ROOT))) {
      invalid.add("general.language");
    }
    if (general == null || general.itemVisibility() == null) {
      invalid.add("general.itemVisibility");
    }
    if (general == null || general.storageMode() == null) {
      invalid.add("general.storageMode");
    }
    if (input == null || input.permissionLevel() < 0 || input.permissionLevel() > 4) {
      invalid.add("input.permissionLevel");
    }
    if (input == null || input.maxBindings() < 0) {
      invalid.add("input.maxBindings");
    }
    if (input == null || input.drainInterval() < MIN_DRAIN_INTERVAL
        || input.drainInterval() > MAX_DRAIN_INTERVAL) {
      invalid.add("input.drainInterval");
    }
    if (input == null || input.multiBlockContainers() == null
        || input.multiBlockContainers().stream().anyMatch(
            value -> value == null || Identifier.tryParse(value) == null)) {
      invalid.add("input.multiBlockContainers");
    }
    if (input == null || input.crafting() == null) {
      invalid.add("input.crafting");
    }
    return List.copyOf(invalid);
  }

  public UltsConfigData canonicalize() {
    return new UltsConfigData(
        new General(
            general.language().trim().toLowerCase(Locale.ROOT),
            general.itemVisibility(),
            general.storageMode()
        ),
        new Input(
            input.permissionLevel(),
            input.maxBindings(),
            input.drainInterval(),
            input.multiBlockContainers().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList(),
            input.crafting()
        )
    );
  }
}
