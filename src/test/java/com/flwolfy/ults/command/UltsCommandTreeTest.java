package com.flwolfy.ults.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UltsCommandTreeTest {

  @BeforeAll
  static void bootstrapRegistries() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void exposesBindBindAreaDeleteListReloadAndHighlight() {
    CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    UltsCommand.register(dispatcher);

    CommandNode<CommandSourceStack> ults = dispatcher.getRoot().getChild("ults");
    assertNotNull(ults);
    assertNull(ults.getChild("create"));
    assertNull(ults.getChild("open"));
    // remove was merged into delete.
    assertNull(ults.getChild("remove"));
    assertNotNull(ults.getChild("bind"));
    assertNotNull(ults.getChild("bindarea"));
    assertNotNull(ults.getChild("delete"));
    assertNotNull(ults.getChild("list"));
    assertNotNull(ults.getChild("show"));
    assertNotNull(ults.getChild("hide"));
    assertNotNull(ults.getChild("reload"));
    // /ults without arguments opens the one storage.
    assertNotNull(ults.getCommand());
  }

  @Test
  void bindTakesAnOptionalNote() {
    CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    UltsCommand.register(dispatcher);
    CommandNode<CommandSourceStack> bind = dispatcher.getRoot().getChild("ults").getChild("bind");

    // /ults bind alone binds the targeted container without a note.
    assertNotNull(bind.getCommand());
    CommandNode<CommandSourceStack> note = bind.getChild("note");
    assertNotNull(note);
    assertNotNull(note.getCommand());
    assertEquals(0, note.getChildren().size());
  }

  @Test
  void bindAreaTakesTwoCornersAndAnOptionalNote() {
    CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    UltsCommand.register(dispatcher);
    CommandNode<CommandSourceStack> area = dispatcher.getRoot().getChild("ults")
        .getChild("bindarea");

    CommandNode<CommandSourceStack> first = area.getChild("first");
    assertNotNull(first);
    CommandNode<CommandSourceStack> second = first.getChild("second");
    assertNotNull(second);
    assertNotNull(second.getCommand());
    CommandNode<CommandSourceStack> note = second.getChild("note");
    assertNotNull(note);
    assertNotNull(note.getCommand());
    assertEquals(0, note.getChildren().size());
  }

  @Test
  void removeTakesASelection() {
    CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    UltsCommand.register(dispatcher);
    CommandNode<CommandSourceStack> delete = dispatcher.getRoot().getChild("ults")
        .getChild("delete");

    // /ults delete alone removes what the player looks at ...
    assertNotNull(delete.getCommand());
    // ... and /ults delete #3 or #3-#5 removes that selection.
    CommandNode<CommandSourceStack> selection = delete.getChild("selection");
    assertNotNull(selection);
    assertNotNull(selection.getCommand());
    assertEquals(0, selection.getChildren().size());
  }

  @Test
  void listTakesAnOptionalPage() {
    CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    UltsCommand.register(dispatcher);
    CommandNode<CommandSourceStack> list = dispatcher.getRoot().getChild("ults").getChild("list");

    assertNotNull(list.getCommand());
    assertNotNull(list.getChild("page").getCommand());
    assertEquals(0, list.getChild("page").getChildren().size());
  }

  @Test
  void showAndHideToggleTheBindingHighlight() {
    CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    UltsCommand.register(dispatcher);
    CommandNode<CommandSourceStack> ults = dispatcher.getRoot().getChild("ults");

    assertNotNull(ults.getChild("show").getCommand());
    assertNotNull(ults.getChild("hide").getCommand());
    assertEquals(0, ults.getChild("show").getChildren().size());
  }
}
