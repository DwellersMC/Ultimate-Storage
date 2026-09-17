package com.flwolfy.ults.data.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flwolfy.ults.UltsMod;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
