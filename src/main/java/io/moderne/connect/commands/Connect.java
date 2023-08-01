package io.moderne.connect.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(name = "mod-connect",
        description = "Automated code remediation.",
        subcommands = {
                HelpCommand.class,
                Jenkins.class,
                GitHub.class
        })
public class Connect {

    public static final int COMMAND_FAILURE = -1;

    private int executionStrategy(CommandLine.ParseResult parseResult) {
        int result;
        try {
            result = new CommandLine.RunLast().execute(parseResult);
        } catch (Exception e) {
            result = COMMAND_FAILURE;
        }
        return result;
    }

    public static void main(String... args) {
        Connect mod = new Connect();
        System.setProperty("picocli.disable.closures", "true");

        int exitCode = new CommandLine(mod)
                .setExecutionStrategy(mod::executionStrategy)
                // #458 Help is normally called by default with PicoCLI, but that fails in GraalVM Native Image
                .execute(args.length == 0 ? new String[]{"help"} : args);
        System.exit(exitCode);
    }


}
