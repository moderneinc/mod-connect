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

import com.fasterxml.jackson.databind.ObjectMapper;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JenkinsIntegTest {
    private final CommandLine cmd = new CommandLine(new Connect());
    private static final String ARTIFACTORY_URL = "https://artifactory.moderne.ninja/artifactory/moderne-ingest";
    private static final String ARTIFACT_CREDS = "artifactCreds";
    private static final String GIT_CREDS = "myGitCreds";
    private static final String MAVEN_SETTINGS = "maven_settings";
    private static final String JENKINS_TESTING_USER = "admin";
    private static final String JENKINS_TESTING_PWD = "jenkins123";
    private static final String AST_PUBLISH_USERNAME = "admin";
    private static final String AST_PUBLISH_PASSWORD = "blah";
    private static String jenkinsHost;
    private String apiToken;

    @SuppressWarnings("resource")
    @Container
    private final GenericContainer<?> jenkinsContainer = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withDockerfile(new File("src/test/jenkins/Dockerfile").toPath())
                    .withFileFromFile("casc.yaml", new File("src/test/jenkins/casc.yaml")))
            .withExposedPorts(8080)
            .withEnv("JENKINS_ADMIN_ID", JENKINS_TESTING_USER)
            .withEnv("JENKINS_ADMIN_PASSWORD", JENKINS_TESTING_PWD)
            .withEnv("JENKINS_AST_PUBLISH_USERNAME", AST_PUBLISH_USERNAME)
            .withEnv("JENKINS_AST_PUBLISH_PASSWORD", AST_PUBLISH_PASSWORD)
            .withEnv("JENKINS_GIT_USERNAME", "")
            .withEnv("JENKINS_GIT_PASSWORD", "")
            .waitingFor(Wait.forLogMessage(".*Jenkins is fully up and running.*\\n", 1));

    @BeforeEach
    void setUp() {
        jenkinsHost = "http://" + jenkinsContainer.getHost() + ":" + jenkinsContainer.getFirstMappedPort();
        apiToken = createApiToken();
    }

    private static String createApiToken() {
        // Create the API token, which appears not to be supported other than through the UI_main/api
        HttpResponse<String> response = Unirest.post(jenkinsHost + "/me/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken")
                .basicAuth(JENKINS_TESTING_USER, JENKINS_TESTING_PWD)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(Jenkins.JENKINS_CRUMB_HEADER, Jenkins.generateCrumb(jenkinsHost, JENKINS_TESTING_USER, JENKINS_TESTING_PWD))
                .body("newTokenName=cli")
                .asString();
        assertTrue(response.isSuccess(), "Failed to create API token: " + response.getStatus() + " " + response.getStatusText());
        try {
            return new ObjectMapper()
                    .readTree(response.getBody())
                    .get("data")
                    .get("tokenValue")
                    .asText();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void submitJobs() throws Exception {
        int result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--verbose");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json")
                .asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsWithPassword() throws Exception {
        int result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--jenkinsPwd", JENKINS_TESTING_PWD,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL);
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json")
                .asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitMultipleJobs() throws Exception {
        int result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/jenkins-repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL);
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-java-migration_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-java-migration_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/rewrite-java-migration-config.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobTwice() throws Exception {
        int result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL);
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);

        // Submit the same job again, to very that it doesn't fail
        result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL);
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobAndRemove() throws Exception {
        int result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL);
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);

        // Now delete the job by marking it as skipped
        result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/jenkins-skipped.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--deleteSkipped=true");
        assertEquals(0, result);

        await().untilAsserted(() -> assertFalse(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));
    }

    @Test
    void submitJobAgentWithXMLEntities() throws Exception {
        int result = cmd.execute("jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--agent", "{ label 'os=windows && !reserved' }",
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL);
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json")
                .asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-agent.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Nested
    class FreestyleJobs {
        @Test
        void submitFreestyleJobs() throws Exception {
            int result = cmd.execute("jenkins",
                    "--fromCsv", new File("src/test/csv/jenkins-repos.csv").getAbsolutePath(),
                    "--controllerUrl", jenkinsHost,
                    "--jenkinsUser", JENKINS_TESTING_USER,
                    "--apiToken", apiToken,
                    "--publishCredsId", ARTIFACT_CREDS,
                    "--gitCredsId", GIT_CREDS,
                    "--publishUrl", ARTIFACTORY_URL,
                    "--folder", "freestyle",
                    "--downloadCLI",
                    "--mavenSettingsConfigFileId", MAVEN_SETTINGS,
                    "--jobType", "FREESTYLE",
                    "--verbose");
            assertEquals(0, result);

            await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/freestyle/job/openrewrite_rewrite-spring_main/api/json")
                    .asString().isSuccess()));

            HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/freestyle/job/openrewrite_rewrite-spring_main/config.xml").asString();
            assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
            String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-freestyle-gradle.xml").toPath()));
            assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
        }

    }
}
