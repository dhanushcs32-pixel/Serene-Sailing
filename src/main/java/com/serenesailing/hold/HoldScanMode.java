package com.serenesailing.hold;

/** Which engine decides where the sea is kept out of a ship. */
public enum HoldScanMode {
    /** The original row scanner: outer walls per row, classified as raft or hold, the span between them is sealed. */
    ROW_SCANNER,
    /** Flood fill from the sea ({@link HoldFlood}): only air pockets the sea cannot reach are dry, breaches flood. */
    FLOOD_FILL
}

