package com.flwolfy.ults.data.config;

import com.flwolfy.ults.data.lang.UltsLangManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;

public record UltsConfigData(General general, Input input, Special special) {

  /** The supported range of {@code input.drainInterval}. */
  public static final int MIN_DRAIN_INTERVAL = 1;
  public static final int MAX_DRAIN_INTERVAL = 20;

  /** How many item rows one page of the storage screen holds, which its listing shows. */
  public static final int SPECIAL_PAGE_SIZE = 35;
  /** How many pages of special items a fresh configuration keeps. */
  public static final int DEFAULT_SPECIAL_PAGES = 10;
  /** The largest special item count a configuration may name. */
  public static final int MAX_SPECIAL_ENTRIES = 1_000_000;

  public record General(
      String language,
      UltsItemVisibility itemVisibility,
      UltsStorageMode storageMode
  ) {}

  /**
   * How the storage is fed and emptied.
   *
   * @param permissionLevel vanilla permission level needed to manage the storage
   * @param maxBindings how many containers may be bound; {@code 0} is unlimited
   * @param drainInterval ticks between two drain visits of one bound container
   * @param multiBlockContainers block ids whose connected blocks form one large container
   * @param crafting whether a withdrawal may craft what is missing
   * @param allowFullInventory whether a withdrawal may go ahead with no room in the inventory, in
   *     which case what does not fit is dropped on the ground
   */
  public record Input(
      int permissionLevel,
      int maxBindings,
      int drainInterval,
      List<String> multiBlockContainers,
      UltsCraftingMode crafting,
      boolean allowFullInventory
  ) {}

  /**
   * What the storage does with the stacks that carry data of their own.
   *
   * @param maxEntries how many special entries the storage keeps; past it the oldest are destroyed
   * @param filterLootEquipment whether equipment a loot table can drop is filtered as well
   * @param filterMode what the filter does to the items it names
   * @param filters item ids the filter names itself, on top of what the flag above adds
   */
  public record Special(
      int maxEntries,
      boolean filterLootEquipment,
      UltsSpecialFilter filterMode,
      List<String> filters
  ) {}

  public static final UltsConfigData DEFAULT = new UltsConfigData(
      new General("en_us", UltsItemVisibility.AVAILABLE, UltsStorageMode.VOID),
      new Input(2, 0, 2, List.of(), UltsCraftingMode.DISABLED, false),
      new Special(
          DEFAULT_SPECIAL_PAGES * SPECIAL_PAGE_SIZE,
          false,
          UltsSpecialFilter.OFF,
          List.of())
  );

  /** How many listing pages a special entry count fills, which is how the screen shows the cap. */
  public static int pagesOf(int entries) {
    return entries <= 0 ? 0 : (entries + SPECIAL_PAGE_SIZE - 1) / SPECIAL_PAGE_SIZE;
  }

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
    if (special == null || special.maxEntries() < 0
        || special.maxEntries() > MAX_SPECIAL_ENTRIES) {
      invalid.add("special.maxEntries");
    }
    if (special == null || special.filterMode() == null) {
      invalid.add("special.filterMode");
    }
    if (special == null || special.filters() == null
        || special.filters().stream().anyMatch(
            value -> value == null || Identifier.tryParse(value) == null)) {
      invalid.add("special.filters");
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
            normalize(input.multiBlockContainers()),
            input.crafting(),
            input.allowFullInventory()
        ),
        new Special(
            special.maxEntries(),
            special.filterLootEquipment(),
            special.filterMode(),
            normalize(special.filters())
        )
    );
  }

  /** Ids are stored the way they are looked up: trimmed, lower case and without duplicates. */
  private static List<String> normalize(List<String> values) {
    return values.stream()
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }
}
