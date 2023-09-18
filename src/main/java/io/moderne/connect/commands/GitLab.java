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
import java.util.LinkedHashMap;
import java.util.Map;
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
            defaultValue = "v0.4.4",
            description = "The version of the Moderne CLI that should be used when running GitLab jobs.\n")
    String cliVersion;

    @CommandLine.Option(
            names = "--commandSuffix",
            defaultValue = "",
            description = "The suffix that should be appended to the Moderne CLI command when running GitLab jobs.\n\n" +
                          "@|bold Example|@: --Xmx 4g\n")
    String commandSuffix;

    @CommandLine.Option(
            names = "--defaultBranch",
            defaultValue = "main",
            description = "If no Git branch is specified for a repository in the CSV file, the GitLab job will attempt " +
                          "to checkout this branch when pulling down the code.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    String defaultBranch;

    @CommandLine.Option(names = "--downloadCLI",
            defaultValue = "true",
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

    @CommandLine.Option(names = "--gradlePluginVersion",
            description = "The version of the Moderne Gradle plugin that should be used to build the artifacts.\n\n" +
                          "Will default to the environment variable @|bold MODERNE_GRADLE_PLUGIN_VERSION|@ or " +
                          "@|bold latest.release|@ if it doesn't exist.\n\n" +
                          "@|bold Example|@: 0.37.0\n",
            defaultValue = "${MODERNE_GRADLE_PLUGIN_VERSION}")
    String gradlePluginVersion;

    @CommandLine.Option(names = "--mvnPluginVersion",
            description = "The version of the Moderne Maven plugin that should be used to build the artifacts.\n\n" +
                          "Will default to the environment variable @|bold MODERNE_MVN_PLUGIN_VERSION|@ or " +
                          "@|bold RELEASE|@ if it doesn't exist.\n\n" +
                          "@|bold Example|@: v0.38.0\n",
            defaultValue = "${MODERNE_MVN_PLUGIN_VERSION}")
    String mvnPluginVersion;

    @CommandLine.Option(
            names = "--mirrorUrl",
            defaultValue = "${MODERNE_MIRROR_URL}",
            description = "For Gradle projects, this can be specified as a Maven repository cache/mirror to check " +
                          "before any other repositories.\n\n" +
                          "Will default to the environment variable @|bold MODERNE_MIRROR_URL|@ if one exists.\n")
    String mirrorUrl;

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
            description = "The OS platform for the Jenkins node/agent. The possible options are: windows, linux, or macos.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "linux")
    String platform;

    @CommandLine.Option(
            names = "--verbose",
            defaultValue = "false",
            description = "If enabled, additional debug statements will be printed throughout the Jenkins configuration.\n" +
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

        if (!cliVersion.startsWith("v0.4") && !cliVersion.startsWith("v0.5") && !cliVersion.startsWith("v1")) {
            System.err.println("Unsupported CLI version: " + cliVersion + ". Please use a version greater than v0.4");
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
            return 1;
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


                buildJobs.put(String.format("build-%s", repoPath), createBuildLstJob(repoPath, branch, repoStyle, additionalBuildArgs));
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
        String downloadCommand;
        if (StringUtils.isNotBlank(downloadCLITokenSecretName)) {
            downloadCommand = String.format("curl --request GET --url '%s' --header 'Authorization: Bearer %s' > mod", downloadURL, variable(downloadCLITokenSecretName));
        } else if (StringUtils.isNotBlank(downloadCLIUserNameSecretName)) {
            downloadCommand = String.format("curl --user %s:%s --request GET --url '%s' > mod", variable(downloadCLIUserNameSecretName), variable(downloadCLIPasswordSecretName), downloadURL);
        } else {
            downloadCommand = String.format("curl --request GET --url '%s' > mod", downloadURL);
        }

        return GitLabYaml.Job.builder()
                .stage(GitLabYaml.Stage.DOWNLOAD)
                .cache(GitLabYaml.Cache.builder()
                        .key(String.format("cli-%s-%s", platform, cliVersion))
                        .path("mod")
                        .policy(GitLabYaml.Cache.Policy.PUSH_AND_PULL).build())
                .command(downloadCommand)
                .command("chmod 755 mod")
                .build();
    }

    GitLabYaml.Job createBuildLstJob(String repoPath, String branch, String activeStyle, String additionalBuildArgs) {
        GitLabYaml.Job.JobBuilder builder = GitLabYaml.Job.builder()
                .stage(GitLabYaml.Stage.BUILD_LST)
                .cache(GitLabYaml.Cache.builder()
                        .key(String.format("cli-%s-%s", platform, cliVersion))
                        .path("mod")
                        .policy(GitLabYaml.Cache.Policy.PULL).build())
                .variable("REPO_PATH", repoPath)
                .beforeCommand("BASE_URL=`echo $CI_REPOSITORY_URL | sed \"s;\\/*$CI_PROJECT_PATH.*;;\"`")
                .beforeCommand("REPO_URL=\"$BASE_URL/$GITLAB_HOST/$REPO_PATH.git\"")
                .beforeCommand("REPO_DIR=$REPO_PATH")
                .beforeCommand("rm -fr $REPO_DIR")
                .beforeCommand(String.format("git clone --single-branch --branch %s $REPO_URL $REPO_DIR", branch));

        String tenantCommand = createConfigTenantCommand();
        if (StringUtils.isNotBlank(tenantCommand)) {
            builder.command(tenantCommand);
        }
        String artifactCommand = createConfigArtifactsCommand();
        if (StringUtils.isNotBlank(artifactCommand)) {
            builder.command(artifactCommand);
        }
        return builder
                .command(createBuildCommand(activeStyle, additionalBuildArgs))
                .command(createPublishCommand())
                .build();
    }


    private String createConfigArtifactsCommand() {
        if (publishUrl == null) {
            return ""; // for unit tests, will always be non-null in production
        }
        String args = String.format("config artifacts %s --user %s --password %s",
                publishUrl,
                variable(publishUserSecretName),
                variable(publishPwdSecretName)
        );
        if (skipSSL) {
            args += " --skipSSL";
        }
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
        String args = String.format("config moderne %s --token %s", tenant.moderneUrl, token);
        return modCommand(args);
    }

    private String createBuildCommand(String activeStyle, String additionalBuildArgs) {
        String args = "build . --no-download";
        if (!StringUtils.isBlank(activeStyle)) {
            args += " --active-style " + activeStyle;
        }
        if (!StringUtils.isBlank(additionalBuildArgs)) {
            args += String.format(" --additional-build-args \"%s\"", additionalBuildArgs);
        }
        if (!StringUtils.isBlank(mirrorUrl)) {
            args += " --mirror-url " + mirrorUrl;
        }
        if (!StringUtils.isBlank(gradlePluginVersion)) {
            args += " --gradle-plugin-version " + gradlePluginVersion;
        }
        if (!StringUtils.isBlank(mvnPluginVersion)) {
            args += " --maven-plugin-version " + mvnPluginVersion;
        }
        if (!StringUtils.isBlank(commandSuffix)) {
            args += " " + commandSuffix;
        }
        return modCommand(args);
    }

    String createPublishCommand() {
        return modCommand("publish .");
    }

    private String modCommand(String args) {
        boolean isWindowsPlatform = isWindowsPlatform();
        String prefix = "";
        if (downloadCLI || !StringUtils.isBlank(downloadCLIUrl)) {
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

}
