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

import io.moderne.connect.utils.TextBlock;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class GitHubTest {

    private static final String USER_SECRET = "AST_PUBLISH_USERNAME";
    private static final String PWD_SECRET = "AST_PUBLISH_PASSWORD";

    private static final String MOD_VERSION = "v0.2.43";

    private static final String ARTIFACTORY_URL = "https://artifactory.moderne.ninja/artifactory/moderne-ingest";

    private final CommandLine cmd = new CommandLine(new Connect());

    @Test
    void submitJobs() {
        String testingToken = System.getenv("GH_TESTING_TOKEN");
        assumeFalse(StringUtils.isBlank(testingToken), () -> "Aborting because there is no GitHub token to create jobs in moderneinc/test-gh-cli");
        int result = cmd.execute("github",
                "--fromCsv", new File("src/test/csv/gh-repos.csv").getAbsolutePath(),
                "--repo", "moderneinc/test-gh-cli",
                "--accessToken", testingToken,
                "--publishUserSecretName", USER_SECRET,
                "--publishPwdSecretName", PWD_SECRET,
                "--dispatchSecretName", "GH_PAT",
                "--publishUrl", ARTIFACTORY_URL,
                "--repoReadSecretName", "GH_PAT");
        assertEquals(0, result);
    }

    @Test
    void createsMavenWorkflow() throws Exception {
        testBuildToolWorkflow("maven", "src/test/repos/mvn-repo");
    }

    @Test
    void createsGradleWorkflow() throws Exception {
        testBuildToolWorkflow("gradle", "src/test/repos/gradle-repo");
    }

    private File deleteWorkflow(File folder) {
        File workflow = new File(folder, ".github/workflows/moderne-workflow.yml");
        if (workflow.exists()) {
            workflow.delete();
        }
        return workflow;
    }

    private void testBuildToolWorkflow(String buildTool, String folder) throws Exception {
        File path = new File(folder);
        File workflow = deleteWorkflow(path);

        int result = cmd.execute( "github",
                "--path", path.getAbsolutePath(),
                "--publishUserSecretName", USER_SECRET,
                "--publishPwdSecretName", PWD_SECRET,
                "--publishUrl", ARTIFACTORY_URL);

        assertEquals(0, result);
        assertTrue(workflow.exists());

        String workflowContent = TextBlock.textBlock("github/workflow_buildtool.yaml");
        assertEquals(String.format(workflowContent, buildTool, MOD_VERSION),
                new String(Files.readAllBytes(workflow.toPath())));
        deleteWorkflow(path);
    }

    @Test
    void createsAgnosticWorkflow() throws Exception {
        File path = new File("src/test/repos/plaintext-repo");
        File workflow = deleteWorkflow(path);

        int result = cmd.execute( "github",
                "--path", path.getAbsolutePath(),
                "--publishUserSecretName", USER_SECRET,
                "--publishPwdSecretName", PWD_SECRET,
                "--publishUrl", ARTIFACTORY_URL,
                "--repoReadSecretName", "PAT_TOKEN");
        assertEquals(0, result);
        assertTrue(workflow.exists());
        String workflowAgnostic = TextBlock.textBlock("github/workflow_agnostic.yaml");
        assertEquals(String.format(workflowAgnostic, MOD_VERSION),
                new String(Files.readAllBytes(workflow.toPath())));
        deleteWorkflow(path);

    }

}
