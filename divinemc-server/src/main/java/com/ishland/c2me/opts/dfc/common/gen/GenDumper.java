/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021-2026 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.c2me.opts.dfc.common.gen;

import com.ishland.c2me.opts.dfc.common.ast.opto.OptoPasses;
import com.ishland.c2me.opts.dfc.util.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * DivineMC - debug-only class dumper.
 * <p>
 * Upstream dumps every compiled class plus GraphViz representations unconditionally;
 * here dumping is gated behind {@code -Ddivinemc.dfc.dump=true} and the dot/GraphViz
 * emitters are not ported. Returns {@code null} when dumping is disabled.
 */
public class GenDumper {

    private static final boolean DUMP_ENABLED = Boolean.getBoolean("divinemc.dfc.dump");

    public static final File exportDir = new File("./cache/c2me-dfc");

    static {
        if (DUMP_ENABLED) {
            try {
                Files.deleteRecursively(exportDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static Path dumpClass(String className, byte[] bytes) {
        if (!DUMP_ENABLED) {
            return null;
        }
        File outputFile = new File(exportDir, "classes/" + className + ".class");
        outputFile.getParentFile().mkdirs();
        try {
            com.google.common.io.Files.write(bytes, outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return outputFile.getAbsoluteFile().toPath();
    }

    public static void dumpDot(String name, Path primary, Map<String, OptoPasses.AstPair> roots) {
        // DivineMC - dot/GraphViz emitters not ported
    }

}
