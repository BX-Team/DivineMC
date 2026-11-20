package org.bxteam.divinemc.region.format;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;

/** A Linear file or record that breaks the format: corrupt, unsupported, or from a legacy layout. */
@NullMarked
public class LinearFormatException extends IOException {
    public LinearFormatException(String message) {
        super(message);
    }

    public LinearFormatException(String message, Throwable cause) {
        super(message, cause);
    }

    /** A file in one of the layouts that predate this format; only the converter reads those. */
    public static final class Legacy extends LinearFormatException {
        public Legacy(String message) {
            super(message);
        }
    }
}
