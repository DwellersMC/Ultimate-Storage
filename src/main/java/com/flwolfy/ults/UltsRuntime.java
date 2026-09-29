package com.flwolfy.ults;

import com.flwolfy.ults.crafting.UltsCraftCatalog;
import com.flwolfy.ults.crafting.UltsCraftPool;
import com.flwolfy.ults.crafting.UltsCraftResolver;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.data.lang.UltsItemNames;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsBinding;
import com.flwolfy.ults.data.state.UltsRemoteStorage;
import com.flwolfy.ults.data.state.UltsState;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsSpecialFilters;
import com.flwolfy.ults.data.state.UltsWithdrawalOutput;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
import com.flwolfy.ults.display.UltsBagSGUI;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.display.UltsStorageSGUI;
import com.flwolfy.ults.display.UltsSurvivalItems;
import com.flwolfy.ults.display.UltsTakeAllStreams;
import com.flwolfy.ults.display.UltsWithdrawSGUI;
import com.flwolfy.ults.input.UltsContainers;
import com.flwolfy.ults.input.UltsInputManager;
import com.flwolfy.ults.util.UltsTextBuilder;
import com.flwolfy.ults.visual.UltsHighlights;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
  private final UltsInputManager inputs;
  private final UltsHighlights highlights = new UltsHighlights();
  /** Stocks on their way out of the storage, a tick's worth at a time. */
  private final UltsTakeAllStreams streams = new UltsTakeAllStreams();
  /** Per player tick of the last break-protection notice, so it cannot flood the chat. */
  private final Map<UUID, Long> protectedNotices = new HashMap<>();
  private final UltsRemoteStorage.Snapshot[] remoteShards =
      new UltsRemoteStorage.Snapshot[REMOTE_SHARDS];
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
    this.server = server;
    rebuildCatalogs(server);
    UltsMod.LOGGER.info("UltStorage automatic crafting is {}", craftingMode());
    state = server.overworld().getDataStorage().computeIfAbsent(UltsState.TYPE);
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
    // The words a search is answered with are read from files too, so a language a server owner has
    // just dropped in is picked up by the same command that rereads everything else.
    UltsItemNames.clear();
    rebuildCatalogs(server);
    if (state.purgeFiltered()) {
      UltsMod.LOGGER.info("UltStorage destroyed the stored stacks the special filter now dismisses");
    }
  }

  /** Whether a withdrawal may go ahead with no room in the inventory, dropping what does not fit. */
  public boolean allowFullInventory() {
    return UltsConfigManager.getInstance().data().input().allowFullInventory();
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
    long amount = 0L;
    for (UltsStoredView view : stock) {
      if (ItemStack.isSameItemSameComponents(view.template(), template)) {
        amount += view.amount();
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
   * Takes one row out of a bag, whole.
   *
   * <p>Void storage owns its rows, so the row that matches the stack is removed and comes back as it
   * was stored, its time and its place among the other rows of that item untouched. Remote storage
   * owns nothing — the containers a player bound do — so the stack is withdrawn from them instead.
   *
   * @param item the item whose bag is being taken from
   * @param stack the stack that is leaving, components and all
   * @return the stack that left, or an empty stack when it could not be taken
   */
  public ItemStack takeBagRow(Item item, ItemStack stack) {
    if (remote()) {
      List<ItemStack> taken = takePlanned(stack.copyWithCount(1), stack.getCount(), false);
      invalidateRemote();
      return taken.isEmpty() ? ItemStack.EMPTY : taken.getFirst();
    }
    return state.takeBagRow(item, stack);
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

  /** How many items one take-everything hands over per tick while it runs. */
  public int takeAllRate() {
    return UltsConfigManager.getInstance().data().input().takeAllRate();
  }

  /**
   * Whether players may empty a stock out with "take everything" at all.
   *
   * <p>Off means the offer is not there: no hint about it and no click that starts one, so nothing about
   * taking everything is shown.
   */
  public boolean allowTakeAll() {
    return UltsConfigManager.getInstance().data().input().allowTakeAll();
  }

  /** How many stacks one take-everything takes at most, which is what its screen promises. */
  public int takeAllStacks() {
    return UltsConfigManager.getInstance().data().input().takeAllStacks();
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
    if (!remote()) {
      return state.revision();
    }
    watch();
    return remoteRevision;
  }

  public List<UltsStoredView> storedItems() {
    return remote() ? aggregate().items() : state.items();
  }

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

  /** Notes that an answer is still owed, so a screen showing them may redraw on a later tick. */
  private void defer() {
    craftableWaits++;
    craftableDeferred = craftableWaits < MAX_CRAFTABLE_WAITS;
  }

  /**
   * Whether an answer is still owed: either one had to wait for a later tick, or the view the answers
   * come from is older than the contents and has not caught up yet. A screen showing them redraws while
   * this is true, which is what keeps an amount from being left on screen after it stopped being true.
   */
  public boolean craftablePending() {
    return craftableDeferred || craftableViewStale;
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
    long tick = server.getTickCount();
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
    if (fingerprint != craftableFingerprint || mode != craftableMode) {
      long tick = server.getTickCount();
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
        server, state.bindings(), template, quantity, boxed, mode);
    invalidateRemote();
    return outputs;
  }

  public ItemStack availableBox() {
    return remote() ? UltsRemoteStorage.availableBox(aggregate()) : state.availableBox();
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
        remoteShards[shard] = UltsRemoteStorage.snapshot(server, slice(shard));
      }
      shardsStale = false;
      shardCursor = 0;
      remoteRevision++;
      merged = null;
    } else {
      advanceShards();
    }
    if (merged == null) {
      merged = UltsRemoteStorage.merge(List.of(remoteShards));
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
    remoteShards[shardCursor] = UltsRemoteStorage.snapshot(server, slice(shardCursor));
    shardCursor = (shardCursor + 1) % REMOTE_SHARDS;
    merged = null;
    remoteRevision++;
  }

  /** The bindings of one slice; a container always belongs to the same slice. */
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
    UltsBinding binding = state.binding(level.dimension().identifier().toString(), position);
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
    if (player == null || !(level instanceof ServerLevel serverLevel)
        || player.isShiftKeyDown()) {
      return true;
    }
    if (!bound(serverLevel, position)) {
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

  /** True when this position, or the other half of its large container, is bound. */
  private boolean bound(ServerLevel level, BlockPos position) {
    String dimension = level.dimension().identifier().toString();
    for (BlockPos part : UltsContainers.parts(level, position)) {
      if (state.binding(dimension, part) != null) {
        return true;
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
