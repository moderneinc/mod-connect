# mod-connect

`mod-connect` is a CLI tool used to create ingestion pipelines that will regularly build and publish LST artifacts to your artifact repository. This will enable the [Moderne agent](https://docs.moderne.io/architecture-readme/architecture#moderne-agent) to pick up the LSTs so that they can be used in the [Moderne platform](https://docs.moderne.io/) to run recipes against.

Setting up these pipelines _will not_ affect or require changes to your existing workflows or pipelines.

## Prerequisites

* You will need to have Java 8 or higher installed to build or run this tool.

## Installation instructions

1. Go to the [releases section of this repository](https://github.com/moderneinc/mod-connect/releases) and download the `mod-connect.zip` file found under the latest release.

2. Unzip the file by running:

   ```shell
   unzip mod-connect.zip
   ```

3. Add the binary to your `$PATH`:

   ```shell
   export PATH=$PATH:$(pwd)/mod-connect/bin
   ```

4. You should now have a `mod-connect` tool you can run use to run various CLI commands. Use the [jenkins](#mod-connect-jenkins)
   or [github](#mod-connect-github) subcommand to configure your ingestion pipeline. For more information about the commands you can run the following command:

   ```shell
   mod-connect help
   ```

## Usage instructions

### `mod-connect jenkins`

This command will create a Jenkins Job for each configured repository that will build and publish LST artifacts to your artifact repository on a regular basis.

Before you can run this command, you will need to prepare a `repos.csv` file that follows this structure:

```
scmHost, repoName, branch, mavenTool, gradleTool, jdkTool, repoStyle, repoBuildAction, repoSkip, skipReason
```

| Column          | Required | Notes                                                                                                                                                                 |
|-----------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| scmHost         | Optional | The URL of the source code management tool where the repository is hosted. Defaults to `github.com`.                                                                  |
| repoName        | Required | The repository that should be ingested. Follows the format of: `organization/repository` (e.g., `google/guava`).                                                      |
| branch          | Optional | The branch of the above repository that should be ingested.                                                                                                           |
| mavenTool       | Optional | The name of the Maven tool that should be used to run Maven jobs. Specified in the Jenkins Global Tool Configuration page: `{controllerUrl}/manage/configureTools/`   |
| gradleTool      | Optional | The name of the Gradle tool that should be used to run Gradle jobs. Specified in the Jenkins Global Tool Configuration page: `{controllerUrl}/manage/configureTools/` |
| jdkTool         | Optional | The name of the JDK tool that should be used to run JDK jobs. Specified in the Jenkins Global Tool Configuration page: `{controllerUrl}/manage/configureTools/`       |
| repoStyle       | Optional | The OpenRewrite style name to apply during ingestion.                                                                                                                 |
| repoBuildAction | Optional | Additional arguments that are added to the Maven or Gradle build command.                                                                                             |
| skip            | Optional | If set to true, this repository will be skipped and not ingested.                                                                                                     |
| skipReason      | Optional | The context for why the repo is being skipped.                                                                                                                        |

Once you've created the `repos.csv` file, you can set up the ingestion pipeline by running:

````shell
mod-connect jenkins --fromCsv repos.csv \
  --jenkinsUser $JENKINS_USER \
  --jenkinsPwd $JENKINS_PWD \
  --publishUrl $ARTIFACTORY_REPO_URL \
  --publishCredsId artifactCreds \
  --gitCredsId myGitCreds \
  --controllerUrl $JENKINS_URL
````

**Note**: You can specify defaults for the repositories in your `repos.csv` file by using the `--defaultMaven`, `--defaultGradle`, and `--defaultJdk` options. If you include those in your command, the defaults you specify for those parameters will be used when a row in your `repos.csv` does not include `mavenTool`, `gradleTool`, or `jdkTool` respectively.

### `mod-connect github`

This command will create a GitHub workflow that builds and publishes LST artifacts to your artifact repository on a regular basis. A workflow can be created for ingesting a single repository (by specifying the `--path` parameter or by manually adding the [moderne-publish-action](https://github.com/moderneinc/moderne-publish-action) to your repository) or a workflow can be created for ingesting a mass number of repositories (by specifying the `--fromCsv` parameter).

#### If you want to ingest a single repository

Please run the following command:

```shell
mod-connect github --path $my-repo-folder \
 --publishUserSecretName $ghSecretUserToPublish \
 --publishPwdSecretName $ghSecretPwdToPublish \
 --publishUrl $urlToPublish` 
```

#### If you want to ingest a mass number of repositories

1. Create a `repos.csv` file with the following columns:

```
repoName, branch, javaVersion, style, repoBuildAction, skip, skipReason
```

| Column          | Required | Notes                                                                                                                     |
|-----------------|----------|---------------------------------------------------------------------------------------------------------------------------|
| repoName        | Required | The repository that should be ingested. Follows the format of `organization/repositoryRepository` (e.g., `google/guava`). |
| branch          | Optional | The branch of the above repository that should be ingested.                                                               |
| javaVersion     | Optional | The Java version used to compile this repository.                                                                         |
| style           | Optional | The OpenRewrite style name to apply during ingestion.                                                                     |
| repoBuildAction | Optional | Additional arguments that are added to the Maven or Gradle build command.                                                 |
| skip            | Optional | If set to true, this repository will be skipped and not ingested.                                                         |
| skipReason      | Optional | The context for why the repo is being skipped.                                                                            |

2. Create and initialize a GitHub repository where this ingestion pipeline will be set up (e.g., `acme/moderne-ingest`).
3. Add the following secrets to the above repository:

| Secret           | Notes                                                                                        |
|------------------|----------------------------------------------------------------------------------------------|
| PUBLISH_AST_USER | The username to publish into your Artifactory or Nexus.                                      |
| PUBLISH_AST_PWD  | The password to publish into your Artifactory or Nexus.                                      |
| GH_PAT           | The secret with read access to clone the repository.                                         |
| GH_DISPATCH_PAT  | The classic secret with write access restricted to the new repository to dispatch workflows. |

4. Create a Personal Access Token (`WORKFLOW_PAT`) with write and workflow access to the new repository
   (or reuse the `GH_DISPATCH_PAT`) to automatically commit and run the workflows in the new repository.

5. Run the following command:

```shell
mod-connect github --fromCsv $my-csv \ 
  --publishUserSecretName PUBLISH_AST_USER \
  --publishPwdSecretName PUBLISH_AST_PWD \
  --artifactoRepoUrl $urlToPublish \
  --dispatchSecretName GH_DISPATCH_PAT \
  --repoReadSecretName GH_PAT \
  --repo $ingestRepo \
  --accessToken $WORKFLOW_PAT
```

## Development

_If you wish to contribute to this project, please follow these instructions._

### Prerequisites

In order to build this project you will need to have:

* Java 8 or higher installed on your machine
* [Docker running](https://www.docker.com/products/docker-desktop/) (for the tests)

### How to build and run `mod-connect` locally

1. Clone the project to your machine:

```shell
git clone git@github.com:moderneinc/mod-connect.git
```

2. Run the following command to build the project. This will compile the project, run the tests, and create a zip file in the `build/distributions` directory:

```shell
./gradlew build
```

3. With the zip file created, unzip it by running:

```shell
unzip build/distributions/mod-connect.zip
```

4. You should now have the latest build of the `mod-connect` tool locally!

```shell
cd mod-connect/bin
./mod-connect help
```
