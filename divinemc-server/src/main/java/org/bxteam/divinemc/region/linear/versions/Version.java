package org.bxteam.divinemc.region.linear.versions;

import org.bxteam.divinemc.region.linear.LinearImplementation;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Version {
    LinearImplementation implementation();

    default byte version() {
        return implementation().version();
    }

    void parse(ByteBuffer byteBuffer) throws IOException;

    void flush() throws IOException;

}
