package com.flwolfy.ults;

import com.flwolfy.ults.crafting.UltsCraftCatalog;
import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.crafting.UltsCraftPool;
import com.flwolfy.ults.crafting.UltsCraftResolver;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.data.lang.UltsItemNames;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsBinding;
import com.flwolfy.ults.data.state.UltsBoxes;
import com.flwolfy.ults.data.state.UltsRemoteStorage;
import com.flwolfy.ults.data.state.UltsStackKinds;
import com.flwolfy.ults.data.state.UltsState;
import com.flwolfy.ults.data.state.UltsWithdrawalPlanner;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsSpecialFilters;
import com.flwolfy.ults.data.state.UltsWithdrawalOutput;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
import com.flwolfy.ults.data.state.UltsWithdrawalResult;
import com.flwolfy.ults.display.UltsBagSGUI;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.display.UltsStorageSGUI;
import com.flwolfy.ults.display.UltsSurvivalItems;
import com.flwolfy.ults.display.UltsTakeAllStreams;
import com.flwolfy.ults.display.UltsTakeAllSGUI;
import com.flwolfy.ults.display.UltsWithdrawSGUI;
import com.flwolfy.ults.input.UltsContainers;
import com.flwolfy.ults.input.UltsInputManager;
import com.flwolfy.ults.util.UltsTextBuilder;
import com.flwolfy.ults.visual.UltsHighlights;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

public final class UltsRuntime {

  /** How often the "could this be crafted" answers are worked out again while contents keep changing. */
  private static final long CRAFTABLE_REFRESH_TICKS = 20;

  /**
   * How long one tick may spend working craftable amounts out before the rest waits for the next one.
   *
   * <p>A screen asks for a whole page of them at once and every answer can be a search of its own, so
   * a large pack must not be allowed to spend a whole tick's worth on one redraw. Rows that did not
   * get their turn are answered on the ticks after this one.
   */
  private static final long CRAFTABLE_NANOS_PER_TICK = 30_000_000L;

  /**
   * How many rows one view may leave waiting before a screen settles for the answers it has.
   *
   * <p>Redrawing until everything is answered is what keeps a row from being blank for long, but it
   * has to end even if some row never gets its turn, or a screen would redraw for ever.
   */
  private static final int MAX_CRAFTABLE_WAITS = 200;
  /** Component variants can be numerous; retain only recently requested exact crafting answers. */
  private static final int MAX_CRAFTING_ANSWERS = 20_000;

  /** How many slices the remote aggregate is computed in. */
  private static final int REMOTE_SHARDS = 8;

  /** How often one slice is recomputed while a screen is watching, in ticks. */
  private static final int REMOTE_SHARD_TICKS = 4;

  /** How long after the last query the slices keep being refreshed, in ticks. */
  private static final int REMOTE_WATCH_TICKS = 40;

  /** How often the "you are looking at binding #N" overlay is refreshed. */
  private static final int LOOK_REFRESH_TICKS = 10;

  /** How far a player may look to inspect or delete a binding. */
  private static final double LOOK_REACH = 6.0D;

  /** How long the "sneak to break it" notice stays quiet after being shown once, in ticks. */
  private static final int PROTECTED_NOTICE_TICKS = 40;

  private final MinecraftServer server;
  private final UltsState state;
  private final LongSupplier ticks;
  private record StockKind(Item item, String kind, boolean special, boolean packable) {}
  private final UltsStockStability<StockKind> stockStability = new UltsStockStability<>();
  private final UltsStockStability<PackingKind> craftingStability = new UltsStockStability<>();
  private List<UltsStoredView> lastStabilityStock;
  private Map<PackingKind, Long> lastStabilityQuantities = Map.of();
  private Map<StockKind, Long> lastKindQuantities = Map.of();
  private Map<Item, Map<PackingKind, Long>> lastItemQuantities = Map.of();
  private record CraftingMemo(Map<PackingKind, Long> inputs, UltsCraftingMode mode,
      long amount, boolean noStation) {}
  private final LinkedHashMap<PackingKind, CraftingMemo> craftingAnswers = new LinkedHashMap<>(64, 0.75f, true);
  private final UltsInputManager inputs;
  private final UltsHighlights highlights = new UltsHighlights();
  /** Stocks on their way out of the storage, a tick's worth at a time. */
  private final UltsTakeAllStreams streams = new UltsTakeAllStreams();
  /** Per player tick of the last break-protection notice, so it cannot flood the chat. */
  private final Map<UUID, Long> protectedNotices = new HashMap<>();
  private record BrokenPart(String dimension, BlockPos position) {}
  private final Map<BrokenPart, UltsBinding> breakingBindings = new HashMap<>();
  private record PackingKind(Item item, DataComponentPatch components) {}
  private final Map<PackingKind, Boolean> packingAnswers = new HashMap<>();
  private long packingFingerprint;
  private UltsCraftingMode packingMode;
  private final UltsRemoteStorage.ShardSnapshot[] remoteShards =
      new UltsRemoteStorage.ShardSnapshot[REMOTE_SHARDS];
  private long shardGeneration;
  private UltsRemoteStorage.Snapshot merged;
  private boolean shardsStale = true;
  private int shardCursor;
  private long shardTick = -1;
  private long remoteDemandTick = Long.MIN_VALUE / 2;
  private long remoteRevision;
  /** The view of the last contents, dropped as soon as contents or mode change. */
  private UltsCraftResolver craftableView;
  private UltsCraftResolver craftableLooseView;
  /** The pile the current views were built from, needed to build the station-less one later. */
  private UltsCraftPool craftableViewPool = new UltsCraftPool(4);
  private long craftableTick = Long.MIN_VALUE;
  /** The contents the last summary was taken from, and the summary itself. */
  private List<UltsStoredView> lastStock;
  private long lastStockFingerprint;
  private UltsCraftingMode craftableMode;
  private long craftableFingerprint;
  /** The tick this budget belongs to, how long it lasts, and whether anything is still waiting. */
  private long craftableBudgetTick = Long.MIN_VALUE;
  private long craftableDeadline = Long.MAX_VALUE;
  private boolean craftableDeferred;
  /** Whether the view the answers come from is behind the contents and has not caught up yet. */
  private boolean craftableViewStale;
  /** How many rows the current view has already left waiting. */
  private int craftableWaits;

  UltsRuntime(MinecraftServer server) {
    this(server, loadState(server), server::getTickCount);
    UltsMod.LOGGER.info("UltStorage automatic crafting is {}", craftingMode());
  }

  private static UltsState loadState(MinecraftServer server) {
    rebuildCatalogs(server);
    return server.overworld().getDataStorage().computeIfAbsent(UltsState.TYPE);
  }

  UltsRuntime(MinecraftServer server, UltsState state, LongSupplier ticks) {
    this.server = server;
    this.state = state;
    this.ticks = ticks;
    inputs = new UltsInputManager(server, state);
  }

  /**
   * Reads every catalogue the storage works from off the running server.
   *
   * <p>Called once at startup and again by {@code /ults reload}, so the two can never drift apart.
   *
   * <p>Where each catalogue comes from decides what a reload can pick up:
   *
   * <ul>
   *   <li>Loot tables and villager trades are opened straight from the server's resource manager, and
   *       the resource manager lists them afresh on every call, so a folder data pack that was edited
   *       or added to while the server runs is seen here without a restart.
   *   <li>The recipe manager and the creative tabs are snapshots the server took when it last loaded
   *       its data packs, so those two only move after a vanilla {@code /reload}.
   * </ul>
   *
   * <p>The equipment the survival catalogue finds in the loot tables is half of the special item
   * filter, so the filter is rebuilt from the freshly read set in the same breath. The other half, the
   * item ids the configuration names, comes from the configuration {@code /ults reload} reloads just
   * before this.
   *
   * @param server the running server
   */
  public static void rebuildCatalogs(MinecraftServer server) {
    UltsCreativeCatalog.rebuild(server);
    UltsSurvivalItems.rebuild(server);
    UltsCraftCatalog.rebuild(server);
    UltsSpecialFilters.rebuild();
    UltsMod.LOGGER.info(
        "UltStorage special filter: {} item id(s) named, filter mode {}, a bag holds {} stack(s)",
        UltsSpecialFilters.declaredSize(),
        UltsConfigManager.getInstance().data().special().filterMode(),
        UltsConfigManager.getInstance().data().special().bundleSlots());
  }

  /**
   * Reads everything again after the configuration or a data pack changed, which is what
   * {@code /ults reload} does.
   *
   * <p>Reading the catalogues is not quite enough for the special item filter: a filter that now
   * names an item has to clear out what the void storage already holds of it, or it would only ever
   * stop new arrivals and the storage would stay full of what it is meant to be rid of. Remote
   * storage keeps nothing of its own — its items sit in containers the storage does not own — so
   * there is nothing to clear and the filter only hides them from the listings.
   */
  public void reload() {
    reload(() -> rebuildCatalogs(server));
  }

  void reload(Runnable rebuild) {
    // The words a search is answered with are read from files too, so a language a server owner has
    // just dropped in is picked up by the same command that rereads everything else. What the stacking
    // rule remembered goes with them, because the rule itself may have changed.
    UltsItemNames.clear();
    UltsStackKinds.clear();
    rebuild.run();
    invalidateCaches();
    if (state.purgeFiltered()) {
      UltsMod.LOGGER.info("UltStorage destroyed the stored stacks the special filter now dismisses");
    }
  }

  /** Catalogues and rules changed, so even unchanged contents need new answers and remote slices. */
  void invalidateCaches() {
    craftingAnswers.clear();
    craftingStability.clear();
    stockStability.clear();
    lastStabilityStock = null;
    packingAnswers.clear();
    packingMode = null;
    craftableView = null;
    craftableLooseView = null;
    craftableViewPool = new UltsCraftPool(4);
    craftableMode = null;
    craftableTick = Long.MIN_VALUE;
    lastStock = null;
    craftableWaits = 0;
    craftableDeferred = false;
    craftableViewStale = false;
    craftableBudgetTick = Long.MIN_VALUE;
    invalidateRemote();
    state.invalidateViews();
  }

  /** Whether a withdrawal may go ahead with no room in the inventory, dropping what does not fit. */
  public boolean allowFullInventory() {
    return UltsConfigManager.getInstance().data().input().allowFullInventory();
  }

  /**
   * Hands a stack that could not be given to a player back to the storage it came from.
   *
   * <p>Void storage puts it back into its own pool. Remote storage has no pool of its own — its items sit
   * in the containers a player bound — so the stack goes back into those containers, and what will not fit
   * there is dropped at one of them. Writing it into the pool in remote mode would be writing it where
   * nothing reads: a listing made of bound containers would never show it again.
   *
   * @param stack the stack being handed back, which this call takes over
   */
  public void putBack(ItemStack stack) {
    if (stack.isEmpty()) {
      return;
    }
    if (remote()) {
      List<ItemStack> handing = List.of(stack);
      if (!UltsRemoteStorage.restore(server, state.bindings(), handing) && !stack.isEmpty()) {
        // Nothing in the warehouse and no drop beside it would take this remainder. Saying so is better
        // than pretending: it goes into the saved recovery queue, outside filters and bag limits.
        UltsMod.LOGGER.warn(
            "UltStorage could not put x{} {} back into the remote storage; it is queued for recovery",
            stack.getCount(), stack.getItem());
        state.keepRemoteRecovery(handing);
      }
    } else {
      state().deposit(stack);
    }
    stack.setCount(0);
  }

  /**
   * The stored rows of one item that carries data of its own, newest first: what the bag a special
   * row opens holds.
   *
   * <p>Void storage keeps one row per arrival, and those rows are exactly what the bag lists. Remote
   * storage has no rows of its own — its items sit in containers a player bound — so it lists the
   * variants it found there, one per combination of components.
   *
   * @param item the item whose bag is being shown
   * @return the rows, newest first
   */
  public List<UltsStoredView> specialsOf(Item item) {
    if (remote()) {
      return aggregate().items().stream()
          .filter(view -> view.special() && view.template().is(item))
          .toList();
    }
    return state.specialsOf(item);
  }

  /**
   * How many of a stack, components and all, a listing says the storage holds.
   *
   * @param template the stack to count, exactly as it is stored
   * @param stock the listing to count it in
   */
  public static long storedAmount(ItemStack template, List<UltsStoredView> stock) {
    // By the configured stacking rule, which is how a listing counts a row: the amount a row shows and the
    // amount a click can take have to be the same number.
    long amount = 0L;
    for (UltsStoredView view : stock) {
      if (UltsStackKinds.same(view.template(), template)) {
        amount = UltsCraftMath.add(amount, view.amount());
      }
    }
    return amount;
  }

  /**
   * The stacks a bag holds, newest first: one stack per stored thing, whole.
   *
   * <p>A stack is handed over as it is, so a named sword somebody stored comes back as that sword.
   * A row that holds more than one stack of the same thing is split, because a bag holds stacks and
   * never a pile.
   *
   * @param item the item whose bag is being shown
   * @return the stacks, newest first
   */
  public List<ItemStack> bagEntries(Item item) {
    List<ItemStack> entries = new ArrayList<>();
    for (UltsStoredView view : specialsOf(item)) {
      entries.addAll(UltsWithdrawalOutput.stacks(view.template(), view.amount()));
    }
    return entries;
  }

  /**
   * Takes up to one inventory of pieces from a bag row.
   *
   * <p>The pieces come back with their stored components, and an unfinished row retains its timestamp.
   * Remote storage withdraws them from the bound containers.
   *
   * @param item the item whose bag is being taken from
   * @param stack the stack that is leaving, components and all
   * @return the stacks that left, empty when none could be taken
   */
  public List<ItemStack> takeBagRow(Item item, ItemStack stack) {
    return takeBagPieces(item, stack, rowPieces(stack));
  }

  /** A bounded, non-crafting withdrawal from one bag row, evaluated from live stock. */
  public List<ItemStack> takeBagPieces(Item item, ItemStack stack, int quantity) {
    quantity = Math.min(quantity, rowPieces(stack.copyWithCount(quantity)));
    if (quantity <= 0 || !stack.is(item)) {
      return List.of();
    }
    if (remote()) {
      // Read live amounts and hand over every output stack within this bounded request.
      long available = UltsRemoteStorage.snapshot(server, state.bindings()).amount(stack);
      int count = (int) Math.min(quantity, available);
      List<ItemStack> taken = count <= 0 ? List.of()
          : takePlanned(stack.copyWithCount(1), count, false, UltsCraftingMode.DISABLED);
      invalidateRemote();
      return taken;
    }
    ItemStack taken = state.takeBagPieces(item, stack, quantity);
    return taken.isEmpty() ? List.of() : UltsWithdrawalOutput.stacks(taken, taken.getCount());
  }

  /**
   * How much of one stored row may be asked for in one withdrawal.
   *
   * <p>A withdrawal is refused outright when it would need more result stacks than a backpack holds, and a
   * row of an unstackable thing is one stack per piece: asking for a whole row of those would be asking
   * for something no withdrawal agrees to, so such a row leaves in as many goes as it takes.
   *
   * @param stack the row, holding as many pieces as the storage has of it
   * @return how many pieces may be asked for at once
   */
  private static int rowPieces(ItemStack stack) {
    long most = (long) UltsWithdrawalPlanner.MAX_OUTPUT_STACKS
        * Math.max(1, stack.getMaxStackSize());
    return (int) Math.min(stack.getCount(), Math.min(most, Integer.MAX_VALUE));
  }

  public MinecraftServer server() {
    return server;
  }

  /**
   * Whether a permission set may manage the storage, using the one configured level.
   *
   * @param permissions permissions of a command source or of a player
   * @return whether binding, deleting, reloading and the highlight are allowed
   */
  public static boolean canManage(PermissionSet permissions) {
    if (permissions == PermissionSet.ALL_PERMISSIONS) {
      return true;
    }
    int required = UltsConfigManager.getInstance().data().input().permissionLevel();
    return permissions instanceof LevelBasedPermissionSet levels
        && levels.level().isEqualOrHigherThan(PermissionLevel.byId(required));
  }

  public UltsState state() {
    return state;
  }

  public UltsInputManager inputs() {
    return inputs;
  }

  /** The glowing outlines shown while the highlight mode is on. */
  public UltsHighlights highlights() {
    return highlights;
  }

  /** The stocks on their way out of the storage, one stream to a player. */
  public UltsTakeAllStreams streams() {
    return streams;
  }

  /** Maximum output units per tick for quantity and bulk withdrawal streams. */
  public int withdrawalRate() {
    return UltsConfigManager.getInstance().data().input().withdrawalRate();
  }

  /**
   * Whether players may empty a stock out with "take everything" at all.
   *
   * <p>Off means the offer is not there: no hint about it and no click that starts one, so nothing about
   * taking everything is shown.
   */
  public boolean allowBulkWithdrawal() {
    return UltsConfigManager.getInstance().data().input().allowBulkWithdrawal();
  }

  /** How many stacks one take-everything takes at most, which is what its screen promises. */
  public int bulkWithdrawalStacks() {
    return UltsConfigManager.getInstance().data().input().bulkWithdrawalStacks();
  }

  /** Remote storage mode keeps the bound containers themselves as the storage. */
  public boolean remote() {
    return UltsConfigManager.getInstance().data().general().storageMode() == UltsStorageMode.REMOTE;
  }

  /**
   * Version of the shown contents. In remote mode the aggregate is refreshed one slice at a time
   * while a screen is asking for it, so a warehouse sized storage never has to be walked completely
   * inside a single tick.
   */
  public long contentRevision() {
    stockPending(storedItems());
    craftingStability.settle(ticks.getAsLong(), stabilityQuietTicks());
    return (remote() ? remoteRevision : state.revision()) + stockStability.revision()
        + craftingStability.revision();
  }

  public List<UltsStoredView> storedItems() {
    List<UltsStoredView> stock = remote() ? aggregate().items() : state.items();
    stockPending(stock);
    return stock;
  }

  /** Clicks use loaded containers as they stand, bypassing the display's sharded cache. */
  public List<UltsStoredView> storedItemsFresh() {
    boolean remote = remote();
    List<UltsStoredView> stock = remote
        ? UltsRemoteStorage.snapshot(server, state.bindings()).items() : state.items();
    // Never let an older display slice overwrite a live click's newly observed quantities.
    if (remote && merged != null && !stockQuantities(stock).equals(stockQuantities(merged.items())))
      invalidateRemote();
    stockPending(stock);
    return stock;
  }

  /** Shared by players, but each stored kind has its own quiet window. */
  public boolean stockPending(List<UltsStoredView> stock) {
    stockQuantities(stock);
    return stockStability.observe(lastKindQuantities, ticks.getAsLong(), stabilityQuietTicks());
  }

  public boolean stockPending(ItemStack template, List<UltsStoredView> stock) {
    stockPending(stock);
    String kind = UltsStackKinds.of(template);
    return stockStability.pending(key -> key.item() == template.getItem() && key.kind().equals(kind));
  }

  /** Bag totals depend on the bag's item variants, including a variant just removed. */
  public boolean stockPending(Item item, List<UltsStoredView> stock) {
    stockPending(stock);
    return stockStability.pending(key -> key.item() == item && key.special());
  }

  public boolean packagingPending(List<UltsStoredView> stock) {
    stockPending(stock);
    return stockStability.pending(StockKind::packable)
        || craftingAmount(Items.SHULKER_BOX.getDefaultInstance(), stock).pending();
  }

  /** Maximum producer interval plus observation lag, with a strict one-tick safety margin. */
  public long stabilityQuietTicks() {
    long inputInterval = Math.max(1, UltsConfigManager.getInstance().data().input().drainInterval());
    return Math.max(inputInterval, UltsTakeAllStreams.DELIVERY_INTERVAL_TICKS)
        + (remote() ? (long) REMOTE_SHARDS * REMOTE_SHARD_TICKS : 0L) + 1L;
  }

  private Map<PackingKind, Long> stockQuantities(List<UltsStoredView> stock) {
    if (stock == lastStabilityStock) return lastStabilityQuantities;
    var quantities = new HashMap<PackingKind, Long>();
    var kinds = new HashMap<StockKind, Long>();
    for (var row : stock) if (row.amount() > 0L) {
      quantities.merge(
        new PackingKind(row.template().getItem(), row.template().getComponentsPatch()),
        row.amount(), UltsCraftMath::add);
      kinds.merge(new StockKind(row.template().getItem(), UltsStackKinds.of(row.template()),
          row.special(), UltsBoxes.isPackable(row.template())),
          row.amount(), UltsCraftMath::add);
    }
    lastStabilityStock = stock;
    lastKindQuantities = Map.copyOf(kinds);
    var indexed = new HashMap<Item, Map<PackingKind, Long>>();
    quantities.forEach((key, amount) -> indexed.computeIfAbsent(key.item(), ignored -> new HashMap<>()).put(key, amount));
    lastItemQuantities = indexed;
    return lastStabilityQuantities = Map.copyOf(quantities);
  }

  public boolean stockPending() { return stockPending(storedItems()); }

  /** The configured crafting mode, which decides whether anything may be crafted at all. */
  public UltsCraftingMode craftingMode() {
    return UltsConfigManager.getInstance().data().input().crafting();
  }

  /**
   * How many more of an item the storage could craft right now.
   *
   * @param template the item in question
   * @return the largest amount the open crafting routes can produce, or {@code 0}
   */
  public long craftable(ItemStack template) {
    return craftable(template, storedItems());
  }

  /**
   * How many more of an item a given stock could craft right now.
   *
   * <p>A screen asks this once per item row, so the answers are remembered for the contents they were
   * computed from: while nobody stores or sets anything, browsing a large storage costs one lookup per
   * item instead of one search per redraw. A summary of the contents is what that memory hangs on, so
   * an answer can never be reused for other contents.
   *
   * <p>One answer can cost more than a tick should spare on a large pack, so a tick only ever spends
   * so long on these and the remaining rows are answered on the ticks after it. {@link
   * #craftablePending()} says whether anything is still waiting, which is what makes a screen redraw
   * until every row has its answer.
   *
   * @param template the item in question
   * @param stock contents to craft from, so a screen can reuse the list it already read
   * @return the largest amount the open crafting routes can produce, or {@code 0}
   */
  public long craftable(ItemStack template, List<UltsStoredView> stock) {
    return craftable(template, stock, true);
  }

  public record CraftingAmount(long amount, boolean pending, boolean noStation) {}

  /** Stored amounts never hide the additional craftable amount; timeout remains visible as pending. */
  public CraftingAmount craftingAmount(ItemStack template, List<UltsStoredView> stock) {
    return calculateCraftingAmount(template, stock);
  }

  /** Confirmation uses a live snapshot and the same per-kind waiting state as a listing. */
  public CraftingAmount craftingAmountFresh(ItemStack template, List<UltsStoredView> stock) {
    return calculateCraftingAmount(template, stock);
  }

  private CraftingAmount calculateCraftingAmount(ItemStack template, List<UltsStoredView> stock) {
    if (stockPending(template, stock)) return new CraftingAmount(0L, true, false);
    UltsCraftingMode mode = craftingMode();
    if (!mode.enabled()) return new CraftingAmount(0L, false, false);
    var key = new PackingKind(template.getItem(), template.getComponentsPatch());
    var required = UltsCraftResolver.capacityInputs(template);
    var signature = new HashMap<PackingKind, Long>();
    stockQuantities(stock);
    for (Item item : required) signature.putAll(lastItemQuantities.getOrDefault(item, Map.of()));
    CraftingMemo known = craftingAnswers.get(key);
    if (known != null && known.mode() == mode && known.inputs().equals(signature))
      return measuredCrafting(key, known.amount(), known.noStation());
    // A row never inherits another row's stale flag. Changed inputs require a current resolver;
    // unchanged inputs retain their exact answer without spending another row's search budget.
    UltsCraftResolver resolver = view(stock, true, true);
    if (resolver == null) return new CraftingAmount(0L, false, false);
    long amount = resolver.capacity(template, budget());
    if (amount == UltsCraftResolver.UNKNOWN) {
      defer();
      return new CraftingAmount(0L, true, false);
    }
    boolean noStation = false;
    if (amount == 0L) {
      long withoutStation = looseView().capacity(template, budget());
      if (withoutStation == UltsCraftResolver.UNKNOWN) {
        defer();
        return new CraftingAmount(0L, true, false);
      }
      noStation = withoutStation > 0L;
    }
    if (!craftingAnswers.containsKey(key) && craftingAnswers.size() >= MAX_CRAFTING_ANSWERS) {
      var evicted = craftingAnswers.pollFirstEntry();
      craftingStability.forget(evicted.getKey());
    }
    craftingAnswers.put(key, new CraftingMemo(Map.copyOf(signature), mode, amount, noStation));
    return measuredCrafting(key, amount, noStation);
  }

  private CraftingAmount measuredCrafting(PackingKind key, long amount, boolean noStation) {
    boolean pending = craftingStability.observe(key, amount, ticks.getAsLong(), stabilityQuietTicks());
    return new CraftingAmount(amount, pending, noStation);
  }

  /**
   * How many more of an item a given stock could craft right now, answered from the contents as they
   * are rather than from the view a listing is allowed to be a moment behind.
   *
   * <p>A row shows answers that may be up to {@link #CRAFTABLE_REFRESH_TICKS} ticks old, so that a
   * storage taking arrivals every tick does not have its whole craft graph worked out again and again.
   * A click is not a moment: it is one event, and it has to be answered from the storage as it stands,
   * or the row would ask for an amount that is no longer there — and stay quiet about one that is.
   *
   * @param template the item in question
   * @param stock contents to craft from
   * @return the largest amount the open crafting routes can produce right now
   */
  public long craftableFresh(ItemStack template, List<UltsStoredView> stock) {
    return craftable(template, stock, true, true);
  }

  /** Quick withdrawals measure the live stock once, even while the listing still shows an older slice. */
  public int stackQuantity(ItemStack template) {
    List<UltsStoredView> live = storedItemsFresh();
    long held = storedAmount(template, live);
    int maximum = Math.max(1, template.getMaxStackSize());
    if (held >= maximum) return maximum;
    return (int) Math.min(maximum, UltsCraftMath.add(held, craftableFresh(template, live)));
  }

  /**
   * How much could be crafted if a station were stored.
   *
   * <p>Only used to explain the missing station on an item row, so it answers zero while crafting is
   * off or while nothing could be crafted anyway.
   *
   * @param template the item in question
   * @param stock contents to craft from
   * @return the largest amount a station-less storage could produce, or {@code 0}
   */
  public long craftableWithoutStation(ItemStack template, List<UltsStoredView> stock) {
    return craftable(template, stock, false);
  }

  /**
   * Whether the storage could hand this item over right now, crafting included.
   *
   * <p>A listing asks this about every row it might show, so it is answered from the view of the pile
   * instead of by searching for each row.
   *
   * @param template the item in question
   * @param stock contents to craft from
   * @return whether at least one could be made right now
   */
  public boolean craftableNow(ItemStack template, List<UltsStoredView> stock) {
    UltsCraftResolver view = view(stock, true, false);
    if (view == null) {
      return false;
    }
    // A listing asks this about every row it might show, so it runs under the same tick budget the
    // amounts do: whatever is left over is worked out on a later tick.
    boolean value = view.craftable(template, budget());
    if (view.ranOut()) {
      defer();
    }
    return value;
  }

  /** Whether contents and their box can be supplied together, with the page's shared tick budget. */
  public boolean canPack(ItemStack template, List<UltsStoredView> stock) {
    long fingerprint = fingerprintOf(stock);
    UltsCraftingMode mode = craftingMode();
    if (fingerprint != packingFingerprint || mode != packingMode) {
      packingAnswers.clear();
      packingFingerprint = fingerprint;
      packingMode = mode;
    }
    var key = new PackingKind(template.getItem(), template.getComponentsPatch());
    Boolean known = packingAnswers.get(key);
    if (known != null) return known;
    UltsCraftPool pool = UltsCraftPool.of(stock);
    if (!com.flwolfy.ults.data.state.UltsBoxes.isShulker(template)
        && !template.isEmpty() && pool.amount(template) >= 27L * template.getMaxStackSize()
        && pool.packableAmount() > 0L) {
      packingAnswers.put(key, true);
      return true;
    }
    long deadline = budget();
    if (System.nanoTime() >= deadline) { defer(); return false; }
    UltsWithdrawalPlan plan = UltsWithdrawalPlanner.plan(
        pool, template, 1, true, mode, deadline);
    if ("pending".equals(plan.problem())) { defer(); return false; }
    packingAnswers.put(key, plan.available());
    return plan.available();
  }

  /** Notes that an answer is still owed, so a screen showing them may redraw on a later tick. */
  private void defer() {
    // A page asks many rows in one tick. Count ticks, not rows, or a few redraws exhaust all retries.
    if (craftableDeferred) return;
    craftableWaits++;
    craftableDeferred = craftableWaits < MAX_CRAFTABLE_WAITS;
  }

  /**
   * Whether an answer is still owed: either one had to wait for a later tick, or the view the answers
   * come from is older than the contents and has not caught up yet. A screen showing them redraws while
   * this is true, which is what keeps an amount from being left on screen after it stopped being true.
   */
  public boolean craftablePending() {
    craftingStability.settle(ticks.getAsLong(), stabilityQuietTicks());
    return stockPending() || craftingStability.pending() || craftableDeferred || craftableViewStale;
  }

  private long craftable(ItemStack template, List<UltsStoredView> stock, boolean requireStation) {
    return craftable(template, stock, requireStation, false);
  }

  private long craftable(
      ItemStack template,
      List<UltsStoredView> stock,
      boolean requireStation,
      boolean fresh
  ) {
    UltsCraftResolver view = view(stock, requireStation, fresh);
    if (view == null) {
      return 0L;
    }
    long value = view.capacity(template, budget());
    if (value == UltsCraftResolver.UNKNOWN) {
      // Out of this tick's time: the row keeps no answer yet and the screen asks again next tick,
      // until it has asked often enough that settling for what it has beats asking again.
      defer();
      return 0L;
    }
    return value;
  }

  /** How long this tick may still spend on craftable amounts; every tick starts over. */
  private long budget() {
    long tick = ticks.getAsLong();
    if (tick != craftableBudgetTick) {
      craftableBudgetTick = tick;
      craftableDeadline = System.nanoTime() + CRAFTABLE_NANOS_PER_TICK;
      craftableDeferred = false;
    }
    return craftableDeadline;
  }

  /**
   * The view of a pile, remembered for the contents it was built from.
   *
   * <p>A storage that changes every tick would otherwise have the whole catalogue worked out every
   * tick, so a listing may be answered from a view that is up to {@link #CRAFTABLE_REFRESH_TICKS} ticks
   * old, and the view is rebuilt once the contents have settled. Such an answer is never the last word:
   * {@link #craftableViewStale} remembers that the view is behind, {@link #craftablePending()} says so,
   * and a screen showing the answers redraws until the view has caught up — otherwise the amounts of
   * the contents before the change would simply stay on screen. A click does not wait at all: it asks
   * through {@link #craftableFresh} and is answered from the contents as they are.
   *
   * @param stock contents the answers are wanted for
   * @param requireStation whether the stored station is needed
   * @param fresh whether the answer must come from these contents, however recently the view was built
   */
  private @Nullable UltsCraftResolver view(
      List<UltsStoredView> stock,
      boolean requireStation,
      boolean fresh
  ) {
    UltsCraftingMode mode = craftingMode();
    if (!mode.enabled()) {
      return null;
    }
    long fingerprint = fingerprintOf(stock);
    if (craftableView == null || fingerprint != craftableFingerprint || mode != craftableMode) {
      long tick = ticks.getAsLong();
      if (!fresh
          && craftableTick != Long.MIN_VALUE
          && tick - craftableTick < CRAFTABLE_REFRESH_TICKS) {
        // The contents moved again before they settled, so the answers of a moment ago still stand —
        // for now: they are owed for the contents as they are, so a screen keeps redrawing until this
        // view has caught up instead of showing the old amounts for ever.
        craftableViewStale = true;
        return requireStation ? craftableView : looseView();
      }
      UltsCraftPool pool = UltsCraftPool.of(stock);
      craftableViewPool = pool;
      craftableView = UltsCraftResolver.of(pool, mode, true);

      // The station-less view is only ever needed to explain a missing station, so it waits.
      craftableLooseView = null;
      craftableFingerprint = fingerprint;
      craftableMode = mode;
      craftableTick = tick;
      craftableWaits = 0;
      craftableViewStale = false;
      // A pass that stopped at its budget knows less than it could. Marking it after the reset is what
      // makes the mark stick: screens then keep redrawing, and what the pass left out is not presented as
      // something the storage cannot do.
      if (craftableView.reachTruncated()) {
        craftableViewStale = true;
      }
    }
    return requireStation ? craftableView : looseView();
  }

  /** The view that pretends a station were stored, built the first time a row really needs it. */
  private UltsCraftResolver looseView() {
    if (craftableLooseView == null) {
      craftableLooseView = UltsCraftResolver.of(craftableViewPool, craftableMode, false);
    }
    return craftableLooseView;
  }

  /** A cheap summary of contents, so a cached answer can never be handed out for other contents. */
  private static long fingerprint(List<UltsStoredView> stock) {
    long hash = stock.size();
    for (UltsStoredView view : stock) {
      hash = hash * 31L + view.template().getItem().hashCode();
      hash = hash * 31L + view.template().getComponentsPatch().hashCode();
      hash = hash * 31L + view.amount();
    }
    return hash;
  }

  /** One screen render asks per item row, so the summary of its contents is only worked out once. */
  private long fingerprintOf(List<UltsStoredView> stock) {
    if (stock == lastStock) {
      return lastStockFingerprint;
    }
    long value = fingerprint(stock);
    lastStock = stock;
    lastStockFingerprint = value;
    return value;
  }

  /** The plan of one withdrawal, crafting included, without touching the storage. */
  public UltsWithdrawalPlan withdrawalPlan(ItemStack template, int quantity, boolean boxed) {
    UltsCraftingMode mode = craftingMode();
    return remote()
        ? UltsRemoteStorage.plan(aggregate(), template, quantity, boxed, mode)
        : state.withdrawalPlan(template, quantity, boxed, mode);
  }

  /** Withdrawals always read the live containers, then drop the cached aggregate. */
  public List<ItemStack> takePlanned(ItemStack template, int quantity, boolean boxed) {
    return takePlanned(template, quantity, boxed, craftingMode());
  }

  /**
   * A withdrawal that says itself whether it may craft what is missing.
   *
   * <p>The configuration decides for every ordinary withdrawal; a screen that offers the player the
   * choice passes the mode in, so taking everything out of the storage can be asked for with or without
   * the recipes filling in what is short.
   *
   * @param mode whether, and how far, the storage may craft what is missing
   */
  public List<ItemStack> takePlanned(
      ItemStack template,
      int quantity,
      boolean boxed,
      UltsCraftingMode mode
  ) {
    if (!remote()) {
      UltsWithdrawalPlan plan = state.withdrawalPlan(template, quantity, boxed, mode);
      return state.takePlanned(plan, template, quantity, boxed);
    }
    List<ItemStack> outputs = UltsRemoteStorage.take(
        server, state.bindings(), state, template, quantity, boxed, mode);
    invalidateRemote();
    return outputs;
  }

  public ItemStack availableBox() {
    return remote() ? UltsRemoteStorage.availableBox(aggregate()) : state.availableBox();
  }

  /** Streams share a tick deadline. An unfinished plan never consumes materials or signals shortage. */
  public UltsWithdrawalResult takeBatch(ItemStack template, int quantity, boolean boxed,
      UltsCraftingMode mode, long deadline) {
    if (System.nanoTime() >= deadline) return UltsWithdrawalResult.WAIT;
    if (!remote()) {
      synchronized (state) {
        UltsWithdrawalPlan plan = state.withdrawalPlan(template, quantity, boxed, mode, deadline);
        if ("pending".equals(plan.problem())) return UltsWithdrawalResult.WAIT;
        return new UltsWithdrawalResult(state.takePlanned(plan, template, quantity, boxed), false);
      }
    }
    UltsWithdrawalResult result = UltsRemoteStorage.take(
        server, state.bindings(), state, template, quantity, boxed, mode, deadline);
    if (!result.outputs().isEmpty()) invalidateRemote();
    return result;
  }

  /** A concurrent withdrawal may leave less than the requested batch, including craftable pieces. */
  public UltsWithdrawalResult takeBatchUpTo(ItemStack template, int quantity, boolean boxed,
      UltsCraftingMode mode, long deadline) {
    UltsWithdrawalResult result = takeBatch(template, quantity, boxed, mode, deadline);
    if (result.pending() || !result.outputs().isEmpty() || boxed || quantity <= 1) return result;
    List<UltsStoredView> stock = storedItemsFresh();
    long held = storedAmount(template, stock);
    if (!mode.enabled()) return held <= 0L ? result : takeBatch(template,
        (int) Math.min(quantity, held), false, mode, deadline);
    UltsCraftPool before = UltsCraftPool.of(stock);
    int low = (int) Math.min(quantity - 1L, held), high = quantity;
    while (low < high - 1) {
      int middle = low + (high - low) / 2;
      UltsWithdrawalPlan probe = UltsWithdrawalPlanner.plan(
          before.copy(), template, middle, false, mode, deadline);
      if ("pending".equals(probe.problem())) return UltsWithdrawalResult.WAIT;
      if (probe.available()) low = middle; else high = middle;
    }
    return low == 0 ? result : takeBatch(template, low, false, mode, deadline);
  }

  /** A quantity-menu preview validates one batch, never materializes an entire large request. */
  public UltsWithdrawalPlan batchPreview(ItemStack template, int quantity, boolean boxed,
      List<UltsStoredView> stock) {
    return UltsWithdrawalPlanner.plan(UltsCraftPool.of(stock), template, quantity, boxed,
        craftingMode(), budget());
  }

  public com.flwolfy.ults.data.state.UltsWithdrawalAssessment assessRequest(ItemStack template,
      long quantity, boolean boxed, List<UltsStoredView> stock) {
    return UltsWithdrawalPlanner.assess(UltsCraftPool.of(stock), template, quantity, boxed,
        craftingMode(), budget());
  }

  /** Marks that a screen is looking at the aggregate, so the slices keep being refreshed. */
  private void watch() {
    remoteDemandTick = server.getTickCount();
  }

  /**
   * The aggregate of every slice. The first caller after an idle period walks everything once, later
   * calls are served from the slices that the tick loop keeps up to date.
   */
  private UltsRemoteStorage.Snapshot aggregate() {
    watch();
    if (shardsStale) {
      for (int shard = 0; shard < REMOTE_SHARDS; shard++) {
        remoteShards[shard] = UltsRemoteStorage.snapshotShard(server, slice(shard), ++shardGeneration);
      }
      shardsStale = false;
      shardCursor = 0;
      remoteRevision++;
      merged = null;
    } else {
      advanceShards();
    }
    if (merged == null) {
      merged = UltsRemoteStorage.mergeShards(List.of(remoteShards));
    }
    return merged;
  }

  /** Recomputes one slice, at most every {@link #REMOTE_SHARD_TICKS} ticks. */
  private void advanceShards() {
    long tick = server.getTickCount();
    if (tick - shardTick < REMOTE_SHARD_TICKS) {
      return;
    }
    shardTick = tick;
    remoteShards[shardCursor] = UltsRemoteStorage.snapshotShard(
        server, slice(shardCursor), ++shardGeneration);
    shardCursor = (shardCursor + 1) % REMOTE_SHARDS;
    merged = null;
    remoteRevision++;
  }

  /** Binding partition. Physical inventory provenance is deduplicated across slice snapshots. */
  private List<UltsBinding> slice(int shard) {
    List<UltsBinding> all = state.bindings();
    if (REMOTE_SHARDS == 1) {
      return all;
    }
    List<UltsBinding> part = new ArrayList<>(all.size() / REMOTE_SHARDS + 1);
    for (UltsBinding binding : all) {
      if (shardOf(binding) == shard) {
        part.add(binding);
      }
    }
    return part;
  }

  private static int shardOf(UltsBinding binding) {
    int hash = binding.dimension().hashCode() * 31 + binding.pos().hashCode();
    return Math.floorMod(hash, REMOTE_SHARDS);
  }

  /** Drops the cached aggregate and walks everything again on the next call. */
  private void invalidateRemote() {
    shardsStale = true;
    merged = null;
    remoteRevision++;
  }

  /** A bound container was destroyed: that single binding is dropped and everyone is told. */
  public void onBlockBroken(Level level, BlockPos position) {
    String dimension = level.dimension().identifier().toString();
    UltsBinding binding = breakingBindings.remove(new BrokenPart(dimension, position));
    if (binding == null) binding = state.binding(dimension, position);
    if (binding == null) {
      return;
    }
    int index = state.number(binding);
    if (index <= 0) {
      return;
    }
    if (!state.removeBindings(index, index).isEmpty()) {
      announce(binding, index);
    }
  }

  /**
   * Whether a player may break this block. The whole bound container is protected, both halves of a
   * large one included, and only a sneaking player can take it down.
   */
  public boolean allowBreak(Player player, Level level, BlockPos position) {
    if (player == null || !(level instanceof ServerLevel serverLevel)) {
      return true;
    }
    UltsBinding binding = bindingAt(serverLevel, position);
    if (binding == null) {
      return true;
    }
    if (player.isShiftKeyDown()) {
      // After the break, the other halves can no longer be discovered from the removed block.
      // Remember the binding here, but remove it only when AFTER confirms the break succeeded.
      breakingBindings.put(new BrokenPart(level.dimension().identifier().toString(),
          position.immutable()), binding);
      return true;
    }
    if (player instanceof ServerPlayer serverPlayer) {
      // Tell the client to drop its predicted break, then explain why nothing happened. The notice
      // goes to the chat and not to the action bar, where the look-at binding hint would overwrite
      // it within a few ticks.
      serverPlayer.connection.send(new ClientboundBlockUpdatePacket(
          position, serverLevel.getBlockState(position)));
      long tick = server.getTickCount();
      Long last = protectedNotices.get(serverPlayer.getUUID());
      if (last == null || tick - last >= PROTECTED_NOTICE_TICKS) {
        protectedNotices.put(serverPlayer.getUUID(), tick);
        serverPlayer.sendSystemMessage(UltsTextBuilder.info(
            UltsLangManager.getInstance().text("ults.input.protected")));
      }
    }
    return false;
  }

  private void announce(UltsBinding binding, int index) {
    invalidateRemote();
    Component message = UltsTextBuilder.info(UltsTextBuilder.format(
        UltsLangManager.getInstance().text("ults.input.destroyed"),
        UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        index, binding.note().isEmpty()
            ? UltsLangManager.getInstance().text("ults.command.note.none")
            : binding.note()));
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      player.sendSystemMessage(message);
    }
    UltsMod.LOGGER.info("UltStorage binding #{} was destroyed and removed", index);
  }

  void tick() {
    // BEFORE/AFTER are synchronous. Anything left here was cancelled by another break listener.
    breakingBindings.clear();
    if (server.getTickCount() % 20 == 0
        && state.retryRemoteRecovery(stacks -> UltsRemoteStorage.restore(server, state.bindings(), stacks))) {
      invalidateRemote();
    }
    if (server.getTickCount() % 600 == 0) {
      long now = server.getTickCount();
      protectedNotices.entrySet().removeIf(entry -> now - entry.getValue() > 600);
    }
    // Stocks on their way out go first, so a stream hands over its tick's worth before anything else
    // asks the storage for its contents.
    streams.tick();
    for (UltsBinding binding : inputs.tick(!remote())) {
      int index = state.number(binding);
      if (index > 0 && !state.removeBindings(index, index).isEmpty()) {
        announce(binding, index);
      }
    }
    if (remote()) {
      // Keep the slices of the aggregate fresh while a screen is watching, otherwise forget them.
      if (server.getTickCount() - remoteDemandTick <= REMOTE_WATCH_TICKS) {
        if (shardsStale) {
          aggregate();
        } else {
          advanceShards();
        }
      } else if (!shardsStale) {
        invalidateRemote();
      }
    }
    if (highlights.active()) {
      highlights.tick(server, state.bindings());
    }
    if (server.getTickCount() % LOOK_REFRESH_TICKS == 0) {
      inspectLookedAtBindings();
    }
    UltsStorageSGUI.refreshAll(this);
    UltsWithdrawSGUI.refreshAll(this);
    UltsBagSGUI.refreshAll(this);
    UltsTakeAllSGUI.refreshAll(this);
  }

  /** Shows "#N note" over the action bar while a player looks at a bound container. */
  private void inspectLookedAtBindings() {
    if (server.getPlayerList().getPlayers().isEmpty()) {
      return;
    }
    List<UltsBinding> bindings = state.bindings();
    if (bindings.isEmpty()) {
      return;
    }
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      if (!(player.pick(LOOK_REACH, 1.0F, false) instanceof BlockHitResult hit)
          || hit.getType() != HitResult.Type.BLOCK) {
        continue;
      }
      // The cheap question first: walking the parts of a large container is only worth doing when what is
      // being looked at is a container at all, and most of what a player looks at is not.
      if (!(player.level() instanceof ServerLevel level)
          || !UltsContainers.hasContainer(level, hit.getBlockPos())) {
        continue;
      }
      UltsBinding binding = bindingAt(player.level(), hit.getBlockPos());
      if (binding == null) {
        continue;
      }
      player.sendSystemMessage(lookedAt(binding, state.number(binding)), true);
    }
  }

  /**
   * The binding of the container a position belongs to, whatever part of a large container it is.
   * Looking at any part of a bound multi block container therefore shows the same binding.
   */
  public UltsBinding bindingAt(Level level, BlockPos position) {
    if (!(level instanceof ServerLevel serverLevel)) {
      return null;
    }
    String dimension = level.dimension().identifier().toString();
    for (BlockPos part : UltsContainers.parts(serverLevel, position)) {
      UltsBinding binding = state.binding(dimension, part);
      if (binding != null) {
        return binding;
      }
    }
    return null;
  }

  private static Component lookedAt(UltsBinding binding, int number) {
    UltsLangManager lang = UltsLangManager.getInstance();
    return UltsTextBuilder.format(
        lang.text("ults.visual.looking"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT, number,
        binding.note().isEmpty()
            ? lang.text("ults.command.note.none")
            : Component.literal(binding.note()).withStyle(UltsTextBuilder.HIGHLIGHT));
  }
}
