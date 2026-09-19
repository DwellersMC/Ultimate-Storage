package com.flwolfy.ults.command;

import static com.flwolfy.ults.util.UltsTextBuilder.failure;
import static com.flwolfy.ults.util.UltsTextBuilder.success;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.crafting.UltsCraftCatalog;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsBinding;
import com.flwolfy.ults.display.UltsStorageSGUI;
import com.flwolfy.ults.input.UltsInputManager;
import com.flwolfy.ults.util.UltsTextBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class UltsCommand {

  private static final int PAGE_SIZE = 8;
  private static final double REACH = 6.0D;

  private UltsCommand() {}

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("ults")
        .executes(UltsCommand::open)
        .then(Commands.literal("bind")
            .requires(UltsCommand::can)
            .executes(context -> bindLookedAt(context, ""))
            .then(Commands.argument("note", StringArgumentType.string())
                .executes(context -> bindLookedAt(
                    context, StringArgumentType.getString(context, "note")))))
        .then(Commands.literal("bindarea")
            .requires(UltsCommand::can)
            .then(Commands.argument("first", BlockPosArgument.blockPos())
                .then(Commands.argument("second", BlockPosArgument.blockPos())
                    .executes(context -> bindArea(context, ""))
                    .then(Commands.argument("note", StringArgumentType.string())
                        .executes(context -> bindArea(
                            context, StringArgumentType.getString(context, "note")))))))
        .then(Commands.literal("delete")
            .requires(UltsCommand::can)
            .executes(UltsCommand::deleteLookedAt)
            .then(Commands.argument("selection", StringArgumentType.string())
                .executes(context -> deleteSelection(
                    context, StringArgumentType.getString(context, "selection")))))
        .then(Commands.literal("show")
            .requires(UltsCommand::can)
            .executes(context -> highlight(context, true)))
        .then(Commands.literal("hide")
            .requires(UltsCommand::can)
            .executes(context -> highlight(context, false)))
        .then(Commands.literal("list")
            .executes(context -> list(context, 1))
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(context -> list(
                    context, IntegerArgumentType.getInteger(context, "page")))))
        .then(Commands.literal("reload")
            .requires(UltsCommand::can)
            .executes(UltsCommand::reload)));
  }

  // ================== //
  // ====== Open ====== //
  // ================== //

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

  // ================== //
  // ====== Bind ====== //
  // ================== //

  private static int bindLookedAt(CommandContext<CommandSourceStack> context, String note) {
    ServerPlayer player = context.getSource().getPlayer();
    UltsRuntime runtime = runtime(context);
    if (player == null || runtime == null) {
      if (player == null) {
        context.getSource().sendFailure(failure(text("ults.command.player_only")));
      }
      return 0;
    }
    HitResult hit = player.pick(REACH, 1.0F, false);
    if (!(hit instanceof BlockHitResult blockHit)
        || hit.getType() != HitResult.Type.BLOCK) {
      context.getSource().sendFailure(failure(text("ults.command.bind.no_target")));
      return 0;
    }
    UltsInputManager.BindResult result = runtime.inputs().bind(
        player.getUUID().toString(), note, player.level(), blockHit.getBlockPos());
    if (result != UltsInputManager.BindResult.SUCCESS) {
      context.getSource().sendFailure(failure(text(bindKey(result))));
      return 0;
    }
    String remark = UltsInputManager.normalizeNote(note);
    context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
        text("ults.command.bind.success"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        noteSuffix(remark))), false);
    return 1;
  }

  private static int bindArea(CommandContext<CommandSourceStack> context, String note) {
    ServerPlayer player = context.getSource().getPlayer();
    UltsRuntime runtime = runtime(context);
    if (runtime == null) {
      return 0;
    }
    ServerLevel level = player == null ? context.getSource().getLevel() : player.level();
    BlockPos first = BlockPosArgument.getBlockPos(context, "first");
    BlockPos second = BlockPosArgument.getBlockPos(context, "second");
    UltsInputManager.AreaResult result = runtime.inputs().bindArea(
        player == null ? "" : player.getUUID().toString(), note, level, first, second);
    if (result.added() == 0 && result.result() != UltsInputManager.BindResult.SUCCESS) {
      context.getSource().sendFailure(failure(text(bindKey(result.result()))));
      return 0;
    }
    if (result.added() == 0) {
      // Nothing new to bind: every container of the selection is bound already.
      context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
          text("ults.command.bindarea.none"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
          result.found())), false);
      return 1;
    }
    String remark = UltsInputManager.normalizeNote(note);
    if (result.result() == UltsInputManager.BindResult.SUCCESS) {
      context.getSource().sendSuccess(() -> success(result.skipped() == 0
          ? UltsTextBuilder.format(text("ults.command.bindarea.success"), UltsTextBuilder.TEXT,
              UltsTextBuilder.HIGHLIGHT, result.added(), noteSuffix(remark))
          : UltsTextBuilder.format(text("ults.command.bindarea.skipped"), UltsTextBuilder.TEXT,
              UltsTextBuilder.HIGHLIGHT, result.added(), noteSuffix(remark), result.skipped())),
          false);
      return 1;
    }
    context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
        text("ults.command.bindarea.partial"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        noteSuffix(remark), result.added(), result.skipped(),
        UltsConfigManager.getInstance().data().input().maxBindings())), false);
    return 1;
  }

  /** "（备注：x）" or nothing at all, so one message covers binding with and without a note. */
  private static Component noteSuffix(String note) {
    return note == null || note.isEmpty()
        ? Component.empty()
        : text("ults.command.note", note).copy().withStyle(UltsTextBuilder.HIGHLIGHT);
  }

  private static String bindKey(UltsInputManager.BindResult result) {
    return "ults.command.bind." + result.name().toLowerCase(Locale.ROOT);
  }

  // ==================== //
  // ====== Delete ====== //
  // ==================== //

  /** Deletes the 1-based inclusive selection, which always has to be written with a leading #. */
  private static int deleteSelection(CommandContext<CommandSourceStack> context, String selection) {
    UltsRuntime runtime = runtime(context);
    if (runtime == null) {
      return 0;
    }
    if (!selection.startsWith("#")) {
      context.getSource().sendFailure(failure(text("ults.command.delete.needs_hash", selection)));
      return 0;
    }
    int total = runtime.state().bindingCount();
    if (total == 0) {
      context.getSource().sendFailure(failure(text("ults.command.list.empty")));
      return 0;
    }
    int[] range = parseSelection(selection);
    if (range == null) {
      context.getSource().sendFailure(failure(text("ults.command.delete.invalid", selection)));
      return 0;
    }
    if (range[0] < 1 || range[1] > total || range[0] > range[1]) {
      context.getSource().sendFailure(failure(text(
          "ults.command.delete.out_of_range", range[0], range[1], total)));
      return 0;
    }
    List<UltsBinding> removed = runtime.inputs().remove(range[0], range[1]);
    if (removed.isEmpty()) {
      context.getSource().sendFailure(failure(text("ults.command.delete.out_of_range",
          range[0], range[1], total)));
      return 0;
    }
    if (range[0] == range[1]) {
      UltsBinding binding = removed.getFirst();
      context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
          text("ults.command.delete.one"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
          range[0], noteOrNone(binding))), false);
      return 1;
    }
    context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
        text("ults.command.delete.range"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        range[0], range[1], removed.size())), false);
    return 1;
  }

  private static Component noteOrNone(UltsBinding binding) {
    return binding.note().isEmpty()
        ? text("ults.command.note.none")
        : Component.literal(binding.note()).withStyle(UltsTextBuilder.HIGHLIGHT);
  }

  /** Accepts {@code #3} and {@code #3-#5}; the leading {@code #} is mandatory. */
  private static int[] parseSelection(String raw) {
    if (!raw.startsWith("#")) {
      return null;
    }
    String value = raw.substring(1);
    int dash = value.indexOf('-');
    try {
      if (dash < 0) {
        int single = Integer.parseInt(value.trim());
        return new int[] {single, single};
      }
      String from = value.substring(0, dash).trim();
      String to = value.substring(dash + 1).trim();
      if (to.startsWith("#")) {
        to = to.substring(1).trim();
      }
      return new int[] {Integer.parseInt(from), Integer.parseInt(to)};
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  // ================== //
  // ====== List ====== //
  // ================== //

  private static int list(CommandContext<CommandSourceStack> context, int requestedPage) {
    UltsRuntime runtime = runtime(context);
    if (runtime == null) {
      return 0;
    }
    List<UltsBinding> bindings = runtime.state().bindings();
    int pages = Math.max(1, (bindings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.max(1, Math.min(requestedPage, pages));
    // Offer the delete click only to sources that are allowed to run it.
    boolean deletable = can(context.getSource());
    MutableComponent message = header(UltsTextBuilder.format(
        text("ults.command.list.title"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        bindings.size()));
    if (bindings.isEmpty()) {
      message.append(Component.literal("\n"));
      message.append(text("ults.command.list.empty").copy().withStyle(UltsTextBuilder.TEXT));
    }
    int start = (page - 1) * PAGE_SIZE;
    int end = Math.min(start + PAGE_SIZE, bindings.size());
    for (int index = start; index < end; index++) {
      message.append(Component.literal("\n"));
      message.append(entry(bindings.get(index), index + 1, deletable));
    }
    return send(context, message, page, pages);
  }

  /** One "{@code #N <维度, (x, y, z)> 备注}" line, clickable while it can be deleted. */
  private static Component entry(UltsBinding binding, int number, boolean deletable) {
    MutableComponent line = Component.literal("#" + number + " ")
        .withStyle(UltsTextBuilder.HIGHLIGHT);
    line.append(Component.literal(information(binding)).withStyle(UltsTextBuilder.HIGHLIGHT));
    // The note belongs to the content of the entry, right after the coordinates.
    line.append(Component.literal(" "));
    line.append(binding.note().isEmpty()
        ? text("ults.command.note.none").copy().withStyle(UltsTextBuilder.SHADE)
        : Component.literal(binding.note()).withStyle(UltsTextBuilder.HIGHLIGHT));
    if (!deletable) {
      return line;
    }
    return UltsTextBuilder.commandText(
        line,
        UltsTextBuilder.format(text("ults.command.list.hover.binding"), number,
            binding.note().isEmpty() ? text("ults.command.note.none") : binding.note()),
        "/ults delete #" + number);
  }

  private static MutableComponent header(Component title) {
    return Component.empty()
        .append(Component.literal("\n"))
        .append(text("ults.command.list.divider").copy().withStyle(UltsTextBuilder.TEXT))
        .append(Component.literal("\n"))
        .append(title);
  }

  private static int send(
      CommandContext<CommandSourceStack> context,
      MutableComponent message,
      int page,
      int pages
  ) {
    message.append(Component.literal("\n\n"));
    message.append(pageButtons(page, pages));
    message.append(Component.literal("\n"));
    message.append(text("ults.command.list.divider").copy().withStyle(UltsTextBuilder.TEXT));
    Component result = message;
    context.getSource().sendSuccess(() -> result, false);
    return 1;
  }

  private static MutableComponent pageButtons(int page, int pages) {
    MutableComponent buttons = Component.empty();
    if (page > 1) {
      buttons.append(UltsTextBuilder.commandText(
          Component.literal("\u23EA").withStyle(UltsTextBuilder.TEXT),
          UltsTextBuilder.format(text("ults.command.list.page"), page - 1),
          "/ults list " + (page - 1)));
    } else {
      buttons.append(Component.literal("\u23EA").withStyle(UltsTextBuilder.SHADE));
    }
    buttons.append(Component.literal(" | ").withStyle(UltsTextBuilder.TEXT));
    buttons.append(Component.literal("[" + page + "]").withStyle(UltsTextBuilder.HIGHLIGHT));
    buttons.append(Component.literal(" / " + pages + " | ").withStyle(UltsTextBuilder.TEXT));
    if (page < pages) {
      buttons.append(UltsTextBuilder.commandText(
          Component.literal("\u23E9").withStyle(UltsTextBuilder.TEXT),
          UltsTextBuilder.format(text("ults.command.list.page"), page + 1),
          "/ults list " + (page + 1)));
    } else {
      buttons.append(Component.literal("\u23E9").withStyle(UltsTextBuilder.SHADE));
    }
    return buttons;
  }

  private static String information(UltsBinding binding) {
    Identifier id = Identifier.tryParse(binding.dimension());
    String dimension = id == null ? binding.dimension() : id.getPath();
    return text("ults.command.list.info", dimension, binding.x(), binding.y(), binding.z())
        .getString();
  }

  // ==================== //
  // ====== Delete ====== //
  // ==================== //

  /** Removes the binding of the container the player is looking at, without naming anything. */
  private static int deleteLookedAt(CommandContext<CommandSourceStack> context) {
    ServerPlayer player = context.getSource().getPlayer();
    UltsRuntime runtime = runtime(context);
    if (player == null || runtime == null) {
      if (player == null) {
        context.getSource().sendFailure(failure(text("ults.command.player_only")));
      }
      return 0;
    }
    UltsBinding binding = lookedAtBinding(player, runtime);
    if (binding == null) {
      context.getSource().sendFailure(failure(text("ults.command.delete.no_target")));
      return 0;
    }
    int index = runtime.state().number(binding);
    if (index <= 0 || runtime.inputs().remove(index, index).isEmpty()) {
      context.getSource().sendFailure(failure(text("ults.command.delete.no_target")));
      return 0;
    }
    context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
        text("ults.command.delete.one"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        index, noteOrNone(binding))), false);
    return 1;
  }

  /** The binding of the block the player looks at, or {@code null} when there is none. */
  private static UltsBinding lookedAtBinding(ServerPlayer player, UltsRuntime runtime) {
    HitResult hit = player.pick(REACH, 1.0F, false);
    if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
      return null;
    }
    // Any part of a large container counts, not only the block that was bound.
    return runtime.bindingAt(player.level(), blockHit.getBlockPos());
  }

  // ====================== //
  // ====== Highlights ==== //
  // ====================== //

  /** The highlight is a personal view: every player toggles their own frames. */
  private static int highlight(CommandContext<CommandSourceStack> context, boolean enabled) {
    ServerPlayer player = context.getSource().getPlayer();
    UltsRuntime runtime = runtime(context);
    if (player == null || runtime == null) {
      if (player == null) {
        context.getSource().sendFailure(failure(text("ults.command.player_only")));
      }
      return 0;
    }
    runtime.highlights().setEnabled(player, enabled);
    if (enabled) {
      int visible = runtime.highlights().visible(
          runtime.server(), player, runtime.state().bindings());
      context.getSource().sendSuccess(() -> success(UltsTextBuilder.format(
          text("ults.command.show.enabled"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
          visible)), false);
      return 1;
    }
    context.getSource().sendSuccess(
        () -> success(text("ults.command.show.disabled")), false);
    return 1;
  }

  // =================== //
  // ====== Reload ===== //
  // =================== //

  private static int reload(CommandContext<CommandSourceStack> context) {
    boolean success = UltsConfigManager.getInstance().reload();
    if (success) {
      // The recipe list is server data, so a reload has to read it again as well.
      UltsRuntime runtime = UltsMod.getRuntime();
      if (runtime != null) {
        UltsCraftCatalog.rebuild(runtime.server());
        UltsMod.LOGGER.info(
            "UltStorage reloaded; automatic crafting is {}", runtime.craftingMode());
      }
      context.getSource().sendSuccess(
          () -> success(text("ults.command.reload.success")), false);
      return 1;
    }
    context.getSource().sendFailure(failure(text("ults.command.reload.failed")));
    return 0;
  }

  /** Binding, deleting, the highlight and reloading all need the one configured permission level. */
  private static boolean can(CommandSourceStack source) {
    return source.getEntity() == null || UltsRuntime.canManage(source.permissions());
  }

  private static UltsRuntime runtime(CommandContext<CommandSourceStack> context) {
    UltsRuntime runtime = UltsMod.getRuntime();
    if (runtime == null) {
      context.getSource().sendFailure(failure(text("ults.command.not_ready")));
    }
    return runtime;
  }

  private static Component text(String key, Object... arguments) {
    return UltsLangManager.getInstance().text(key, arguments);
  }
}
