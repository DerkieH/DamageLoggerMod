# DamageLogger (Alliantie Hardcore Run Mod)

DamageLogger is a **Fabric server-side mod** designed for hardcore / speedrun-style Minecraft servers.
It tracks **damage**, **splits (milestones)**, **run state**, and **end conditions**, while persisting all runs to JSON for later comparison.

The mod focuses on **clarity for spectators**, **dramatic run endings**, and **low server log spam**.

---

## Core Concepts

- A **run** starts automatically when the server is active.
- The run ends in one of two ways:
  - **FAILED** – the first real player death.
  - **COMPLETED** – the Ender Dragon is killed.
- All players are treated as part of a single shared run.

---

## Features

### Damage Logging (Chat)
- Colored damage messages in chat:
  - `[Damage]` – dark gray
  - Player name – white
  - Text – gray
  - Damage value + ♥ – red
- Anti-spam system:
  - Per-damage-source cooldown
  - Longer cooldown for DoT sources (fire, lava, poison, wither, starvation, cactus)
- Ignores `genericKill` (e.g. `/kill`)

### Damage Leaderboard (Per Run)
- Tracks **total damage taken per player** during the run.
- On run end, broadcasts a **Top 5 “Most damage taken (this run)”** leaderboard in chat.

### Splits / Milestones
Milestones are detected via advancements.
The **first player** to complete an advancement claims the split.

Milestones:
- `IRON` – Smelt Iron
- `NETHER` – Enter the Nether
- `FORT` – Find a Nether Fortress
- `BLAZE` – Obtain Blaze Rod
- `END` – Enter the End
- `DRAGON` – Kill the Ender Dragon

#### Split Chat Messages
- Always announced with delta vs best run:
```
⏱ SPLIT IRON: 00:02:10 (+00:00:07) — PlayerName
```
- Faster than best:
```
🏁 NEW PB IRON: 00:02:03 (-00:00:05)
```

### Sidebar Scoreboard (SPLITS)
- Objective: `alliance_splits`
- Always shows **all milestones**
- Baseline is the best split from stored runs
- Shows `(+ / −)` delta after completion
- Diff-based updates (minimal log spam)
- No timer in sidebar

### Actionbar Timer
- During run: `00:00:00`
- On fail: `RUN FAILED — 00:00:00`
- On completion: `RUN COMPLETED — 00:00:00`

### Run End: FAILED
- Triggered by the **first real death**
- Ignores `/kill` and genericKill
- Forces respawn to bypass hardcore Game Over screen
- All players switched to **Spectator**
- All players teleported to death location
- **5 second pin** at death spot
- Dramatic broadcast after spectator state
- Damage leaderboard shown
- Run saved to JSON

### Run End: COMPLETED
- Triggered by **Dragon kill**
- Broadcast completion message
- No spectator or pin
- Damage leaderboard shown
- Run saved to JSON

---

## Persistence

All runs are saved to:

```
/opt/minecraft/server/splits/runs.json
```

---

## Installation

1. Build:
```
gradlew clean build
```
2. Place JAR in `mods/`
3. Ensure write access to:
```
/opt/minecraft/server/splits/
```

---

## Notes
- Server-side only (Fabric)
- Designed for hardcore race environments
- Scoreboard updates are diff-based

---

## License
Not specified.
