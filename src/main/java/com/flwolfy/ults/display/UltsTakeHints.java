package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.state.UltsBoxes;
import com.flwolfy.ults.data.state.UltsStoredView;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What the lore of a row promises about the clicks it answers.
 *
 * <p>A row lists every click it answers, one line each, and never lists one it cannot serve: the box
 * line is left out altogether while a full box could not be packed — not enough of the item, no box
 * stored or craftable, or the thing being a shulker box itself — and a bag row lists its right click
 * only, because a bag is opened rather than emptied here and everything inside it is taken from inside
 * it, where the whole bag is in view.
 *
 * <p>What a left click takes is always said as a number, and that number is what the click really takes
 * from the storage as it stands: a whole stack while a stack of it is there, and the few pieces that are
 * left while there are fewer than a stack of them. A click that
 * still arrives after the storage changed under it is answered too: it takes what is really there, and
 * the row redraws with the new number.
 *
 * <p>The lines cannot follow the shift key while the screen is open, and this is worth knowing: opening
 * a screen makes the client release every key ({@code Gui.setScreen} calls {@code KeyMapping.releaseAll})
 * and it stops tracking keys at all until the screen closes, so the input it reports to the server says
 * "no shift" however the player holds the key. Every line a row answers is therefore listed at once.
 */
final class UltsTakeHints {

  /** How many stacks one shulker box holds. */
  static final int SHULKER_SLOTS = 27;
  /** The line that carries the number a left click takes. */
  static final String TAKE = "ults.gui.take.count";
  static final String SHIFT_BOX = "ults.gui.shift.box";
  static final String SHIFT_ALL = "ults.gui.shift.all";

  private UltsTakeHints() {}

  /**
   * How much one left click takes: what the storage holds and could craft, capped at one stack of it.
   *
   * <p>An amount that is not worked out yet is not a small one, so it is promised as at least a piece,
   * which is also what the click takes once the answer is in.
   *
   * @param obtainable how much of it the storage holds or could craft
   * @param maxStack how many one stack of it holds
   * @return the number a left click takes
   */
  static long takenByLeftClick(long obtainable, int maxStack) {
    return Math.max(1L, Math.min(obtainable, Math.max(1, maxStack)));
  }

  /**
   * Whether one whole box of an item could be packed at all.
   *
   * <p>A box is twenty-seven full stacks of the thing and a box to put them in, so this only asks the
   * first half of the question: whether enough of the item is on hand, and whether the thing may go
   * into a box in the first place. Whether a box is there to be filled, or could be crafted, is asked
   * of the storage by {@link #boxPossible}, which knows the contents the row was drawn from.
   *
   * @param template what the row stands for
   * @param obtainable how much of it the storage holds or could craft
   * @return whether a full box of it is within reach
   */
  static boolean boxReachable(ItemStack template, long obtainable) {
    int maxStack = template.getMaxStackSize();
    // A shulker box cannot be packed into a shulker box, however many of them are stored.
    return maxStack > 0
        && !UltsBoxes.isShulker(template)
        && obtainable >= (long) SHULKER_SLOTS * maxStack;
  }

  /**
   * Whether one whole box of an item could be handed over right now, which is what the box line stands
   * for.
   *
   * <p>Enough of the item has to be on hand, the thing has to be allowed into a box, and a box has to
   * be there to fill — either one the storage already holds or a plain one it could craft. Nothing is
   * resolved here: the box is packed for real on the click, and a click that cannot be served says so.
   *
   * @param runtime the storage the row was drawn from
   * @param template what the row stands for
   * @param stock contents to craft from, so a screen can reuse the list it already read
   * @param obtainable how much of it the storage holds or could craft
   * @param boxInStock whether the storage holds a box that could be filled, asked once per screen
   * @return whether the box line may stand on this row
   */
  static boolean boxPossible(
      UltsRuntime runtime,
      ItemStack template,
      List<UltsStoredView> stock,
      long obtainable,
      boolean boxInStock
  ) {
    if (!boxReachable(template, obtainable)) {
      return false;
    }
    return boxInStock || runtime.craftable(Items.SHULKER_BOX.getDefaultInstance(), stock) > 0;
  }

  /**
   * The keys of the lines a row of a listing shows, in the order they are drawn.
   *
   * <p>The line about taking everything is left out while the configuration does not allow it: an offer a
   * click cannot keep is not an offer, and a server that turns it off should not have its rows keep
   * mentioning it.
   *
   * @param right the key of what a right click offers, which every row words for itself
   * @param boxPossible whether a whole box could be packed right now
   * @param takeAll whether the configuration allows taking everything at all
   * @return the keys, in the order they are drawn
   */
  static List<String> lines(String right, boolean boxPossible, boolean takeAll) {
    List<String> lines = new ArrayList<>(4);
    lines.add(TAKE);
    lines.add(right);
    if (boxPossible) {
      lines.add(SHIFT_BOX);
    }
    if (takeAll) {
      lines.add(SHIFT_ALL);
    }
    return List.copyOf(lines);
  }

  /**
   * The keys of the lines a bag row shows, in the order they are drawn.
   *
   * <p>One line, because a bag row answers one click: the right click that opens the bag. What is inside
   * is taken from inside, on rows of its own, and emptying the whole bag belongs to the bag's own screen.
   *
   * @param right the key of what a right click offers
   * @return the keys, in the order they are drawn
   */
  static List<String> bagLines(String right) {
    return List.of(right);
  }

  /**
   * Fills the click lines of a row of a listing.
   *
   * @param builder the row being built
   * @param template what the row stands for, for the size of one stack of it
   * @param obtainable how much of it the storage holds or could craft
   * @param right the key of what a right click offers, which every row words for itself
   * @param boxPossible whether a whole box could be packed right now
   * @param takeAll whether the configuration allows taking everything at all
   * @return the same row, for chaining
   */
  static GuiElementBuilder hints(
      GuiElementBuilder builder,
      ItemStack template,
      long obtainable,
      String right,
      boolean boxPossible,
      boolean takeAll
  ) {
    String taken = UltsGuiText.format(takenByLeftClick(obtainable, template.getMaxStackSize()));
    for (String key : lines(right, boxPossible, takeAll)) {
      builder.addLoreLine(TAKE.equals(key)
          ? UltsGuiText.text(key, taken).copy().withStyle(ChatFormatting.GRAY)
          : line(key));
    }
    return builder;
  }

  /**
   * Fills the click lines of a bag row, which opens the bag and answers nothing else.
   *
   * @param builder the bag row being built
   * @param right the key of what a right click offers
   * @return the same row, for chaining
   */
  static GuiElementBuilder bagHints(GuiElementBuilder builder, String right) {
    for (String key : bagLines(right)) {
      builder.addLoreLine(line(key));
    }
    return builder;
  }

  private static MutableComponent line(String key) {
    return UltsGuiText.text(key).copy().withStyle(ChatFormatting.GRAY);
  }
}
