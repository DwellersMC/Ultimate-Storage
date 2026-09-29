package com.flwolfy.ults.data.lang;

import com.flwolfy.ults.UltsMod;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

/**
 * The name of an item the way the player's own client draws it, so a search finds what the player sees.
 *
 * <p>An item's name only exists on the client: the server sends the translation key and the client fills
 * in the words of the language it is set to. The server is told that language when a player joins, so a
 * search on the server can be answered in it — but the words themselves have to come from somewhere the
 * server can reach. They are read, lowest first:
 *
 * <ol>
 *   <li>the game's own files, which carry English;
 *   <li>the tables this mod bakes in at build time, one for every language the mod itself ships;
 *   <li>the language files of every loaded mod, for the namespace of each;
 *   <li>{@code config/ults/lang/<locale>.json}, a file the server owner may drop in to add a language
 *       or correct a word of their own.
 * </ol>
 *
 * <p>A language is always read on top of English, the way the client does it, so a word a language does
 * not carry falls back to its English one instead of going missing. Whatever a search cannot resolve
 * this way it still finds by item id, which is what makes the search work on a server with no language
 * data at all.
 */
public final class UltsItemNames {

  private static final String DEFAULT_LOCALE = "en_us";
  private static final String ITEM_NAMES_ROOT = "/assets/" + UltsMod.MOD_ID + "/itemnames/";
  private static final String GAME_LANG_ROOT = "/assets/minecraft/lang/";
  private static final Gson GSON = new Gson();
  private static final Pattern LOCALE = Pattern.compile("[a-z0-9][a-z0-9_-]*");
  /** How many player languages are kept, so a server with many of them cannot grow without a bound. */
  private static final int MAX_LOCALES = 8;
  private static final int MAX_NAMES = 20_000;
  private static final Map<String, Map<String, String>> FILES = new ConcurrentHashMap<>();
  private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();
  private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
  /** What a stack's own data says, which is only ever worked out when a search really needs it. */
  private static final Map<String, String> DATA = new ConcurrentHashMap<>();

  private UltsItemNames() {}

  /**
   * Whether a stack answers a search: by its name in the player's language, by its own id, or by what
   * its data says.
   *
   * @param template what is being searched
   * @param filter the text the player typed, already trimmed and lower-cased
   * @param locale the language the player's client is set to
   * @return whether the stack should show
   */
  public static boolean matches(ItemStack template, String filter, String locale) {
    if (filter == null || filter.isEmpty()) {
      return true;
    }
    return matches(template, filter, languages(normalize(locale)));
  }

  /** The same search answered from words already in hand, which is what a caller may hand in. */
  static boolean matches(ItemStack template, String filter, Map<String, String> language) {
    if (idOf(template).contains(filter) || nameIn(template, language).contains(filter)) {
      return true;
    }
    return dataIn(template, language).contains(filter);
  }

  /**
   * What a stack's own data says, in the player's language, as one searchable piece of text.
   *
   * <p>A name and an id say what a thing is; this says what is <em>on</em> it. It is the tooltip the
   * client would draw — an enchantment and its level, a potion's effect, an attribute, a banner pattern,
   * a written page — put into words by the same language files the names come from. That is what lets a
   * search in the player's own language find a stack by what it carries: `锋利` or `sharpness` finds the
   * enchanted book holding it, whatever the book is called.
   *
   * <p>A stack that carries nothing of its own has nothing to say, so a plain pile costs no work at all;
   * anything else is worked out once and remembered per stack and language.
   *
   * @param template the stack being searched
   * @param locale the language the player's client is set to
   * @return the lower-cased words of its data, or an empty string while it carries none
   */
  public static String data(ItemStack template, String locale) {
    if (template.isEmpty() || template.getComponentsPatch().isEmpty()) {
      return "";
    }
    String language = normalize(locale);
    if (DATA.size() >= MAX_NAMES) {
      DATA.clear();
    }
    return DATA.computeIfAbsent(keyOf(template, language),
        ignored -> dataIn(template, languages(language)));
  }

  private static String dataIn(ItemStack template, Map<String, String> language) {
    if (template.getComponentsPatch().isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (Component line : tooltip(template)) {
      text.append(resolve(line, language)).append('\n');
    }
    // The data itself as well, ids and all: an enchantment or an effect is then found by its own name
    // whatever language the client is set to, so `sharpness` finds the book that says 锋利.
    text.append(template.getComponentsPatch());
    return text.toString().toLowerCase(Locale.ROOT);
  }

  /**
   * The lines the client would draw on a stack, as the components it would draw them from.
   *
   * <p>The first line is the stack's own name; the rest is what it carries — an enchantment and its
   * level, a brew's effects, lore, the author of a written book. A screen that shows a stack's contents
   * in words rather than leaving them to a hover builds them from here, so what it says and what the
   * client would have said come from one place and cannot drift apart.
   *
   * <p>They are asked for with no level and no player, which is the same way {@link #data} asks: what a
   * stack carries is a fact about the stack, not about where it is being looked at. A line whose words
   * depend on who is looking — the damage a weapon adds, the speed it swings at — is therefore written
   * the way the game writes it without a player, and {@link #tooltip(ItemStack, Player)} is what a row a
   * player reads should ask for instead.
   *
   * @param template the stack being shown
   * @return its tooltip lines, ready to be drawn
   */
  public static List<Component> tooltip(ItemStack template) {
    return template.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL);
  }

  /**
   * The same lines, worked out for the player who is going to read them.
   *
   * <p>A player is not decoration here. An item that adds damage or speed writes those numbers from the
   * reader's own base values: without a player the game can only print the modifier it carries, so a
   * netherite sword reads `-2.4 Attack Speed` where the client, which hands its own player in, reads
   * `1.6 Attack Speed`. A row that is to read like the hover a player knows has to hand one in too.
   *
   * @param template the stack being shown
   * @param player the player who will read the lines, or {@code null} for none
   * @return its tooltip lines, ready to be drawn
   */
  public static List<Component> tooltip(ItemStack template, @Nullable Player player) {
    if (player == null) {
      return tooltip(template);
    }
    return template.getTooltipLines(
        Item.TooltipContext.of(player.level()), player, TooltipFlag.NORMAL);
  }

  /**
   * The name of an item in a language, or its id's own name while nothing is known about it.
   *
   * <p>The answer is lower-cased for searching and remembered per item and language: the language is
   * read once, and a screen searching a large catalogue then costs one lookup a row.
   *
   * @param template what is being named
   * @param locale the language the player's client is set to
   * @return the name as the client would draw it
   */
  public static String name(ItemStack template, String locale) {
    if (template.isEmpty()) {
      return "";
    }
    String language = normalize(locale);
    if (NAMES.size() >= MAX_NAMES) {
      NAMES.clear();
    }
    return NAMES.computeIfAbsent(keyOf(template, language),
        ignored -> nameIn(template, languages(language)));
  }

  /** What one stack of one language is remembered by, which is what every answer here hangs on. */
  private static String keyOf(ItemStack template, String language) {
    return language + '\u0000' + BuiltInRegistries.ITEM.getKey(template.getItem())
        + '\u0000' + (template.getComponentsPatch().isEmpty()
            ? "" : Integer.toHexString(template.getComponentsPatch().hashCode()));
  }

  /** The name of an item, lower-cased for searching, written with the words in hand. */
  private static String nameIn(ItemStack template, Map<String, String> language) {
    return resolve(template.getHoverName(), language).toLowerCase(Locale.ROOT);
  }

  /**
   * A language code as the files name them.
   *
   * <p>Anything that is not a plain code is answered with English, which also keeps a locale that a
   * player's client reported from being able to name a file of its own.
   *
   * @param locale what the client reported
   * @return the code to look files up by
   */
  public static String normalize(String locale) {
    String value = locale == null
        ? "" : locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return LOCALE.matcher(value).matches() ? value : DEFAULT_LOCALE;
  }

  /** Forgets every language that was read, which is what a resource or config reload asks for. */
  public static void clear() {
    FILES.clear();
    LANGUAGES.clear();
    NAMES.clear();
    DATA.clear();
  }

  /**
   * Writes a component out in a language, the way the client would.
   *
   * <p>A translatable part is looked up by its key, then by the fallback the sender gave it, and only
   * then stands as its key; its arguments are written out first, so a name built out of other names
   * comes out whole. This is what makes a potion say "Potion of Healing" and its bottle say what the
   * potion is.
   *
   * @param component the component to write out
   * @param language the words to write it with
   * @return the plain text of the component
   */
  static String resolve(Component component, Map<String, String> language) {
    StringBuilder text = new StringBuilder();
    append(component, language, text);
    return text.toString();
  }

  private static void append(Component component, Map<String, String> language, StringBuilder text) {
    switch (component.getContents()) {
      case TranslatableContents translatable -> appendTranslated(translatable, language, text);
      case PlainTextContents plain -> text.append(plain.text());
      // A keybind, a score, a selector or a piece of data has no words of its own on the server; it
      // stands as the key it was sent with, which is the best a name-carrying component can do here.
      default -> text.append(component.getString());
    }
    for (Component sibling : component.getSiblings()) {
      append(sibling, language, text);
    }
  }

  private static void appendTranslated(
      TranslatableContents translatable,
      Map<String, String> language,
      StringBuilder text
  ) {
    String pattern = language.get(translatable.getKey());
    if (pattern == null) {
      pattern = translatable.getFallback();
    }
    if (pattern == null) {
      text.append(translatable.getKey());
      return;
    }
    Object[] arguments = translatable.getArgs();
    if (arguments.length == 0) {
      text.append(pattern);
      return;
    }
    Object[] resolved = new Object[arguments.length];
    for (int index = 0; index < arguments.length; index++) {
      resolved[index] = arguments[index] instanceof Component component
          ? resolve(component, language) : arguments[index];
    }
    try {
      text.append(String.format(Locale.ROOT, pattern, resolved));
    } catch (RuntimeException ignored) {
      // A pattern with a placeholder of another shape than its arguments still says something.
      text.append(pattern);
    }
  }

  private static String idOf(ItemStack template) {
    return BuiltInRegistries.ITEM.getKey(template.getItem()).toString().toLowerCase(Locale.ROOT);
  }

  /** One language, read on top of English, remembered for as long as the cache holds. */
  private static Map<String, String> languages(String locale) {
    Map<String, String> cached = LANGUAGES.get(locale);
    if (cached != null) {
      return cached;
    }
    Map<String, String> merged = new LinkedHashMap<>(files(DEFAULT_LOCALE));
    if (!DEFAULT_LOCALE.equals(locale)) {
      merged.putAll(files(locale));
    }
    if (LANGUAGES.size() >= MAX_LOCALES) {
      LANGUAGES.clear();
      NAMES.clear();
    }
    Map<String, String> language = Map.copyOf(merged);
    LANGUAGES.put(locale, language);
    return language;
  }

  private static Map<String, String> files(String locale) {
    return FILES.computeIfAbsent(locale, UltsItemNames::read);
  }

  private static Map<String, String> read(String locale) {
    Map<String, String> merged = new LinkedHashMap<>();
    readStream(UltsItemNames.class.getResourceAsStream(GAME_LANG_ROOT + locale + ".json"), merged);
    readStream(UltsItemNames.class.getResourceAsStream(ITEM_NAMES_ROOT + locale + ".json"), merged);
    readMods(locale, merged);
    readPath(configFile(locale), merged);
    return Map.copyOf(merged);
  }
  /**
   * Every language file the loaded mods ship for a language.
   *
   * <p>A mod names its own items, so its file is read whatever its namespace is: the keys inside are
   * already unique to it, and reading them all is what lets an item of another mod be searched for in
   * the player's own language without that mod knowing anything about this one.
   */
  private static void readMods(String locale, Map<String, String> merged) {
    String file = locale + ".json";
    for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
      for (Path root : mod.getRootPaths()) {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
          continue;
        }
        try (var namespaces = Files.list(assets)) {
          for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
            readPath(namespace.resolve("lang").resolve(file), merged);
          }
        } catch (Exception exception) {
          // A mod whose files cannot be walked simply brings no names to the search.
          UltsMod.LOGGER.debug("Ults could not read the language files of {}", root, exception);
        }
      }
    }
  }

  /**
   * The file a server owner may drop in to add a language or correct a word of their own.
   *
   * <p>A process without a game directory — a test run — has no such file, and every other source can
   * still answer, so this says so rather than failing to read the language at all.
   */
  private static @Nullable Path configFile(String locale) {
    try {
      return FabricLoader.getInstance().getConfigDir()
          .resolve(UltsMod.MOD_ID).resolve("lang").resolve(locale + ".json");
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static void readStream(InputStream stream, Map<String, String> merged) {
    if (stream == null) {
      return;
    }
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      merged.putAll(read(reader));
    } catch (Exception exception) {
      UltsMod.LOGGER.warn("Ults could not read a bundled language file", exception);
    }
  }

  private static void readPath(@Nullable Path path, Map<String, String> merged) {
    if (path == null || !Files.isRegularFile(path)) {
      return;
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      merged.putAll(read(reader));
    } catch (Exception exception) {
      UltsMod.LOGGER.warn("Ults could not read the language file {}", path, exception);
    }
  }

  /**
   * Reads the words of one language file, which is a flat object of key to word.
   *
   * @param reader the file to read
   * @return the words it holds, empty while it says nothing of the sort
   */
  static Map<String, String> read(Reader reader) {
    Map<String, String> words = new LinkedHashMap<>();
    JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
    if (parsed == null || !parsed.isJsonObject()) {
      return words;
    }
    JsonObject object = parsed.getAsJsonObject();
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
        words.put(entry.getKey(), entry.getValue().getAsString());
      }
    }
    return words;
  }
}
