package com.flwolfy.ults.data.state;

import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsStackRule;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Which kind of thing a stack is, which is what decides whether two of them are one.
 *
 * <p>An item and its name are the precondition either way: a diamond sword and a netherite sword are two
 * things, and so are a sword called one thing and the same sword called another. The configured
 * {@link UltsStackRule} says how much of the rest has to agree — every component, or only as much as a
 * player can read on the tooltip.
 *
 * <p>The same answer is used everywhere two stacks are compared: what pools into one stored row, what a
 * catalogue row counts as its own stock, and whether a stack is one a category can show at all. One rule
 * therefore means one behaviour, whichever screen is asking.
 */
public final class UltsStackKinds {

  /** What one stack's tooltip says, remembered per item and set of components: it is asked a lot. */
  private static final Map<TooltipKey, String> TOOLTIPS = new ConcurrentHashMap<>();

  /**
   * How many tooltips are remembered before the cache is dropped.
   *
   * <p>A key is one item with one set of components, so every differently worn, enchanted or named stack
   * a server ever compares is its own entry. Without a bound that is a cache which only ever grows; with
   * one, the worst a full cache costs is writing the tooltips out again.
   */
  private static final int MAX_TOOLTIPS = 20_000;

  /** Drops what is remembered, which a configuration reload does because the rule may have changed. */
  public static void clear() {
    TOOLTIPS.clear();
  }

  /** What a tooltip answer belongs to: the same components on another item read differently. */
  private record TooltipKey(net.minecraft.world.item.Item item, DataComponentPatch patch) {}

  private UltsStackKinds() {}

  /**
   * Whether two stacks are the same kind of thing, as the configuration defines it.
   *
   * @param first one stack
   * @param second another stack
   * @return whether the storage treats them as one
   */
  public static boolean same(ItemStack first, ItemStack second) {
    return of(first).equals(of(second));
  }

  /** The kind of thing a stack is, under the configured rule. */
  public static String of(ItemStack stack) {
    return of(stack, UltsConfigManager.getInstance().data().special().stackRule());
  }

  /**
   * The kind of thing a stack is, under a rule that is handed in.
   *
   * @param stack the stack in question
   * @param rule how much of a stack's data is part of its kind
   * @return a key that is equal exactly for two stacks of the same kind
   */
  public static String of(ItemStack stack, UltsStackRule rule) {
    String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    if (rule.byComponents()) {
      return item + "|" + stack.getComponentsPatch();
    }
    return item + "|" + tooltipOf(stack);
  }

  /**
   * What a stack's tooltip says, as the words a player would read rather than as rendered text.
   *
   * <p>The lines are written out unresolved, so the same stack says the same thing whatever language
   * the player who stored it had: an enchantment is its key and its level, and the comparison never
   * depends on who is looking.
   *
   * <p>Every stack is written out the same way, a plain one included: an item says things about itself
   * whatever its components are — a sword its attack damage — so a stack whose only difference is a
   * component the tooltip does not show has to be written out as the same words, not as no words at all.
   * The answer is remembered per item and set of components, which is what keeps a screen asking about
   * every row of a listing cheap.
   */
  private static String tooltipOf(ItemStack stack) {
    if (TOOLTIPS.size() >= MAX_TOOLTIPS) {
      TOOLTIPS.clear();
    }
    return TOOLTIPS.computeIfAbsent(
        new TooltipKey(stack.getItem(), stack.getComponentsPatch()), ignored -> {
          StringBuilder text = new StringBuilder();
          for (Component line : stack.getTooltipLines(
              net.minecraft.world.item.Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL)) {
            text.append(line.getString()).append('\n');
          }
          return text.toString();
        });
  }
}
