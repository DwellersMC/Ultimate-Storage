package com.flwolfy.ults.modmenu;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import java.util.Optional;
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
    Values values = new Values(current);
    ConfigBuilder builder = ConfigBuilder.create()
        .setParentScreen(parent)
        .setTitle(Component.translatable(KEY + "title"))
        .setDoesConfirmSave(true);
    ConfigEntryBuilder entries = builder.entryBuilder();
    ConfigCategory all = builder.getOrCreateCategory(Component.translatable(KEY + "all"));
    all.addEntry(entries.startTextDescription(Component.translatable(KEY + "local_only")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC)).build());
    ConfigCategory general = builder.getOrCreateCategory(Component.translatable(KEY + "general"));
    ConfigCategory input = builder.getOrCreateCategory(Component.translatable(KEY + "input"));
    addLanguage(entries, general, values);
    addBaseBlock(entries, input, values);
    addPermission(entries, input, values);
    addLimit(entries, input, values);
    builder.setSavingRunnable(() -> save(values.build()));
    return builder.build();
  }

  private static void addLanguage(
      ConfigEntryBuilder entries, ConfigCategory category, Values values
  ) {
    category.addEntry(entries.startStrField(Component.translatable(KEY + "language"), values.language)
        .setDefaultValue(UltsConfigData.DEFAULT.general().language())
        .setTooltip(Component.translatable(KEY + "language.tooltip"))
        .setErrorSupplier(value -> languageError(value))
        .setSaveConsumer(value -> values.language = value)
        .build());
  }

  private static void addBaseBlock(
      ConfigEntryBuilder entries, ConfigCategory category, Values values
  ) {
    category.addEntry(entries.startStrField(Component.translatable(KEY + "base_block"), values.baseBlock)
        .setDefaultValue(UltsConfigData.DEFAULT.input().baseBlock())
        .setTooltip(Component.translatable(KEY + "base_block.tooltip"))
        .setErrorSupplier(value -> fieldError(values.withBaseBlock(value), "input.baseBlock"))
        .setSaveConsumer(value -> values.baseBlock = value)
        .build());
  }

  private static void addPermission(
      ConfigEntryBuilder entries, ConfigCategory category, Values values
  ) {
    category.addEntry(entries.startIntSlider(
            Component.translatable(KEY + "create_permission"), values.permission, 0, 4)
        .setDefaultValue(UltsConfigData.DEFAULT.input().createPermissionLevel())
        .setTooltip(Component.translatable(KEY + "create_permission.tooltip"))
        .setSaveConsumer(value -> values.permission = value)
        .build());
  }

  private static void addLimit(
      ConfigEntryBuilder entries, ConfigCategory category, Values values
  ) {
    category.addEntry(entries.startIntField(
            Component.translatable(KEY + "max_terminals"), values.maxTerminals)
        .setDefaultValue(UltsConfigData.DEFAULT.input().maxTerminals())
        .setMin(0)
        .setTooltip(Component.translatable(KEY + "max_terminals.tooltip"))
        .setSaveConsumer(value -> values.maxTerminals = value)
        .build());
  }

  private static Optional<Component> languageError(String value) {
    return com.flwolfy.ults.data.lang.UltsLangManager.getInstance().availableLocales().contains(
        value.trim().toLowerCase(java.util.Locale.ROOT))
        ? Optional.empty() : Optional.of(Component.translatable(KEY + "language.invalid"));
  }

  private static Optional<Component> fieldError(UltsConfigData value, String field) {
    return value.validate().contains(field)
        ? Optional.of(Component.translatable(KEY + field + ".invalid")) : Optional.empty();
  }

  private static void save(UltsConfigData data) {
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

  private static final class Values {
    private String language;
    private String baseBlock;
    private int permission;
    private int maxTerminals;

    private Values(UltsConfigData value) {
      language = value.general().language();
      baseBlock = value.input().baseBlock();
      permission = value.input().createPermissionLevel();
      maxTerminals = value.input().maxTerminals();
    }

    private UltsConfigData build() {
      return new UltsConfigData(
          new UltsConfigData.General(language),
          new UltsConfigData.Input(baseBlock, permission, maxTerminals)
      );
    }

    private UltsConfigData withBaseBlock(String value) {
      return new UltsConfigData(
          new UltsConfigData.General(language),
          new UltsConfigData.Input(value, permission, maxTerminals)
      );
    }
  }
}
