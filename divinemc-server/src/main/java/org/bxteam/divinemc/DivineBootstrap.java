package org.bxteam.divinemc;

import io.papermc.paper.ServerBuildInfo;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;

public class DivineBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("DivineBootstrap");

    public static OptionSet bootstrap(String[] args) {
        OptionParser parser = Main.main(args);
        OptionSet options = parser.parse(args);

        if ((options == null) || (options.has("?"))) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException ex) {
                LOGGER.warn(ex.getMessage());
            }
        } else if (options.has("v")) {
            System.out.println(CraftServer.class.getPackage().getImplementationVersion());
        } else {
            String path = new File(".").getAbsolutePath();
            if (path.contains("!") || path.contains("+")) {
                System.err.println("Cannot run server in a directory with ! or + in the pathname. Please rename the affected folders and try again.");
                System.exit(70);
            }

            boolean skip = Boolean.getBoolean("Paper.IgnoreJavaVersion");
            String javaVersionName = System.getProperty("java.version");
            boolean isPreRelease = javaVersionName.contains("-");
            if (isPreRelease) {
                if (!skip) {
                    System.err.println("Unsupported Java detected (" + javaVersionName + "). You are running an unsupported, non official, version. Only general availability versions of Java are supported. Please update your Java version. See https://docs.papermc.io/paper/faq#unsupported-java-detected-what-do-i-do for more information.");
                    System.exit(70);
                }

                System.err.println("Unsupported Java detected ("+ javaVersionName + "), but the check was skipped. Proceed with caution! ");
            }

            try {
                if (options.has("nojline")) {
                    System.setProperty(net.minecrell.terminalconsole.TerminalConsoleAppender.JLINE_OVERRIDE_PROPERTY, "false");
                    Main.useJline = false;
                }

                if (options.has("noconsole")) {
                    Main.useConsole = false;
                    Main.useJline = false;
                    System.setProperty(net.minecrell.terminalconsole.TerminalConsoleAppender.JLINE_OVERRIDE_PROPERTY, "false"); // Paper
                }

                System.setProperty("library.jansi.version", "Paper");
                System.setProperty("jdk.console", "java.base");

                SharedConstants.tryDetectVersion();
                getStartupVersionMessages().forEach(LOGGER::info);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        return options;
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            ),
            String.format(
                "Running JVM args %s",
                ManagementFactory.getRuntimeMXBean().getInputArguments().toString()
            )
        );
    }
}
