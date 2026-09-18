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
  private final String langKey;
  private final ItemStack icon;
  private final Map<Item, List<ItemStack>> entries;
  private final List<ItemStack> templates;
  private final boolean special;

  UltsItemCategory(
      String id,
      Component displayName,
      String langKey,
      ItemStack icon,
      Map<Item, List<ItemStack>> entries,
      List<ItemStack> templates,
      boolean special
  ) {
    this.id = id;
    this.displayName = displayName.copy();
    this.langKey = langKey == null ? "" : langKey;
    this.icon = icon.copyWithCount(1);
    this.entries = entries;
    this.templates = templates;
    this.special = special;
  }

  public String id() {
    return id;
  }

  public Component displayName() {
    return langKey.isEmpty()
        ? displayName.copy() : UltsLangManager.getInstance().text(langKey);
  }

  public ItemStack icon() {
    return icon.copyWithCount(1);
  }

  // Read-only catalog listing in creative-tab order; never mutate the returned stacks.
  public List<ItemStack> templates() {
    return templates;
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
