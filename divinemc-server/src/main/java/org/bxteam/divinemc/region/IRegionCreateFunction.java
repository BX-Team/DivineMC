package org.bxteam.divinemc.region;

import java.io.IOException;

public interface IRegionCreateFunction {
    IRegionFile create(RegionFileInfo info) throws IOException;
}
