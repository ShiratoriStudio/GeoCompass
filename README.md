# GeoCompass

GeoCompass is a mineral survey and tracking plugin for Paper 1.20.1+.

It provides two core features:
- **Survey (right-click):** scan nearby chunks and show mineral distribution.
- **Tracking (holding compass):** show direction and distance to nearby target minerals.

---

## Features

- Custom GeoCompass item (name, lore, CustomModelData)
- Configurable crafting recipe
- Survey cooldown and result cache
- Multiple display modes: ActionBar / BossBar / Title
- Dimension/world-based target mineral config
- First-discovery broadcast + reward commands
- `lang.yml` language support
---

## Requirements

- **Java:** 17+
- **Server:** Paper / Purpur (API 1.20.1)

---

## Installation

1. Build or download `GeoCompass-*.jar`.
2. Put it into your server `plugins/` folder.
3. Start server once to generate default files.
4. Edit `config.yml` and `lang.yml` as needed.
5. Restart server or run `/gc reload`.

---

## Build

```bash
mvn clean package
```

Output jar:

```text
target/GeoCompass-<version>.jar
```

---

## Commands

Main command: `/geocompass` (alias: `/gc`)

- `/gc give <player> [amount]` - Give GeoCompass to a player
- `/gc reload` - Reload config and language files
- `/gc stats [player]` - Show discovery stats

---

## Permissions

- `geocompass.use` - Use GeoCompass (default: true)
- `geocompass.craft` - Craft GeoCompass (default: true)
- `geocompass.bypass.cooldown` - Bypass survey cooldown (default: op)
- `geocompass.admin.give` - Use give command (default: op)
- `geocompass.admin.reload` - Use reload command (default: op)

---

## Configuration Overview

- `item.*` - GeoCompass item settings
- `crafting.*` - crafting recipe settings
- `survey.*` - survey logic (radius, cooldown, display, cache)
- `tracking.*` - tracking logic (interval, range, ui, dimension defaults)
- `announcements.*` - first-discovery broadcast and rewards
- `worlds.*` - world enable list and world mineral override

See `src/main/resources/config.yml` for full options.

---

## Data Files

Plugin folder: `plugins/GeoCompass/`

- `config.yml`
- `lang.yml`
- `data/<uuid>.yml` (player discovery data)

---

## V1.1 Roadmap

- Tracking performance optimization (cache + scheduling)
- Player target mineral modes
- Better stats commands (top / inspect)
- Optional SQLite storage backend

