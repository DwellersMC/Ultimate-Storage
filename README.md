# UltStorage Mod Documentation

**UltStorage** is a server-side Fabric mod for Minecraft that turns bound containers into **one shared item storage for the whole world**. In the default mode, items pushed into a bound container are drained into a server-side store, and every player can browse and withdraw them through a storage screen that is sent with vanilla container packets.

Storage belongs to the save, not to a name: a world has **one** storage, its inputs are the containers you bind to it, and the same storage can be fed and opened from any dimension.

中文文档请看[这里](./README.cn.md)

---

## Features

- **One shared storage per world**, reachable from every dimension
- Bind containers by looking at them (`/ults bind`), each with an optional note
- Bind a whole area at once (`/ults bindarea`), loaded chunks only
- Storage screen built from the creative tabs discovered at runtime, so modded items appear automatically
- Per-player persisted view: category, category page, item page, item-ID filter and item visibility
- Withdrawal screen with an item mode and a full-box mode (shulker boxes)
- Per-player container highlight (`/ults show` and `/ults hide`)
- Bound blocks are **protected**: breaking them needs sneaking
- A bound block that is destroyed anyway loses its binding, and a vanished block is detected automatically
- Hopper input draining that empties the hoppers of a bound container every tick in void mode, with a configurable drain interval and automatic idle skip
- Multi-block containers: vanilla chests are recognised on their own, modded containers can be listed
- Three item visibility modes, including a derived **survival obtainable** catalogue; the configured mode is the default and every player can switch their own with a right-click on the status book
- Optional **automatic crafting**: when a withdrawal runs short, the storage crafts the missing part from what it has, following recipes down as far as it takes, using a stored crafting table or stonecutter
- **Special data items** are managed rather than left to pile up: one row per item stands for its bag, drawn as the newest stack that arrived in it, under two yellow rules that say which stack that is — a right click opens the bag itself, a spacious screen of stored stacks a page at a time where a box of one is packed and the whole bag is emptied from; a configurable capacity destroys the least recently stored ones past it, and a filter can throw unwanted ones away on sight — named by item id, nothing else
- Two storage modes: a server-side void store or the bound containers themselves (remote)
- Bundled `en_us`, `zh_cn` and `zh_tw` messages; further locales are discovered at runtime
- **Cloth Config** API support through Mod Menu (client-side, optional)
- Deployable as a **server-side only** mod; unmodified vanilla clients can join

---

## Commands

*`<>` marks a required argument and `()` an optional one. All commands are rooted at `/ults`.*

| Command                                    | Description                                                                                 |
|--------------------------------------------|---------------------------------------------------------------------------------------------|
| `/ults`                                    | Open the storage screen                                                                     |
| `/ults bind (note)`                        | Bind the container you are looking at, with an optional note                                |
| `/ults bindarea <first> <second> (note)`   | Bind every container inside the two corner positions, with an optional note                  |
| `/ults delete`                             | Remove the binding of the container you are looking at                                      |
| `/ults delete <#N or #N-#M>`               | Remove one binding or an inclusive range by its number                                      |
| `/ults list (page)`                        | List all bindings as `#N <dimension, (x, y, z)> note`; the lines are clickable while allowed |
| `/ults show`                               | Show the highlight of nearby bound containers to you                                        |
| `/ults hide`                               | Hide your highlight again                                                                   |
| `/ults reload`                             | Reload `config/ults.json` and read the server data (loot tables, trades, recipe files, recipes, creative tabs) again |

`/ults` and `/ults list` are open to everyone. Binding a container, binding an area, deleting bindings, the highlight and reloading need the **management permission level** (`input.permissionLevel`, vanilla level `0`-`4`, default `2`); the server console is always allowed. `/ults list` only offers the click-to-delete action to players that may delete.

`/ults bind` and `/ults delete` use a reach of 6 blocks. Binding and deleting work on any part of a large container, not only on the block that was bound first. A selection is written with a mandatory hash, so `#3` and `#3-#5` are valid while `3` is rejected with a hint.

### What `/ults reload` can and cannot pick up

It re-reads everything the storage works from, but two of those are snapshots the server took when it last loaded its data packs:

| Changed on disk | Picked up by `/ults reload` | Why |
|-----------------|------------------------------|-----|
| A loot table — edited **or newly added** to a folder data pack | Yes | Loot tables are listed and opened straight from the resource manager on every reload |
| A `villager_trade`, or a recipe **file** | Yes | Same |
| The recipe a **withdrawal** should craft, or a new item in the creative tabs | No, run vanilla `/reload` first | The recipe manager and the creative tabs are snapshots the server only replaces on a data pack reload |
| A whole new data pack (folder or zip), or an edit inside a zip | No, run vanilla `/reload` or restart | The pack is not in the server's pack list, or the archive was opened once at startup |

So a loot table that starts dropping a new sword makes that sword filterable after `/ults reload` alone, while teaching the storage a brand new recipe needs `/reload` and then `/ults reload`.

---

## Getting Started

1. Look at a container (a chest, a barrel, a modded crate, …) and run `/ults bind` — optionally with a note such as `/ults bind "north warehouse"`.
2. Feed it. In the default **void** storage mode every enabled hopper that pushes into a bound container is emptied, so the bound container itself never fills up.
3. Run `/ults` to open the storage screen and withdraw items.
4. Run `/ults show` to see outlines around every bound container within 48 blocks.

---

## Configuration

### Configuration File Location

```
config/ults.json
```

### Example Structure

```json
{
  "general": {
    "language": "en_us",
    "itemVisibility": "AVAILABLE",
    "storageMode": "VOID"
  },
  "input": {
    "permissionLevel": 2,
    "maxBindings": 0,
    "drainInterval": 2,
    "multiBlockContainers": [],
    "crafting": "DISABLED",
    "allowFullInventory": false,
    "allowBulkWithdrawal": true,
    "bulkWithdrawalStacks": 36,
    "withdrawalRate": 64
  },
  "special": {
    "bundleSlots": 120,
    "stackRule": "COMPONENTS",
    "filterMode": "OFF",
    "filters": []
  }
}
```

`input.withdrawalRate` is shared by quantity and bulk withdrawals. `input.allowBulkWithdrawal` and `input.bulkWithdrawalStacks` control the take-everything offer and its selection limit. Loading an older configuration migrates `takeAllRate`, `allowTakeAll` and `takeAllStacks` to these names while preserving their values. If both names exist, an explicitly set new value wins; the saved file contains only the new names.

A missing file, a missing field or an unknown enum value falls back to the default, and the file is rewritten in canonical form on load. `general.language` and `general.itemVisibility` / `general.storageMode` are applied by `/ults reload`. `special.bundleSlots` is applied as soon as the next stack is stored or the world is loaded again, so lowering it trims a bag without waiting for a restart. A file written by an earlier release holds `maxEntries`, `filterEquipment` and `stackByData`; all three are dropped on load, because none means what it used to — the old cap counted every special stack the storage kept while `bundleSlots` counts the stacks one bag holds, equipment is no longer named by a switch, and pooling identical data is now simply how the storage works rather than something to turn off. **Filtering is by `special.filters` alone**, so a server that wants a farm's gear gone writes that gear down.

---

## Configuration Details

### General Settings

| Field            | Type     | Description                                                                                                            |
|------------------|----------|------------------------------------------------------------------------------------------------------------------------|
| `language`       | `string` | Locale used for server-rendered messages, the command output and the storage UI; a bundled locale such as `en_us`.    |
| `itemVisibility` | `enum`   | Default visibility for players who have not chosen one themselves: `ALL`, `SURVIVAL` or `AVAILABLE`.                     |
| `storageMode`    | `enum`   | Where the stored items live: `VOID` or `REMOTE`.                                                                        |

### Storage Settings

| Field                   | Type       | Description                                                                                                                     |
|-------------------------|------------|---------------------------------------------------------------------------------------------------------------------------------|
| `permissionLevel`       | `int`      | Vanilla permission level `0`-`4` required to bind a container, to bind an area, to delete bindings, to toggle the highlight and to reload; defaults to `2`. |
| `maxBindings`           | `int`      | Maximum number of containers the storage may bind; `0` means unlimited.                                                          |
| `drainInterval`         | `int`      | Ticks between two drain visits of the same bound container, from `1` to `20`; defaults to `2`. The hoppers of an input path are emptied every tick regardless, see below. |
| `multiBlockContainers`  | `string[]` | Block ids whose connected blocks together form one large container; see below. Empty by default.                                 |
| `crafting`              | `enum`     | Whether a withdrawal may craft what is missing: `DISABLED`, `SHULKER_BOXES_ONLY` or `ALL`; see below. Disabled by default.       |
| `allowFullInventory`    | `bool`     | Whether outputs that do not fit may drop at the player's feet. Rejected drops return to storage. Defaults to `false`: quantity and take-everything requests deliver the fitting portion and leave the rest stored; a full backpack blocks delivery. Quick withdrawals still require room for the whole batch. |
| `allowBulkWithdrawal`          | `bool`     | Whether players may empty a stock out with **take everything** at all; `true` by default. With it off the offer is not there: the hint on a row and on a bag's status book leaves the line out, and the clicks that would start one do nothing — no screen, no sound and no message. |
| `bulkWithdrawalStacks`         | `int`      | How many stacks one **take everything** takes at most, from `1` up; defaults to `36`, one backpack's worth. A bigger value takes more per click; the storage pours it out a tick's worth at a time either way, so this decides how much one click is *for*, not how fast it leaves. |
| `withdrawalRate`           | `int`      | Maximum output units per tick for quantity and take-everything streams (loose items or filled boxes), from `1` up; defaults to `64`. Actual throughput also respects the 36-stack tick limit and shared planning budget. See [Taking everything](#taking-everything). |

### Special Settings

A stack that carries data of its own — a custom name, stored enchantments, a written book, a brew, anything worn — is **one kind of thing per its components**: two stacks of the same kind are one stack, whether or not the game would let them sit in one slot, and a stack **no tab entry matches exactly** is a **bag** of the special category, because a category has no row to put it on. These settings decide what counts as one kind, how much one bag holds, and which of those stacks are worth keeping at all; see [Special Data Items](#special-data-items).

| Field                   | Type       | Description                                                                                                                     |
|-------------------------|------------|---------------------------------------------------------------------------------------------------------------------------------|
| `stackRule`             | `enum`     | When two stacks of one item are the same kind and pool into one row: `COMPONENTS` (every component identical, durability included) or `TOOLTIP` (the same thing read on them is enough). Defaults to `COMPONENTS`; see below. |
| `bundleSlots`           | `int`      | How many stored stacks one bag holds. Defaults to `120`, which is three pages of the bag screen (forty-five stacks to a page). Past it the **least recently stored** stack is destroyed as new ones arrive. |
| `filterMode`            | `enum`     | What the filter does to the items it names: `OFF`, `KEEP_FULL_DURABILITY` or `FILTER_ALL`; see below. Defaults to `OFF`.          |
| `filters`               | `string[]` | Item ids the filter takes out of the storage. Namespaces may be omitted and case is folded. **Nothing is filtered unless it is named here.** Empty by default. |

#### When two stacks are one kind

The item and its name are the precondition: a plain sword and an enchanted one are two kinds, and so are
two swords with different names. `special.stackRule` decides how much of the rest has to agree.

| Rule | What it means |
|------|---------------|
| `COMPONENTS` (default) | Every component has to be identical, durability included — the game's own rule for two interchangeable stacks. A stack handed over is always the very one that was stored. |
| `TOOLTIP` | It is enough that a player would read the same thing on them, so two swords worn differently pool while a different enchantment never does. A stack handed over is then a stack of that kind rather than the exact one that was put in. |

Changing the rule re-reads the storage: `/ults reload` pools what is already stored by the new rule, so
switching to `TOOLTIP` merges the rows it can merge.

**The one rule is used everywhere two stacks are compared**, not only in the special category: what pools
into one stored row, what a catalogue row counts as its own stock — under `TOOLTIP` a sword that is merely
more worn is counted by its item's row — and whether a category can show a stack at all. So with
`TOOLTIP`, a plain sword and a worn plain sword are one kind and both belong to Combat, while an enchanted
one is a kind of its own and still goes to the special category.

#### What a category can hold, and what a bag is for

A category is the creative tab's listing, and it can only show a stored stack of a kind some tab entry is
— under the components rule that means component for component, and under the tooltip rule that means the
same thing read on both. Everything else — the enchanted sword, the named pickaxe, the brew nobody lists —
goes to **Special Data Items**, where one bag holds every such stack of one item. That is the rule behind
both examples: a plain netherite sword is in Combat, an enchanted one is in the special category.

#### What the filter names, and what it cannot see

The filter decides by **item id**, and only by the ids written in `filters`: there is no switch that
names equipment for you. Two things are worth knowing before trusting it to keep a bag clean.

**1. A mob's gear is put there by game code, so no loot table mentions it.** A zombified piglin's
spear and a piglin's golden armour are handed to the mob while it spawns; the zombified piglin's own
loot table drops nothing but rotten flesh, gold nuggets and gold ingots. A farm's equipment output
therefore has to be named item by item — `minecraft:golden_spear`, `minecraft:golden_helmet` and so on
— which is exactly what the list is for.

**2. Six item kinds have no component-free form at all**, so every instance of them carries data and none of them can ever be a plain stack: `enchanted_book`, `potion`, `splash_potion`, `lingering_potion`, `tipped_arrow`, `suspicious_stew`. None of them carries durability, so no list of equipment could ever name them, and **enchanted books are the one to watch**: villager halls, fishing and chests all produce them, and every enchantment and level is its own kind of stack. They are rows of the book item like any other; name them in `filters` if you want them gone.

**3. Component variants of an item that also has a plain form** are named by `filters` too: a banner
with patterns, a goat horn with an instrument, a bucket with a fish, a decorated pot, a compass bound
to a lodestone.

**The backstop:** `special.bundleSlots` bounds one bag, and it destroys the least recently stored entry
once that bag is full, so a bag can never grow without limit. Only the items no category lists take a bag
at all now, and there is no cap across a whole category, so a server that takes in a lot of
data-carrying stacks — a farm's gear, enchanted books — should keep `filters` up to date.

---

### Special Data Items

A stack that carries data of its own — a named sword, an enchanted book, a written book, anything damaged — is **one kind of thing per exact set of components**, and it is a row of its own item in the category that item belongs to, right after that item's own rows. What is left for a **bag** is a stack whose item no visible category lists at all, so there is no row to put it on. See [How variants are stacked](#how-variants-are-stacked).

- **One row per kind.** Two stacks of the same kind are **one stack**, exactly as they would be for a plain item: what makes two of them different is what they carry, not how many of them fit in a slot. Which kinds those are is `special.stackRule`; the item and its name are always part of it.
- **A category only shows what its own tabs list**, in the sense the stacking rule gives that word: under `COMPONENTS` component for component, under `TOOLTIP` the same thing read on both. A netherite sword is the combat tab's business; an **enchanted** netherite sword is not, because no tab entry reads like it — so it is special.
- **A bag row.** A stored stack no category can show goes to the special category, where one bag keeps every such stack of one item together: **Special Data Items** shows one row for each such item and says how many stacks are in the bag and when the newest of them arrived. Rows are ordered newest first, in the special category and at the end of **All Items** alike.
- **The bag row is its bag.** One row stands for one item and everything of it that carries data of its own, and it is drawn as **the stack that arrived last** — an enchanted book lists the enchantment it holds, a named sword the lore written on it — under a title that names the bag: `【附魔书】收纳袋`, which is the item's own name in brackets with the word for a bag after it. `最新` / `Latest` marks the block under that title as the newest arrival, and a short yellow rule closes it off from the bag's own facts: how many stacks the bag holds — or, while a search is on, how many of them that search kept — and when the newest arrived.
- **The row answers one click: a right click opens the bag.** There is no shortcut take here, because what a bag holds is a crowd and the row is drawn as only the newest member of it — everything inside is taken from inside, on rows of its own, where the bag is open and the whole of it is in view. A left click, and both shift clicks, do nothing. See [Bag Screen](#bag-screen) and [Taking everything](#taking-everything).
- **A cap with the least recently stored going first.** A bag holds at most `special.bundleSlots` stacks (120 by default, three pages). Once it holds more, the ones that arrived longest ago are destroyed as new ones arrive. The cap counts **stacks, not pieces**: two differently named swords cost two of it, while putting the same named sword in twice costs one. The cap is enforced again when the world loads and on `/ults reload`, so lowering it trims an existing bag.
- **A filter for what is not worth keeping.** Items named in `special.filters` are taken out of the storage. `special.filterMode` decides how hard it comes down on them:

| Mode                   | What happens to a filtered special stack                                                            |
|------------------------|-----------------------------------------------------------------------------------------------------|
| `OFF`                  | Nothing. The list is not in effect and every special stack is kept. This is the default.             |
| `KEEP_FULL_DURABILITY` | A pristine one is kept, a worn one is taken out of the storage. A stack that carries no durability counts as pristine. |
| `FILTER_ALL`           | Taken out of the storage whatever state it is in.                                                   |

- **The filter means different things to the two storage modes**, because they own their items differently:

| Mode     | What a filtered stack gets                                                                                                              |
|----------|------------------------------------------------------------------------------------------------------------------------------------------|
| `VOID`   | **Destroyed.** The storage holds these items itself, so they are thrown away on the way in — and applying the filter again clears out what was already stored, so changing the filter empties the store of what it now names rather than only stopping new arrivals. |
| `REMOTE` | **Hidden.** The items sit in containers a player bound, which are not the storage's to destroy, so nothing is ever removed: a filtered stack simply never appears in a listing, a box count or a plan. Take it out of the chest by hand if you want it. |

- **Only bagged stacks are affected.** A plain iron sword stacks with its own kind, so it is never special and the filter never touches it — however its item is listed. A stack that can hold more than one piece pools with its kind for the same reason. The filter only ever sees the stacks that would otherwise take an entry in a bag.
- **A stack a player renamed is never touched either.** Giving something a name is a deliberate act — the stack is somebody's own thing, not anonymous loot — so the filter leaves it alone whatever the list and the mode say, in both storage modes. Enchanting is *not* renaming: enchanted gear is filtered like anything else. A named shulker box and everything inside it can therefore never be thrown away by the filter.
- In `VOID` mode the filter is applied on the way in, including the contents of an unnamed shulker box being emptied into the storage, and again whenever the filter is applied. Anything it destroys is gone: there is no bin to recover it from.
- **What a farm produces has to be named item by item**: no loot table mentions the gear the game hands to a mob while it spawns, so there is no switch that could name it for you — see [what the filter names](#what-the-filter-names-and-what-it-cannot-see).
- The Mod Menu screen validates every item id while you type and refuses to save one that names no item of the running game.

---

### Automatic Crafting

With `input.crafting` enabled, a withdrawal that runs short is completed by crafting, as long as the storage holds the station the recipe needs; an item that is not stored at all but can be crafted can be withdrawn just the same:

| Mode                  | Craftable                                                                                          |
|-----------------------|----------------------------------------------------------------------------------------------------|
| `DISABLED`            | Nothing. This is the default.                                                                       |
| `SHULKER_BOXES_ONLY`  | Only an **uncolored shulker box**, which is what a full-box withdrawal packs into.                  |
| `ALL`                 | Any item the stored station can produce.                                                            |

- A crafting recipe needs a **crafting table** in the storage, a stonecutting recipe needs a **stonecutter**. The station is only used, never consumed.
- Item rows show a positive additional crafting amount (`Craftable: N`), including when outputs are already stocked. Known zero quantities hide the line; pending quantities show "Calculating". Recipes still require their stored station.
- Recipes are followed **all the way down**: an ingredient the storage does not hold is crafted first, as many levels deep as it takes. Logs and shulker shells are therefore enough for a shulker box, because the plan makes the planks and then the chest on the way. `Shulker boxes only` works the same way, it just limits what the result may be. Recipes that feed each other, such as a block and its ingots, cannot make the search loop.
- Routes are compared and the best one wins: whichever route produces the most from the current stock, and among those the one needing the fewest operations. A recipe that a stonecutter does in fewer operations is therefore preferred over the crafting table while a stonecutter is stored, and the crafting table takes over when no stonecutter is left.
- Only the missing part is crafted. Withdrawing 64 of an item while 60 are stored and the recipe produces 4 per operation runs the recipe once and takes the remaining 60 from the stock. What a batch makes on the way and the request does not need, extra planks for example, stays in the storage instead of disappearing.
- Recipes whose result or ingredients the game decides while it runs (dyeing, fireworks, banner and map copying, repairing, and the like) are not used.
- Crafting-table runs return containers from the ingredients actually consumed: one cake returns three empty buckets. Planning and execution use the same rule, and a failed run rolls its containers back too.
- **Special data items never show a craftable amount.** Such a row shows how many stacks are in its bag and when the newest of them arrived instead, because what could be made of an item says nothing about one particular named sword; a right click opens the bag. The crafting catalogue is not even asked about them.
- The empty boxes of a full-box withdrawal are taken in the order **plain boxes in storage, then boxes crafted for the request, and only then the other colours**: a box that can be crafted is never passed over in favour of a coloured one, and when not all of them can be crafted, the colours cover what is left. The screen says how many stored boxes are used and how many are crafted before the confirm button is pressed.
- The withdrawal screen lists what will be crafted before the confirm button is pressed.
- On a very large storage a row's amount may take a moment to appear: a tick only ever spends a short while working craftable amounts out, so the rows a page shows fill in over the next tick or two instead of stalling the server. Once worked out they are remembered until the contents change, and a row that is still waiting keeps the answer its pile's reach gives it rather than disappearing.
- `/ults reload` reads the server data again: the recipes used for automatic crafting, the creative tabs and the survival catalogue. See [what `/ults reload` can and cannot pick up](#what-ults-reload-can-and-cannot-pick-up) — loot tables and trades follow it alone, a changed recipe needs a vanilla `/reload` first.

---

### Storage Modes

| Mode     | Behaviour                                                                                                                                                                                       |
|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `VOID`   | Default. Bound containers are inputs: the drain loop moves their contents (and the contents of the hoppers feeding them) into a server-side store that is written with the world.                  |
| `REMOTE` | No store of its own. The bound containers **are** the storage: contents are aggregated from them while their chunks are loaded, and withdrawals are taken straight out of them.                    |

In `REMOTE` mode nothing is drained: the bound containers keep their items, so they work as chests that are reachable from the storage screen. Switching the mode takes effect with `/ults reload`; existing bindings are kept either way.

#### What is stored as it is

- A **named shulker box** goes in as it is, contents and all: the storage never opens it, and it comes back out unchanged. An unnamed box is opened, so what it holds joins the storage and only the empty box is kept.
- A **named empty box is never used as packaging material** for a full-box withdrawal, even though it is empty: boxes for packing are taken from the unnamed plain boxes, crafted if those run out, and from the other colours only after that.
- Any stack whose components no creative tab entry describes — a **custom name** on anything, stored enchantments, written books and the like — is listed under **Special Data Items** as a bag, one row per item, and the row is drawn as the newest stack that arrived in it, marked off by two yellow rules that say so. A variant that can hold more than one piece pools with the copy already there, so it is one stack in that bag. That category is always reachable in the category pager and shows nothing but the paper while it holds nothing. A bag is capped and can be filtered; see [Special Data Items](#special-data-items).

---

### Item Visibility Modes

| Mode        | Listed items                                                                                                                       |
|-------------|------------------------------------------------------------------------------------------------------------------------------------|
| `ALL`       | Every item and every category, even with nothing stored.                                                                            |
| `SURVIVAL`  | Everything a survival player can obtain, plus anything that is stored. Categories without content are hidden.                       |
| `AVAILABLE` | What the storage can hand over right now: everything in stock, plus everything that can be crafted at this moment while automatic crafting is on and a station is stored. Categories without such an item are hidden. This is the configured default. |

The configured mode is the **default**, not a fixed setting: every player can switch their own mode with a right-click on the status book of the storage screen, and the choice is remembered with their other view settings. Showing everything is only on offer while the configured default already is `ALL`, or while the player may manage the storage, so a plain player cannot open a view the owner did not hand out. There is no way back to "default" other than switching again.

The special item filter is a second, separate kind of hiding: it applies in every visibility mode and in the `REMOTE` storage mode it is what keeps filtered stacks out of the listings. See [Special Data Items](#special-data-items).

#### How the survival catalogue is derived

Minecraft has no "survival obtainable" flag, so the catalogue is built from everything the server knows when a world loads (and again on `/ults reload`):

- the creative tabs a normal player sees, which already cover the acquisition paths that are pure code and appear in no data file (fishing, filling buckets, brushing suspicious blocks, trading, enchanting, …)
- every recipe result, read both from the recipe manager and from the recipe files, so special recipes such as fireworks, tipped arrows and map copying are included
- every item any loot table can drop, including nested tables and item tags
- every item villagers offer, from `villager_trade` and `trade_set` data
- code sources that are described by no data file: every brewable potion, the water bottle and every enchantment the enchanting table can roll or a villager can trade

Minus the creative-only tabs (`op_blocks`, `spawn_eggs`), every `*_spawn_egg` item, and a short verified list of blocks that vanilla lists in a normal tab but no survival player can obtain (bedrock, budding amethyst, reinforced deepslate, vault, end portal frame, chorus plant, frogspawn, suspicious sand and gravel, spawner, player head, petrified oak slab, and the seven infested blocks, because silk touch drops the block they pretend to be).

Because everything comes from the tabs, data packs and the recipe manager of the running world, **modded items are covered automatically**.

#### Base forms and types

| Case                                                                   | Rows in the survival view                                                                    |
|------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| The catalogue holds the item without any component (a painting, a firework rocket, a goat horn, an ominous bottle, a banner) | Exactly that base form; variants are never used to stand in for an item |
| The item only exists as variants, and the data knows the values of that type (potions, enchanted books, tipped arrows) | One row per type survival can really produce: every brewable or lootable potion, every enchantment a book can carry |
| The item only exists as variants and the data knows nothing about that type | Every catalogued variant, because nothing can be ruled out                |
| Anything that is really stored                                          | Always keeps its own row, whatever the mode is                                               |

The uncraftable and lucky potions therefore never appear, while a potion that can be brewed or found does. Stocked rows are never collapsed, so a specific enchanted book that is stored stays visible even when its enchantment is not otherwise listed.

---

### Multi Block Containers

Some modded containers split one inventory over several blocks and give every part a container of its own. Listing the block id of such a container in `multiBlockContainers` makes the storage treat its directly connected blocks of the same id as one large input:

```json
"multiBlockContainers": ["modid:big_chest"]
```

- Ids may omit the namespace (`chest` is read as `minecraft:chest`), and case and surrounding spaces are folded.
- Vanilla chests, trapped chests and copper chests are recognised without configuration; barrels never merge, because they are always single blocks.
- A configured block joins the same block only, climbs over adjacent same-id blocks in all directions (whether a part has an inventory of its own makes no difference), and is capped at 128 parts.
- The Mod Menu list validates every entry while you type and refuses to save an id that names no block of the running game.

---

### Languages

`en_us`, `zh_cn` and `zh_tw` ship with the mod. Any other locale is discovered at runtime from `assets/ultimate-storage/lang/<locale>.json` inside the mod, as long as the filename matches `[a-z0-9][a-z0-9_-]*` and the file contains a non-blank `ults.language.name`; `en_us.json` is required as the fallback. A missing key falls back to English and is reported in the server log.

#### Searching by name

An item's name only exists on the client: the server sends the translation key and the client fills in the words of the language it is set to. A search is typed on the client and answered on the server, so the server has to know the words — and a dedicated server's own files carry **English only**. The mod therefore reads names from four places, lowest first, and answers a search in **the language the searching player's own client reports to the server**:

| Source | What it brings |
|--------|----------------|
| The game's own files, read at runtime | English, which a dedicated server always has. |
| `assets/ultimate-storage/itemnames/<locale>.json`, baked at build time | The words the game itself gives its items, for **every language the mod ships** (`en_us`, `zh_cn` and `zh_tw` today). |
| The language files of every loaded mod | Each mod's own items, in whatever languages that mod ships. |
| `config/ults/lang/<locale>.json` | Whatever a server owner drops in: another language entirely, or a correction of their own. It wins over everything else. |

- A language is always read on top of English, the way the client does it, so a word a language does not carry falls back to its English one instead of going missing.
- **A search reads the category it is typed in.** Select Redstone Blocks, search for `ore`, and the matches are redstone blocks; the filter screen's count is the number of rows that category will show.
- **A bag is searched by what is inside it.** A bag row shows one stack but stands for all of them, so in **Special Data Items** it is kept when any stack stored in it answers the search, and opening it lays out exactly those stacks and hides the rest. Searching `锋利` there therefore finds the bag of enchanted books and opens onto the books that carry it. While a search is on the row counts what that search kept, `In this bag, matching: 1`, which is what opening it will lay out — the bag screen's own answer and not a second guess at it.
- **What a stack carries is searched too**, in the player's own language and by the ids behind it: `锋利` and `sharpness` both find the enchanted book holding that enchantment, a potion is found by its effect, a banner by its pattern — anything the tooltip says, because that is what is being matched.
- **An item id is always searched too** (`diamond`, `minecraft:diamond`), which is what makes the search work on a server with no language data for that player at all.
- Names are the ones the item really carries: a custom name is searched as it was written, and a name built out of other names — a potion, a spawn egg — is put together before it is matched.
- The tables are baked from the Minecraft assets Loom already downloaded, so they follow the version in `gradle.properties` on their own — and **which languages are baked is read from the mod's own language files**, so dropping `ja_jp.json` into `assets/ultimate-storage/lang` is all it takes to have Japanese item names baked as well (English comes out of the game jar rather than the asset index). A language the game has no words for is reported and skipped, and a build without those files simply leaves the game's own English names in place. `/ults reload` rereads everything, `config/ults/lang` included.

---

## Container Inputs

### Binding

Look at a container and run `/ults bind`; the note is free text of at most 64 characters without control characters and without the formatting character `§`. Binding the same block twice is rejected, and the limit (`input.maxBindings`, `0` = unlimited) reports a clear message while an area binding continues until the limit is reached.

### Area Binding

`/ults bindarea <first> <second>` binds every container between two corner positions. Only loaded chunks are scanned, the selection may span at most 128 chunks per axis, and containers that are already bound keep their binding, note and all. The reply distinguishes three cases: everything new was bound, some containers were already bound, or the binding limit was reached and the rest was skipped.

### Draining (void mode only)

| Behaviour                  | Detail                                                                                                                       |
|----------------------------|------------------------------------------------------------------------------------------------------------------------------|
| Drain interval             | Each binding is visited every `drainInterval` ticks, distributed over the ticks so a large warehouse is not visited at once.  |
| Idle skip                  | A binding that produced nothing for a while is visited less often: every 2nd visit while a hopper path feeds it, every 8th visit otherwise. |
| Hopper emptying            | Enabled hoppers that push into the bound container, or into another hopper of that path, are emptied and their transfer cooldown is reset; the chain is followed up to 8 hoppers deep. |
| Hopper suction             | In `VOID` mode the hoppers of an input path are emptied **every tick**, not only on the drain schedule, so a bound container pulls items in as fast as its hoppers can push them: about one item per tick per hopper instead of the vanilla 8-tick cycle. |
| Unloaded chunks            | Bindings in unloaded chunks are skipped and keep their contents until the chunk is loaded again.                              |
| Vanished block             | A binding whose block is gone (air) is removed and reported to the players.                                                   |

### Removing Bindings

`/ults delete` removes the binding you are looking at. `/ults delete #3` and `/ults delete #5-#8` remove bindings by number; `/ults list` shows the numbers and offers a click-to-delete line to players that may delete. Breaking a bound block with sneaking removes its binding as well.

---

## Storage Screen

`/ults` opens a 9×6 screen. The left column is the category pager: an up arrow, four category buttons and a down arrow. The next column is a divider, and the remaining 5×7 area shows up to 35 item rows per page. The top row of that area holds the previous-page arrow, a status book and the next-page arrow; while a single page is left, both item arrows are left out completely, because there is nowhere to turn to. The category arrows are always shown.

- **Categories** are the creative tabs discovered when the world loads, plus **All Items** — every other category added up, with the bags at the end — and **Special Data Items**, which holds only the stored stacks whose item no category lists at all. Tabs that hold nothing listable in the current visibility mode are hidden, while **All Items** and **Special Data Items** always stay reachable, even while they are empty. A list without a single row shows a paper in the middle of the item area instead. A bag row stands for one item and every variant of it, is ordered by when the newest stack of it arrived, is drawn as that newest stack with its own lines set into it, and is marked off by its short yellow rules, in the special category and in the tail of **All Items** alike. While a search is on it counts what that search kept instead of what the bag holds.
- **Status book** shows the selected category, the category page, the item page, how many item types are listed and stored, the current filter and the current visibility; left-clicking it opens the **filter screen**, where part of what an item is or what it carries is typed, applied, or cleared, and right-clicking it switches the visibility mode this player sees. **A search reads the category it is typed in** — select Redstone Blocks, search, and the matches are redstone blocks — and it answers by item name, by item id and by **what a stack carries**, so `锋利` or `sharpness` finds the enchanted book holding it and, in the special category, the bag holding that book. The count appears while something is typed and is the number of rows that category will show, so the number and the listing can never disagree. See [Searching by name](#searching-by-name).
- **Item rows** show the stored amount and the equivalent in shulker boxes once it reaches a box, and say what a click does: **left takes one stack right there** — as much as the stack holds, or everything there is when there is less than one, crafted first while automatic crafting is on and a station is stored — and **right asks how many**, opening the **withdrawal screen**. That screen always opens, even when a single piece is all there is: how much leaves is the player's answer to give, and the screen says what there is before anything moves. Then shift and a left click packs a **whole shulker box** of it, and shift and a right click takes **everything** — which asks first, and offers the answer with or without crafting. A **bag row** answers two clicks: a right click that opens the **bag** — because the stack it is drawn as is only the newest one inside and not the bag itself — and, while `input.allowBulkWithdrawal` is on, shift and a right click that asks to **empty the whole bag out**, which is the same question the bag's own status book asks. See [Special Data Items](#special-data-items), [Bag Screen](#bag-screen) and [Taking everything](#taking-everything).
- **The hints never promise more than the storage can do.** A row lists every click it answers, and the ones it cannot serve are left out rather than shown greyed. The left line always names the number a click takes — `Left-click: take 64.`, or the few that are left while there are fewer than a stack of them. The `Shift + left-click: take a full shulker box.` line is left out altogether while a full box could not be packed — not enough of the item, no box stored or craftable, or the item being a shulker box itself — so a row then only offers taking everything. A **bag row lists the lines it answers**: `Right-click: open the bag.` and, while taking everything is allowed, `Shift + right-click: take everything.` — nothing about a left click, because a left click does nothing there. While amounts are calculating, changing withdrawal hints are replaced by a waiting message. A click always rechecks live quantities and cannot withdraw until they have settled. Packing a box that cannot be filled says `Not enough stored or craftable to fill a box.` in the chat, in red, and takes nothing.
- **The lines list every click at once rather than following the shift key**, because a line is built on the server when the row is drawn and it has no way of knowing whether the key is held by the time it is read. The server therefore cannot redraw the hints when shift is pressed, and a row says everything it answers instead. Shift clicks themselves are not affected: the client sends a shift click as `QUICK_MOVE` with the mouse button beside it, and both reach the server as they are.
- Whatever a click takes goes into the backpack the way a withdrawal does: what does not fit is refused with a message, or dropped on the ground with `input.allowFullInventory` on.
- **Every refusal a screen makes is said in the chat**, in red, so it can be read back after the fact: no room in the backpack, a box that cannot be filled, nothing left to take. Only the highlight readout while you look at a bound container uses the line above the hotbar, because that one is a live reading rather than an answer to a click.
- The selected category, category page, item page, filter and visibility are saved per player in the world data, so everyone returns to their own view — **and a withdrawal does not lose it**: the screen comes back exactly where it was.
- Clicking plays a short sound that only the clicking player hears: a light click for opening, paging, selecting and switching, and a brighter pickup sound for applying or confirming. Cancelling a screen stays silent.
- Each stored kind and each craftable quantity has its own sliding quiet window. Only an actual change to that kind's stock or computed craftable quantity marks its row "Calculating"; other rows retain their numbers and remain usable. Taking 3000 planks from 2000 stored planks leaves logs usable throughout the first 2000; logs wait only after crafting actually consumes them. Recipe dependencies invalidate only relevant cached answers, without treating a possible recipe link as an inventory change. The quiet duration is `max(input.drainInterval, delivery interval) + observation lag + 1` ticks: delivery currently runs every tick, and remote mode adds the 8 × 4 tick shard sweep. Continuous changes restart that kind's timer. New quick, quantity and bulk withdrawals of pending kinds wait, including old button callbacks; existing streams continue. Bag totals follow only the variants in that bag. Stable quantities reappear automatically even without another stock change.

### Withdrawal Screen

A **left click on a row takes a stack right away**; this screen is what a **right click** opens, and it opens for every row that has anything to give, a single piece included: how much leaves is a question only the player answers, so there is no amount small enough to skip asking. It asks for an amount above an anvil-style input and offers two modes — and its mode slot **glints in item mode and goes dark in full-box mode**, because what a glint says is "this is the thing you are taking", not "this is the box it goes in":

| Mode           | Meaning                                                                                                    |
|----------------|------------------------------------------------------------------------------------------------------------|
| Item mode      | Withdraw that many loose items.                                                                            |
| Full-box mode  | Treat the amount as a number of shulker boxes, consume that many empty boxes and fill each with 27 full stacks. |

The left slot cancels; the middle slot switches modes and shows stored, requested and additional craftable amounts. Existing stock does not hide additional crafting. A known zero craftable amount hides its line; an unfinished answer says "Calculating". Stored amounts use the same waiting state, and confirmation becomes a barrier while waiting. The cancel label remains in the tooltip so vanilla cannot copy it into the input field.

Positive quantities up to `Long.MAX_VALUE` have no 36-slot limit on the total request. A request exceeding stored plus craftable quantities shows a shortage barrier and is never silently reduced to available stock. Full-box requests first validate all contents and boxes together against shared materials. Previewing never allocates the complete output. Confirmation starts the same delivery stream as take-everything, reading live stock and committing each batch separately. With `input.allowFullInventory` off, only the fitting portion is selected; with it on, excess outputs drop in batches. Both action icons remain present for invalid text. If stock becomes insufficient after confirmation, the stream stops safely and reports actual delivery.

Each stream delivers at most `withdrawalRate` output units (loose items or filled boxes) per tick and at most 36 output stacks per tick. All players share a 20ms planning budget with rotating turn order. An unfinished plan waits for a later tick without consuming stock or claiming a shortage. A player has one stream; a new request replaces the old one, and repeated clicks on the same confirmation cannot submit it twice. Take-everything retains its `bulkWithdrawalStacks` selection limit; explicit quantity input does not use that limit. Delivery failures retain the existing restoration rules.

The filter screen uses the same three slots: cancel, clear the filter, and apply what was typed.

### Bag Screen

A bag row's bag is opened by a **right click** on the row; while taking everything is allowed, **shift and a right click** on the row empties the whole bag out — the same question the bag's own status book asks, without having to open it first. The row answers no left click, because the stack it is drawn as is only the newest one inside. Everything is taken from inside the bag, which is where the box is packed and where the bag is emptied from. The bag screen is a roomy 9×6 screen whose top five rows are **the stored stacks themselves**, one stored stack to a slot, newest first: **forty-five to a page**, and therefore three pages for a full bag at the default capacity.

- **A slot shows one piece and its lore says how many are there**, whatever the stack holds: a bag reads as rows of one thing each — the row says `Stored: 64` — rather than as a row of numbers, and holds for a thing that does not stack just the same.
- **A stack inside the bag behaves exactly like a row of the storage screen**, because it is one: a left click takes one stack of it, a right click asks how many, shift and a left click packs a whole shulker box, and shift and a right click takes everything of that stack. There is no separate way of taking things out of a bag, and the lines are the same smart ones: the left line says how much of that stack is really left while it is less than a stack, and the box line only appears while a full box could really be packed.
- **The bag row itself is drawn as the stack that arrived last, contents and all.** Hovering it reads what that stack really is — its name, the enchantments on it, the lore written on it, the damage and speed it adds — colours and numbers and all, because those are the lines the game itself draws on that stack. Two things make that read like a hover a player knows:
  ```
  【附魔书】收纳袋
  ───── 最新 ─────
  附魔书
  锋利 V
  ──────────────
  袋中符合筛选：1
  最近存入：2026-09-28 22:26
  右键：打开收纳袋。
  ```
  - **The title names the bag, not the stack**: the item every stack in it is a kind of, in brackets, with the word for a bag after it. A bag of differently named netherite swords is a bag of netherite swords however its newest one is called. The line is built from the item's own name *component* rather than from finished words, so a Chinese client reads `【附魔书】收纳袋` and an English one `[Enchanted Book] Bag`.
  - **The lines under it are the stack's own, worked out for the reader.** The game asks the player for the base values behind a weapon's damage and speed, so the same stack read without a player says `-2.4 Attack Speed` where the client says `1.6 Attack Speed`; the row hands the player in, so what it prints is what the hover prints. Nothing is added between those lines and nothing is taken out — a stack whose own lines hold a gap keeps it, one whose lines run straight on runs straight on — so the block reads exactly as the hover does.
- **Two colours meet on the title, and they mean two things.** The item's own name, the one in the brackets, is drawn the way the game draws that item's name anywhere else — in **its rarity's colour** — so a bag of rare things says so in the same words and the same colour a hover would; it is the *item's* rarity, not the newest stack's, because the title names the kind of thing the bag holds and that does not change because the stack that arrived last happens to be enchanted. The bag's own words, the brackets and the name of the bag, wear a pink of the mod's own instead: `#FF88CC`, not one of the game's named colours, so it can never be read as a rarity or as one of the row's facts. Every other colour on the row is spoken for too — yellow for the label, the rule and the other labels, green for every number, dark green for the lines the game writes about a weapon, grey for the hint — and green was tried as the bag's colour and failed exactly that way: the block under the title is full of greens, so the title read as one more number. The game still wraps the whole line in a rarity colour of its own before drawing it, but a colour set on a part wins over that wrapper, so each part keeps the colour it was given. The icon is the newest stack exactly as it is, glint and all, and a click hands over the stored stack itself.
- **The line under that title, the stack's own name, follows the stack.** It comes straight from the game's own tooltip, so it wears the rarity the game gives *that stack* — raised a step when it is enchanted — in whatever colour that rarity is.
- **The search that found the bag is still on inside it.** Opening a bag in the special category lays out the stacks the search kept and hides the rest, so a bag found by `锋利` opens onto the books that carry it; the book in the bottom row names that search and glints while one is on. While a search is on the row above says one number and one only — how many of the bag's stacks it kept, which is what opening the bag will lay out — because that is what a player looking at a filtered listing is asking about; with no search it says how many the bag holds. Both are the bag screen's own answer, not a second guess at it.
- What a click takes goes into the backpack the way a withdrawal does: what does not fit is refused with a message, or dropped on the ground with `input.allowFullInventory` on. The storage follows immediately: what was handed over is no longer stored from that moment.
- The bottom row holds the way back to the storage, **the page arrows on either side of the status book** while more than one page is filled, and that book: it shows how many stacks the bag holds, which page is shown and which search is on, and a **right click on it asks to take the whole bag** — every stack in it, which is where emptying a bag belongs. A bag that is emptied simply shows the paper in the middle — or, while a search is on, a paper saying that nothing in it matches — and the row behind it disappears from the storage screen as soon as nothing of that item is left.
- Bags refresh themselves while the storage changes, exactly like the other screens: an arrival joins the front, a stack the filter destroys leaves, and the page a player is on is kept.
- In `REMOTE` mode a bag can only hand things over, never take them in: what leaves a bag is taken out of the bound containers it came from, and a container that ran dry in the meantime simply leaves the bag as it really is.

### Taking everything

Both bags and ordinary items count physical stacks toward `bulkWithdrawalStacks`: eight unstackable swords cost eight stacks even when they share one row. Bag rows can leave in batches, strictly limited to `withdrawalRate` pieces per tick. Confirmation rereads rows and amounts together; items deposited afterwards stay for the next request.

Shift and a right click asks to empty a stock out, and that is a question rather than a command: **three buttons centred in one row** — the way back at slot 3, the mode at slot 5, the confirmation at slot 7. It is a plain container and not the anvil screen the amount screen uses, because there is nothing to type here and a screen that cannot be typed into beats one whose field has to be kept empty. The way back is the **same red dye the anvil screens carry**, with its label on the first tooltip line, so leaving a screen looks the same wherever it is done.

| Mode | What it takes |
|------|---------------|
| Item mode | Every piece stored, and nothing else. Shows a barrier in the confirmation slot while nothing is stored. |
| Crafting table mode | Every piece stored, plus everything the recipes and the stored station can add to it. Shows a barrier in the confirmation slot while nothing can be crafted. |

The middle slot always switches between the two, including while calculating or when stock or crafting is unavailable. A barrier on the right explains why the selected mode cannot withdraw. The middle slot reports how much is stored, how much could be crafted right now, and how much this mode would take. Nothing leaves until the confirmation on the right is clicked.

- Inside a bag, the status book offers taking **the whole bag**: every stack in it leaves as the thing it is. A bag holds what somebody put in, which no recipe makes, so selecting crafting mode shows a barrier in the confirmation slot.
- **The whole offer can be switched off.** With `input.allowBulkWithdrawal` off, a server says it does not want stocks emptied out in one go, and nothing is offered: the row and the bag's status book leave the hint line out, and the shift right click that would start one does nothing at all — no screen, no sound and no message.
- **Take-everything delivers in batches.** Confirmation starts a stream that delivers at most `withdrawalRate` items per tick, subject to the same work and planning budgets as quantity withdrawals. Its selection remains limited to `bulkWithdrawalStacks`, one backpack's worth by default; explicit quantity input does not use this selection limit. The button reports how much is selected, how much fits in the backpack, how much may drop, and how much stays stored.
- **A pour stops the moment it cannot go on**, and always says which it was:
  - **your backpack has no room left**, which ends it while `allowFullInventory` is off (with it on, the rest lands on the ground instead);
  - **you are no longer there** — you died, left, or the server let you go;
  - **the storage has nothing left to hand over**, when something else emptied it mid-pour;
  - or **another take-out took its place**, when the same player asks for another one.
- **Opening a screen does not end a pour.** What it hands over is in the backpack and on the ground, where it stays: a player who opens a screen mid-pour keeps everything the pour already took, and it keeps pouring behind the screen. The notice comes in two steps, and the colours say which is which: `Taking out … ` in yellow when the pour starts, and then `Took everything: N out of the storage.` in green when it finishes, or `Taking out stopped: …` in red with the reason, naming how far it got.
- **The confirmation is a barrier while it could not be served**, exactly as the amount screen's is: nothing stored leaves it saying so, and while `input.allowFullInventory` is off a backpack with no room blocks it with the same words the withdrawal screen uses — the two screens answer the same question the same way.
- **What leaves is handed over the way a withdrawal is**: into the backpack first, and what does not fit is dropped on the ground when `input.allowFullInventory` is on. The backpack is filled by the mod's own hand-over rather than the game's, and a drop is only counted as done once the item is really in the level — a world that says it took the item and then never carries it, or that refuses it outright, leaves the stack going back into the storage rather than lost with it, which is the one thing a full backpack must never do. In remote mode a stack goes back into the containers it came from, and what none of them will take is dropped beside one of them; if even that is refused the stack is kept in the world data and the server log says so, because two silent ways to lose an item were two too many.
- **The mode slot glints while it stands for the item itself**, and not while it stands for the crafting table or the box that fills in what is missing: the glint says "this is the thing you are taking", and a station or a container to pack it in is not that. The amount screen works the same way — its slot glints in item mode and goes dark in full-box mode.

---

## Highlights & Protection

`/ults show` and `/ults hide` toggle **your own** highlight and need the same permission as binding and deleting; other players are unaffected. Within 48 blocks, at most 16 bound containers are outlined with a glowing outline that follows the block exactly, refreshed twice per second. While you look at a bound container, the action bar names its binding, for example `Binding #3 (north warehouse)`.

Bound blocks are protected: breaking one requires sneaking. A normal break attempt is cancelled and answered with `This container is bound to the storage: sneak to break it.` (at most once every two seconds per player). Sneaking through a break removes the binding and tells you which one it was, and a bound block that disappears for other reasons loses its binding automatically.

---

## World Data

Remote items that cannot be returned are saved in a separate `remoteRecovery` queue in the same `SavedData`. The queue preserves packed boxes and bypasses destructive filters and bag limits. Once a second it retries at most 36 normal stacks, rotating through the queue. Recovery resumes after loading the world and removes only what a container or the world actually accepted.

Bindings and per-player view profiles are stored in the overworld `SavedData` of the world, so **each world has its own storage** and copying a world copies its storage. Nothing is written into container or player NBT. In `VOID` mode the stored items live in that same world data; in `REMOTE` mode they stay where they are, inside the bound containers. Every stored stack remembers when it was put in, which is what the special category lists, displays, sorts and trims by; a save written before that stamp existed still loads, its stacks simply count as the oldest. In `REMOTE` mode the containers themselves cannot tell two identical named swords apart, so a bag holds the variants it finds there, one entry per combination of components.

---

## Cloth Config Support

With **Cloth Config API** and **Mod Menu** installed on a client, every setting can be changed in-game. The screen is split into **General**, **Storage** and **Special items** tabs, each mirrored inside an **Overview** tab. Every label is short and every tooltip is laid out the same way — a one-line summary, a blank line, the detail, and an example where one helps — so a setting is readable without reading a paragraph. The screen edits only that client's local `config/ults.json`; it cannot modify a remote dedicated server. Saving validates and atomically writes UTF-8 JSON, and a block id or item id that names nothing in the running game is highlighted while typing and blocks the save. The item filter list is edited with the same numbered, validating list widget the multi block container list uses. Run `/ults reload` on an integrated server to apply the file.

---

## Building

```bash
./gradlew build      # builds build/libs/ultimate-storage-<version>.jar
./gradlew test       # runs the unit tests
./gradlew acceptanceTest # runs headless storage, crafting, delivery and recovery acceptance scenarios
./gradlew -PserverAcceptance serverAcceptanceTest # runs isolated native-world acceptance with vanilla recipes
./gradlew runServer  # starts a development server
```

Java 25 is required.

`build` runs both suites. Acceptance covers container returns, failed remote surplus restoration through save/reload/retry, strict tick limits, stock changes between opening and confirming, failed-delivery conservation, and crafting cache reloads with unchanged contents. Tests use build directories and never read or modify a running game's save or operate its UI.

Capacity, withdrawal planning and execution share the same inventory rules. Planning combines intermediate materials and root recipes, backtracks choices that starve later ingredients, and records concrete material allocations for replay. An independent forward-exploration oracle checks maximum production, every feasible quantity and failed-plan rollback for 27 small inventories. Further scenarios cover shared resources, overlapping slots, subsequent use of returned containers, packing reservations and large-count saturation. Depth and work budgets remain bounded; exhaustion reports an unknown result rather than caching zero production.

The separate `serverAcceptanceTest` runs Minecraft 26.2 with its vanilla recipes: 64 logs produce 512 sticks, three log varieties produce 24, and planks plus bamboo produce five, with matching planned and executed inventories. World scenarios exercise redstone locks, blocked upstream paths, rotation and reconnection, joined/split/removed chest halves across shards, unloaded-chunk reads, and an actual filtered saved-data write followed by reading with the filter disabled. Each run creates a fresh world under `build/server-acceptance`, using an already accepted `run/eula.txt`. The probe is excluded from release jars. Results appear in `server-acceptance-results.txt` there; any failed assertion fails the Gradle task.

Packing scenarios check joint allocation of box and contents: 216 logs and an existing blue box still yield 1,728 packed sticks after shells are added; 218 logs permit crafting the preferred plain box. Further regressions cover every physical inventory of configured multi-block containers, real partial inventory delivery and rejected entity drops, removing the binding when the other chest half is broken while preserving cancelled breaks, and quick withdrawals reading live stock. Headless tests also cover the last edit winning across configuration tabs, saturating inventory totals, and backpack capacity previews leaving their inputs unchanged.

`build` also bakes the game's own item names into the jar for **every language the mod ships** — `en_us`, `zh_cn` and `zh_tw` at the moment, read from the files in `assets/ultimate-storage/lang` (task `generateItemNames`, output `build/generated/ultsItemNames`) — so that a search can be answered in the language the player's client draws items in. The words are taken from the Minecraft assets Loom has already downloaded, or from the game jar in the Loom cache for the languages the asset index does not carry; a build without them simply leaves the game's English names in place. See [Searching by name](#searching-by-name).

---

## Compatibility & Deployment

| Type                       | Supported                                                        |
|----------------------------|------------------------------------------------------------------|
| Fabric Loader              | Yes, `0.19.3+`                                                   |
| Server only                | Yes; unmodified vanilla clients can join                         |
| Client UI (Cloth Config)   | Optional; local configuration only                               |
| Multi-language Support     | Bundled `en_us` / `zh_cn` / `zh_tw`, further locales discovered  |
| Minecraft Version          | `26.2`                                                           |

PB4 SGUI is bundled inside the mod JAR and talks through vanilla container packets, and the highlight is sent as ordinary entity packets, so neither feature requires a client installation. The mod registers only the `/ults` command and no custom network channels of its own.

---

## Credits

Developed with the **Fabric API**. The storage and withdrawal screens are built with **PB4 SGUI**, and the optional configuration screen uses **Cloth Config API** together with **Mod Menu**. Licensed under **LGPL-3.0**.

Feel free to submit issues or pull requests on GitHub to improve the storage, the binding workflow or the storage screen.
