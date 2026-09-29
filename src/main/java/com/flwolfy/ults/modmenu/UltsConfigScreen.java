package com.flwolfy.ults.modmenu;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.config.UltsSpecialFilter;
import com.flwolfy.ults.data.config.UltsStackRule;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.modmenu.entry.UltsIdListEntry;
import com.flwolfy.ults.modmenu.entry.UltsSectionEntry;
import com.flwolfy.ults.modmenu.model.UltsIdEditorModel;
import com.flwolfy.ults.util.UltsBlockIds;
import com.flwolfy.ults.util.UltsItemIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class UltsConfigScreen {

  private static final String KEY = "ults.config.";
  private static final SystemToast.SystemToastId SAVE_RESULT = new SystemToast.SystemToastId();

  private UltsConfigScreen() {}

  public static Screen create(Screen parent) {
    UltsConfigData current = UltsConfigManager.getInstance().loadForEditing();
    ConfigBuilder builder = ConfigBuilder.create()
        .setParentScreen(parent)
        .setTitle(Component.translatable(KEY + "title"))
        .setDoesConfirmSave(true);
    ConfigEntryBuilder entries = builder.entryBuilder();

    ConfigCategory all = builder.getOrCreateCategory(Component.translatable(KEY + "all"));
    ConfigCategory general = builder.getOrCreateCategory(Component.translatable(KEY + "general"));
    ConfigCategory input = builder.getOrCreateCategory(Component.translatable(KEY + "input"));
    ConfigCategory special = builder.getOrCreateCategory(Component.translatable(KEY + "special"));

    all.addEntry(entries.startTextDescription(Component.translatable(KEY + "local_only")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC)).build());

    Field<String> language = new Field<>(current.general().language());
    Field<UltsItemVisibility> itemVisibility = new Field<>(current.general().itemVisibility());
    Field<UltsStorageMode> storageMode = new Field<>(current.general().storageMode());
    Field<Integer> permission = new Field<>(current.input().permissionLevel());
    Field<Integer> maxBindings = new Field<>(current.input().maxBindings());
    Field<Integer> drainInterval = new Field<>(current.input().drainInterval());
    Field<UltsCraftingMode> crafting = new Field<>(current.input().crafting());
    Field<Boolean> allowFullInventory = new Field<>(current.input().allowFullInventory());
    Field<Boolean> allowTakeAll = new Field<>(current.input().allowTakeAll());
    Field<Integer> takeAllStacks = new Field<>(current.input().takeAllStacks());
    Field<Integer> takeAllRate = new Field<>(current.input().takeAllRate());
    Field<Integer> bundleSlots = new Field<>(current.special().bundleSlots());
    Field<UltsStackRule> stackRule = new Field<>(current.special().stackRule());
    Field<UltsSpecialFilter> filterMode = new Field<>(current.special().filterMode());
    UltsIdEditorModel multiBlock = new UltsIdEditorModel(
        current.input().multiBlockContainers(),
        UltsConfigData.DEFAULT.input().multiBlockContainers(),
        value -> UltsBlockIds.resolve(value).isPresent());
    UltsIdEditorModel filters = new UltsIdEditorModel(
        current.special().filters(),
        UltsConfigData.DEFAULT.special().filters(),
        value -> UltsItemIds.resolve(value).isPresent());

    // Every section is shown twice: once in its own tab and once inside the "all" overview. Scalar
    // settings share a field, so the copy that was changed away from the loaded value wins; the id
    // lists share a model instead, which keeps both copies in step while they are edited.
    List<AbstractConfigListEntry<?>> generalEntries = List.of(
        languageEntry(entries, language),
        itemVisibilityEntry(entries, itemVisibility),
        storageModeEntry(entries, storageMode)
    );
    List<AbstractConfigListEntry<?>> inputEntries = List.of(
        permissionEntry(entries, permission),
        countEntry(entries, maxBindings, "max_bindings"),
        drainEntry(entries, drainInterval),
        craftingEntry(entries, crafting),
        fullInventoryEntry(entries, allowFullInventory),
        allowTakeAllEntry(entries, allowTakeAll),
        takeAllStacksEntry(entries, takeAllStacks),
        takeAllRateEntry(entries, takeAllRate),
        idListEntry(entries, "multi_block_containers", multiBlock, false)
    );
    List<AbstractConfigListEntry<?>> specialEntries = List.of(
        stackRuleEntry(entries, stackRule),
        bundleSlotsEntry(entries, bundleSlots),
        filterModeEntry(entries, filterMode),
        idListEntry(entries, "special_filters", filters, false)
    );
    // One list entry may only sit in one place, so the overview builds its own copies of everything.
    List<AbstractConfigListEntry<?>> generalOverview = List.of(
        languageEntry(entries, language),
        itemVisibilityEntry(entries, itemVisibility),
        storageModeEntry(entries, storageMode)
    );
    List<AbstractConfigListEntry<?>> inputOverview = List.of(
        permissionEntry(entries, permission),
        countEntry(entries, maxBindings, "max_bindings"),
        drainEntry(entries, drainInterval),
        craftingEntry(entries, crafting),
        fullInventoryEntry(entries, allowFullInventory),
        allowTakeAllEntry(entries, allowTakeAll),
        takeAllStacksEntry(entries, takeAllStacks),
        takeAllRateEntry(entries, takeAllRate),
        idListEntry(entries, "multi_block_containers", multiBlock, true)
    );
    List<AbstractConfigListEntry<?>> specialOverview = List.of(
        stackRuleEntry(entries, stackRule),
        bundleSlotsEntry(entries, bundleSlots),
        filterModeEntry(entries, filterMode),
        idListEntry(entries, "special_filters", filters, true)
    );

    generalEntries.forEach(general::addEntry);
    inputEntries.forEach(input::addEntry);
    specialEntries.forEach(special::addEntry);
    all.addEntry(subCategory(entries, "general", generalOverview));
    all.addEntry(subCategory(entries, "input", inputOverview));
    all.addEntry(subCategory(entries, "special", specialOverview));

    builder.setSavingRunnable(() -> {
      multiBlock.flush();
      filters.flush();
      save(multiBlock, filters, new UltsConfigData(
          new UltsConfigData.General(
              language.resolve(), itemVisibility.resolve(), storageMode.resolve()),
          new UltsConfigData.Input(
              permission.resolve(),
              maxBindings.resolve(),
              drainInterval.resolve(),
              multiBlock.values(),
              crafting.resolve(),
              allowFullInventory.resolve(),
              allowTakeAll.resolve(),
              takeAllStacks.resolve(),
              takeAllRate.resolve()
          ),
          new UltsConfigData.Special(
              bundleSlots.resolve(),
              stackRule.resolve(),
              filterMode.resolve(),
              filters.values()
          )
      ));
    });
    return builder.build();
  }

  /**
   * Header of one section inside the "all" overview.
   *
   * <p>The overview mirrors the tabs, so the errors a section reports are already shown by the tab
   * copy of the same entries and are hidden here.
   */
  private static AbstractConfigListEntry<?> subCategory(
      ConfigEntryBuilder entries,
      String key,
      List<AbstractConfigListEntry<?>> children
  ) {
    return new UltsSectionEntry(
        entries,
        Component.translatable(KEY + key).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
        children,
        true,
        true);
  }

  private static AbstractConfigListEntry<?> languageEntry(
      ConfigEntryBuilder entries,
      Field<String> field
  ) {
    String[] locales = UltsLangManager.getInstance().availableLocales().toArray(String[]::new);
    AbstractConfigListEntry<String> entry = entries.startSelector(
            Component.translatable(KEY + "language"), locales, field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.general().language())
        .setTooltip(Component.translatable(KEY + "language.tooltip"))
        .setNameProvider(locale -> Component.literal(
            UltsLangManager.getInstance().languageName(locale)))
        .build();
    return field.track(entry);
  }

  private static AbstractConfigListEntry<?> itemVisibilityEntry(
      ConfigEntryBuilder entries,
      Field<UltsItemVisibility> field
  ) {
    AbstractConfigListEntry<UltsItemVisibility> entry = entries.startEnumSelector(
            Component.translatable(KEY + "item_visibility"),
            UltsItemVisibility.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.general().itemVisibility())
        .setTooltip(Component.translatable(KEY + "item_visibility.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "item_visibility." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  private static AbstractConfigListEntry<?> storageModeEntry(
      ConfigEntryBuilder entries,
      Field<UltsStorageMode> field
  ) {
    AbstractConfigListEntry<UltsStorageMode> entry = entries.startEnumSelector(
            Component.translatable(KEY + "storage_mode"),
            UltsStorageMode.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.general().storageMode())
        .setTooltip(Component.translatable(KEY + "storage_mode.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "storage_mode." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  /** One permission level covers binding, deleting, the highlight and reloading. */
  private static AbstractConfigListEntry<?> permissionEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntSlider(
            Component.translatable(KEY + "permission"), field.initial(), 0, 4)
        .setDefaultValue(UltsConfigData.DEFAULT.input().permissionLevel())
        .setTooltip(Component.translatable(KEY + "permission.tooltip"))
        .build();
    return field.track(entry);
  }

  private static AbstractConfigListEntry<?> countEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field,
      String key
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntField(
            Component.translatable(KEY + key), field.initial())
        .setDefaultValue(field.initial())
        .setMin(0)
        .setTooltip(Component.translatable(KEY + key + ".tooltip"))
        .build();
    return field.track(entry);
  }

  /** The drain interval is a tick count between 1 and 20. */
  private static AbstractConfigListEntry<?> drainEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntSlider(
            Component.translatable(KEY + "drain_interval"), field.initial(),
            UltsConfigData.MIN_DRAIN_INTERVAL, UltsConfigData.MAX_DRAIN_INTERVAL)
        .setDefaultValue(UltsConfigData.DEFAULT.input().drainInterval())
        .setTooltip(Component.translatable(KEY + "drain_interval.tooltip"))
        .build();
    return field.track(entry);
  }

  /** Automatic crafting is off, limited to shulker boxes, or open to every recipe. */
  private static AbstractConfigListEntry<?> craftingEntry(
      ConfigEntryBuilder entries,
      Field<UltsCraftingMode> field
  ) {
    AbstractConfigListEntry<UltsCraftingMode> entry = entries.startEnumSelector(
            Component.translatable(KEY + "crafting"),
            UltsCraftingMode.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.input().crafting())
        .setTooltip(Component.translatable(KEY + "crafting.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "crafting." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  /** Whether a withdrawal may exceed the room left in the player's inventory. */
  private static AbstractConfigListEntry<?> fullInventoryEntry(
      ConfigEntryBuilder entries,
      Field<Boolean> field
  ) {
    AbstractConfigListEntry<Boolean> entry = entries.startBooleanToggle(
            Component.translatable(KEY + "allow_full_inventory"), field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.input().allowFullInventory())
        .setTooltip(Component.translatable(KEY + "allow_full_inventory.tooltip"))
        .build();
    return field.track(entry);
  }

  /**
   * Whether players may empty a stock out with "take everything" at all.
   *
   * <p>With it off the offer is not there: the hint on a row and on a bag's status book leaves the line
   * out, and the clicks that would start one do nothing, so nothing about taking everything is shown.
   */
  private static AbstractConfigListEntry<?> allowTakeAllEntry(
      ConfigEntryBuilder entries,
      Field<Boolean> field
  ) {
    AbstractConfigListEntry<Boolean> entry = entries.startBooleanToggle(
            Component.translatable(KEY + "allow_take_all"), field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.input().allowTakeAll())
        .setTooltip(Component.translatable(KEY + "allow_take_all.tooltip"))
        .build();
    return field.track(entry);
  }

  /**
   * How many stacks one take-everything takes at most.
   *
   * <p>It is a count of stacks, not of items, because that is the unit the storage and a backpack both
   * think in; the tooltip spells out what one stack is worth for a thing that does not stack.
   */
  private static AbstractConfigListEntry<?> takeAllStacksEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntField(
            Component.translatable(KEY + "take_all_stacks"), field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.input().takeAllStacks())
        .setMin(1)
        .setMax(UltsConfigData.MAX_TAKE_ALL_STACKS)
        .setTooltip(Component.translatable(
            KEY + "take_all_stacks.tooltip", UltsConfigData.DEFAULT_TAKE_ALL_STACKS))
        .build();
    return field.track(entry);
  }

  /** How many items one take-everything hands over per tick while it runs. */
  private static AbstractConfigListEntry<?> takeAllRateEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntField(
            Component.translatable(KEY + "take_all_rate"), field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.input().takeAllRate())
        .setMin(1)
        .setMax(UltsConfigData.MAX_TAKE_ALL_RATE)
        .setTooltip(Component.translatable(
            KEY + "take_all_rate.tooltip", UltsConfigData.DEFAULT_TAKE_ALL_RATE))
        .build();
    return field.track(entry);
  }

  /**
   * How many stacks one bag holds.
   *
   * <p>It is a count of stored stacks, not a page number: the screen turns it into the pages a player
   * pages through, and the tooltip says how many those are.
   */
  private static AbstractConfigListEntry<?> bundleSlotsEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntField(
            Component.translatable(KEY + "special_bundle_slots"), field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.special().bundleSlots())
        .setMin(1)
        .setMax(UltsConfigData.MAX_BUNDLE_SLOTS)
        .setTooltip(Component.translatable(
            KEY + "special_bundle_slots.tooltip",
            UltsConfigData.BUNDLE_PAGE_SIZE,
            UltsConfigData.DEFAULT_BUNDLE_SLOTS,
            UltsConfigData.pagesOf(UltsConfigData.DEFAULT_BUNDLE_SLOTS)))
        .build();
    return field.track(entry);
  }

  /** When two stacks of one item are the same kind of thing and pool into one row. */
  private static AbstractConfigListEntry<?> stackRuleEntry(
      ConfigEntryBuilder entries,
      Field<UltsStackRule> field
  ) {
    AbstractConfigListEntry<UltsStackRule> entry = entries.startEnumSelector(
            Component.translatable(KEY + "special_stack_rule"),
            UltsStackRule.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.special().stackRule())
        .setTooltip(Component.translatable(KEY + "special_stack_rule.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "special_stack_rule.value." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  /** What the special filter does to the items it names. */
  private static AbstractConfigListEntry<?> filterModeEntry(
      ConfigEntryBuilder entries,
      Field<UltsSpecialFilter> field
  ) {
    AbstractConfigListEntry<UltsSpecialFilter> entry = entries.startEnumSelector(
            Component.translatable(KEY + "special_filter_mode"),
            UltsSpecialFilter.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.special().filterMode())
        .setTooltip(Component.translatable(KEY + "special_filter_mode.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "special_filter_mode." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  /** Item ids the special filter throws away. */
  private static AbstractConfigListEntry<?> idListEntry(
      ConfigEntryBuilder entries,
      String key,
      UltsIdEditorModel model,
      boolean suppressErrors
  ) {
    return new UltsIdListEntry(
        Component.translatable(KEY + key),
        KEY + key,
        model,
        entries.getResetButtonKey(),
        suppressErrors);
  }

  private static void save(
      UltsIdEditorModel multiBlock,
      UltsIdEditorModel filters,
      UltsConfigData data
  ) {
    List<String> invalid = new ArrayList<>(multiBlock.invalid());
    invalid.addAll(filters.invalid());
    if (!invalid.isEmpty()) {
      UltsMod.LOGGER.error(
          "UltStorage configuration not saved, unknown ids: {}", invalid);
      SystemToast.add(
          Minecraft.getInstance().gui.toastManager(),
          SAVE_RESULT,
          Component.translatable(KEY + "save_failed"),
          Component.literal(String.join(", ", invalid))
      );
      return;
    }
    boolean success = UltsConfigManager.getInstance().savePending(data);
    if (!success) {
      UltsMod.LOGGER.error("Failed to save Ults client configuration");
    }
    SystemToast.add(
        Minecraft.getInstance().gui.toastManager(),
        SAVE_RESULT,
        Component.translatable(KEY + (success ? "save_success" : "save_failed")),
        Component.translatable(KEY + (success ? "save_success.detail" : "save_failed.detail"))
    );
  }

  /**
   * Shared value of one setting across every copy of its entry. The entry that was changed away from
   * the loaded value wins, so editing the setting in any tab is applied exactly once.
   */
  private static final class Field<T> {

    private final T initial;
    private final List<AbstractConfigListEntry<T>> copies = new ArrayList<>();

    private Field(T initial) {
      this.initial = initial;
    }

    private T initial() {
      return initial;
    }

    private AbstractConfigListEntry<?> track(AbstractConfigListEntry<T> entry) {
      copies.add(entry);
      return entry;
    }

    private T resolve() {
      for (AbstractConfigListEntry<T> copy : copies) {
        T value = copy.getValue();
        if (!Objects.equals(value, initial)) {
          return value;
        }
      }
      return initial;
    }
  }
}
