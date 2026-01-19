package org.bxteam.divinemc.region.linear.versions;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.bxteam.divinemc.region.linear.LinearImplementation;

public interface Version {
    LinearImplementation implementation();

    default byte version() {
        return implementation().version();
    }

    void parse(ByteBuffer byteBuffer) throws IOException;

    void flush() throws IOException;

}
