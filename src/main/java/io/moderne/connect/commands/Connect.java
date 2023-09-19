/**
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.moderne.connect.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(name = "mod-connect",
        description = "Automated code remediation.",
        subcommands = {
                HelpCommand.class,
                Jenkins.class,
                GitHub.class,
                GitLab.class,
                Version.class
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
