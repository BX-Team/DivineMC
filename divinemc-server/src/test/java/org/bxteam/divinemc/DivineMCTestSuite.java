package org.bxteam.divinemc;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Test suite for DivineMC tests which need vanilla registry values present")
@IncludeTags("VanillaFeature")
@SelectPackages("org.bxteam.divinemc")
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class DivineMCTestSuite {
}
