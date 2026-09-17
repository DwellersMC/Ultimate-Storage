package com.flwolfy.ults.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public final class UltsModMenu implements ModMenuApi {
  @Override
  public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return FabricLoader.getInstance().isModLoaded("cloth-config2")
        ? UltsConfigScreen::create : parent -> parent;
  }
}
