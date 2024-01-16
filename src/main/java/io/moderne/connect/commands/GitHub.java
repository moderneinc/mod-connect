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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.moderne.connect.utils.TextBlock;
import kong.unirest.HeaderNames;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "github",
        headerHeading = "@|bold,underline Usage|@:%n%n",
        synopsisHeading = "%n",
        descriptionHeading = "%n@|bold,underline Description|@:%n%n",
        parameterListHeading = "%n@|bold,underline Parameters|@:%n%n",
        optionListHeading = "%n@|bold,underline Options|@:%n%n",
        header = "Creates a GitHub workflow that builds and publishes LST artifacts to your artifact repository on a regular basis.",
        description = """
                This command will create a GitHub workflow that builds and publishes LST artifacts to your \
                artifact repository on a regular basis. \
                A workflow can be created for ingesting a single repository (by specifying the @|bold path|@ parameter) \
                or a workflow can be created for ingesting a mass number of repositories (by specifying the \
                @|bold fromCsv|@ parameter).
                
                @|bold,underline If you specify the path parameter|@:
                
                This command will create a @|bold moderne-workflow.yml|@ file in the @|bold .github/workflows|@ \
                directory at the path you specified. This workflow file can then be modified and published to a \
                GitHub repository to set up the workflow for building and publishing LST artifacts for that repository.
                
                When running this command, you will need to ensure that you provide the \
                @|bold publishPwdSecretName|@, @|bold publishUrl|@, and @|bold publishUserSecretName|@ parameters.
                
                For the @|bold publishPwdSecretName|@ and @|bold publishUserSecretName|@ parameters, the expectation \
                is that you will create a GitHub secret for each inside of the repository you're wanting to ingest. \
                When running this command, you'd then provide the names of these secrets rather than the secrets \
                themselves
                (e.g., @|bold --publishPwdSecretName <name of GitHub secret>|@).
                
                * @|bold Example|@:
                
                  mod connect github --publishPwdSecretName publishPwdSecretName \\
                      --publishUrl https://some-repo.com \\
                      --publishUserSecretName publishUserSecretName \\
                      --path /path/to/project\s
                
                
                @|bold,underline If you specify the fromCsv parameter|@:
                
                This command will directly commit an ingestion workflow and the necessary files to run it to the \
                GitHub repository you specify. This workflow will iterate over every repository in the CSV and \
                build/publish LST artifacts for each.
                
                Before running this command, you will need to ensure that you've created a dedicated GitHub \
                repository where all of these files can be uploaded to.
                
                When running this command, you will need to ensure that you provide the @|bold accessToken|@, \
                @|bold dispatchSecretName|@, @|bold publishPwdSecretName|@, @|bold publishUrl|@, \
                @|bold publishUserSecretName|@, @|bold repo|@, and @|bold repoReadSecretName|@ parameters.
                
                For the @|bold dispatchSecretName|@, @|bold repoReadSecretName|@, @|bold publishPwdSecretName|@ and \
                @|bold publishUserSecretName|@ parameters, the expectation is that you will \
                create a GitHub secret for each inside of the repository you provided to this command. When running \
                this command, you'd then provide the names of these secrets rather than the secrets themselves \
                (e.g., @|bold --publishPwdSecretName <name of GitHub secret>|@).
                
                * @|bold Example|@:
                
                  mod connect github --accessToken moderne-github-access-token \\
                      --dispatchSecretName dispatchSecretName \\
                      --fromCsv /path/to/repos.csv \\
                      --publishPwdSecretName publishPwdSecretName \\
                      --publishUrl https://artifact-place.com/artifactory/moderne-ingest \\
                      --publishUserSecretName publishUserSecretName\s
                      --repo company-name/repo-name \\
                      --repoReadSecretName readSecretName\
                """)
public class GitHub implements Callable<Integer> {

    /**
     * Required Parameters
     **/
    @CommandLine.Option(names = "--publishPwdSecretName", required = true,
            description = """
                    The name of the GitHub secret that contains the password needed to upload LST artifacts to \
                    your artifact repository.
                    
                    GitHub secrets can be created inside of of the Security -> Secrets -> Actions section in a \
                    GitHub repository.
                    """)
    private String publishPwdSecretName;

    @CommandLine.Option(names = "--publishUrl", required = true, defaultValue = "${MODERNE_PUBLISH_URL}",
            description = """
                    The URL of the Maven formatted artifact repository where LST artifacts should be uploaded \
                    to.
                    
                    Will default to the environment variable @|bold MODERNE_PUBLISH_URL|@ if one exists.
                    """)
    private String publishUrl;

    @CommandLine.Option(names = "--publishUserSecretName", required = true,
            description = """
                    The name of the GitHub secret that contains the username needed to upload LST artifacts to \
                    your artifact repository.
                    
                    GitHub secrets can be created inside of of the Security -> Secrets -> Actions section in a \
                    GitHub repository.
                    """)
    private String publishUserSecretName;

    @CommandLine.ArgGroup(multiplicity = "1")
    private Source source;

    // One of these two is required
    static class Source {
        @CommandLine.Option(names = "--path",
                description = """
                        The local path to the Git repository where a GitHub workflow should be created.
                        
                        To run the connect github command, you must either specify the @|bold path|@ or the \
                        @|bold fromCsv|@ parameter.
                        """)
        Path path;
        @CommandLine.Option(names = "--fromCsv",
                description = """
                        The location of the CSV file containing the list of repositories that should be \
                        ingested into Moderne. A GitHub action will build and publish LST artifacts for every \
                        repository in this file.
                        
                        Follows the schema of:\s
                        
                        @|bold [repoName,repoBranch,javaVersion,desiredStyle,additionalBuildArgs,skip,skipReason]|@
                        
                        * @|bold repoName|@: @|italic Required|@ - The repository that should be ingested. Follows \
                        the format of organization/repository.
                        
                        ** @|bold Example|@: openrewrite/rewrite
                        
                        * @|bold repoBranch|@: @|italic Optional|@ - The branch of the above repository that should be \
                        ingested.
                        
                        ** @|bold Default|@: main
                        
                        * @|bold javaVersion|@: @|italic Optional|@ - The Java version used to compile this \
                        repository.
                        ** @|bold Default|@: 11
                        
                        * @|bold desiredStyle|@: @|italic Optional|@ - The OpenRewrite style name to apply during \
                        ingest.
                        
                        ** @|bold Example|@: org.openrewrite.java.SpringFormat
                        
                        * @|bold additionalBuildArgs|@: @|italic Optional|@ - Additional arguments that are added to \
                        the Maven or Gradle build command.
                        
                        ** @|bold Example|@: -Dmaven.antrun.skip=true
                        
                        * @|bold skip|@: @|italic Optional|@ - If set to true, this repo will not be ingested.
                        
                        ** @|bold Default|@: false
                        
                        * @|bold skipReason|@: @|italic Optional|@ - The context for why the repo is being skipped.
                        
                        
                        @|bold CSV Example|@:
                        
                          openrewrite/rewrite-spring,main,11,org.openrewrite.java.SpringFormat,,false,,
                          openrewrite/rewrite,master,17,,-Phadoop_2,,
                          foo/bar,main,11,,,true,some skip reason
                          // More Rows
                        
                        To run the connect github command, you must either specify the @|bold path|@ or the \
                        @|bold fromCsv|@ parameter.
                        """)
        Path csvFile;
    }

    /**
     * Optional Parameters
     **/
    @CommandLine.Option(names = "--accessToken",
            description = """
                    A @|bold classic|@ GitHub access token that will be used to commit files and create \
                    workflows (a fine-grained token won't work). This token @|italic will not|@ be used to run the \
                    workflows.
                    
                    This token requires the @|bold workflow|@ permission.
                    
                    This parameter is @|bold required|@ if the @|bold fromCSV|@ parameter is specified.
                    """)
    private String accessToken;

    @CommandLine.Option(names = "--apiUrl", defaultValue = "https://api.github.com",
            description = """
                    The base URL for the GitHub REST API. For GitHub enterprise users, this commonly \
                    follows the format of: @|bold http(s)://HOSTNAME/api/v3|@\s
                    
                    @|bold Default|@: ${DEFAULT-VALUE}
                    """)
    private String apiURL;

    @CommandLine.Option(names = "--branch", defaultValue = "main",
            description = """
                    The branch of the repository specified in the @|bold repo|@ parameter where the generated \
                    workflow files should be committed to.
                    
                    @|bold Default|@: ${DEFAULT-VALUE}
                    """)
    private String branch;

    @CommandLine.Option(names = "--additionalBuildArgs", defaultValue = "",
            description = """
                    Additional arguments that are added to the Maven or Gradle build command.
                    
                    @|bold Example|@: -Dmaven.antrun.skip=true
                    """)
    private String additionalBuildArgs;

    @CommandLine.Option(names = "--cliVersion", defaultValue = "v2.0.5",
            description = """
                    The version of the Moderne CLI that should be used when running the ingestion workflow. \
                    Follows standard semantic versioning with a v in front.
                    
                    @|bold Example|@: @|bold v2.0.5|@
                    """)
    private String cliVersion;

    @CommandLine.Option(names = "--dispatchSecretName",
            description = """
                    The name of the GitHub secret that contains the access token that will be used to run the \
                    GitHub workflows in the repo specified in the repo parameter.
                    
                    This token requires the @|bold workflow|@ permission.
                    
                    GitHub secrets can be created inside of of the Security -> Secrets -> Actions section in a \
                    GitHub repository.
                    
                    This parameter is @|bold required|@ if the @|bold fromCSV|@ parameter is specified.
                    """)
    private String dispatchSecretName;

    @CommandLine.Option(names = "--javaVersion", defaultValue = "11",
            description = """
                    The Java version needed to compile and run the repository that is indicated in the \
                    @|bold path|@ parameter. Can be any major version (e.g., 8, 11, 17).
                    
                    @|bold Default|@: ${DEFAULT-VALUE}
                    """)
    private int javaVersion;

    @CommandLine.Option(names = "--repoReadSecretName",
            description = """
                    The name of the GitHub secret that contains the access token with read access to each \
                    repository in the provided CSV.
                    
                    GitHub secrets can be created inside of of the Security -> Secrets -> Actions section in a \
                    GitHub repository.
                    
                    This parameter is @|bold required|@ if the @|bold fromCSV|@ parameter is specified.
                    """)
    private String repoReadSecretName;

    @CommandLine.Option(names = "--repo",
            description = """
                    The dedicated GitHub repository where the workflows and the CSV file will be committed to. \
                    Follows the format of organization/repository name.
                    
                    This parameter is @|bold required|@ if the @|bold fromCSV|@ parameter is specified.
                    
                    @|bold Example|@: openrewrite/rewrite
                    """)
    private String repository;

    @CommandLine.Option(names = "--verbose", defaultValue = "false",
            description = """
                    If enabled, additional debug statements will be printed throughout the GitHub configuration.
                    
                    @|bold Default|@: ${DEFAULT-VALUE}
                    """)
    private boolean verbose;

    private static final String GITHUB_WORKFLOWS_FOLDER = ".github/workflows/";
    private static final String MODERNE_DISPATCH_INGEST_WORKFLOW = GITHUB_WORKFLOWS_FOLDER + "moderne-dispatch-ingest.yml";
    private static final String MODERNE_MASS_INGEST_WORKFLOW = GITHUB_WORKFLOWS_FOLDER + "moderne-mass-ingest.yml";
    private static final String WORKFLOW_TEMPLATE = ".github/minimal-cli-workflow.yml";

    @Override
    public Integer call() {

        try {

            if (source.csvFile != null) {

                if (!source.csvFile.toFile().exists()) {
                    System.err.println("[ERROR] The " + source.csvFile.toFile().getName() + " does not exist");
                    return 1;
                }

                System.out.println("The CSV " + source.csvFile.toFile().getName() + " ...[FOUND]");
                if (StringUtils.isBlank(accessToken)) {
                    System.err.println("[ERROR] Missing required option: --accessToken=<accessToken>");
                    return 1;
                }

                if (StringUtils.isBlank(repository)) {
                    System.err.println("[ERROR] Missing required option: --repo=<repo>");
                    return 1;
                }

                if (repository.split("/").length != 2) {
                    System.err.println("[ERROR] The repository parameter must follow the format of organization/repository name (e.g., openrewrite/rewrite)");
                    return 1;
                }

                if (StringUtils.isBlank(repoReadSecretName)) {
                    System.err.println("[ERROR] Missing required option: --repoReadSecretName=<repoReadSecretName>");
                    return 1;
                }

                if (!validCSVSchema()) {
                    System.err.println(
                            "[ERROR] Empty file or invalid CSV schema. Expected format: repoName,branch,javaVersion,style,buildAction,skip,skipReason");
                    return 1;
                }

                if (StringUtils.isBlank(dispatchSecretName)) {
                    System.err.println("[ERROR] Missing required option: --dispatchSecretName=<dispatchSecretName>");
                    return 1;
                }
                commitFiles();

                System.out.printf("The repository %s workflows have been committed successfully%n",
                        repository);
            } else {
                File repository = source.path.toFile();
                if (!repository.exists()) {
                    System.err.println("[ERROR] The --path  " + repository.getPath() + " does not exist");
                    return 1;
                }
                if (!repository.isDirectory()) {
                    System.err.println("[ERROR] The --path  " + repository.getPath() + " is not a directory");
                    return 1;
                }

                generateWorkflowFile();

                System.out.printf("A new workflow has been generated in %s%n", source.path);
            }
        } catch (Throwable e) {
            System.err.println("ERROR configuring GitHub.");
            System.err.println(e.getMessage());
            if (verbose) {
                e.printStackTrace();
            } else {
                System.err.println("Please, use --verbose for more details.");
            }
            return 1;
        }
        return 0;
    }

    private void generateWorkflowFile() throws IOException {
        String setupJavaAction = TextBlock.textBlock("cli/github/setupJava.json.template");
        if (new File(source.path.toFile(), "pom.xml").exists()) {
            setupJavaAction = setupJavaAction.formatted(javaVersion, "maven");
        } else if (new File(source.path.toFile(), "build.gradle").exists()
                || new File(source.path.toFile(), "build.gradle.kts").exists()) {
            setupJavaAction = setupJavaAction.formatted(javaVersion, "gradle");
        } else {
            setupJavaAction = "";
        }

        String workflow = toString(WORKFLOW_TEMPLATE).formatted(
                setupJavaAction, cliVersion, publishUrl, publishUserSecretName, publishPwdSecretName, this.additionalBuildArgs);
        Path workflowsDir = Files.createDirectories(new File(source.path.toFile(), GITHUB_WORKFLOWS_FOLDER).toPath());

        File workflowFile = new File(workflowsDir.toFile(), "moderne-workflow.yml");
        if (!workflowFile.exists() && !workflowFile.createNewFile()) {
            throw new RuntimeException("Unable to create workflow file");
        }
        try (PrintWriter out = new PrintWriter(workflowFile)) {
            out.print(workflow);
        }
    }

    private boolean validCSVSchema() throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(source.csvFile.toFile()))) {
            String line = br.readLine();
            int columns = 7;
            return line != null && line.split(",", columns).length == columns;
        }
    }

    private void commitFiles() throws IOException {
        HttpResponse<String> response = Unirest.post(apiURL + "/graphql")
                .header(HeaderNames.AUTHORIZATION, "Bearer " + accessToken)
                .body(TextBlock.textBlock("cli/github/createRepo.json.template").formatted(
                repository, branch,
                toBase64(toString(MODERNE_DISPATCH_INGEST_WORKFLOW).formatted(
                        repoReadSecretName, cliVersion, publishUrl, publishUserSecretName, publishPwdSecretName)),
                toBase64(toString(MODERNE_MASS_INGEST_WORKFLOW).formatted(apiURL, repository, dispatchSecretName)),
                fromResourcetoBase64(),
                toBase64(new String(Files.readAllBytes(source.csvFile))),
                lastCommit())).asString();

        if (!response.isSuccess()) {
            throw new RuntimeException("[ERROR] The commit in %s to submit Moderne workflows failed with error code %s. Message: %s".formatted(
                    repository, response.getStatus(), response.getBody()));
        }
    }

    private String lastCommit() throws JsonProcessingException {
        String[] slug = repository.split("/");
        HttpResponse<String> lastCommitResponse = Unirest.post(apiURL + "/graphql")
                .header(HeaderNames.AUTHORIZATION, "Bearer " + accessToken)
                .body(TextBlock.textBlock("cli/github/last_commit.json.template").formatted(slug[1], slug[0], branch)).asString();

        if (!lastCommitResponse.isSuccess()) {
            throw new RuntimeException(
                    ("""
                            [ERROR] It is not possible to resolve the last commit for \
                            the repository %s and branch %s. Error code %s with message %s\
                            """).formatted(repository, branch,
                            lastCommitResponse.getStatus(), lastCommitResponse.getBody()));
        }

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree(lastCommitResponse.getBody());

        if (node.has("errors")) {

            JsonNode messageNode = node.get("errors").get("message");
            String message = messageNode == null
                    ? "Verify your PATH has workflow permissions" : messageNode.asText();

            throw new RuntimeException(("""
                    [ERROR] It is not possible to resolve the l\
                    ast commit for the repository %s and branch %s. Error %s\
                    """).formatted(repository, branch, message));
        } else {
            JsonNode ref = node.get("data")
                    .get("repository")
                    .get("ref");

            if (ref == null || ref.isNull()) {
                throw new RuntimeException(
                        "The branch %s do not exist in %s".formatted(branch, repository));
            }
            return ref
                    .get("target")
                    .get("history")
                    .get("nodes")
                    .get(0).get("oid").asText();
        }
    }

    private String toString(String resource) throws IOException {
        InputStream input = GitHub.class.getClassLoader()
                .getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("Resource not found: " + resource);
        }
        return new String(IOUtils.toByteArray(input));
    }

    private String fromResourcetoBase64() throws IOException {
        InputStream input = GitHub.class.getClassLoader()
                .getResourceAsStream(".github/workflows/ingest.sh");
        if (input == null) {
            throw new IOException("Resource not found: .github/workflows/ingest.sh");
        }
        return Base64.getEncoder().encodeToString(IOUtils.toByteArray(input));
    }

    private String toBase64(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes());
    }

}
