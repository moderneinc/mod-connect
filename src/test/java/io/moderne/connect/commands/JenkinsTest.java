package io.moderne.connect.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.junit.jupiter.api.BeforeEach;
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
class JenkinsTest {
    private final CommandLine cmd = new CommandLine(new Connect());
    private static final String ARTIFACTORY_URL = "https://artifactory.moderne.ninja/artifactory/moderne-ingest";
    private static final String ARTIFACT_CREDS = "artifactCreds";
    private static final String GIT_CREDS = "myGitCreds";
    private static final String JENKINS_TESTING_USER = "admin";
    private static final String JENKINS_TESTING_PWD = "jenkins123";
    private static final String AST_PUBLISH_USERNAME = "admin";
    private static final String AST_PUBLISH_PASSWORD = "blah";
    private static String jenkinsHost;
    private String apiToken;

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
    void submitJobsWithPassword() throws Exception {
        int result = cmd.execute( "jenkins",
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
    void submitJobWithMavenSettings() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--mavenSettingsConfigFileId", "maven-ingest-settings-credentials");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json")
                .asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-with-maven-settings.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobWithGradlePluginVersion() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--gradlePluginVersion", "5.0.2");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json")
                .asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-with-gradle-plugin.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobWithMavenPluginVersion() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--mvnPluginVersion", "5.0.2");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json")
                .asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-with-maven-plugin.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitTwoJobs() throws Exception {
        int result = cmd.execute( "jenkins",
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
    void submitJobsWithoutCLIDownload() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--downloadCLI=false");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-without-download.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsDetectJava() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos-detect-java.csv").getAbsolutePath(),
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
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-detect-java.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsDetectJavaWithSupportedVersions() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos-detect-java.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--supportedJdkVersions", "8,11,17");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-detect-java-with-supported.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsDefaultJdk() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos-detect-java.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--defaultJdkVersion", "8");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-defaultJdk.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsWithoutJava() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos-without-java.csv").getAbsolutePath(),
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
        String expectedJob = new String(Files.readAllBytes(new File("src/test/jenkins/config-without-java.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsWithCustomCLIURL() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--downloadCLIUrl", "https://acme.com/moderne-cli");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob =
                new String(Files.readAllBytes(new File("src/test/jenkins/config-with-custom-url.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobsWithSkipSSL() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--skipSSL=true");
        assertEquals(0, result);

        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));

        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();
        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob =
                new String(Files.readAllBytes(new File("src/test/jenkins/config-with-skipSSL.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void downloadsCLIWithCreds() throws Exception {
        int result = cmd.execute( "jenkins",
                "--fromCsv", new File("src/test/csv/repos.csv").getAbsolutePath(),
                "--controllerUrl", jenkinsHost,
                "--jenkinsUser", JENKINS_TESTING_USER,
                "--apiToken", apiToken,
                "--publishCredsId", ARTIFACT_CREDS,
                "--gitCredsId", GIT_CREDS,
                "--publishUrl", ARTIFACTORY_URL,
                "--downloadCLIUrl", "https://acme.com/moderne-cli",
                "--downloadCLICreds", "downloadCreds");
        assertEquals(0, result);
        await().untilAsserted(() -> assertTrue(Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/api/json").asString().isSuccess()));
        HttpResponse<String> response = Unirest.get(jenkinsHost + "/job/moderne-ingest/job/openrewrite_rewrite-spring_main/config.xml").asString();

        assertTrue(response.isSuccess(), "Failed to get job config.xml: " + response.getStatusText());
        String expectedJob =
                new String(Files.readAllBytes(new File("src/test/jenkins/config-with-custom-url-creds.xml").toPath()));
        assertThat(response.getBody()).isEqualToIgnoringWhitespace(expectedJob);
    }

    @Test
    void submitJobTwice() throws Exception {
        int result = cmd.execute( "jenkins",
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
        result = cmd.execute( "jenkins",
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
        int result = cmd.execute( "jenkins",
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
}
