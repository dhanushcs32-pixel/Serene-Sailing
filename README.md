# Serene Sailing: Ship Rigging

Addon mod for Minecraft 1.21.1 / NeoForge. Adds sails, rudders, and
wind-driven ship propulsion on top of Sable + Create Aeronautics, per the
"piggyback, don't fork" decision in the project state file.

## What's in this scaffold

- Standard NeoForge 1.21.1 NeoGradle project layout (matches the official
  `NeoForgeMDKs/MDK-1.21.1-NeoGradle` template).
- `neoforge.mods.toml` declaring hard dependencies on `sable`, `create`,
  and `createaeronautics`.
- Two inert placeholder blocks (`CanvasSail`, `Rudder`) that register and
  render as plain textured cubes - just enough to confirm the mod loads
  and the dependency wiring is correct before writing real physics.
- `SailPhysics.java` - empty, with the intended thrust formula documented
  as a comment. Real implementation needs Sable's/Aeronautics' actual API,
  which I could not fetch here (see below).
- `SailingConfig.java` - a few tunables (sail force multiplier, rudder
  turn rate, Weather 2 wind integration toggle) wired up and ready.

## Important: what I could NOT verify from here

I built this without network access to `maven.neoforged.net` or
`api.modrinth.com` (only a small allowlist of domains is reachable from
this sandbox). That means:

1. **`neo_version=21.1.234`** in `gradle.properties` - confirmed from the
   official 1.21.1 MDK template as of Aug 2026, but check
   https://projects.neoforged.net/neoforged/neoforge for anything newer
   before your first `gradlew build`.
2. **Sable/Create/Aeronautics version strings** in `gradle.properties`
   are literally `REPLACE_ME` placeholders. To fill them in:
   - Go to each mod's Modrinth page (sable, create, create-aeronautics),
     pick the 1.21.1 NeoForge build you want, and copy its **version
     number** (not the file name) into `gradle.properties`.
   - The `build.gradle` Modrinth Maven coordinates
     (`maven.modrinth:sable:${sable_version}`) follow the pattern
     documented at https://support.modrinth.com/en/articles/8801191 -
     double check the exact artifact slug matches (e.g. it might be
     `sable` or a different slug - confirm on the Modrinth page URL).
3. **I have not compiled this.** No `gradlew build` was run - your
   sandbox has real internet access, mine doesn't. Treat this as a
   correctly-shaped starting point, not a verified-working build. Expect
   at least one round of "fix the import that doesn't quite match this
   NeoForge minor version" when you first sync in VS Code.

## First steps in VS Code

0. The Gradle wrapper *scripts* (`gradlew` / `gradlew.bat`) and wrapper
   *jar* are not included - I couldn't download the binary jar without
   network access. If you have Gradle installed locally, run
   `gradle wrapper --gradle-version 8.10` once in this folder to generate
   them (uses the `gradle-wrapper.properties` already included), or just
   open the folder in VS Code / IntelliJ and let it use a local Gradle
   install directly.
1. Install the "Extension Pack for Java" and (optionally) the NeoForge/MC
   dev extensions VS Code suggests when it detects `build.gradle`.
2. Fill in the three `REPLACE_ME` version strings in `gradle.properties`.
3. Run `./gradlew build` (or let VS Code's Gradle sync do it). Fix
   compile errors as they surface - they'll mostly be import path
   mismatches against whatever exact Sable/Aeronautics API version you
   pulled.
4. Run `./gradlew runClient` to boot a dev client with this mod +
   Sable + Create + Aeronautics loaded, and confirm the Canvas Sail and
   Rudder items appear in the creative inventory / JEI.
5. Once that boots cleanly, move on to `SailPhysics.java`: open Sable's
   and Aeronautics' actual decompiled/API sources in your IDE (now that
   they're real dependencies) and look for how Aeronautics' propeller or
   thruster blocks apply force to a sub-level - mirror that pattern for
   sail thrust rather than reinventing sub-level physics access.

## Next mod-design decisions to make (see SailBlock.java / RudderBlock.java TODOs)

- Is sail trim angle player-adjustable, or fixed-facing?
- Does the rudder need a separate "Helm/Wheel" block as the actual input
  point, or does right-clicking the rudder itself steer?
- Simplified downwind-only thrust model, or full tacking/upwind physics?

Log decisions and progress back into the project state file as you go.
