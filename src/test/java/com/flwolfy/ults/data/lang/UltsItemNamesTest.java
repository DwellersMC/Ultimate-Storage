package com.flwolfy.ults.data.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.Test;

/**
 * The words a search is answered with: a name is written the way the client would write it, and a
 * search finds an item by that name or by its own id.
 */
class UltsItemNamesTest {

  private static final Map<String, String> CHINESE = Map.of(
      "item.minecraft.diamond", "钻石",
      "item.minecraft.spawn_egg", "%s刷怪蛋",
      "entity.minecraft.pig", "猪");

  @Test
  void aNameIsWrittenTheWayTheClientWouldWriteIt() {
    assertEquals("钻石",
        UltsItemNames.resolve(Component.translatable("item.minecraft.diamond"), CHINESE));
  }

  @Test
  void aNameBuiltOutOfAnotherNameComesOutWhole() {
    Component egg = Component.translatable(
        "item.minecraft.spawn_egg", Component.translatable("entity.minecraft.pig"));
    assertEquals("猪刷怪蛋", UltsItemNames.resolve(egg, CHINESE));
  }

  @Test
  void aWordThatIsMissingStandsAsItsFallbackAndThenAsItsKey() {
    Map<String, String> english = Map.of("item.minecraft.diamond", "Diamond");
    assertEquals("Diamond",
        UltsItemNames.resolve(Component.translatable("item.minecraft.diamond"), english));
    assertEquals("fallback",
        UltsItemNames.resolve(Component.translatableWithFallback("ults.unknown", "fallback"), english));
    assertEquals("ults.unknown",
        UltsItemNames.resolve(Component.translatable("ults.unknown"), english));
  }

  @Test
  void aCodeThatIsNotALanguageIsAnsweredWithEnglish() {
    assertEquals("zh_cn", UltsItemNames.normalize("zh-CN"));
    assertEquals("zh_tw", UltsItemNames.normalize(" zh_TW "));
    assertEquals("en_us", UltsItemNames.normalize(null));
    // Nothing a client reports may ever name a file of its own.
    assertEquals("en_us", UltsItemNames.normalize("../../etc/passwd"));
    assertEquals("en_us", UltsItemNames.normalize("zh cn"));
  }

  @Test
  void aSearchFindsAnItemByItsNameOrByItsId() {
    UltsTestBootstrap.boot();
    ItemStack diamond = UltsTestBootstrap.stack(Items.DIAMOND);
    // A server binds every item's name to the key of its own translation while it loads its data; the
    // test registry has no components bound at all, so the key is set here the way that binding does.
    diamond.set(DataComponents.ITEM_NAME, Component.translatable("item.minecraft.diamond"));

    assertTrue(UltsItemNames.matches(diamond, "钻石", CHINESE));
    assertTrue(UltsItemNames.matches(diamond, "diamond", CHINESE));
    assertTrue(UltsItemNames.matches(diamond, "minecraft:diamond", CHINESE));
    assertFalse(UltsItemNames.matches(diamond, "石头", CHINESE));
  }

  @Test
  void aSearchFindsAStackByWhatItCarries() {
    UltsTestBootstrap.boot();
    // What a stack carries is searched the way the client would draw it, so a word of its tooltip is
    // found in the same language the client shows it in — an enchantment name here stands for any data.
    ItemStack book = UltsTestBootstrap.stack(Items.ENCHANTED_BOOK);
    book.set(DataComponents.ITEM_NAME, Component.translatable("item.minecraft.enchanted_book"));
    book.set(DataComponents.LORE, new ItemLore(
        List.of(Component.translatable("enchantment.minecraft.sharpness"))));
    Map<String, String> words = Map.of(
        "item.minecraft.enchanted_book", "附魔书",
        "enchantment.minecraft.sharpness", "锋利");

    assertTrue(UltsItemNames.matches(book, "锋利", words));
    assertTrue(UltsItemNames.matches(book, "附魔书", words));
    // Without a word for it there is nothing to match, and a plain stack carries nothing at all.
    assertFalse(UltsItemNames.matches(book, "锋利", Map.of("item.minecraft.enchanted_book", "书")));
    assertEquals("", UltsItemNames.data(UltsTestBootstrap.stack(Items.STONE), "en_us"));
  }

  @Test
  void theNamesBakedIntoTheJarCanBeRead() throws Exception {
    // The build bakes the game's own names for every language the mod ships; a build machine without
    // the game's files leaves them out, which is a build this test has nothing to say about.
    Map<String, String> chinese = baked("zh_cn");
    assumeTrue(chinese != null, "this build carries no baked Chinese names");
    assertTrue(chinese.size() > 2_000, "baked names: " + chinese.size());
    assertEquals("钻石", chinese.get("item.minecraft.diamond"));
    assertEquals("潜影盒", chinese.get("block.minecraft.shulker_box"));
    assertEquals("附魔书", chinese.get("item.minecraft.enchanted_book"));

    // English comes out of the game jar rather than the asset index, so it is baked by another path.
    Map<String, String> english = baked("en_us");
    assumeTrue(english != null, "this build carries no baked English names");
    assertEquals("Diamond", english.get("item.minecraft.diamond"));
    assertEquals("Shulker Box", english.get("block.minecraft.shulker_box"));
  }

  /** The words one language was baked with, or null while this build baked none of it. */
  private static Map<String, String> baked(String locale) throws Exception {
    var stream = UltsItemNamesTest.class.getResourceAsStream(
        "/assets/ultimate-storage/itemnames/" + locale + ".json");
    if (stream == null) {
      return null;
    }
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return UltsItemNames.read(reader);
    }
  }
}
