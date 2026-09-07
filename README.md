# SimpleSeasons  Current Version 26.2

# SimpleSeasons Features
* Four seasonal world cycles: Spring, Summer, Fall, and Winter
* Fully configurable season length and transition periods
* Optional sleep-driven calendar, so seasons progress when players sleep through the night
* Seasonal chat notice when a new season begins
* Hotbar action-bar display showing the current season
* Seasonal weather, including winter snowfall
* Water freezes in winter, even when snow is not currently falling
* Seasonal snow and ice remain through winter, then melt outside of winter
* Winter crop-growth pause
* Seasonal creature spawning lists
* Seasonal grass, foliage, water, fog, and sky color palettes for Java players through ProtocolLib
* Nearby chunks refresh quickly after a season change for smoother visual changes
* Winter survival effects: outdoor unarmored players slow down, with stronger cold at night
* Summer survival effects: outdoor players in hot armor can receive heat sickness during the day
* No heat or cold effects underground, indoors, in the Nether, or in the End
* Per-world season control with /seasons
* Lightweight, configurable processing limits to help keep server performance smooth
* No NMS dependencies; designed for Paper 26.2 compatibility
* Clear, commented configuration file for easy customization

# Dependencies
**ProtocolLib** https://hangar.papermc.io/dmulloy2/ProtocolLib/versions
> Make sure u us the most upto date version for everything to work properly.

# First Installation Guide
1. Download SimpleSeasons.jar.
2. Stop your Paper server.
3. Place the JAR inside your server’s plugins folder.
4. Make sure you have ProtocolLib installed if you want seasonal grass, leaf, water, sky, and fog colors.
5. Start the server once. SimpleSeasons creates its configuration folder and default config file
6. Stop the server again, then open:
```
plugins/SimpleSeasons/config.yml
```
7. Adjust settings such as season duration, sleep-based progression, weather, environment changes, player effects, and action-bar display.
8. Start the server again.
9. Enable seasons in each world you want to use:
```
/seasons enable <world>
Example:
/seasons enable world
```
10. Check the current season:
```
/seasons info
```

# Command List
```
/seasons or /season
  Shows the season, season day, and transition status for your current world.
  Permission: seasons.use
```
```
/seasons info [world]
  Shows the season, season day, and transition status for the selected world.
  Permission: seasons.use
```
```
/seasons set <spring|summer|fall|winter> [world]
  Sets a world's current main season and resets its season day to 1.
  Permission: seasons.admin
```
```
/seasons next [world]
  Immediately advances the selected world to its next main season.
  Permission: seasons.admin
```
```
/seasons enable [world]
  Enables SimpleSeasons in a world. This is required before that world receives seasonal changes.
  Permission: seasons.admin
```
```
/seasons disable [world]
  Stops seasonal changes in a world without deleting its saved season state.
  Permission: seasons.admin
```
```
/seasons reload
  Reloads config.yml and all saved world season state.
  Permission: seasons.admin
```
```
seasons.use   - Allowed for all players by default; permits /seasons and /season.
seasons.admin - Allowed for operators by default; permits setup and management commands.
```