package com.flwolfy.ults.data.lang;

import com.flwolfy.ults.UltsMod;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

final class UltsLanguageLoader {

  static final String DEFAULT_LOCALE = "en_us";
  static final String NAME_KEY = "ults.language.name";
  private static final Gson GSON = new Gson();
  private static final Pattern LOCALE = Pattern.compile("[a-z0-9][a-z0-9_-]*");
  private final List<Path> roots;

  UltsLanguageLoader() {
    this(resolveModRoots());
  }

  UltsLanguageLoader(List<Path> roots) {
    this.roots = List.copyOf(roots);
  }

  Map<String, UltsLanguage> load() {
    Map<String, UltsLanguage> discovered = new TreeMap<>();
    for (Path root : roots) {
      Path directory = root.resolve("assets").resolve(UltsMod.MOD_ID).resolve("lang");
      if (Files.isDirectory(directory)) {
        discover(directory, discovered);
      }
    }
    if (!discovered.containsKey(DEFAULT_LOCALE)) {
      throw new IllegalStateException("Missing bundled Ults language " + DEFAULT_LOCALE + ".json");
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(discovered));
  }

  private static void discover(Path directory, Map<String, UltsLanguage> discovered) {
    try (var paths = Files.list(directory)) {
      for (Path path : paths.filter(Files::isRegularFile)
          .filter(UltsLanguageLoader::isJson)
          .sorted(Comparator.comparing(value -> value.getFileName().toString())).toList()) {
        String locale = locale(path);
        if (discovered.putIfAbsent(locale, read(path)) != null) {
          throw new IllegalStateException("Duplicate bundled Ults language locale " + locale);
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to scan bundled Ults languages in " + directory,
          exception);
    }
  }

  private static List<Path> resolveModRoots() {
    ModContainer mod = FabricLoader.getInstance().getModContainer(UltsMod.MOD_ID)
        .orElseThrow(() -> new IllegalStateException("Could not resolve Ults mod container"));
    return mod.getRootPaths();
  }

  private static boolean isJson(Path path) {
    return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
  }

  private static String locale(Path path) {
    String file = path.getFileName().toString();
    String locale = file.substring(0, file.length() - 5).toLowerCase(Locale.ROOT);
    if (!LOCALE.matcher(locale).matches()) {
      throw new IllegalStateException("Invalid bundled Ults language filename " + path.getFileName());
    }
    return locale;
  }

  private static UltsLanguage read(Path path) {
    JsonElement parsed;
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      parsed = GSON.fromJson(reader, JsonElement.class);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Invalid bundled Ults language JSON " + path, exception);
    }
    if (parsed == null || !parsed.isJsonObject()) {
      throw new IllegalStateException("Bundled Ults language must be an object: " + path);
    }
    JsonObject object = parsed.getAsJsonObject();
    Map<String, String> translations = new LinkedHashMap<>();
    object.entrySet().forEach(entry -> {
      if (!entry.getValue().isJsonPrimitive()
          || !entry.getValue().getAsJsonPrimitive().isString()) {
        throw new IllegalStateException("Bundled Ults language value must be a string: " + path);
      }
      translations.put(entry.getKey(), entry.getValue().getAsString());
    });
    String name = translations.get(NAME_KEY);
    if (name == null || name.isBlank()) {
      throw new IllegalStateException("Bundled Ults language is missing " + NAME_KEY + ": " + path);
    }
    return new UltsLanguage(name, translations);
  }
}
