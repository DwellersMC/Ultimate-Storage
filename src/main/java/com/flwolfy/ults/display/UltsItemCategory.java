package com.flwolfy.ults.display;

import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class UltsItemCategory {

  private final String id;
  private final Component displayName;
  private final ItemStack icon;
  private final Map<Item, List<ItemStack>> entries;
  private final boolean special;

  UltsItemCategory(
      String id,
      Component displayName,
      ItemStack icon,
      Map<Item, List<ItemStack>> entries,
      boolean special
  ) {
    this.id = id;
    this.displayName = displayName.copy();
    this.icon = icon.copyWithCount(1);
    this.entries = entries;
    this.special = special;
  }

  public String id() {
    return id;
  }

  public Component displayName() {
    return special
        ? UltsLangManager.getInstance().text("ults.gui.category.special_nbt")
        : displayName.copy();
  }

  public ItemStack icon() {
    return icon.copyWithCount(1);
  }

  public boolean accepts(UltsStoredView view) {
    if (special) {
      return view.special();
    }
    if (view.special()) {
      return false;
    }
    return entries.getOrDefault(view.template().getItem(), List.of()).stream()
        .anyMatch(candidate -> ItemStack.isSameItemSameComponents(candidate, view.template()));
  }

  public boolean special() {
    return special;
  }
}
