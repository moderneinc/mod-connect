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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "version", description = "Prints the mod-connect version")
public class Version implements Callable<Integer> {

    @CommandLine.Spec
    protected CommandLine.Model.CommandSpec commandSpec;

    @Override
    public Integer call() throws Exception {
        try(InputStream is = this.getClass().getClassLoader().getResourceAsStream("version.txt")){
            if (is == null) {
                System.out.println("[ERROR] Version not found");
                return 1;
            }
            String version = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).readLine();
            commandSpec.commandLine().getOut().println(version);
        }
        return 0;
    }
}
