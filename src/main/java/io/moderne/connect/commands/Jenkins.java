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
import kong.unirest.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(name = "jenkins",
        footerHeading = "%n@|bold,underline Notes|@:%n%n",
        headerHeading = "@|bold,underline Usage|@:%n%n",
        synopsisHeading = "%n",
        descriptionHeading = "%n@|bold,underline Description|@:%n%n",
        parameterListHeading = "%n@|bold,underline Parameters|@:%n%n",
        optionListHeading = "%n@|bold,underline Options|@:%n%n",
        header = "Creates a Jenkins Job for each configured repository that will build and publish LST artifacts " +
                "to your artifact repository on a regular basis.",
        description = "Creates a Jenkins Job for each configured repository that will build and publish LST artifacts " +
                "to your artifact repository on a regular basis.\n\n" +
                "@|bold,underline Example|@:\n\n" +
                "  mod connect jenkins --apiToken jenkinsApiToken \\\n" +
                "     --controllerUrl https://jenkins.company-name.com \\\n" +
                "     --fromCsv /path/to/repos.csv \\\n" +
                "     --gitCredsId username-pat \\\n" +
                "     --jenkinsUser some-username \\\n" +
                "     --publishCredsId artifactory \\\n" +
                "     --publishUrl https://artifact-place.com/artifactory/moderne-ingest",
        footer = "If you are a CloudBees CI authenticated user, you will also need these permissions:\n\n" +
                "1. Overall/System Read access. This is needed to get the list of plugins and their versions.\n" +
                "    - GET /pluginManager/api/json\n\n" +
                "2. Create, Configure, Read folders and Jobs.\n" +
                "    - POST /createItem\n" +
                "    - GET  /job/$folder/api/json\n" +
                "    - GET  /job/$folder/job/$item/api/json\n\n" +
                "3. (Optionally) Delete jobs. This is only required if --deleteSkipped is selected.\n" +
                "    - POST /job/$folder/job/$item/doDelete\n\n" +
                "For more details around these permissions, please see: https://cutt.ly/75J0mtI")
// The CloudBees docs for permissions are https://docs.cloudbees.com/docs/cloudbees-ci/latest/cloud-secure-guide/delegating-administration-modern#_overallsystem_read
public class Jenkins implements Callable<Integer> {

    /**
     * Required Parameters
     **/
    @CommandLine.Option(names = "--controllerUrl",
            required = true,
            description = "The URL of the Jenkins controller that will create the jobs. Typically this is the URL " +
                    "of your Jenkins instance.\n\n" +
                    "@|bold Example|@: https://jenkins.company-name.com\n")
    private String controllerUrl;

    @CommandLine.Option(names = "--fromCsv",
            required = true,
            description = "The location of the CSV file containing the list of repositories that should be ingested. " +
                    "One Jenkins Job will be made for each repository. Follows the schema of:\n" +
                    "\n" + // TODO Remove CSV columns after https://github.com/moderneinc/jenkins-ingest/pull/161
                    "@|bold [scmHost,repoName,repoBranch,mavenTool,gradleTool,jdkTool,desiredStyle,additionalBuildArgs,skip,skipReason]|@\n" +
                    "\n" +
                    "* @|bold scmHost|@: @|italic Optional|@ - The URL of the source code management tool where the " +
                    "repository is hosted. \n" +
                    "\n" +
                    "** @|bold Example|@: github.com or gitlab.com\n" +
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
                    "* @|bold mavenTool|@: @|italic Optional|@ - The name of the Maven tool that should be used to " +
                    "run Maven jobs. Specified in the Jenkins Global Tool Configuration page:\n" +
                    "    {controllerUrl}/manage/configureTools/\n" +
                    "\n" +
                    "* @|bold gradleTool|@: @|italic Optional|@ - The name of the Gradle tool that should be used to " +
                    "run Gradle jobs. Specified in the Jenkins Global Tool Configuration page:\n" +
                    "    {controllerUrl}/manage/configureTools/\n" +
                    "\n" +
                    "* @|bold jdkTool|@: @|italic Optional|@ - No longer in use.\n" +
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
    private Path csvFile;

    @CommandLine.Option(names = "--gitCredsId", required = true,
            description = "The ID of the Jenkins credentials needed to clone the provided list of repositories.\n")
    private String gitCredentialsId;

    @CommandLine.Option(names = "--jenkinsUser", required = true,
            description = "The Jenkins user that will be used to create the Jenkins Jobs.\n")
    private String jenkinsUser;

    @CommandLine.Option(names = "--publishCredsId", required = true,
            description = "The ID of the Jenkins credentials needed to upload LST artifacts to your artifact repository.\n")
    private String publishCredsId;

    @CommandLine.Option(names = "--publishUrl", required = true, defaultValue = "${MODERNE_PUBLISH_URL}",
            description = "The URL of the Maven repository where LST artifacts should be uploaded to.\n\n" +
                    "Will default to the environment variable @|bold MODERNE_PUBLISH_URL|@ if one exists.\n")
    private String publishUrl;

    /**
     * Optional Parameters
     **/
    @CommandLine.Option(names = "--agent",
            description = "The name of the Jenkins agent that will run the pipeline.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "any")
    private String agent;

    @CommandLine.Option(names = "--cliVersion", defaultValue = "v0.3.2",
            description = "The version of the Moderne CLI that should be used when running Jenkins Jobs.\n")
    private String cliVersion;

    @CommandLine.Option(names = "--commandSuffix", defaultValue = "",
            description = "The suffix that should be appended to the Moderne CLI command when running Jenkins Jobs.\n\n" +
                    "@|bold Example|@: --Xmx 4g\n")
    private String commandSuffix;

    @CommandLine.Option(names = "--defaultBranch", defaultValue = "main",
            description = "If no Git branch is specified for a repository in the CSV file, the Jenkins Job will attempt " +
                    "to checkout this branch when pulling down the code.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n")
    private String defaultBranch;


    @CommandLine.Option(names = "--defaultGradle",
            description = "If no Gradle tool is specified for a repository in the CSV file, the Jenkins job will attempt " +
                    "to use this one for Gradle jobs. Specified in the Jenkins Global Tool Configuration page:\n" +
                    "    {controllerUrl}/manage/configureTools/\n\n" +
                    "@|bold Example|@: gradle7.4.2\n")
    private String defaultGradle;

    @CommandLine.Option(names = "--defaultMaven",
            description = "If no Maven tool is specified for a repository in the CSV file, the Jenkins job will attempt " +
                    "to use this one for Maven jobs. Specified in the Jenkins Global Tool Configuration page:\n" +
                    "    {controllerUrl}/manage/configureTools/\n\n" +
                    "@|bold Example|@: maven3.3.9\n")
    private String defaultMaven;

    @CommandLine.Option(names = "--deleteSkipped", defaultValue = "false",
            description = "If set to true, whenever a repository in the CSV file has 'skip' set to true, the corresponding " +
                    "Jenkins Job will be deleted. This is useful if you want to remove specific jobs that are failing, " +
                    "but you also want to preserve the list of repositories that are ingested.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n")
    private boolean deleteSkipped;

    @CommandLine.Option(names = "--downloadCLI", defaultValue = "true",
            description = "Specifies whether or not the Moderne CLI should be downloaded at the beginning of each Jenkins Job run.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n")
    private boolean downloadCLI;

    @CommandLine.Option(names = "--downloadCLIUrl",
            description = "Specifies an internal URL to download the CLI from if you'd prefer to host the CLI yourself.\n")
    private String downloadCLIURL;

    @CommandLine.Option(names = "--downloadCLICreds",
            description = "Specifies the Jenkins credentials Id to download the CLI if you host the CLI yourself.\n")
    private String downloadCLICreds;

    @CommandLine.Option(names = "--folder",
            description = "The Jenkins folder that will store the created jobs. This folder will be created if it does not exist.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "moderne-ingest")
    private String folderName;

    @CommandLine.Option(names = "--mavenSettingsConfigFileId",
            description = "The ID of the Jenkins Maven settings config file that will be used to configure Maven builds. " +
                    "Specified in the Jenkins Global Tool Configuration page:\n" +
                    "    {controllerUrl}/manage/configureTools/\n")
    private String mavenSettingsConfigFileId;

    @CommandLine.Option(names = "--mirrorUrl", defaultValue = "${MODERNE_MIRROR_URL}",
            description = "For Gradle projects, this can be specified as a Maven repository cache/mirror to check " +
                    "before any other repositories.\n\n" +
                    "Will default to the environment variable @|bold MODERNE_MIRROR_URL|@ if one exists.\n")
    private String mirrorUrl;

    @CommandLine.Option(names = "--platform",
            description = "The OS platform for the Jenkins node/agent. The possible options are: windows, linux, or macos.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "linux")
    private String platform;

    @CommandLine.Option(names = "--prefix",
            description = "If specified, Jenkins Jobs will only be created for repositories that start with this prefix.\n",
            defaultValue = "")
    private String prefix;

    @CommandLine.Option(names = "--scheduledAt", defaultValue = "H H * * *",
            description = "The cron schedule that the Jenkins Jobs should follow. By default, Jenkins will execute " +
                    "each job once a day while making sure to space them out so that the system is not overloaded at " +
                    "one particular time.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n")
    private String scheduledAt;

    @CommandLine.Option(names = "--skipSSL",
            defaultValue = "false",
            description = "If this parameter is included, SSL verification will be skipped on the generated jobs.\n\n" +
                    "@|bold Default|@: ${DEFAULT-VALUE}\n")
    protected boolean skipSSL;

    @CommandLine.ArgGroup(multiplicity = "1")
    private UserSecret userSecret;

    @CommandLine.Option(names = "--gradlePluginVersion",
            description = "The version of the Moderne Gradle plugin that should be used to build the artifacts.\n\n" +
                    "Will default to the environment variable @|bold MODERNE_GRADLE_PLUGIN_VERSION|@ or " +
                    "@|bold latest.release|@ if it doesn't exist.\n\n" +
                    "@|bold Example|@: 0.37.0\n",
            defaultValue = "${MODERNE_GRADLE_PLUGIN_VERSION}")
    protected String moderneGradlePluginVersion;

    @CommandLine.Option(names = "--mvnPluginVersion",
            description = "The version of the Moderne Maven plugin that should be used to build the artifacts.\n\n" +
                    "Will default to the environment variable @|bold MODERNE_MVN_PLUGIN_VERSION|@ or " +
                    "@|bold RELEASE|@ if it doesn't exist.\n\n" +
                    "@|bold Example|@: v0.38.0\n",
            defaultValue = "${MODERNE_MVN_PLUGIN_VERSION}")
    protected String moderneMavenPluginVersion;

    @CommandLine.Option(names = "--verbose", defaultValue = "false",
            description = "If enabled, additional debug statements will be printed throughout the Jenkins configuration.\n" +
                    "\n@|bold Default|@: ${DEFAULT-VALUE}\n")
    private boolean verbose;

    @SuppressWarnings("unused")
    static class UserSecret {
        @CommandLine.Option(names = "--apiToken", description = "The Jenkins apiToken that will be used when " +
                "authentication is needed in Jenkins (e.g., the creation of Jenkins Jobs).\n")
        private String apiToken;

        @CommandLine.Option(names = "--jenkinsPwd",
                description = "The Jenkins password that will be used when authentication is needed in Jenkins " +
                        "(e.g., the creation of Jenkins Jobs).\n\n"
                        + "@|italic Jenkins best practices recommend using an apiToken instead of a password|@.\n")
        private String jenkinsPwd;

        String get() {
            return StringUtils.isBlank(apiToken) ? jenkinsPwd : apiToken;
        }

        boolean needsCrumb() {
            return StringUtils.isBlank(apiToken);
        }
    }

    static final String JENKINS_CRUMB_HEADER = "Jenkins-Crumb";

    private static final String PLATFORM_WINDOWS = "windows";
    private static final String CLOUDBEES_FOLDER_PLUGIN = "cloudbees-folder";
    private static final String WORKFLOW_JOB_PLUGIN = "workflow-job";
    private static final String PIPELINE_MODEL_DEFINITION_PLUGIN = "pipeline-model-definition";
    private static final String WORKFLOW_CPS_PLUGIN = "workflow-cps";
    private static final String CLEAN_UP_PLUGIN = "ws-cleanup";

    @RequiredArgsConstructor
    enum Templates {
        DOWNLOAD_WITHOUT_CREDENTIALS("cli/jenkins/pipeline_download.groovy.template"),
        DOWNLOAD_WITH_CREDENTIALS("cli/jenkins/pipeline_download_creds.groovy.template"),
        DOWNLOAD_WITHOUT_CREDENTIALS_WINDOWS("cli/jenkins/pipeline_download_windows.groovy.template"),
        DOWNLOAD_WITH_CREDENTIALS_WINDOWS("cli/jenkins/pipeline_download_creds_windows.groovy.template"),

        FLOW_DEFINITION("cli/jenkins/flow_definition.xml.template"),
        FOLDER_DEFINITION("cli/jenkins/jenkins_folder.xml.template"),

        PIPELINE("cli/jenkins/pipeline.groovy.template"),

        PUBLISH_CREDENTIALS("cli/jenkins/pipeline_credentials.groovy.template"),
        MAVEN_SETTINGS("cli/jenkins/pipeline_maven_settings.groovy.template"),

        STAGE_CHECKOUT("cli/jenkins/pipeline_stage_checkout.groovy.template"),
        STAGE_DOWNLOAD("cli/jenkins/pipeline_stage_download.groovy.template"),
        STAGE_PUBLISH("cli/jenkins/pipeline_stage_publish.groovy.template"),
        TOOLS("cli/jenkins/pipeline_tools.groovy.template");

        private final String filename;

        public String format(String... varargs) {
            return String.format(TextBlock.textBlock(filename), (Object[]) varargs);
        }
    }

    @Override
    public Integer call() {
        if (!csvFile.toFile().exists()) {
            System.err.println(csvFile.toString() + " does not exist");
            return 1;
        }

        if (!cliVersion.startsWith("v0.3")) {
            System.err.println("Unsupported CLI version: " + cliVersion + ". Please use a version greater than v0.3");
            return 1;
        }

        final Map<String, String> plugins;
        try {
            // try-with-resources not possible until Java
            ExecutorService executorService = Executors.newFixedThreadPool(50);
            plugins = resolveJenkinsPlugins();

            if (!StringUtils.isBlank(folderName)) {
                if (!folderExists(folderName)) {
                    createFolder(plugins, folderName);
                }
            }

            List<Future<Boolean>> responses = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(csvFile.toFile()));
            String line;
            int lineNumber = 1;
            while ((line = br.readLine()) != null) {
                // scmHost, repoName, repoBranch, mavenTool, gradleTool, jdkTool, repoStyle, repoBuildAction, repoSkip, skipReason
                if (line.startsWith("scmHost")) {
                    lineNumber++;
                    continue;
                }
                String[] values = line.split(",", 10);
                if (values.length != 10) {
                    System.err.println("[ERROR] Invalid schema for line " + lineNumber);
                    System.err.println("The required schema is [scmHost, repoName, repoBranch, mavenTool, gradleTool, jdkTool, repoStyle, repoBuildAction, repoSkip, skipReason]");
                    return 1;
                }

                String host = values[0];
                String repoSlug = values[1];
                String branch = values[2];
                String mavenTool = values[3];
                String gradleTool = values[4];
                // String jdkTool = values[5]; // TODO Remove jdkTool https://github.com/moderneinc/jenkins-ingest/pull/161
                String repoStyle = values[6];
                String repoBuildAction = values[7];
                String repoSkip = values[8];
                String skipReason = values[9];

                if (StringUtils.isBlank(host)) {
                    host = "https://github.com";
                }

                if (StringUtils.isBlank(repoSlug)) {
                    System.out.printf("Skipping line %d because there is an empty Git repo%n", lineNumber);
                    lineNumber++;
                    continue;
                }
                if (!repoSlug.startsWith(prefix)) {
                    lineNumber++;
                    continue;
                }

                String projectName = repoSlug.replaceAll("/", "_") + "_" + branch.replaceAll("/", "_");
                if (!StringUtils.isBlank(repoSkip) && "true".equalsIgnoreCase(repoSkip)) {
                    if (deleteSkipped) {
                        final int currentNumberFinal = lineNumber;
                        responses.add(executorService.submit(() -> {
                            if (!jobExists(folderName, projectName)) {
                                System.out.printf("Skipping %s at line %d because it is marked as skipped: %s%n", repoSlug, currentNumberFinal, skipReason);
                                return true;
                            }
                            if (!deleteJob(folderName, projectName)) {
                                System.out.printf("Failed to delete %s at line %d because it is marked as skipped: %s%n", repoSlug, currentNumberFinal, skipReason);
                                return false;
                            }
                            System.out.printf("Deleted %s at line %d because it is marked as skipped: %s%n", repoSlug, currentNumberFinal, skipReason);
                            return true;
                        }));
                    } else {
                        System.out.printf("Skipping %s at line %d because it is marked as skipped: %s%n", repoSlug, lineNumber, skipReason);
                    }
                    lineNumber++;
                    continue;
                }

                String gitURL = host + "/" + repoSlug + ".git";
                if (StringUtils.isBlank(branch)) {
                    branch = defaultBranch;
                }

                // Create the Jenkins job
                String stageCheckout = Templates.STAGE_CHECKOUT.format(gitURL, branch, gitCredentialsId);
                String stageDownload = createStageDownload();
                String stagePublish = createStagePublish(mavenTool, gradleTool, repoStyle, repoBuildAction);
                String pipeline = createPipeline(stageCheckout, stageDownload, stagePublish);
                String flowDefinition = createFlowDefinition(plugins, pipeline);

                responses.add(executorService.submit(() -> createJob(folderName, projectName, flowDefinition)));
                lineNumber++;
            }
            // Wait for all the jobs to be created before returning
            int failed = 0;
            for (Future<Boolean> future : responses) {
                try {
                    if (!future.get()) {
                        failed++;
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Swallow any exceptions to ensure all other jobs are created before we exit
                    failed++;
                }
            }
            return failed;
        } catch (Throwable e) {
            System.err.println("ERROR configuring Jenkins.");
            System.err.println(e.getMessage());
            if (verbose) {
                e.printStackTrace();
            } else {
                System.err.println("Please, use --verbose for more details.");
            }
            return 1;
        }
    }

    private <T extends HttpRequest<T>> T authenticate(T request) {
        T withBasicAuth = request.basicAuth(jenkinsUser, userSecret.get());
        if (!userSecret.needsCrumb()) {
            return withBasicAuth;
        }
        return withBasicAuth
                .header(JENKINS_CRUMB_HEADER, generateCrumb(controllerUrl, jenkinsUser, userSecret.get()));
    }

    private Map<String, String> resolveJenkinsPlugins() throws JsonProcessingException {
        HttpResponse<String> pluginsResponse = authenticate(Unirest.get(controllerUrl + "/pluginManager/api/json"))
                .queryString("depth", "1")
                .queryString("xpath", "/*/*/shortName|/*/*/version")
                .queryString("wrapper", "plugins").asString();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree(pluginsResponse.getBody());
        Map<String, String> result = new HashMap<>();
        Set<String> requiredPlugins = Arrays.stream(
                        new String[]{CLOUDBEES_FOLDER_PLUGIN, WORKFLOW_JOB_PLUGIN,
                                PIPELINE_MODEL_DEFINITION_PLUGIN, WORKFLOW_CPS_PLUGIN, CLEAN_UP_PLUGIN})
                .collect(Collectors.toSet());
        JsonNode pluginsNode = node.get("plugins");
        int pluginsSize = pluginsNode.size();
        for (int i = 0; i < pluginsSize; i++) {
            JsonNode pluginNode = pluginsNode.get(i);
            if (pluginNode.get("active").asBoolean(false)) {
                if (pluginNode.has("shortName") && requiredPlugins.contains(pluginNode.get("shortName").asText())) {
                    result.put(pluginNode.get("shortName").asText(), pluginNode.get("version").asText());
                }
            }
        }
        if (!result.keySet().containsAll(requiredPlugins)) {
            throw new RuntimeException(String.format(
                    "Jenkins requires to install the following plugins: %s",
                    requiredPlugins.stream().filter(plugin -> !result.containsKey(plugin))
                            .collect(Collectors.joining(", "))));
        }
        return result;
    }

    private boolean folderExists(String folderPath) {
        return authenticate(Unirest.get(controllerUrl + "/job/" + folderPath + "/api/json"))
                .asEmpty()
                .isSuccess();
    }

    private void createFolder(Map<String, String> plugins, String folderPath) {
        if (!authenticate(Unirest.post(controllerUrl + "/createItem?name=" + folderPath))
                .header(HeaderNames.CONTENT_TYPE, "text/xml")
                .body(Templates.FOLDER_DEFINITION.format(plugins.get(CLOUDBEES_FOLDER_PLUGIN), folderPath))
                .asString()
                .ifFailure(response -> {
                    System.err.println("[ERROR] The folder " + folderPath + " can not be created");
                    System.err.println(response.getBody());
                }).isSuccess()) {
            throw new RuntimeException("Aborting. Error creating the folder " + folderPath);
        }
    }

    private boolean jobExists(String folderPath, String jobName) {
        return authenticate(Unirest.get(controllerUrl + "/job/" + folderPath + "/job/" + jobName + "/api/json"))
                .asEmpty()
                .isSuccess();
    }

    private boolean deleteJob(String folderPath, String jobName) {
        try {
            int code = authenticate(Unirest.post(controllerUrl + "/job/" + folderPath + "/job/" + jobName + "/doDelete"))
                    .asString()
                    .ifFailure(response -> {
                        int responseStatus = response.getStatus();
                        if (responseStatus != 302) {
                            System.err.printf("[ERROR] The job %s can not be deleted: HTTP %s: %s%n",
                                    jobName, responseStatus, response.getStatusText());
                            System.err.println(response.getHeaders());
                            System.err.println(response.getBody());
                        }
                    })
                    .getStatus();
            return code == 302 || code == 200;
        } catch (UnirestException e) {
            System.err.printf("[ERROR] The job %s can not be deleted: Exception %s%n", jobName, e.getMessage());
            return false;
        }
    }

    private boolean createJob(String folderPath, String jobName, String pipeline) {
        // Switch between create and update URLs
        boolean jobExists = jobExists(folderPath, jobName);
        String verb = jobExists ? "updated" : "created";
        String url = jobExists
                ? controllerUrl + "/job/" + folderPath + "/job/" + jobName + "/config.xml"
                : controllerUrl + "/job/" + folderPath + "/createItem?name=" + jobName;
        try {
            return authenticate(Unirest.post(url)
                    .header(HeaderNames.ACCEPT, "application/json")
                    .header(HeaderNames.CONTENT_TYPE, "text/xml"))
                    .body(pipeline)
                    .asString()
                    .ifFailure(response -> {
                        System.err.printf("[ERROR] The job %s can not be %s: HTTP %s: %s%n",
                                jobName, verb, response.getStatus(), response.getStatusText());
                        System.err.println(response.getHeaders());
                        System.err.println(response.getBody());
                    })
                    .ifSuccess(response -> System.out.printf("Job %s %s successfully in %s%n",
                            jobName, verb, folderPath))
                    .isSuccess();
        } catch (UnirestException e) {
            System.err.printf("[ERROR] The job %s can not be %s: Exception %s%n", jobName, verb, e.getMessage());
            return false;
        }
    }

    static String generateCrumb(String controllerUrl, String user, String password) {
        String response = Unirest.get(controllerUrl + "/crumbIssuer/api/json")
                .basicAuth(user, password)
                .asString()
                .getBody();
        try {
            // Use ObjectMapper instead of Unirest to avoid using reflection classes that conflicts with GraalVM
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode node = objectMapper.readTree(response);
            return node.get("crumb").asText();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String createStageDownload() {
        if (!downloadCLI) {
            return "";
        }

        String downloadURL = downloadCLIURL;
        if (StringUtils.isBlank(downloadURL)) {
            downloadURL = String.format("https://pkgs.dev.azure.com/moderneinc/moderne_public/_packaging/moderne/maven/v1/io/moderne/moderne-cli-%s/%s/moderne-cli-%s-%s", platform, cliVersion, platform, cliVersion);
        }
        final String downloadCommand;
        if (StringUtils.isBlank(downloadCLICreds)) {
            downloadCommand = isWindowsPlatform() ?
                    Templates.DOWNLOAD_WITHOUT_CREDENTIALS_WINDOWS.format(downloadURL) :
                    Templates.DOWNLOAD_WITHOUT_CREDENTIALS.format(downloadURL);
        } else {
            downloadCommand = isWindowsPlatform() ?
                    Templates.DOWNLOAD_WITH_CREDENTIALS_WINDOWS.format(downloadURL) :
                    Templates.DOWNLOAD_WITH_CREDENTIALS.format(downloadCLICreds, downloadURL);
        }
        return Templates.STAGE_DOWNLOAD.format(downloadCommand);
    }

    private String createStagePublish(String mavenTool, String gradleTool, String repoStyle, String repoBuildAction) {
        String toolsConcatenated = Stream.of(
                        generateSingleToolExpr("maven", defaultMaven, mavenTool),
                        generateSingleToolExpr("gradle", defaultGradle, gradleTool))
                .filter(str -> !StringUtils.isBlank(str))
                .collect(Collectors.joining("\n                        "));
        String toolsBlock = "";
        if (!StringUtils.isBlank(toolsConcatenated)) {
            toolsBlock = Templates.TOOLS.format(toolsConcatenated);
        }
        return Templates.STAGE_PUBLISH.format(toolsBlock,
                createConfigArtifactsCommand(),
                createBuildCommand(repoStyle, repoBuildAction),
                createPublishCommand());
    }

    private static String generateSingleToolExpr(String toolName, String defaultTool, String repoTool) {
        if (!StringUtils.isBlank(repoTool)) {
            return toolName + " '" + repoTool + "'";
        }
        if (!StringUtils.isBlank(defaultTool)) {
            return toolName + " '" + defaultTool + "'";
        }
        return "";
    }

    private String createConfigArtifactsCommand() {
        boolean isWindowsPlatform = isWindowsPlatform();
        String command = "";
        if (downloadCLI) {
            command += isWindowsPlatform ? ".\\\\" : "./";
        }
        command += String.format("%s config artifacts %s --user %s --password %s",
                isWindowsPlatform ? "mod.exe" : "mod",
                publishUrl,
                isWindowsPlatform ? "$env:ARTIFACTS_PUBLISH_CRED_USR" : "${ARTIFACTS_PUBLISH_CRED_USR}",
                isWindowsPlatform ? "$env:ARTIFACTS_PUBLISH_CRED_PWD" : "${ARTIFACTS_PUBLISH_CRED_PWD}"
        );
        if (skipSSL) {
            command += " --skipSSL";
        }
        // Always wrap in publish credentials block
        String shell = String.format("%s '%s'", isWindowsPlatform ? "powershell" : "sh", command);
        return Templates.PUBLISH_CREDENTIALS.format(publishCredsId, shell);
    }


    private String createBuildCommand(String activeStyle, String additionalBuildArgs) {
        boolean isWindowsPlatform = isWindowsPlatform();
        String prefix = "";
        if (downloadCLI) {
            prefix += isWindowsPlatform ? ".\\\\" : "./";
        }
        String command = String.format("%s%s build .", prefix, isWindowsPlatform ? "mod.exe" : "mod");
        if (!StringUtils.isBlank(activeStyle)) {
            command += " --active-style " + activeStyle;
        }
        if (!StringUtils.isBlank(additionalBuildArgs)) {
            command += String.format(" --additional-build-args \"%s\"", additionalBuildArgs);
        }
        if (!StringUtils.isBlank(mirrorUrl)) {
            command += " --mirror-url " + mirrorUrl;
        }
        if (!StringUtils.isBlank(moderneGradlePluginVersion)) {
            command += " --gradle-plugin-version " + moderneGradlePluginVersion;
        }
        if (!StringUtils.isBlank(moderneMavenPluginVersion)) {
            command += " --maven-plugin-version " + moderneMavenPluginVersion;
        }
        if (!StringUtils.isBlank(commandSuffix)) {
            command += " " + commandSuffix;
        }

        // Conditionally wrap in maven settings block
        if (!StringUtils.isBlank(mavenSettingsConfigFileId)) {
            String settings = isWindowsPlatform ? "$env:MAVEN_SETTINGS_CONFIG_FILE_ID" : "${MAVEN_SETTINGS_CONFIG_FILE_ID}";
            String shell = String.format("%s '%s'", isWindowsPlatform ? "powershell" : "sh", command + " --maven-settings " + settings);
            return Templates.MAVEN_SETTINGS.format(mavenSettingsConfigFileId, shell);
        }
        return String.format("%s '%s'", isWindowsPlatform ? "powershell" : "sh", command);
    }

    private String createPublishCommand() {
        boolean isWindowsPlatform = isWindowsPlatform();
        String prefix = "";
        if (downloadCLI) {
            prefix = isWindowsPlatform ? ".\\\\" : "./";
        }
        String command = String.format("%s%s publish . ", prefix, isWindowsPlatform ? "mod.exe" : "mod");
        return String.format("%s '%s'", isWindowsPlatform ? "powershell" : "sh", command);
    }

    private String createPipeline(String stageCheckout, String stageDownload, String stagePublish) {
        return Templates.PIPELINE.format(
                agent,
                scheduledAt,
                stageCheckout,
                stageDownload,
                stagePublish);
    }

    private String createFlowDefinition(Map<String, String> plugins, String pipeline) {
        return Templates.FLOW_DEFINITION.format(
                plugins.get(WORKFLOW_JOB_PLUGIN),
                plugins.get(PIPELINE_MODEL_DEFINITION_PLUGIN),
                plugins.get(PIPELINE_MODEL_DEFINITION_PLUGIN),
                scheduledAt,
                plugins.get(WORKFLOW_CPS_PLUGIN),
                pipeline);
    }

    private boolean isWindowsPlatform() {
        return PLATFORM_WINDOWS.equals(platform);
    }
}
