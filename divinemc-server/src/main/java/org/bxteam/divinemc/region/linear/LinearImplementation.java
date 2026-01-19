package org.bxteam.divinemc.region.linear;

public enum LinearImplementation {
    V1(2),
    V2(3),
    V3(4),
    ;

    private final int version;

    LinearImplementation(int i) {
        this.version = i;
    }

    public byte version() {
        return (byte) version;
    }
}
