package com.flwolfy.ults.data.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.UltsMod;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UltsLanguageLoaderTest {

  @TempDir
  Path root;

  @Test
  void discoversAndOrdersLanguages() throws Exception {
    write("zh_cn.json", "{\"ults.language.name\":\"简体中文\",\"key\":\"值\"}");
    write("en_us.json", "{\"ults.language.name\":\"English\",\"key\":\"Value\"}");
    var languages = new UltsLanguageLoader(List.of(root)).load();
    assertEquals(List.of("en_us", "zh_cn"), List.copyOf(languages.keySet()));
    assertEquals("值", languages.get("zh_cn").translations().get("key"));
  }

  @Test
  void requiresEnglishFallback() throws Exception {
    write("zh_cn.json", "{\"ults.language.name\":\"简体中文\"}");
    assertThrows(IllegalStateException.class,
        () -> new UltsLanguageLoader(List.of(root)).load());
  }

  @Test
  void rejectsNonStringValues() throws Exception {
    write("en_us.json", "{\"ults.language.name\":\"English\",\"bad\":1}");
    assertThrows(IllegalStateException.class,
        () -> new UltsLanguageLoader(List.of(root)).load());
  }

  private void write(String fileName, String contents) throws Exception {
    Path directory = root.resolve("assets").resolve(UltsMod.MOD_ID).resolve("lang");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(fileName), contents, StandardCharsets.UTF_8);
  }

  @Test
  void theBagBlockRulesLineUpInEveryShippedLanguage() throws Exception {
    for (String locale : List.of("en_us", "zh_cn", "zh_tw")) {
      Map<String, String> translations = shipped(locale);
      String header = translations.get("ults.gui.bag.newest");
      String divider = translations.get("ults.gui.bag.divider");
      assertNotNull(header, locale + " has no bag block header");
      assertNotNull(divider, locale + " has no bag block divider");
      // A framed block is only framed while both rules are the same width and made of one character,
      // which is what the row draws around the newest stack it holds.
      assertEquals(header.length(), divider.length(), locale + " rules are not the same width");
      // The header is the same rule with a label set into it: rules at both ends, and words between
      // them that are not the rule itself.
      String rule = String.valueOf(divider.charAt(0));
      assertTrue(header.startsWith(rule) && header.endsWith(rule),
          locale + " header is not an unbroken rule at both ends");
      assertFalse(header.replace(rule, " ").trim().isEmpty(),
          locale + " header names nothing");
      assertTrue(divider.chars().allMatch(c -> c == divider.charAt(0)),
          locale + " divider is not one unbroken rule");
    }
  }

  @Test
  void theBagTitleTakesTheItemNameInEveryShippedLanguage() throws Exception {
    for (String locale : List.of("en_us", "zh_cn", "zh_tw")) {
      String title = shipped(locale).get("ults.gui.bag.title");
      assertNotNull(title, locale + " has no bag title");
      // One placeholder and one only: it is filled with the item's own name component, and a pattern with
      // a second placeholder would lose everything after it.
      assertEquals(1, title.split("%s", -1).length - 1, locale + " title placeholders");
      // The name has to sit inside the line rather than at one of its ends, or the line would read as a
      // bare item name with something appended.
      int at = title.indexOf("%s");
      assertTrue(at > 0 && at < title.length() - 2, locale + " title does not frame the name");
    }
  }

  /** The words one shipped language is written with, read out of the built jar. */
  private static Map<String, String> shipped(String locale) throws Exception {
    String resource = "/assets/" + UltsMod.MOD_ID + "/lang/" + locale + ".json";
    try (var stream = UltsLanguageLoaderTest.class.getResourceAsStream(resource)) {
      assertNotNull(stream, resource + " is missing from the build");
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject()
          .entrySet()
          .stream()
          .collect(java.util.stream.Collectors.toMap(
              Map.Entry::getKey, entry -> entry.getValue().getAsString()));
    }
  }
}
