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

import io.moderne.connect.utils.GitLabYaml;
import org.apache.commons.lang3.StringUtils;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "gitlab",
        footerHeading = "%n@|bold,underline Notes|@:%n%n",
        headerHeading = "@|bold,underline Usage|@:%n%n",
        synopsisHeading = "%n",
        descriptionHeading = "%n@|bold,underline Description|@:%n%n",
        parameterListHeading = "%n@|bold,underline Parameters|@:%n%n",
        optionListHeading = "%n@|bold,underline Options|@:%n%n",
        header = "Creates a GitLab job with each configured repository that will build and publish LST artifacts " +
                 "to your artifact repository.",
        description = "Creates a GitLab job for each configured repository that will build and publish LST artifacts " +
                      "to your artifact repository on a regular basis.\n\n" +
                      "@|bold,underline Example|@:\n\n" +
                      "  mod connect gitlab\\\n" +
                      "     --fromCsv /path/to/repos.csv \\\n" +
                      "     --publishUserSecretName publishUserSecretName \\\n" +
                      "     --publishPwdSecretName publishPwdSecretName \\\n" +
                      "     --publishUrl https://artifact-place.com/artifactory/moderne-ingest")
public class GitLab implements Callable<Integer> {

    /**
     * Required Parameters
     **/

    @CommandLine.Option(names = "--fromCsv",
            required = true,
            description = "The location of the CSV file containing the list of repositories that should be ingested. " +
                          "One GitLab job will run for each repository. Follows the schema of:\n" +
                          "\n" +
                          "@|bold [repoName,repoBranch,desiredStyle,additionalBuildArgs,skip,skipReason]|@\n" +
                          "\n" +
                          "* @|bold repoName|@: @|bold Required|@ - The repository that should be ingested. Follows the " +
                          "format of: organization/repository.\n" +
                          "\n" +
                          "** @|bold Example|@: openrewrite/rewrite\n" +
                          "\n" +
                          "* @|bold repoBranch|@: @|italic Optional|@ - The branch of the above repository that should be " +
                          "ingested.\n" +
                          "\n" +
                          "** @|bold Default|@: main\n" +
                          "\n" +
                          "* @|bold desiredStyle|@: @|italic Optional|@ - The OpenRewrite style name to apply during ingest.\n" +
                          "\n" +
                          "** @|bold Example|@: org.openrewrite.java.SpringFormat\n" +
                          "\n" +
                          "* @|bold additionalBuildArgs|@: @|italic Optional|@ - Additional arguments that are added to " +
                          "the Maven or Gradle build command.\n" +
                          "\n" +
                          "** @|bold Example|@: -Dmaven.antrun.skip=true\n" +
                          "\n" +
                          "* @|bold skip|@: @|italic Optional|@ - If set to true, this repo will not be ingested.\n" +
                          "\n" +
                          "** @|bold Default|@: false\n" +
                          "\n" +
                          "* @|bold skipReason|@: @|italic Optional|@ - The context for why the repo is being skipped.\n" +
                          "\n\n" +
                          "@|bold CSV Example|@:\n" +
                          "\n" +
                          "  ,openrewrite/rewrite-spring,main,,gradle,,,,,\n" +
                          "  ,openrewrite/rewrite-java-migration,main,,gradle,,,,,\n" +
                          "  additional rows...\n"
    )
    Path fromCsv;


    @CommandLine.Option(
            names = "--publishUserSecretName",
            required = true,
            description = "The name of the GitLab secret that contains the username needed to upload LST artifacts to " +
                          "your artifact repository.\n" +
                          "\n" +
                          "GitLab secrets can be created inside of the Settings -> CI/CD, find Variables and click on " +
                          "the Expand button inside your GitLab repository.\n")
    String publishUserSecretName;


    @CommandLine.Option(
            names = "--publishPwdSecretName",
            required = true,
            description = "The name of the GitLab secret that contains the password needed to upload LST artifacts to " +
                          "your artifact repository.\n" +
                          "\n" +
                          "GitLab secrets can be created inside of the Settings -> CI/CD, find Variables and click on " +
                          "the Expand button inside your GitLab repository.\n")
    String publishPwdSecretName;

    @CommandLine.Option(
            names = "--publishUrl",
            required = true,
            defaultValue = "${MODERNE_PUBLISH_URL}",
            description = "The URL of the Maven formatted artifact repository where LST artifacts should be uploaded " +
                          "to.\n" +
                          "\n" +
                          "Will default to the environment variable @|bold MODERNE_PUBLISH_URL|@ if one exists.\n")
    String publishUrl;

    @CommandLine.Option(
            names = "--cliVersion",
            defaultValue = "v2.0.5",
            description = "The version of the Moderne CLI that should be used when running GitLab jobs.\n")
    String cliVersion;

    @CommandLine.Option(
            names = "--commandSuffix",
            defaultValue = "",
            description = "The suffix that should be appended to the Moderne CLI command when running GitLab jobs.\n\n" +
                          "@|bold Example|@: --dry-run\n")
    String commandSuffix;

    @CommandLine.Option(
            names = "--defaultBranch",
            defaultValue = "main",
            description = "If no Git branch is specified for a repository in the CSV file, the GitLab job will attempt " +
                          "to checkout this branch when pulling down the code.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    String defaultBranch;

    @CommandLine.Option(
            names = "--repositoryAccessUserSecretName",
            description = "The name of the secret containing the username that has access to the repositories in the CSV. " +
                          "This can be a personal username or group name.\n" +
                          "The minimum required grant is @|bold read_repository|@.\n" +
                          "If no token is specified, the $CI_JOB_TOKEN is used.\n")
    String repositoryAccessUserSecretName;

    @CommandLine.Option(
            names = "--repositoryAccessTokenSecretName",
            description = "The name of the secret containing the token that has access to the repositories in the CSV. " +
                          "This can be a personal or group access token.\n" +
                          "The minimum required grant is @|bold read_repository|@.\n" +
                          "If no token is specified, the $CI_JOB_TOKEN is used.\n")
    String repositoryAccessTokenSecretName;

    @CommandLine.Option(names = "--downloadCLI",
            defaultValue = "false",
            description = "Specifies whether or not the Moderne CLI should be downloaded at the beginning of each run." +
                          "Should be set to true when the base image does not include the CLI\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean downloadCLI;

    @CommandLine.Option(
            names = "--downloadCLIUrl",
            description = "Specifies an internal URL to download the CLI from if you'd prefer to host the CLI yourself.\n")
    String downloadCLIUrl;

    @CommandLine.Option(
            names = "--downloadCLITokenSecret",
            description = "The name of the GitLab secret that contains a Bearer token needed to download the CLI\n" +
                          "\n" +
                          "GitLab secrets can be created inside of the Settings -> CI/CD, find Variables and click on " +
                          "the Expand button inside your GitLab repository.\n")
    String downloadCLITokenSecretName;

    @CommandLine.Option(
            names = "--downloadCLIUserNameSecret",
            description = "The name of the GitLab secret that contains the username needed to download the CLI\n" +
                          "\n" +
                          "GitLab secrets can be created inside of the Settings -> CI/CD, find Variables and click on " +
                          "the Expand button inside your GitLab repository.\n")
    String downloadCLIUserNameSecretName;

    @CommandLine.Option(
            names = "--downloadCLIPasswordSecret",
            description = "The name of the GitLab secret that contains the password needed to download the CLI\n" +
                          "\n" +
                          "GitLab secrets can be created inside of the Settings -> CI/CD, find Variables and click on " +
                          "the Expand button inside your GitLab repository.\n")
    String downloadCLIPasswordSecretName;

    @CommandLine.Option(
            names = "--jobTag",
            description = "If specified, GitLab jobs will be tagged with this value for runners to pick up.\n",
            defaultValue = "")
    String jobTag;

    @CommandLine.Option(
            names = "--prefix",
            description = "If specified, GitLab jobs will only be created for repositories that start with this prefix.\n",
            defaultValue = "")
    String prefix;

    @CommandLine.Option(names = "--skipSSL",
            defaultValue = "false",
            description = "If this parameter is included, SSL verification will be skipped when pushing to artifactory.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean skipSSL;

    @CommandLine.Option(names = "--platform",
            description = "The OS platform for the Gitlab runner. The possible options are: windows, linux, or macos.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "linux")
    String platform;

    @CommandLine.Option(names = "--dockerImageBuildJob",
            description = "The full name of the docker image to run the build jobs on.\n" +
                          "This image requires both git and a JDK to be present.\n" +
                          "\n" +
                          "@|bold Example|@: \"registry.example.com/my/image:latest\"\n",
            defaultValue = "registry.gitlab.com/moderneinc/moderne-gitlab-ingest:latest")
    String dockerImageBuildJob;

    @CommandLine.Option(names = "--dockerImageDownloadJob",
            description = "The full name of the docker image to run the download job on.\n" +
                          "This image should be based on unix and requires curl to be present.\n" +
                          "\n" +
                          "@|bold Example|@: \"registry.example.com/my/image:latest\"\n",
            defaultValue = "ruby:latest")
    String dockerImageDownloadJob;

    @CommandLine.Option(names = "--buildJobRetries",
            description = "Retries to attempt for the build job. Options are: 0, 1 or 2.\n" +
                          "\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "0")
    int buildJobRetries;

    @CommandLine.Option(
            names = "--verbose",
            defaultValue = "false",
            description = "If enabled, additional debug statements will be printed.\n" +
                          "\n@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean verbose;

    @CommandLine.ArgGroup(exclusive = false)
    Tenant tenant;

    static class Tenant {
        @CommandLine.Option(names = "--moderneUrl", required = true,
                description = "The URL of the Moderne tenant.")
        String moderneUrl;

        @CommandLine.Option(names = "--moderneToken", required = true,
                description = "A personal access token for the Moderne tenant." +
                              "\n" +
                              "Note you can also use --moderneTokenSecret if you want to use a secret variable")
        String moderneToken;

        @CommandLine.Option(names = "--moderneTokenSecret", required = true,
                description = "A secret containing a personal access token for the Moderne tenant." +
                              "\n" +
                              "GitLab secrets can be created inside of the Settings -> CI/CD, find Variables and click on " +
                              "the Expand button inside your GitLab repository.\n")
        String moderneTokenSecret;
    }


    private static final String PLATFORM_WINDOWS = "windows";

    @Override
    public Integer call() {
        if (!fromCsv.toFile().exists()) {
            System.err.println(fromCsv.toString() + " does not exist");
            return 1;
        }

        if (!cliVersion.startsWith("v2")) {
            System.err.println("Unsupported CLI version: " + cliVersion + ". Please use a version greater than v2");
            return 1;
        }

        try {
            final GitLabYaml.Pipeline pipeline = createPipeline();
            if (pipeline == null) return 1;

            File pipelineFile = new File("moderne-pipeline.yml");
            if (!pipelineFile.exists() && !pipelineFile.createNewFile()) {
                throw new RuntimeException("Unable to create pipeline file");
            }
            try (PrintWriter out = new PrintWriter(pipelineFile)) {
                out.print(GitLabYaml.write(pipeline));
            }
            return 0;
        } catch (Throwable e) {
            System.err.println("ERROR configuring GitLab jobs.");
            System.err.println(e.getMessage());
            if (verbose) {
                e.printStackTrace();
            } else {
                System.err.println("Please, use --verbose for more details.");
            }
            return 1;
        }
    }

    GitLabYaml.Pipeline createPipeline() throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(fromCsv.toFile()))) {
            String line;
            int lineNumber = 1;

            Map<String, GitLabYaml.Job> buildJobs = new LinkedHashMap<>();

            while ((line = br.readLine()) != null) {
                if (line.startsWith("repoName")) { // line is a header line
                    lineNumber++;
                    continue;
                }
                String[] values = line.split(",", 6);
                if (values.length != 6) {
                    System.err.println("[ERROR] Invalid schema for line " + lineNumber);
                    System.err.println("The required schema is [repoName,repoBranch,desiredStyle,additionalBuildArgs,skip,skipReason]");
                    return null;
                }

                String repoPath = values[0];
                String branch = values[1];
                String repoStyle = values[2];
                String additionalBuildArgs = values[3];
                String repoSkip = values[4];
                String skipReason = values[5];

                if (StringUtils.isBlank(repoPath)) {
                    System.out.printf("Skipping line %d because there is an empty Git repo%n", lineNumber);
                    lineNumber++;
                    continue;
                }
                if (StringUtils.isNotBlank(prefix) && !repoPath.startsWith(prefix)) {
                    lineNumber++;
                    continue;
                }

                if (!StringUtils.isBlank(repoSkip) && "true".equalsIgnoreCase(repoSkip)) {
                    System.out.printf("Skipping %s at line %d because it is marked as skipped: %s%n", repoPath, lineNumber, skipReason);
                    lineNumber++;
                    continue;
                }
                if (StringUtils.isBlank(branch)) {
                    branch = defaultBranch;
                }


                buildJobs.put(String.format("build-%s", repoPath), createBuildLstJob(repoPath, branch));
                lineNumber++;
            }

            GitLabYaml.Pipeline.PipelineBuilder builder = GitLabYaml.Pipeline.builder();
            if (downloadCLI) {
                builder.stage(GitLabYaml.Stage.DOWNLOAD).download(createDownloadJob());
            }
            return builder
                    .stage(GitLabYaml.Stage.BUILD_LST)
                    .jobs(buildJobs)
                    .build();
        }
    }

    GitLabYaml.Job createDownloadJob() {
        String downloadURL = downloadCLIUrl;
        if (StringUtils.isBlank(downloadURL)) {
            downloadURL = String.format("https://pkgs.dev.azure.com/moderneinc/moderne_public/_packaging/moderne/maven/v1/io/moderne/moderne-cli-%s/%s/moderne-cli-%s-%s", platform, cliVersion, platform, cliVersion);
        }
        String baseCommand = String.format("curl --fail --location --output mod --request GET --url '%s'", downloadURL);
        String downloadCommand = baseCommand;
        if (StringUtils.isNotBlank(downloadCLITokenSecretName)) {
            downloadCommand = String.format("%s --header 'Authorization: Bearer %s'", baseCommand, variable(downloadCLITokenSecretName));
        } else if (StringUtils.isNotBlank(downloadCLIUserNameSecretName)) {
            downloadCommand = String.format("%s --user %s:%s", baseCommand, variable(downloadCLIUserNameSecretName), variable(downloadCLIPasswordSecretName));
        }


        String ifFileExistsExit = "[ -f 'mod' ] && echo 'mod loaded from cache, skipping download.' && ./mod help && exit 0";
        GitLabYaml.Job.JobBuilder builder = GitLabYaml.Job.builder();
        if (StringUtils.isNotBlank(jobTag)) {
            builder.tags(Collections.singletonList(jobTag));
        }
        return builder.stage(GitLabYaml.Stage.DOWNLOAD)
                .cache(GitLabYaml.Cache.builder()
                        .key(createCliCacheKey())
                        .path("mod")
                        .policy(GitLabYaml.Cache.Policy.PUSH_AND_PULL).build())
                .command(ifFileExistsExit)
                .command(downloadCommand)
                .command("chmod 755 mod")
                .image(dockerImageDownloadJob)
                .build();
    }

    GitLabYaml.Job createBuildLstJob(String repoPath, String branch) {
        GitLabYaml.Job.JobBuilder builder = GitLabYaml.Job.builder();
        String user = StringUtils.isBlank(repositoryAccessUserSecretName) ? "gitlab-ci-token" : variable(repositoryAccessUserSecretName);
        String token = StringUtils.isBlank(repositoryAccessTokenSecretName) ? variable("CI_JOB_TOKEN") : variable(repositoryAccessTokenSecretName);

        builder.image(dockerImageBuildJob)
                .retry(buildJobRetries)
                .stage(GitLabYaml.Stage.BUILD_LST)
                .variable("REPO_PATH", repoPath)
                .beforeCommand(String.format("REPO_ACCESS_USER=%s", user))
                .beforeCommand(String.format("REPO_ACCESS_TOKEN=%s", token))
                .beforeCommand("REPO_URL=$(echo \"$CI_REPOSITORY_URL\" | sed -E \"s|^(https?://)([^/]+@)?([^/]+)(/.+)?/([^/]+)/([^/]+)\\.git|\\1$REPO_ACCESS_USER:$REPO_ACCESS_TOKEN@\\3\\4/$REPO_PATH.git|\")")
                .beforeCommand("rm -fr $REPO_PATH")
                .beforeCommand(String.format("git clone --single-branch --branch %s $REPO_URL $REPO_PATH", branch))
                .beforeCommand("echo '127.0.0.1  host.docker.internal' >> /etc/hosts"); // required for org.openrewrite.polyglot.RemoteProgressBarReceiver to work inside gitlab docker container

        if (StringUtils.isNotBlank(jobTag)) {
            builder.tags(Collections.singletonList(jobTag));
        }
        String tenantCommand = createConfigTenantCommand();
        if (StringUtils.isNotBlank(tenantCommand)) {
            builder.command(tenantCommand);
        }
        String artifactCommand = createConfigArtifactsCommand();
        if (StringUtils.isNotBlank(artifactCommand)) {
            builder.command(artifactCommand);
        }
        if (downloadCLI) {
            builder.cache(GitLabYaml.Cache.builder()
                    .key(createCliCacheKey())
                    .path("mod")
                    .policy(GitLabYaml.Cache.Policy.PULL).build());
        }
        return builder
                .command(createBuildCommand())
                .command(createPublishCommand())
                .artifacts(GitLabYaml.Artifacts.builder()
                        .when(GitLabYaml.Artifacts.When.ALWAYS)
                        .path("$REPO_PATH/.moderne/build/*/build.log")
                        .build())
                .build();
    }


    private String createConfigArtifactsCommand() {
        if (publishUrl == null) {
            return ""; // for unit tests, will always be non-null in production
        }
        String args = String.format("config artifacts artifactory edit --local=$REPO_PATH %s--user=%s --password=%s %s",
                skipSSL ? "--skip-ssl " : "",
                variable(publishUserSecretName),
                variable(publishPwdSecretName),
                publishUrl
        );
        return modCommand(args);
    }

    private String createConfigTenantCommand() {
        if (tenant == null) {
            return "";
        }
        boolean isWindowsPlatform = isWindowsPlatform();
        String token = tenant.moderneToken;
        if (tenant.moderneTokenSecret != null) {
            token = variable(tenant.moderneTokenSecret);
        }
        if (token == null) {
            token = isWindowsPlatform ? "$env:MODERNE_TOKEN" : "${MODERNE_TOKEN}";
        }
        String args = String.format("config moderne --token=%s %s", token, tenant.moderneUrl);
        return modCommand(args);
    }

    private String createBuildCommand() {
        String args = "build $REPO_PATH --no-download";
        if (!StringUtils.isBlank(commandSuffix)) {
            args += " " + commandSuffix;
        }
        return modCommand(args);
    }

    String createPublishCommand() {
        return modCommand("publish $REPO_PATH");
    }

    private String modCommand(String args) {
        boolean isWindowsPlatform = isWindowsPlatform();
        String prefix = "";
        if (downloadCLI) {
            prefix = isWindowsPlatform ? ".\\\\" : "./";
        }
        String executable = isWindowsPlatform ? "mod.exe" : "mod";
        return String.format("%s%s %s", prefix, executable, args);
    }

    private static String variable(String name) {
        return String.format("$%s", name);
    }

    private boolean isWindowsPlatform() {
        return PLATFORM_WINDOWS.equals(platform);
    }

    private String createCliCacheKey() {
        if (StringUtils.isBlank(downloadCLIUrl)) {
            return String.format("cli-%s-%s", platform, cliVersion);
        }
        String encodedUrl = new String(Base64.getEncoder().encode(downloadCLIUrl.getBytes()));
        return String.format("cli-%s", encodedUrl);
    }

}
