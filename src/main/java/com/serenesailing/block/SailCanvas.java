package com.serenesailing.block;

import net.minecraft.util.StringRepresentable;

public enum SailCanvas implements StringRepresentable {
    FULL("full"),
    HALF("half"),
    ROLLED("rolled"),
    EMPTY("empty");

    private final String name;

    SailCanvas(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
