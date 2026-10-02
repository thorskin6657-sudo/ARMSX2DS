# Dual-screen companion profiles

On a device with a second display (AYN Thor, Retroid dual-screen add-on, ...), ARMSX2's second-screen
panel can show a per-game companion instead of the generic tile grid: live stats, party, inventory,
a position map, and touch buttons. A game gets a companion when a profile exists for its disc serial.

Turn the second-screen panel on in App settings first. A game with no profile keeps the normal tiles.
The "Tiles" chip on the companion (and the "Companion" button on the tile view) flips between them.

## Adding a game

1. Copy `app/src/main/assets/companion/profiles/_template.json`, name it for the game, and set `serials`.
   Bundled profiles live in that folder. To add or fix one without rebuilding, put the file in
   `Android/data/<package>/files/companion/profiles/` on the device; those override bundled ones.
2. Find the RAM addresses (see below) and fill in the sections the game needs. Delete the rest.
3. Set `"verified": true` once every address has been checked against a running game. Until then the
   panel shows an UNVERIFIED badge so a wrong number is not mistaken for a bug in the emulator.

Addresses are physical EE RAM (`0x00000000`-`0x01FFFFFF`), the numbering cheat codes and
RetroAchievements use. Cheat-style `0x2xxxxxxx` and KSEG `0x8xxxxxxx` forms are accepted.

## RAM search tool (finding addresses on the handheld)

Every companion has a **Search** tab (and a game with no profile gets a **RAM search** button on the
tile view). It works like Cheat Engine, on the bottom screen while the game runs on the top one:

1. **Pause** the game (the Pause button is on the tab) so values hold still during a scan.
2. Pick the value type (`u16` is typical for health, `u32` for money, `f32` for positions) and type the
   number you can see on screen using the keypad. There is no text box on purpose: the system keyboard
   does not appear on a second display on many devices.
3. **First scan**. Resume, change the value in game (take damage, spend money), pause again.
4. Enter the new number and **Next scan**, or pick *Changed / Unchanged / Increased / Decreased* when
   there is no number to type. Repeat until a few addresses are left.
5. Tap **Watch** on the survivors and see which ones track the value as you play. **Save + copy** writes
   them to `Android/data/<package>/files/companion/found/<serial>.json` and copies them to the clipboard.

Party and inventory arrays: watch the first entry, then the second, and the difference between their
addresses is the `stride`. Turn the tool off with `setCompanionDevTools(false)` when you no longer need it.

## Finding addresses

Use the game's existing cheat/RetroAchievements memory notes where they exist (RAM maps are often
published for popular games), or search RAM yourself with a memory-search tool: note a value, change
it in game, search again, repeat until one address is left. Party and inventory are usually arrays:
find the first entry, find the second, and the difference is the `stride`.

## Sections

| Section | Shows | Notes |
|---|---|---|
| `stats` | Labelled values, with a bar when `max` is given | `names` turns a raw number into a word |
| `party` | One card per member | First field is the heading; `text` reads a string; `maxOffset` adds a bar |
| `inventory` | Item chips | `names` maps id to name; unknown ids show as `#id` |
| `map` | Player dot, trail, markers, optional image | `areas` can switch on an `areaId` address; images go in `assets/companion/maps/` |
| `buttons` | Touch buttons along the bottom | Keys: CROSS CIRCLE SQUARE TRIANGLE L1 R1 L2 R2 START SELECT L3 R3. `"latch": true` makes a button tap-to-hold, tap-to-release, for hold-to-use buttons; latched buttons are let go when the panel stops |

Value types: `u8 s8 u16 s16 u32 s32 f32` (little-endian). `scale` multiplies after reading.
The panel only reads game RAM; it never writes to it.
