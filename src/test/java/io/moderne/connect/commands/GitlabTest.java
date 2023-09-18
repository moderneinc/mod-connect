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
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;

public class GitlabTest {
    GitLab gitlab = new GitLab();

    @BeforeEach
    void setup() {
        gitlab.downloadCLI = true;
        gitlab.platform = "linux";
        gitlab.cliVersion = "v0.4.4";
    }

    @Nested
    class Pipeline {
        @Test
        void createPipeline() throws IOException {
            gitlab.downloadCLI = true;
            gitlab.fromCsv = Path.of("src/test/csv/gitlab-repos.csv");
            GitLabYaml.Pipeline pipeline = gitlab.createPipeline();
            assertThat(pipeline.getDownload()).isNotNull();
            assertThat(pipeline.getJobs().keySet()).containsExactly("build-moderneinc/git-test", "build-moderneinc/moderne-gitlab-ingest");
            assertThat(pipeline.getStages()).containsExactly(GitLabYaml.Stage.DOWNLOAD, GitLabYaml.Stage.BUILD_LST);
        }

        @Test
        void skipDownload() throws IOException {
            gitlab.downloadCLI = false;
            gitlab.fromCsv = Path.of("src/test/csv/gitlab-repos.csv");
            GitLabYaml.Pipeline pipeline = gitlab.createPipeline();
            assertThat(pipeline.getDownload()).isNull();
            assertThat(pipeline.getStages()).containsExactly(GitLabYaml.Stage.BUILD_LST);
        }

        @Test
        void writePipeline() throws IOException {
            gitlab.fromCsv = Path.of("src/test/csv/gitlab-repos.csv");
            gitlab.call();
            Path path = Path.of("moderne-pipeline.yml");
            assertThat(Files.exists(path)).isTrue();
            Files.delete(path);
        }
    }

    @Nested
    class DownloadStage {
        @Test
        void download() {
            gitlab.downloadCLI = true;
            gitlab.platform = "macos";
            gitlab.cliVersion = "v0.5.0";

            assertDownloadSteps(
                    "curl --request GET --url 'https://pkgs.dev.azure.com/moderneinc/moderne_public/_packaging/moderne/maven/v1/io/moderne/moderne-cli-macos/v0.5.0/moderne-cli-macos-v0.5.0' > mod",
                    "chmod 755 mod"
            );
        }

        @Test
        void customUrl() {
            gitlab.downloadCLIUrl = "https://acme.com/moderne-cli";
            assertDownloadSteps(
                    "curl --request GET --url 'https://acme.com/moderne-cli' > mod",
                    "chmod 755 mod"
            );
        }

        @Test
        void tokenAndCustomUrl() {
            gitlab.downloadCLIUrl = "https://acme.com/moderne-cli";
            gitlab.downloadCLITokenSecretName = "CLI_DOWNLOAD_TOKEN";
            assertDownloadSteps("curl --request GET --url 'https://acme.com/moderne-cli' --header 'Authorization: Bearer $CLI_DOWNLOAD_TOKEN' > mod",
                    "chmod 755 mod"
            );
        }

        @Test
        void credentialsAndCustomUrl() {
            gitlab.downloadCLIUrl = "https://acme.com/moderne-cli";
            gitlab.downloadCLIUserNameSecretName = "CLI_DOWNLOAD_CRED_USR";
            gitlab.downloadCLIPasswordSecretName = "CLI_DOWNLOAD_CRED_PWD";
            assertDownloadSteps("curl --user $CLI_DOWNLOAD_CRED_USR:$CLI_DOWNLOAD_CRED_PWD --request GET --url 'https://acme.com/moderne-cli' > mod",
                    "chmod 755 mod"
            );
        }

        void assertDownloadSteps(@Language("bash") String... expectation) {
            GitLabYaml.Job job = gitlab.createDownloadJob();
            assertThat(job.getStage()).isEqualTo(GitLabYaml.Stage.DOWNLOAD);
            assertThat(job.getCache().getKey()).isEqualTo(String.format("cli-%s-%s", gitlab.platform, gitlab.cliVersion));

            assertThat(job.getScript())
                    .containsExactly(expectation);
        }
    }

    @Nested
    class BuildStage {
        @Test
        void withoutJava() {
            assertBuildSteps(
                    "./mod build . --no-download --active-style some-style --additional-build-args \"--magic\"",
                    "./mod publish .");
        }

        @Test
        void windows() {
            gitlab.platform = "windows";
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            assertBuildSteps(".\\\\mod.exe config artifacts https://my.artifactory/moderne-ingest --user $PUBLISH_USER --password $PUBLISH_SECRET",
                    ".\\\\mod.exe build . --no-download --active-style some-style --additional-build-args \"--magic\"",
                    ".\\\\mod.exe publish .");
        }

        @Test
        void skipSsl() {
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            gitlab.skipSSL = true;
            assertBuildSteps(
                    "./mod config artifacts https://my.artifactory/moderne-ingest --user $PUBLISH_USER --password $PUBLISH_SECRET --skipSSL",
                    "./mod build . --no-download --active-style some-style --additional-build-args \"--magic\"",
                    "./mod publish ."
            );
        }

        @Test
        void modConfigModerne() {
            gitlab.tenant = new GitLab.Tenant();
            gitlab.tenant.moderneUrl = "https://app.moderne.io";
            gitlab.tenant.moderneToken = "modToken";
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            assertBuildSteps(
                    "./mod config moderne https://app.moderne.io --token modToken",
                    "./mod config artifacts https://my.artifactory/moderne-ingest --user $PUBLISH_USER --password $PUBLISH_SECRET",
                    "./mod build . --no-download --active-style some-style --additional-build-args \"--magic\"",
                    "./mod publish ."
            );
        }

        @Test
        void modConfigModerneEnvVar() {
            gitlab.tenant = new GitLab.Tenant();
            gitlab.tenant.moderneUrl = "https://app.moderne.io";
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            assertBuildSteps(
                    "./mod config moderne https://app.moderne.io --token ${MODERNE_TOKEN}",
                    "./mod config artifacts https://my.artifactory/moderne-ingest --user $PUBLISH_USER --password $PUBLISH_SECRET",
                    "./mod build . --no-download --active-style some-style --additional-build-args \"--magic\"",
                    "./mod publish ."
            );
        }

        @Test
        void modConfigModerneWithSecret() {
            gitlab.tenant = new GitLab.Tenant();
            gitlab.tenant.moderneUrl = "https://app.moderne.io";
            gitlab.tenant.moderneTokenSecret = "SECRET";
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            assertBuildSteps(
                    "./mod config moderne https://app.moderne.io --token $SECRET",
                    "./mod config artifacts https://my.artifactory/moderne-ingest --user $PUBLISH_USER --password $PUBLISH_SECRET",
                    "./mod build . --no-download --active-style some-style --additional-build-args \"--magic\"",
                    "./mod publish ."
            );
        }

        @Test
        void submitJobWithMavenPluginVersion() {
            gitlab.mvnPluginVersion = "5.0.2";
            assertBuildSteps("./mod build . --no-download --active-style some-style --additional-build-args \"--magic\" --maven-plugin-version 5.0.2",
                    "./mod publish .");
        }

        @Test
        void submitJobWithGradlePluginVersion() {
            gitlab.gradlePluginVersion = "5.0.2";
            assertBuildSteps("./mod build . --no-download --active-style some-style --additional-build-args \"--magic\" --gradle-plugin-version 5.0.2",
                    "./mod publish .");
        }

        void assertBuildSteps(@Language("bash") String... scriptCommands) {
            GitLabYaml.Job build = gitlab.createBuildLstJob("org/repo-path", "main", "some-style", "--magic");
            assertThat(build.getStage()).isEqualTo(GitLabYaml.Stage.BUILD_LST);
            assertThat(build.getCache().getPolicy()).isEqualTo(GitLabYaml.Cache.Policy.PULL);
            assertThat(build.getCache().getKey()).isEqualTo(String.format("cli-%s-%s", gitlab.platform, gitlab.cliVersion));
            assertThat(build.getCache().getPaths()).containsExactly("mod");
            assertThat(build.getVariables())
                    .containsEntry("REPO_PATH", "org/repo-path");
            assertThat(build.getBeforeScript()).containsExactly("BASE_URL=`echo $CI_REPOSITORY_URL | sed \"s;\\/*$CI_PROJECT_PATH.*;;\"`",
                    "REPO_URL=\"$BASE_URL/$GITLAB_HOST/$REPO_PATH.git\"",
                    "REPO_DIR=$REPO_PATH",
                    "rm -fr $REPO_DIR",
                    "git clone --single-branch --branch main $REPO_URL $REPO_DIR");
            assertThat(build.getScript()).containsExactly(scriptCommands);
        }
    }
}
