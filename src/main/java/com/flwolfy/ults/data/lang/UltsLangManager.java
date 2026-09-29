package com.flwolfy.ults.data.lang;

import com.flwolfy.ults.UltsMod;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;

public final class UltsLangManager {

  private final Set<String> warned = ConcurrentHashMap.newKeySet();
  private volatile String language = UltsLanguageLoader.DEFAULT_LOCALE;

  private UltsLangManager() {}

  public static UltsLangManager getInstance() {
    return Holder.INSTANCE;
  }

  /**
   * Switches the language used for the text the server renders.
   *
   * <p>The value is remembered as it is asked for: a locale the mod does not ship finds no
   * translations and the English ones are used instead, so nothing has to be read to answer this. The
   * configuration refuses a locale that is not on offer anyway.
   */
  public void setLanguage(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    language = normalized.isEmpty() ? UltsLanguageLoader.DEFAULT_LOCALE : normalized;
  }

  public Component text(String key, Object... arguments) {
    String pattern = translation(language, key);
    if (pattern == null) {
      pattern = translation(UltsLanguageLoader.DEFAULT_LOCALE, key);
    }
    if (pattern == null) {
      if (warned.add(key)) {
        UltsMod.LOGGER.warn("Missing language key: {}", key);
      }
      return Component.literal(key);
    }
    try {
      return Component.literal(pattern.formatted(arguments));
    } catch (RuntimeException ignored) {
      return Component.literal(pattern);
    }
  }

  public Set<String> availableLocales() {
    return languages().keySet();
  }

  public String languageName(String locale) {
    UltsLanguage value = languages().get(locale);
    return value == null ? locale : value.name();
  }

  private String translation(String locale, String key) {
    UltsLanguage value = languages().get(locale);
    return value == null ? null : value.translations().get(key);
  }

  /** The languages the mod bundles, read from its own files when something first needs them. */
  private static Map<String, UltsLanguage> languages() {
    return Languages.INSTANCE;
  }

  private static final class Languages {
    private static final Map<String, UltsLanguage> INSTANCE =
        Collections.unmodifiableMap(new LinkedHashMap<>(new UltsLanguageLoader().load()));
  }

  private static final class Holder {
    private static final UltsLangManager INSTANCE = new UltsLangManager();
  }
}
