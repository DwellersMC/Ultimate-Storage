# UltStorage Mod Documentation

**UltStorage** is a server-side Fabric mod for Minecraft that turns bound containers into **one shared item storage for the whole world**. In the default mode, items pushed into a bound container are drained into a server-side store, and every player can browse and withdraw them through a storage screen that is sent with vanilla container packets.

Storage belongs to the save, not to a name: a world has **one** storage, its inputs are the containers you bind to it, and the same storage can be fed and opened from any dimension.

中文文档请看[这里](./README.cn.md)

> [!WARNING]
> - *Only Minecraft **26.2** is supported, together with Fabric Loader **0.19.3+** and Java **25**. API changes in 26.x mean that older versions are not supported.*
> - *Clients do **not** need this mod. The storage screen, the withdrawal screen and the container highlight all use vanilla packets, so unmodified vanilla clients can join. The Mod Menu screen is optional and only edits a local file.*

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
| `/ults reload`                             | Reload `config/ults.json`                                                                   |

`/ults` and `/ults list` are open to everyone. Binding a container, binding an area, deleting bindings, the highlight and reloading need the **management permission level** (`input.permissionLevel`, vanilla level `0`-`4`, default `2`); the server console is always allowed. `/ults list` only offers the click-to-delete action to players that may delete.

`/ults bind` and `/ults delete` use a reach of 6 blocks. Binding and deleting work on any part of a large container, not only on the block that was bound first. A selection is written with a mandatory hash, so `#3` and `#3-#5` are valid while `3` is rejected with a hint.

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
    "multiBlockContainers": []
  }
}
```

A missing file, a missing field or an unknown enum value falls back to the default, and the file is rewritten in canonical form on load. `general.language` and `general.itemVisibility` / `general.storageMode` are applied by `/ults reload`.

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

---

### Automatic Crafting

With `input.crafting` enabled, a withdrawal that runs short is completed by crafting, as long as the storage holds the station the recipe needs; an item that is not stored at all but can be crafted can be withdrawn just the same:

| Mode                  | Craftable                                                                                          |
|-----------------------|----------------------------------------------------------------------------------------------------|
| `DISABLED`            | Nothing. This is the default.                                                                       |
| `SHULKER_BOXES_ONLY`  | Only an **uncolored shulker box**, which is what a full-box withdrawal packs into.                  |
| `ALL`                 | Any item the stored station can produce.                                                            |

- A crafting recipe needs a **crafting table** in the storage, a stonecutting recipe needs a **stonecutter**. The station is only used, never consumed.
- Every item row of a craftable item shows how much of it could be crafted right now (`Craftable: N`). While automatic crafting is on but the station the recipe needs is not stored, the row says `Craftable: no station stored` instead, so a stored crafting table that went missing is visible. Rows of items that no stored station can produce keep no such line.
- Recipes are followed **all the way down**: an ingredient the storage does not hold is crafted first, as many levels deep as it takes. Logs and shulker shells are therefore enough for a shulker box, because the plan makes the planks and then the chest on the way. `Shulker boxes only` works the same way, it just limits what the result may be. Recipes that feed each other, such as a block and its ingots, cannot make the search loop.
- Routes are compared and the best one wins: whichever route produces the most from the current stock, and among those the one needing the fewest operations. A recipe that a stonecutter does in fewer operations is therefore preferred over the crafting table while a stonecutter is stored, and the crafting table takes over when no stonecutter is left.
- Only the missing part is crafted. Withdrawing 64 of an item while 60 are stored and the recipe produces 4 per operation runs the recipe once and takes the remaining 60 from the stock. What a batch makes on the way and the request does not need, extra planks for example, stays in the storage instead of disappearing.
- Recipes whose result or ingredients the game decides while it runs (dyeing, fireworks, banner and map copying, repairing, and the like) are not used.
- The empty boxes of a full-box withdrawal are taken in the order **plain boxes in storage, then boxes crafted for the request, and only then the other colours**: a box that can be crafted is never passed over in favour of a coloured one, and when not all of them can be crafted, the colours cover what is left. The screen says how many stored boxes are used and how many are crafted before the confirm button is pressed.
- The withdrawal screen lists what will be crafted before the confirm button is pressed.
- `/ults reload` reads the recipes again, which is needed after a data pack changed them.

---

### Storage Modes

| Mode     | Behaviour                                                                                                                                                                                       |
|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `VOID`   | Default. Bound containers are inputs: the drain loop moves their contents (and the contents of the hoppers feeding them) into a server-side store that is written with the world.                  |
| `REMOTE` | No store of its own. The bound containers **are** the storage: contents are aggregated from them while their chunks are loaded, and withdrawals are taken straight out of them.                    |

In `REMOTE` mode nothing is drained: the bound containers keep their items, so they work as chests that are reachable from the storage screen. Switching the mode takes effect with `/ults reload`; existing bindings are kept either way.

---

### Item Visibility Modes

| Mode        | Listed items                                                                                                                       |
|-------------|------------------------------------------------------------------------------------------------------------------------------------|
| `ALL`       | Every item and every category, even with nothing stored.                                                                            |
| `SURVIVAL`  | Everything a survival player can obtain, plus anything that is stored. Categories without content are hidden.                       |
| `AVAILABLE` | What the storage can hand over right now: everything in stock, plus everything that can be crafted at this moment while automatic crafting is on and a station is stored. Categories without such an item are hidden. This is the configured default. |

The configured mode is the **default**, not a fixed setting: every player can switch their own mode with a right-click on the status book of the storage screen, and the choice is remembered with their other view settings. Showing everything is only on offer while the configured default already is `ALL`, or while the player may manage the storage, so a plain player cannot open a view the owner did not hand out. There is no way back to "default" other than switching again.

#### How the survival catalogue is derived

Minecraft has no "survival obtainable" flag, so the catalogue is built from everything the server knows when a world loads:

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

- **Categories** are the creative tabs discovered when the world loads, plus **All Items** and **Special Data Items** (stored stacks whose components no creative tab describes). Tabs that hold nothing listable in the current visibility mode are hidden, while **All Items** and **Special Data Items** always stay reachable, even while they are empty. A list without a single row shows a paper in the middle of the item area instead.
- **Status book** shows the selected category, the category page, the item page, how many item types are listed and stored, the current filter and the current visibility; left-clicking it opens the **filter screen**, where a part of an item id (for example `diamond`) is typed, applied, or cleared, and right-clicking it switches the visibility mode this player sees.
- **Item rows** show the stored amount, the equivalent in shulker boxes once it reaches a box, and open the **withdrawal screen** on a left click while something is stored or automatic crafting could make the item right now.
- The selected category, category page, item page, filter and visibility are saved per player in the world data, so everyone returns to their own view.
- Clicking plays a short sound that only the clicking player hears: a light click for opening, paging, selecting and switching, and a brighter pickup sound for applying or confirming. Cancelling a screen stays silent.
- Open screens refresh themselves when stored quantities change.

### Withdrawal Screen

The withdrawal screen asks for an amount above an anvil-style input and offers two modes:

| Mode           | Meaning                                                                                                    |
|----------------|------------------------------------------------------------------------------------------------------------|
| Item mode      | Withdraw that many loose items.                                                                            |
| Full-box mode  | Treat the amount as a number of shulker boxes, consume that many empty boxes and fill each with 27 full stacks. |

The left slot of the anvil holds the cancel button, which returns to the storage screen, and the middle slot switches between the two modes. The middle slot also reports what the request needs: the stored amount, the amount asked for, how many items and how many boxes would be crafted, and in full-box mode how many empty boxes are available and how many of them are used. The cancel label is the first tooltip line of that button rather than its name, because the vanilla client copies the name of the item in this slot into the input field: a name would fill the field with the sentence and every later keystroke would be appended to it. Confirmation stays unavailable and explains itself while the request cannot be fulfilled: a non-positive amount, packing shulker boxes inside shulker boxes, more than 36 result stacks, not enough stored items or empty boxes, or a backpack that cannot hold the complete result. Confirming revalidates everything and deducts atomically, so two players cannot withdraw the same items.

The filter screen uses the same three slots: cancel, clear the filter, and apply what was typed.

---

## Highlights & Protection

`/ults show` and `/ults hide` toggle **your own** highlight; other players are unaffected. Within 48 blocks, at most 16 bound containers are outlined with a glowing outline that follows the block exactly, refreshed twice per second. While you look at a bound container, the action bar names its binding, for example `Binding #3 (north warehouse)`.

Bound blocks are protected: breaking one requires sneaking. A normal break attempt is cancelled and answered with `This container is bound to the storage: sneak to break it.` (at most once every two seconds per player). Sneaking through a break removes the binding and tells you which one it was, and a bound block that disappears for other reasons loses its binding automatically.

---

## World Data

Bindings and per-player view profiles are stored in the overworld `SavedData` of the world, so **each world has its own storage** and copying a world copies its storage. Nothing is written into container or player NBT. In `VOID` mode the stored items live in that same world data; in `REMOTE` mode they stay where they are, inside the bound containers.

---

## Cloth Config Support

With **Cloth Config API** and **Mod Menu** installed on a client, every setting can be changed in-game. The screen edits only that client's local `config/ults.json`; it cannot modify a remote dedicated server. Saving validates and atomically writes UTF-8 JSON, and a block id that names no block of the running game is highlighted while typing and blocks the save. Run `/ults reload` on an integrated server to apply the file.

---

## Building

```bash
./gradlew build      # builds build/libs/ultimate-storage-<version>.jar
./gradlew test       # runs the unit tests
./gradlew runServer  # starts a development server
```

Java 25 is required.

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
