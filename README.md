# Serene Sailing: Ship Rigging & Ocean Physics

**Serene Sailing** is a high-fidelity naval physics and rigging addon mod for **Minecraft 1.21.1 (NeoForge)** built on top of **Create Aeronautics**, **Sable**, and **Weather2**.

> [!NOTE]
> For comprehensive architectural and mathematical documentation, see [PROJECT_OVERVIEW.md](file:///d:/SereneSailing/PROJECT_OVERVIEW.md).
> For the complete chronological engineering journal, see [Serene_Sailing_state_file.txt](file:///d:/SereneSailing/Serene_Sailing_state_file.txt).

---

## Key Features

1. **Rigging & Ship Controls:**
   - **Canvas Sails (`CanvasSailBlock`):** Multi-stage reefing (`FULL`, `HALF`, `ROLLED`, `EMPTY`) with apparent wind lift/drag dynamics and layered models eliminating Z-fighting.
   - **Rudders (`RudderBlock`):** Deflection clamping ($\pm 35^\circ$) with 3-point MMG vertical blade submersion ratios.
   - **Helm Station (`HelmBlock`):** Interactive mouse-drag steering wheel with client-server network synchronization.
   - **Taffrail Log & Capstan:** Stern hydrodynamic speedometer and anchor mooring lock.
   - **Wind Vane:** Apparent and true wind direction indicator.

2. **6-DOF Marine Hydrodynamics:**
   - Fossen / MMG marine equations of motion (surge, sway, heave, roll, pitch, yaw).
   - Metacentric alignment righting torque with low-hull virtual keel ballast.
   - Karnopp conditional stick-slip friction enabling smooth breakaway acceleration.
   - Decoupled centerline aerodynamic propulsion preventing spin leakage.
   - Thread-safe Weather2 wind decoupling via Netty-safe main-thread caches.

3. **3D Gerstner Ocean Wave Mesh:**
   - Isolated `Tesselator` pass rendering phase-continuous Gerstner and Trochoidal waves.
   - Static 1x1 block world-locked UV mapping using vanilla animated `block/water_still` texture.
   - Exact 1x1 block surface water mask preventing dock overlaps.
   - Airtight boundary vertex pinning to $Y = 62.890\text{f}$ eliminating hollow underside gaps.
   - Dynamic rolling offshore surf breaker ribbons ($1.2\text{--}5.5\text{ blocks offshore}$) that roll toward beaches and wash out before reaching the sand.
   - Multi-zone particle emitters (beach surf, open-ocean whitecaps, ship bow wake spray).

---

## Quick Start (Offline Build)

Compile the mod:
```powershell
./gradlew.bat compileJava --offline
```

Run the 24 automated unit tests:
```powershell
./gradlew.bat test --offline
```

Launch the development client:
```powershell
./gradlew.bat runClient --offline
```
