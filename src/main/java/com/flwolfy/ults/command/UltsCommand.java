package com.flwolfy.ults.command;

import static com.flwolfy.ults.util.UltsTextBuilder.failure;
import static com.flwolfy.ults.util.UltsTextBuilder.info;
import static com.flwolfy.ults.util.UltsTextBuilder.success;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsTerminal;
import com.flwolfy.ults.display.UltsStorageSGUI;
import com.flwolfy.ults.input.UltsInputManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

public final class UltsCommand {

  private static final int PAGE_SIZE = 8;

  private UltsCommand() {}

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("ults")
        .executes(UltsCommand::open)
        .then(Commands.literal("create")
            .requires(UltsCommand::canManage)
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(UltsCommand::create)))
        .then(Commands.literal("delete")
            .requires(UltsCommand::canManage)
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .suggests(UltsCommand::suggestNames)
                .executes(UltsCommand::delete)))
        .then(Commands.literal("list")
            .executes(context -> list(context, 1))
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(context -> list(
                    context, IntegerArgumentType.getInteger(context, "page")))))
        .then(Commands.literal("reload")
            .requires(UltsCommand::canManage)
            .executes(UltsCommand::reload)));
  }

  private static int open(CommandContext<CommandSourceStack> context) {
    ServerPlayer player = context.getSource().getPlayer();
    if (player == null) {
      context.getSource().sendFailure(failure(text("ults.command.player_only")));
      return 0;
    }
    UltsRuntime runtime = runtime(context);
    if (runtime == null) {
      return 0;
    }
    UltsStorageSGUI.open(player, runtime);
    return 1;
  }

  private static int create(CommandContext<CommandSourceStack> context) {
    ServerPlayer player = context.getSource().getPlayer();
    UltsRuntime runtime = runtime(context);
    if (player == null || runtime == null) {
      return 0;
    }
    String name = StringArgumentType.getString(context, "name");
    UltsInputManager.CreateResult result = runtime.inputs().create(player, name);
    if (result == UltsInputManager.CreateResult.SUCCESS) {
      context.getSource().sendSuccess(
          () -> success(text("ults.command.create.success", name)), false);
      return 1;
    }
    context.getSource().sendFailure(failure(text(
        "ults.command.create." + result.name().toLowerCase(java.util.Locale.ROOT))));
    return 0;
  }

  private static int delete(CommandContext<CommandSourceStack> context) {
    UltsRuntime runtime = runtime(context);
    if (runtime == null) {
      return 0;
    }
    String name = StringArgumentType.getString(context, "name");
    UltsTerminal removed = runtime.inputs().delete(name);
    if (removed == null) {
      context.getSource().sendFailure(failure(text("ults.command.delete.not_found", name)));
      return 0;
    }
    context.getSource().sendSuccess(
        () -> success(text("ults.command.delete.success", removed.name())), false);
    return 1;
  }

  private static int list(CommandContext<CommandSourceStack> context, int requestedPage) {
    UltsRuntime runtime = runtime(context);
    if (runtime == null) {
      return 0;
    }
    List<UltsTerminal> terminals = runtime.state().terminals().stream()
        .sorted(Comparator.comparing(UltsTerminal::name, String.CASE_INSENSITIVE_ORDER)).toList();
    int pages = Math.max(1, (terminals.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.min(requestedPage, pages);
    context.getSource().sendSuccess(() -> info(text(
        "ults.command.list.header", terminals.size(), page, pages)), false);
    terminals.stream().skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).forEach(terminal ->
        context.getSource().sendSuccess(() -> info(text(
            "ults.command.list.entry",
            terminal.name(), terminal.dimension(), terminal.x(), terminal.y() + 1, terminal.z()
        )), false));
    return 1;
  }

  private static int reload(CommandContext<CommandSourceStack> context) {
    boolean success = UltsConfigManager.getInstance().reload();
    if (success) {
      context.getSource().sendSuccess(
          () -> success(text("ults.command.reload.success")), false);
      return 1;
    }
    context.getSource().sendFailure(failure(text("ults.command.reload.failed")));
    return 0;
  }

  private static CompletableFuture<Suggestions> suggestNames(
      CommandContext<CommandSourceStack> context,
      SuggestionsBuilder builder
  ) {
    UltsRuntime runtime = UltsMod.getRuntime();
    return runtime == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(
        runtime.state().terminals().stream().map(UltsTerminal::name).toList(), builder);
  }

  private static boolean canManage(CommandSourceStack source) {
    if (source.getEntity() == null) {
      return true;
    }
    PermissionSet permissions = source.permissions();
    if (permissions == PermissionSet.ALL_PERMISSIONS) {
      return true;
    }
    int required = UltsConfigManager.getInstance().data().input().createPermissionLevel();
    return permissions instanceof LevelBasedPermissionSet levels
        && levels.level().isEqualOrHigherThan(PermissionLevel.byId(required));
  }

  private static UltsRuntime runtime(CommandContext<CommandSourceStack> context) {
    UltsRuntime runtime = UltsMod.getRuntime();
    if (runtime == null) {
      context.getSource().sendFailure(failure(text("ults.command.not_ready")));
    }
    return runtime;
  }

  private static net.minecraft.network.chat.Component text(String key, Object... arguments) {
    return UltsLangManager.getInstance().text(key, arguments);
  }
}
