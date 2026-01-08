# DamageLogger (Alliantie Hardcore Tools)

Een Fabric server-mod die **damage**, **splits (milestones)** en **run-einde events** logt voor hardcore/race runs.
De mod toont een **actionbar timer**, een **SPLITS sidebar**, bewaart alle runs in **JSON**, en meldt **persoonlijke/beste splits (PB’s)** in de chat.

> Gericht op servergebruik (dedicated), met minimale log-spam door diff-based scoreboard updates.

---

## Features

### Damage logging (chat)
- Kleur-gecodeerde damage meldingen in de chat:
  - `[Damage]` donkergrijs
  - Spelernaam wit
  - Tekst grijs
  - Damage + ♥ rood
- Anti-spam cooldown per damage-source:
  - Standaard cooldown: ~300ms
  - DoT (fire/lava/wither/poison/starve/cactus) cooldown: ~1500ms
- Negeert `genericKill` (bijv. `/kill`), zodat admin acties geen “run death” of spam veroorzaken.

### Damage leaderboard (per run)
- Houdt bij hoeveel **totaal damage** elke speler deze run heeft gekregen.
- Bij run-einde: **Top 5 “Most damage taken (this run)”** in de chat (met ♥).

### Splits / milestones
- Milestones worden gedetecteerd via advancements (eerste speler die hem haalt “pakt” de split).
- Bij elke split:
  - Chatmelding: `⏱ SPLIT <MILESTONE>: <tijd> — <speler>`
  - Vergelijking met opgeslagen “best split”:
    - Bij sneller: chatmelding `🏁 NEW PB <MILESTONE>: <tijd> (-<verschil>)`
- Milestones in deze mod:
  - `IRON` (story/smelt_iron)
  - `NETHER` (story/enter_the_nether)
  - `FORT` (nether/find_fortress)
  - `BLAZE` (nether/obtain_blaze_rod)
  - `END` (story/enter_the_end)
  - `DRAGON` (end/kill_dragon)

### Sidebar scoreboard: SPLITS
- Sidebar objective: `alliance_splits` met title `SPLITS`.
- Toont behaalde splits met tijd.
- Geen timer in de sidebar (timer staat in de actionbar).
- **Diff-based updates**: scoreboard commands worden alleen uitgevoerd als een regel echt verandert → minder logspam.

### Actionbar timer
- Tijdens run: toont alleen tijd `00:00:00`.
- Na fail: `RUN FAILED — 00:00:00`
- Na completion: `RUN COMPLETED — 00:00:00`

### Run einde: FAIL (eerste echte death)
- Triggert op de **eerste echte player death** (excl. `genericKill`).
- Zet **iedereen** in **Spectator**.
- Stuurt “dramatische” end-run info in chat:
  - Speler
  - Coords
  - Dimension
  - Cause/type
- **5 seconden pin**: spelers worden “vastgehouden” rond de death-locatie (teleport terug bij bewegen / verkeerde dimension).

### Run einde: SUCCESS (DRAGON)
- Als `DRAGON` split wordt gehaald:
  - Run eindigt als **COMPLETED**
  - Chatmelding met eindtijd + speler
  - **Geen spectator / geen pin**
  - Voorkomt dat een latere death de run alsnog als FAIL markeert.

### Persistente opslag (JSON)
- Alle runs worden opgeslagen in:
  - `/opt/minecraft/server/splits/runs.json`
- Best splits worden gelezen uit dezelfde JSON bij server start/first join.

### Gamerule
- Zet (via server command):
  - `gamerule doImmediateRespawn true`

---

## Installatie

1. Build de mod jar (Gradle):
   - `gradlew clean build`
2. Plaats de jar in de server `mods/` map.
3. Start/restart de server.

> Zorg dat de server user schrijfrechten heeft op:
> `/opt/minecraft/server/splits/`

---

## Bestanden & opslag

### JSON locatie
- Directory: `/opt/minecraft/server/splits/`
- File: `runs.json`

### JSON structuur (globaal)

- `version`: integer
- `runs`: lijst met runs
- `bestSplits`: map met per milestone de beste tijd

Voorbeeld (ingekort):

```json
{
  "version": 1,
  "runs": [
    {
      "runId": "run-1700000000000-1700000123456",
      "startMs": 1700000000000,
      "endMs": 1700000123456,
      "durationMs": 123456,
      "failed": true,
      "completed": false,
      "endReason": "FAILED",
      "endPlayer": "PlayerName",
      "splits": {
        "IRON": { "timeMs": 90000, "player": "PlayerName", "playerUuid": "..." }
      }
    }
  ],
  "bestSplits": {
    "IRON": { "timeMs": 85000, "runId": "run-..." }
  }
}
```

---

## Gebruik

### Tijdens de run
- Damage logs verschijnen in chat (met kleuren).
- Actionbar toont de timer.
- Sidebar toont de behaalde splits.
- Elke split wordt in chat gemeld; bij PB ook met verschil.

### Einde van de run
- FAIL: eerste echte death → spectator, pin 5s, dramatische output, leaderboard, run wordt opgeslagen.
- SUCCESS: DRAGON split → completed message, leaderboard, run wordt opgeslagen.

---

## Notes / Troubleshooting

- **Geen chat output?**  
  De mod gebruikt `PlayerManager.broadcast(...)` voor chat. Als er toch niets verschijnt, check:
  - server chat settings / permissions
  - eventuele mods/plugins die chat intercepten

- **runs.json wordt niet geschreven**  
  Controleer permissions:
  - `/opt/minecraft/server/splits/` moet bestaan of aan te maken zijn
  - server user moet schrijf-rechten hebben

- **Scoreboard spam in latest.log**  
  De mod update diff-based, maar als je nog spam ziet kan je:
  - command feedback settings checken
  - server config (log admin commands) nalopen

---

## Licentie
Nog niet gespecificeerd (voeg hier je licentie toe, bijv. MIT).
