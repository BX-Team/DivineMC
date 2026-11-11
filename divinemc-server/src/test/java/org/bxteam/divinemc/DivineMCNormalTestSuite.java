package org.bxteam.divinemc;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Test suite for standalone DivineMC tests, which don't need any registry values present")
@IncludeTags("Normal")
@SelectPackages("org.bxteam.divinemc")
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class DivineMCNormalTestSuite {
}
