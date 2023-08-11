# mod-connect

This is a CLI tool to generate LST files with the Moderne CLI (mod) for all your repositories without having
to manually adapt the existing repository workflows/pipelines.

## Development

_If you wish to contribute to this project, follow these instructions._

### How to build and run the CLI locally

1. Clone the project to your machine.

2. Run the following command to build the project. This will create a zip file in the `build/distributions` directory:

   ```shell
   ./gradlew build
   ```

3. With the zip file made, unzip it by running:

   ```shell
   unzip build/distributions/mod-connect.zip
   ```

4. You should now have a `mod-connect` tool you can run use to run various CLI commands. This is the file you unzipped
   in the previous step.

   ```shell
   cd build/distributions/mod-connect
   bin/mod-connect --help
   ```

5. Now you can use mod-connect for GitHub Actions or Jenkins.

## How to use the mod-connect CLI

### Pre-requisites

- The CLI is written in Java and can run on top of Java 8 or higher.

### How to download mod-connect

1. You need to go to releases section of this repository (https://github.com/moderneinc/mod-connect/releases) and
   download
   the latest version of the CLI.

2. Unzip the file by running:

   ```shell
   unzip mod-connect.zip
   ```

3. Add the binary to your `$PATH`:

   ```shell
   export PATH=$PATH:$(pwd)/mod-connect/bin
   ```

4. You should now have a `mod-connect` tool you can run use to run various CLI commands. Use the `jenkins`
   or `github` subcommand to configure your repositories.

   ```shell
   mod-connect --help
   ```

### mod-connect for Jenkins

You need to prepare a `repos.csv` file following this structure:

`[scmHost, repoName, branch, mavenTool, gradleTool, jdkTool, repoStyle, repoBuildAction, repoSkip, skipReason]`

| Column          | Required | Notes                                                                                   |
|-----------------|----------|-----------------------------------------------------------------------------------------|
| scmHost         | Optional | SCM Host. By default `github.com`.                                                      |
| repoName        | Required | Repository Slug with form `organization/name`, i.e. `google/guava`.                     |
| branch          | Optional | Github branch name to ingest.                                                           |
| mavenTool       | Optional | The maven tool name installed as a central Jenkins Maven Tool to apply in the pipeline  |
| gradleTool      | Optional | The gradle tool name installed as a central Jenkins Maven Tool to apply in the pipeline |
| jdkTool         | Optional | The jdk tool name installed as a central Jenkins Maven Tool to apply in the pipeline    |
| repoStyle       | Optional | OpenRewrite style name to apply during ingest.                                          |
| repoBuildAction | Optional | Additional build tool tasks/targets to execute first for Maven and Gradle builds.       |
| skip            | Optional | Repo to skip                                                                            |
| skipReason      | Optional | The reason to skip the repository                                                       |

````shell
mod-connect jenkins --fromCsv repos.csv --jenkinsUser $JENKINS_USER --jenkinsPwd $JENKINS_PWD --publishUrl $ARTIFACTORY_REPO_URL --publishCredsId artifactCreds --gitCredsId myGitCreds --controllerUrl=$JENKINS_URL
````

The command can also be enriched with the default `mavenTool`, `gradleTool` and `jdkTool` when there is no value
applied in the CSV using `--defaultMaven`, `--defaultGradle` and `--defaultJdk` options.

### mod-connect for GitHub Actions

For a single repository, you can add the [moderne-publish-action](https://github.com/moderneinc/moderne-publish-action)
in your workflow
or run the following command:

```shell
mod-connect github --path $my-repo-folder\
 --publishUserSecretName $ghSecretUserToPublish --publishPwdSecretName $ghSecretPwdToPublish --publishUrl $urlToPublish` 
```

For a massive ingestion, you need to:

1. Prepare a `repos.csv` file with the following columns:

`repoName, branch, javaVersion, style, repoBuildAction, skip, skipReason`

| Column          | Required | Notes                                                                             |
|-----------------|----------|-----------------------------------------------------------------------------------|
| repoName        | Required | Repository Slug with form `organization/name`, i.e. `google/guava`.               |
| branch          | Optional | Github branch name to ingest.                                                     |
| javaVersion     | Optional | The Java version number to apply in actions/setup-java@v3                         |
| style           | Optional | OpenRewrite style name to apply during ingest.                                    |
| repoBuildAction | Optional | Additional build tool tasks/targets to execute first for Maven and Gradle builds. |
| skip            | Optional | Repo to skip                                                                      |
| skipReason      | Optional | The reason to skip the repository                                                 |

2. Create and initialize a GitHub repository for ingestion purposes. For instance, `acme/moderne-ingest`
3. Add the following secrets for ingestion available via Actions

| Secret           | Notes                                                                                       |
|------------------|---------------------------------------------------------------------------------------------|
| PUBLISH_AST_USER | The username to publish into your Artifactory or Nexus                                      |
| PUBLISH_AST_PWD  | The password to publish into your Artifactory or Nexus                                      |
| GH_PAT           | The secret with read access to clone the repository                                         |
| GH_DISPATCH_PAT  | The classic secret with write access restricted to the new repository to dispatch workflows |

4. Create a Personal Access Token (WORKFLOW_PAT) with write and workflow access to the new repository
   (or reuse the GH_DISPATCH_PAT) to automatically commit and run the workflows in the new repository
   (e.g `acme/moderne-ingest`).

5. Run the following command:

```shell
mod-connect github --fromCsv $my-csv\ 
  --publishUserSecretName PUBLISH_AST_USER --publishPwdSecretName PUBLISH_AST_PWD --artifactoRepoUrl $urlToPublish\
  --dispatchSecretName GH_DISPATCH_PAT --repoReadSecretName GH_PAT \
  --repo $ingestRepo --accessToken $WORKFLOW_PAT
```

