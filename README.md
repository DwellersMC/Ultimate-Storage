# Ultimate-Storage

A server-side Fabric shared storage mod for Minecraft 26.2.

Place a barrel on the configured base block, stand on it, and run `/ults create <name>`. Automation may insert items through the barrel. An enabled hopper pointing into an input barrel is drained every tick and has its transfer cooldown reset. Every player can open the read-only withdrawal UI with `/ults`.

The storage UI discovers vanilla and mod-added creative tabs at runtime. Each player's selected category, category page, and item page are persisted in the world. Open menus update whenever stored quantities change.

Left-click an item to enter an amount in the anvil UI. Item mode withdraws that many loose items. Full-box mode treats the amount as a number of shulker boxes, consumes that many empty boxes, and fills every box with 27 full stacks. Confirmation is disabled unless all items, empty boxes, and backpack space are available; the final operation revalidates and deducts atomically.

Commands: `/ults create <name>`, `/ults delete <name>`, `/ults list [page]`, and `/ults reload`.

Configuration is stored at `config/ults/ults.json`. Clients do not need the mod; the optional Mod Menu/Cloth Config screen only edits a local configuration.
