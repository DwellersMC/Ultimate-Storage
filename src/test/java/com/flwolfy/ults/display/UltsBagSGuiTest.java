package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * What opening a bag lays out: the search the player typed in the storage screen is still on, so a bag
 * shows the stacks it keeps and not only the ones it holds.
 */
class UltsBagSGuiTest {

  @Test
  void aBagShowsWhatTheSearchLeft() {
    UltsTestBootstrap.boot();
    UltsStoredView sharp = new UltsStoredView(named(Items.DIAMOND_SWORD, "锋利之剑"), 1, true, 100L);
    UltsStoredView plain = new UltsStoredView(named(Items.DIAMOND_SWORD, "普通之剑"), 1, true, 200L);
    List<UltsStoredView> kept = List.of(plain, sharp);

    // No search, or a search that was cleared, leaves the bag whole and in its own order.
    assertEquals(kept, UltsBagSGUI.shown(kept, "", "zh_cn"));
    assertEquals(kept, UltsBagSGUI.shown(kept, null, "zh_cn"));
    // A search keeps the stack it names and hides the rest, by name, by id or by what the stack carries.
    assertEquals(List.of(sharp), UltsBagSGUI.shown(kept, "锋利", "zh_cn"));
    assertEquals(List.of(plain, sharp), UltsBagSGUI.shown(kept, "minecraft:diamond_sword", "zh_cn"));
    assertEquals(List.of(), UltsBagSGUI.shown(kept, "南瓜", "zh_cn"));
  }

  private static ItemStack named(Item item, String name) {
    ItemStack stack = UltsTestBootstrap.stack(item);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return stack;
  }
}
