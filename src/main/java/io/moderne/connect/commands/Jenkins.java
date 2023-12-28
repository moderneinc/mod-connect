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
import java.net.MalformedURLException;
import java.net.URL;
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
    String controllerUrl;

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
    Path fromCsv;

    @CommandLine.Option(names = "--gitCredsId", required = true,
            description = "The ID of the Jenkins credentials needed to clone the provided list of repositories.\n")
    String gitCredsId;

    @CommandLine.Option(names = "--jenkinsUser", required = true,
            description = "The Jenkins user that will be used to create the Jenkins Jobs.\n")
    String jenkinsUser;

    @CommandLine.Option(names = "--publishCredsId", required = true,
            description = "The ID of the Jenkins credentials needed to upload LST artifacts to your artifact repository.\n")
    String publishCredsId;

    @CommandLine.Option(names = "--publishUrl", required = true, defaultValue = "${MODERNE_PUBLISH_URL}",
            description = "The URL of the Maven repository where LST artifacts should be uploaded to.\n\n" +
                          "Will default to the environment variable @|bold MODERNE_PUBLISH_URL|@ if one exists.\n")
    String publishUrl;

    /**
     * Optional Parameters
     **/
    @CommandLine.Option(names = "--agent",
            description = "An expression to match the Jenkins agent that will run the job.\n")
    String agent;

    @CommandLine.Option(names = "--cliVersion", defaultValue = "v2.0.5",
            description = "The version of the Moderne CLI that should be used when running Jenkins Jobs.\n")
    String cliVersion;

    @CommandLine.Option(names = "--commandSuffix", defaultValue = "",
            description = "The suffix that should be appended to the Moderne CLI command when running Jenkins Jobs.\n\n" +
                          "@|bold Example|@: --dry-run\n")
    String commandSuffix;

    @CommandLine.Option(names = "--defaultBranch", defaultValue = "main",
            description = "If no Git branch is specified for a repository in the CSV file, the Jenkins Job will attempt " +
                          "to checkout this branch when pulling down the code.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    String defaultBranch;

    @CommandLine.Option(names = "--deleteSkipped", defaultValue = "false",
            description = "If set to true, whenever a repository in the CSV file has 'skip' set to true, the corresponding " +
                          "Jenkins Job will be deleted. This is useful if you want to remove specific jobs that are failing, " +
                          "but you also want to preserve the list of repositories that are ingested.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean deleteSkipped;

    @CommandLine.Option(names = "--downloadCLI", defaultValue = "false",
            description = "Specifies whether or not the Moderne CLI should be downloaded at the beginning of each Jenkins Job run.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean downloadCLI;

    @CommandLine.Option(names = "--downloadCLIUrl",
            description = "Specifies an internal URL to download the CLI from if you'd prefer to host the CLI yourself.\n")
    String downloadCLIUrl;

    @CommandLine.Option(names = "--downloadCLICreds",
            description = "Specifies the Jenkins credentials Id to download the CLI if you host the CLI yourself.\n")
    String downloadCLICreds;

    @CommandLine.Option(names = "--folder",
            description = "The Jenkins folder that will store the created jobs. This folder will be created if it does not exist.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "moderne-ingest")
    String folder;

    @CommandLine.Option(names = "--mavenSettingsConfigFileId",
            description = "The ID of the Jenkins Maven settings config file that will be used to configure Maven builds. " +
                          "Specified in the Jenkins Global Tool Configuration page:\n" +
                          "    {controllerUrl}/manage/configureTools/\n")
    String mavenSettingsConfigFileId;

    @CommandLine.Option(names = "--platform",
            description = "The OS platform for the Jenkins node/agent. The possible options are: windows, linux, or macos.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n",
            defaultValue = "linux")
    String platform;

    @CommandLine.Option(names = "--prefix",
            description = "If specified, Jenkins Jobs will only be created for repositories that start with this prefix.\n",
            defaultValue = "")
    String prefix;

    @CommandLine.Option(names = "--scheduledAt", defaultValue = "H H * * *",
            description = "The cron schedule that the Jenkins Jobs should follow. By default, Jenkins will execute " +
                          "each job once a day while making sure to space them out so that the system is not overloaded at " +
                          "one particular time.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    String scheduledAt;

    @CommandLine.Option(names = "--skipSSL",
            defaultValue = "false",
            description = "If this parameter is included, SSL verification will be skipped on the generated jobs.\n\n" +
                          "@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean skipSSL;

    @CommandLine.Option(names = "--createValidateJobs",
            defaultValue = "false",
            description = "(Incubating) If supplied validate jobs will be created for each repository.\n")
    boolean createValidateJobs;

    @CommandLine.ArgGroup(multiplicity = "1")
    UserSecret userSecret;

    @CommandLine.Option(names = "--verbose", defaultValue = "false",
            description = "If enabled, additional debug statements will be printed throughout the Jenkins configuration.\n" +
                          "\n@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean verbose;

    @CommandLine.Option(names = "--workspaceCleanup", defaultValue = "false",
            description = "If enabled, use the WsCleanup plugin to clean the workspace after finishing the job.\n" +
                          "\n@|bold Default|@: ${DEFAULT-VALUE}\n")
    boolean workspaceCleanup;

    @SuppressWarnings("unused")
    static class UserSecret {
        @CommandLine.Option(names = "--apiToken", description = "The Jenkins apiToken that will be used when " +
                                                                "authentication is needed in Jenkins (e.g., the creation of Jenkins Jobs).\n")
        String apiToken;

        @CommandLine.Option(names = "--jenkinsPwd",
                description = "The Jenkins password that will be used when authentication is needed in Jenkins " +
                              "(e.g., the creation of Jenkins Jobs).\n\n"
                              + "@|italic Jenkins best practices recommend using an apiToken instead of a password|@.\n")
        String jenkinsPwd;

        String get() {
            return StringUtils.isBlank(apiToken) ? jenkinsPwd : apiToken;
        }

        boolean needsCrumb() {
            return StringUtils.isBlank(apiToken);
        }
    }

    @CommandLine.ArgGroup(exclusive = false)
    Tenant tenant;

    static class Tenant {
        @CommandLine.Option(names = "--moderneUrl", required = true,
                description = "The URL of the Moderne tenant.")
        String moderneUrl;

        @CommandLine.Option(names = "--moderneToken", required = true,
                description = "A personal access token for the Moderne tenant.")
        String moderneToken;
    }

    @CommandLine.Option(names = "--credentials",
        description = "Extra credentials to bind in the form of " +
                      "CRENDENTIALS_ID=VARIABLE for StringBinding or " +
                      "CREDENTIALS_ID=USERNAME_VARIABLE:PASSWORD_VARIABLE for UsernamePasswordMultiBinding")
    Map<String, String> extraCredentials;

    static final String JENKINS_CRUMB_HEADER = "Jenkins-Crumb";

    private static final String PLATFORM_WINDOWS = "windows";
    private static final String CLOUDBEES_FOLDER_PLUGIN = "cloudbees-folder";
    private static final String CLEAN_UP_PLUGIN = "ws-cleanup";
    private static final String GIT_PLUGIN = "git";
    private static final String GRADLE_PLUGIN = "gradle";
    private static final String CREDENTIALS_PLUGIN = "credentials-binding";
    private static final String CONFIG_FILE_PLUGIN = "config-file-provider";
    private static final String POWERSHELL_PLUGIN = "powershell";
    private static final Set<String> REQUIRED_PLUGINS = Stream.of(
            CLOUDBEES_FOLDER_PLUGIN, GIT_PLUGIN, CREDENTIALS_PLUGIN
    ).collect(Collectors.toSet());
    private static final Set<String> OPTIONAL_PLUGINS = Stream.of(
            GRADLE_PLUGIN, POWERSHELL_PLUGIN
    ).collect(Collectors.toSet());

    @RequiredArgsConstructor
    enum Templates {
        FREESTYLE_JOB_DEFINITION("cli/jenkins/freestyle_job.xml.template"),
        FREESTYLE_SCM_DEFINITION("cli/jenkins/freestyle_scm.xml.template"),
        FREESTYLE_SHELL_DEFINITION("cli/jenkins/freestyle_shell.xml.template"),
        FREESTYLE_POWERSHELL_DEFINITION("cli/jenkins/freestyle_powershell.xml.template"),
        FREESTYLE_GRADLE_DEFINITION("cli/jenkins/freestyle_gradle.xml.template"),
        FREESTYLE_MAVEN_DEFINITION("cli/jenkins/freestyle_maven.xml.template"),
        FREESTYLE_CREDENTIALS_DEFINITION("cli/jenkins/freestyle_credentials.xml.template"),
        FREESTYLE_CREDENTIALS_BINDING_USER_DEFINITION("cli/jenkins/freestyle_credentials_binding_user.xml.template"),
        FREESTYLE_CREDENTIALS_BINDING_TOKEN_DEFINITION("cli/jenkins/freestyle_credentials_binding_token.xml.template"),
        FREESTYLE_MAVEN_SETTINGS_DEFINITION("cli/jenkins/freestyle_maven_settings.xml.template"),
        FREESTYLE_CLEANUP_DEFINITION("cli/jenkins/freestyle_cleanup.xml.template"),
        FOLDER_DEFINITION("cli/jenkins/jenkins_folder.xml.template"),
        PARAMETERS_VALIDATE_DEFINITION("cli/jenkins/validate_parameters.xml.template"),
        BUILD_NAME_SETTER_VALIDATE_DEFINITION("cli/jenkins/validate_build_name_setter.xml.template");

        private final String filename;

        public String format(String... varargs) {
            return String.format(TextBlock.textBlock(filename), (Object[]) varargs);
        }
    }

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

        final Map<String, String> plugins;
        try {
            // try-with-resources not possible until Java
            ExecutorService executorService = Executors.newFixedThreadPool(50);
            plugins = resolveJenkinsPlugins();

            if (!StringUtils.isBlank(folder)) {
                if (!folderExists(folder)) {
                    createFolder(plugins, folder);
                }
            }

            List<Future<Boolean>> responses = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(fromCsv.toFile()));
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
                            if (!jobExists(folder, projectName)) {
                                System.out.printf("Skipping %s at line %d because it is marked as skipped: %s%n", repoSlug, currentNumberFinal, skipReason);
                                return true;
                            }
                            if (!deleteJob(folder, projectName)) {
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
                String job = createJob(plugins, branch, mavenTool, gradleTool, repoStyle, repoBuildAction, gitURL, false);
                responses.add(executorService.submit(() -> createJob(folder, projectName, job)));

                if (createValidateJobs) {
                    String validateFolder = "validate";
                    if (!folderExists(validateFolder)) {
                        createFolder(plugins, validateFolder);
                    }

                    String validateJob = createJob(plugins, branch, mavenTool, gradleTool, repoStyle, repoBuildAction, gitURL, true);
                    responses.add(executorService.submit(() -> createJob(validateFolder, projectName, validateJob)));
                }

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

    String createJob(Map<String, String> plugins, String branch, String mavenTool, String gradleTool, String repoStyle, String repoBuildAction, String gitURL, boolean isValidateJob) {
        String scm = createFreestyleScm(plugins, gitURL, branch);
        String assignedNode = StringUtils.isBlank(agent)? "  <canRoam>true</canRoam>" : "  <assignedNode>" + agent.replace("&", "&amp;") + "</assignedNode>\n  <canRoam>false</canRoam>";
        String steps = createFreestyleSteps(plugins, mavenTool, gradleTool, repoStyle, repoBuildAction, isValidateJob);
        String credentials = isValidateJob ? createFreestyleValidateCredentials(plugins, gitURL) : createFreestyleCredentials(plugins);
        String configFiles = createFreestyleConfigFiles(plugins);
        String cleanup = createFreestyleCleanup(plugins);
        String jobParameters = isValidateJob ? Templates.PARAMETERS_VALIDATE_DEFINITION.format() : "";
        String buildNameSetter = isValidateJob ? Templates.BUILD_NAME_SETTER_VALIDATE_DEFINITION.format() : "";
        return createFreestyleJob(jobParameters, scm, assignedNode, steps, cleanup, credentials, configFiles, buildNameSetter, isValidateJob);
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
        Set<String> requiredPlugins = new HashSet<>(REQUIRED_PLUGINS);
        if (!StringUtils.isBlank(mavenSettingsConfigFileId)) {
            requiredPlugins.add(CONFIG_FILE_PLUGIN);
        }
        if (workspaceCleanup) {
            requiredPlugins.add(CLEAN_UP_PLUGIN);
        }

        JsonNode pluginsNode = node.get("plugins");
        int pluginsSize = pluginsNode.size();
        for (int i = 0; i < pluginsSize; i++) {
            JsonNode pluginNode = pluginsNode.get(i);
            if (pluginNode.get("active").asBoolean(false)) {
                result.put(pluginNode.get("shortName").asText(), pluginNode.get("version").asText());
            }
        }
        if (!result.keySet().containsAll(requiredPlugins)) {
            throw new RuntimeException(String.format(
                    "mod-connect requires to install the following Jenkins plugins: %s",
                    requiredPlugins.stream().filter(plugin -> !result.containsKey(plugin))
                            .collect(Collectors.joining(", "))));
        }
        if (!result.keySet().containsAll(OPTIONAL_PLUGINS)) {
            System.out.printf(
                    "mod-connect recommends to install the following Jenkins plugins: %s%n",
                    OPTIONAL_PLUGINS.stream().filter(plugin -> !result.containsKey(plugin))
                            .collect(Collectors.joining(", ")));
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

    private boolean createJob(String folderPath, String jobName, String job) {
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
                    .body(job)
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

    private String getDownloadCLIUrl() {
        if (StringUtils.isBlank(downloadCLIUrl)) {
            return String.format("https://pkgs.dev.azure.com/moderneinc/moderne_public/_packaging/moderne/maven/v1/io/moderne/moderne-cli-%s/%s/moderne-cli-%s-%s", platform, cliVersion, platform, cliVersion);
        }
        return downloadCLIUrl;
    }

    private String createConfigArtifactsCommand() {
        if (publishUrl == null) {
            return ""; // for unit tests, will always be non-null in production
        }

        boolean isWindowsPlatform = isWindowsPlatform();
        String command = "";
        if (downloadCLI || !StringUtils.isBlank(downloadCLIUrl)) {
            command += isWindowsPlatform ? ".\\" : "./";
        }
        command += String.format("%s config artifacts artifactory edit --local=. %s--user=%s --password=%s %s ",
                isWindowsPlatform ? "mod.exe" : "mod",
                skipSSL ? "--skip-ssl " : "",
                isWindowsPlatform ? "$env:ARTIFACTS_PUBLISH_CRED_USR" : "${ARTIFACTS_PUBLISH_CRED_USR}",
                isWindowsPlatform ? "$env:ARTIFACTS_PUBLISH_CRED_PWD" : "${ARTIFACTS_PUBLISH_CRED_PWD}",
                publishUrl
        );
        return command;
    }

    private String createConfigTenantCommand() {
        if (tenant == null) {
            return "";
        }

        boolean isWindowsPlatform = isWindowsPlatform();
        String command = "";
        if (downloadCLI || !StringUtils.isBlank(downloadCLIUrl)) {
            command += isWindowsPlatform ? ".\\" : "./";
        }
        command += String.format("%s config moderne edit --local=. --token=%s %s ",
                isWindowsPlatform ? "mod.exe" : "mod",
                isWindowsPlatform ? "$env:MODERNE_TOKEN" : "${MODERNE_TOKEN}",
                tenant.moderneUrl
        );
        return command;
    }

    private String createConfigMavenSettingsCommand() {
        if (StringUtils.isBlank(mavenSettingsConfigFileId)) {
            return "";
        }

        boolean isWindowsPlatform = isWindowsPlatform();
        String command = "";
        if (downloadCLI || !StringUtils.isBlank(downloadCLIUrl)) {
            command += isWindowsPlatform ? ".\\" : "./";
        }

        return command + String.format("%s config build maven settings edit --local=. %s ",
                isWindowsPlatform ? "mod.exe" : "mod",
                isWindowsPlatform ? "$env:MODERNE_MVN_SETTINGS_XML" : "${MODERNE_MVN_SETTINGS_XML}");
    }

    private String createBuildCommand() {
        boolean isWindowsPlatform = isWindowsPlatform();
        String prefix = "";
        if (downloadCLI || !StringUtils.isBlank(downloadCLIUrl)) {
            prefix += isWindowsPlatform ? ".\\" : "./";
        }
        String command = String.format("%s%s build . --no-download", prefix, isWindowsPlatform ? "mod.exe" : "mod");
        if (!StringUtils.isBlank(commandSuffix)) {
            command += " " + commandSuffix;
        }

        return command;
    }

    private String createPublishCommand() {
        boolean isWindowsPlatform = isWindowsPlatform();
        String prefix = "";
        if (downloadCLI || !StringUtils.isBlank(downloadCLIUrl)) {
            prefix = isWindowsPlatform ? ".\\" : "./";
        }
        return String.format("%s%s publish .", prefix, isWindowsPlatform ? "mod.exe" : "mod");
    }

    private String createFreestyleScm(Map<String, String> plugins, String scmHost, String branch) {
        return Templates.FREESTYLE_SCM_DEFINITION.format(
                plugins.get(GIT_PLUGIN),
                scmHost,
                gitCredsId,
                branch);
    }

    private String createFreestyleDownload() {
        if (!downloadCLI && StringUtils.isBlank(downloadCLIUrl)) {
            return "";
        }
        String downloadURL = getDownloadCLIUrl();

        boolean isWindowsPlatform = isWindowsPlatform();
        if (isWindowsPlatform) {
            String credentials = "";
            if (StringUtils.isNotBlank(downloadCLICreds)) {
                credentials = "$wc.Headers[\"Authorization\"] = string.Format(\"Basic {0}\", " +
                        "Convert.ToBase64String(Encoding.ASCII.GetBytes(\"$env:CLI_DOWNLOAD_CRED_USR\", \"$env:CLI_DOWNLOAD_CRED_PWD\")))";
            }
            return String.format(
                    "$wc = New-Object System.Net.WebClient\n" +
                    "%s\n" +
                    "$wc.DownloadFile(\"%s\", \"mod.exe\")",
                    credentials,
                    downloadURL);
        } else {
            String credentials = "";
            if (!StringUtils.isBlank(downloadCLICreds)) {
                credentials = "--user ${CLI_DOWNLOAD_CRED_USR}:${CLI_DOWNLOAD_CRED_PWD} ";
            }
            return String.format("curl %s--request GET %s --fail -o mod;\nchmod 755 mod;", credentials, downloadURL);
        }
    }

    private String createFreestyleSteps(Map<String, String> plugins, String mavenTool, String gradleTool, String repoStyle, String repoBuildAction, boolean isValidate) {
        StringBuilder builder = new StringBuilder();

        boolean isWindowsPlatform = isWindowsPlatform();

        if (isValidate) {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(
                        plugins.get(POWERSHELL_PLUGIN),
                        "$wc = New-Object System.Net.WebClient\n" +
                        "$wc.Headers[\"Authorization\"] = \"Bearer \\$env:MODERNE_TOKEN\"\n" +
                        "$wc.Headers[\"x-moderne-scmtoken\"] = $env:SCM_TOKEN\n" +
                        "$wc.DownloadFile(\"$patchDownloadUrl\", \"patch.diff\")"
                ));
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format("git apply patch.diff"));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(
                        "curl -o patch.diff --request GET --url $patchDownloadUrl --header \"Authorization: Bearer $MODERNE_TOKEN\" --header \"x-moderne-scmtoken: $SCM_TOKEN\""
                ));
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format("git apply patch.diff"));
            }
        }
        String download = createFreestyleDownload();
        if (!StringUtils.isBlank(download)) {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(plugins.get(POWERSHELL_PLUGIN), download));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(download));
            }
        }

        String configTenant = createConfigTenantCommand();
        if (!isValidate && !StringUtils.isBlank(configTenant)) {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(plugins.get(POWERSHELL_PLUGIN), configTenant));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(configTenant));
            }
        }

        if (!isValidate) {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(plugins.get(POWERSHELL_PLUGIN), createConfigArtifactsCommand()));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(createConfigArtifactsCommand()));
            }
        }

        String buildCommand = createBuildCommand();
        String configMavenSettings = createConfigMavenSettingsCommand();
        if (!StringUtils.isBlank(configMavenSettings)) {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(plugins.get(POWERSHELL_PLUGIN), configMavenSettings));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(configMavenSettings));
            }
        }

        if (!StringUtils.isBlank(gradleTool)) {
            String buildCommandArray = Arrays.stream(buildCommand.split(" +"))
                    .collect(Collectors.joining("', '", "'", "'"));

            builder.append(Templates.FREESTYLE_GRADLE_DEFINITION.format(
                    buildCommandArray,
                    plugins.get(GRADLE_PLUGIN),
                    gradleTool
            ));
        } else if (!StringUtils.isBlank(mavenTool)) {
            String[] parts = buildCommand.split(" +");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Build command is not valid: " + buildCommand);
            }
            String executable = parts[0];
            String args = Arrays.stream(Arrays.copyOfRange(parts, 1, parts.length))
                    .map(arg -> String.format("<argument>%s</argument>", arg))
                    .collect(Collectors.joining("\n              "));
            builder.append(Templates.FREESTYLE_MAVEN_DEFINITION.format(
                    executable,
                    args,
                    mavenTool
            ));
        } else {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(plugins.get(POWERSHELL_PLUGIN), buildCommand));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(buildCommand));
            }
        }
        builder.append("\n");

        if (!isValidate) {
            if (isWindowsPlatform) {
                builder.append(Templates.FREESTYLE_POWERSHELL_DEFINITION.format(plugins.get(POWERSHELL_PLUGIN), createPublishCommand()));
            } else {
                builder.append(Templates.FREESTYLE_SHELL_DEFINITION.format(createPublishCommand()));
            }
        }

        return builder.toString();
    }

    private String createFreestyleCredentials(Map<String, String> plugins) {
        StringBuilder bindings = new StringBuilder();
        bindings.append(Templates.FREESTYLE_CREDENTIALS_BINDING_USER_DEFINITION.format(publishCredsId, "ARTIFACTS_PUBLISH_CRED_USR", "ARTIFACTS_PUBLISH_CRED_PWD"));
        addCommonCredentialBindings(bindings);
        return Templates.FREESTYLE_CREDENTIALS_DEFINITION.format(
                plugins.get(CREDENTIALS_PLUGIN),
                bindings.toString()
        );
    }

    private String createFreestyleValidateCredentials(Map<String, String> plugins, String host) {
        StringBuilder bindings = new StringBuilder();
        addCommonCredentialBindings(bindings);
        if (host != null && !StringUtils.isBlank(host)) {
            bindings.append(Templates.FREESTYLE_CREDENTIALS_BINDING_TOKEN_DEFINITION.format(createScmTokenReference(host), "SCM_TOKEN"));
        }
        return Templates.FREESTYLE_CREDENTIALS_DEFINITION.format(
                plugins.get(CREDENTIALS_PLUGIN),
                bindings.toString()
        );
    }

    private void addCommonCredentialBindings(StringBuilder bindings) {
        if (tenant != null && !StringUtils.isBlank(tenant.moderneToken)) {
            bindings.append(Templates.FREESTYLE_CREDENTIALS_BINDING_TOKEN_DEFINITION.format(tenant.moderneToken, "MODERNE_TOKEN"));
        }
        if (!StringUtils.isBlank(downloadCLICreds)) {
            bindings.append("\n");
            bindings.append(Templates.FREESTYLE_CREDENTIALS_BINDING_USER_DEFINITION.format(downloadCLICreds, "CLI_DOWNLOAD_CRED_USR", "CLI_DOWNLOAD_CRED_PWD"));
        }

        if (extraCredentials != null) {
            for (Map.Entry<String, String> entry : extraCredentials.entrySet()) {
                String credentialsId = entry.getKey();
                String[] variables = entry.getValue().split(":");
                if (variables.length == 1) {
                    bindings.append(Templates.FREESTYLE_CREDENTIALS_BINDING_TOKEN_DEFINITION.format(credentialsId, variables[0]));
                } else if (variables.length == 2) {
                    bindings.append(Templates.FREESTYLE_CREDENTIALS_BINDING_USER_DEFINITION.format(credentialsId, variables[0], variables[1]));
                }
            }
        }
    }

    private String createFreestyleConfigFiles(Map<String, String> plugins) {
        StringBuilder files = new StringBuilder();
        if (!StringUtils.isBlank(mavenSettingsConfigFileId)) {
            files.append(Templates.FREESTYLE_MAVEN_SETTINGS_DEFINITION.format(
                    plugins.get(CONFIG_FILE_PLUGIN),
                    mavenSettingsConfigFileId
            ));
        }
        return files.toString();
    }

    private String createFreestyleCleanup(Map<String, String> plugins) {
        if (workspaceCleanup) {
            return Templates.FREESTYLE_CLEANUP_DEFINITION.format(plugins.get(CLEAN_UP_PLUGIN));
        }
        return "";
    }

    private String createFreestyleJob(String params, String scm, String assignedNode, String steps, String cleanup, String credentials, String configFiles, String buildNameSetter, boolean isValidateJob) {
        return Templates.FREESTYLE_JOB_DEFINITION.format(
                params,
                scm,
                assignedNode,
                isValidateJob ? "" : scheduledAt,
                steps,
                cleanup,
                credentials,
                configFiles,
                buildNameSetter
        );
    }

    private boolean isWindowsPlatform() {
        return PLATFORM_WINDOWS.equals(platform);
    }

    private String createScmTokenReference(String host) {
        try {
            return "scmToken_" + new URL(host).getHost();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
