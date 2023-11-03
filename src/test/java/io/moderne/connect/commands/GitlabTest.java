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
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitlabTest {
    GitLab gitlab = new GitLab();

    @BeforeEach
    void setup() {
        gitlab.downloadCLI = true;
        gitlab.platform = "linux";
        gitlab.cliVersion = "v1.0.3";
    }

    @Nested
    class Pipeline {
        @Test
        void createPipeline() throws IOException {
            gitlab.downloadCLI = true;
            gitlab.fromCsv = Path.of("src/test/csv/gitlab-repos.csv");
            gitlab.dockerImageBuildJob = "ruby:latest";
            GitLabYaml.Pipeline pipeline = gitlab.createPipeline();
            assertThat(pipeline.getDownload()).isNotNull();
            assertThat(pipeline.getJobs().keySet()).containsExactly("build-moderneinc/git-test", "build-moderneinc/moderne-gitlab-ingest");
            assertThat(pipeline.getJobs().values()).extracting(GitLabYaml.Job::getImage).containsExactly("ruby:latest", "ruby:latest");
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
            gitlab.cliVersion = "v1.0.3";

            assertDownloadSteps("[ -f 'mod' ] && echo 'mod loaded from cache, skipping download.' && ./mod help && exit 0",
                    "curl --fail --location --output mod --request GET --url 'https://pkgs.dev.azure.com/moderneinc/moderne_public/_packaging/moderne/maven/v1/io/moderne/moderne-cli-macos/v1.0.3/moderne-cli-macos-v1.0.3'",
                    "chmod 755 mod"
            );
        }

        @Test
        void customUrl() {
            gitlab.downloadCLIUrl = "https://acme.com/moderne-cli";
            assertDownloadSteps("[ -f 'mod' ] && echo 'mod loaded from cache, skipping download.' && ./mod help && exit 0",
                    "curl --fail --location --output mod --request GET --url 'https://acme.com/moderne-cli'",
                    "chmod 755 mod"
            );
        }

        @Test
        void tokenAndCustomUrl() {
            gitlab.downloadCLIUrl = "https://acme.com/moderne-cli";
            gitlab.downloadCLITokenSecretName = "CLI_DOWNLOAD_TOKEN";
            assertDownloadSteps("[ -f 'mod' ] && echo 'mod loaded from cache, skipping download.' && ./mod help && exit 0",
                    "curl --fail --location --output mod --request GET --url 'https://acme.com/moderne-cli' --header 'Authorization: Bearer $CLI_DOWNLOAD_TOKEN'",
                    "chmod 755 mod"
            );
        }

        @Test
        void credentialsAndCustomUrl() {
            gitlab.downloadCLIUrl = "https://acme.com/moderne-cli";
            gitlab.downloadCLIUserNameSecretName = "CLI_DOWNLOAD_CRED_USR";
            gitlab.downloadCLIPasswordSecretName = "CLI_DOWNLOAD_CRED_PWD";
            assertDownloadSteps("[ -f 'mod' ] && echo 'mod loaded from cache, skipping download.' && ./mod help && exit 0",
                    "curl --fail --location --output mod --request GET --url 'https://acme.com/moderne-cli' --user $CLI_DOWNLOAD_CRED_USR:$CLI_DOWNLOAD_CRED_PWD",
                    "chmod 755 mod"
            );
        }

        void assertDownloadSteps(@Language("bash") String... expectation) {
            GitLabYaml.Job job = gitlab.createDownloadJob();
            assertThat(job.getStage()).isEqualTo(GitLabYaml.Stage.DOWNLOAD);
            if (StringUtils.isBlank(gitlab.downloadCLIUrl)) {
                assertThat(job.getCache().getKey()).isEqualTo(String.format("cli-%s-%s", gitlab.platform, gitlab.cliVersion));
            } else {
                String encoded = new String(Base64.getEncoder().encode(gitlab.downloadCLIUrl.getBytes()));
                assertThat(job.getCache().getKey()).isEqualTo(String.format("cli-%s", encoded));
            }

            assertThat(job.getScript())
                    .containsExactly(expectation);
        }
    }

    @Nested
    class BuildStage {
        @Test
        void withoutJava() {
            assertBuildSteps(
                    "./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    "./mod publish $REPO_PATH");
        }

        @Test
        void withoutDownload() {
            gitlab.downloadCLI = false;
            assertBuildSteps(
                    "mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    "mod publish $REPO_PATH");
        }

        @Test
        void windows() {
            gitlab.platform = "windows";
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            assertBuildSteps(".\\\\mod.exe config artifacts --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    ".\\\\mod.exe build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    ".\\\\mod.exe publish $REPO_PATH");
        }

        @Test
        void skipSsl() {
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            gitlab.skipSSL = true;
            assertBuildSteps(
                    "./mod config artifacts edit --skipSSL --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    "./mod publish $REPO_PATH"
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
                    "./mod config moderne --token=modToken https://app.moderne.io",
                    "./mod config artifacts edit --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    "./mod publish $REPO_PATH"
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
                    "./mod config moderne --token=${MODERNE_TOKEN} https://app.moderne.io",
                    "./mod config artifacts edit --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    "./mod publish $REPO_PATH"
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
                    "./mod config moderne --token=$SECRET https://app.moderne.io",
                    "./mod config artifacts edit --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                    "./mod publish $REPO_PATH"
            );
        }

        @Test
        void submitJobWithMavenPluginVersion() {
            gitlab.mvnPluginVersion = "5.0.2";
            assertBuildSteps("./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\" --maven-plugin-version 5.0.2",
                    "./mod publish $REPO_PATH");
        }

        @Test
        void submitJobWithGradlePluginVersion() {
            gitlab.gradlePluginVersion = "5.0.2";
            assertBuildSteps("./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\" --gradle-plugin-version 5.0.2",
                    "./mod publish $REPO_PATH");
        }

        @Test
        void useProvidedGitLabCredentials() {
            gitlab.repositoryAccessUserSecretName = "USER_SECRET";
            gitlab.repositoryAccessTokenSecretName = "TOKEN_SECRET";
            //language=bash
            assertBuildSteps(List.of(
                            "REPO_ACCESS_USER=$USER_SECRET",
                            "REPO_ACCESS_TOKEN=$TOKEN_SECRET",
                            "REPO_URL=$(echo \"$CI_REPOSITORY_URL\" | sed -E \"s|^(https?://)([^/]+@)?([^/]+)(/.+)?/([^/]+)/([^/]+)\\.git|\\1$REPO_ACCESS_USER:$REPO_ACCESS_TOKEN@\\3\\4/$REPO_PATH.git|\")",
                            "rm -fr $REPO_PATH",
                            "git clone --single-branch --branch main $REPO_URL $REPO_PATH",
                            "echo '127.0.0.1  host.docker.internal' >> /etc/hosts"
                    )
                    , List.of(
                            "./mod build $REPO_PATH --no-download --active-styles some-style --additional-build-args \"--magic\"",
                            "./mod publish $REPO_PATH"
                    ));
        }

        void assertBuildSteps(@Language("bash") String... scriptCommands) {
            //language=bash
            assertBuildSteps(List.of(
                    "REPO_ACCESS_USER=gitlab-ci-token",
                    "REPO_ACCESS_TOKEN=$CI_JOB_TOKEN",
                    "REPO_URL=$(echo \"$CI_REPOSITORY_URL\" | sed -E \"s|^(https?://)([^/]+@)?([^/]+)(/.+)?/([^/]+)/([^/]+)\\.git|\\1$REPO_ACCESS_USER:$REPO_ACCESS_TOKEN@\\3\\4/$REPO_PATH.git|\")",
                    "rm -fr $REPO_PATH",
                    "git clone --single-branch --branch main $REPO_URL $REPO_PATH",
                    "echo '127.0.0.1  host.docker.internal' >> /etc/hosts"
            ), List.of(scriptCommands));
        }

        void assertBuildSteps(List<String> beforeScriptCommands, List<String> scriptCommands) {
            GitLabYaml.Job build = gitlab.createBuildLstJob("org/repo-path", "main", "some-style", "--magic");
            assertThat(build.getStage()).isEqualTo(GitLabYaml.Stage.BUILD_LST);
            if (gitlab.downloadCLI) {
                assertThat(build.getCache().getPolicy()).isEqualTo(GitLabYaml.Cache.Policy.PULL);
                assertThat(build.getCache().getKey()).isEqualTo(String.format("cli-%s-%s", gitlab.platform, gitlab.cliVersion));
                assertThat(build.getCache().getPaths()).containsExactly("mod");
            } else {
                assertThat(build.getCache()).isNull();
            }
            assertThat(build.getVariables())
                    .containsEntry("REPO_PATH", "org/repo-path");
            assertThat(build.getBeforeScript()).containsExactlyElementsOf(beforeScriptCommands);
            assertThat(build.getScript()).containsExactlyElementsOf(scriptCommands);
            assertThat(build.getArtifacts().getWhen()).isEqualTo(GitLabYaml.Artifacts.When.ALWAYS);
            assertThat(build.getRetry()).isEqualTo(gitlab.buildJobRetries);
            assertThat(build.getArtifacts().getPaths()).containsExactly("$REPO_PATH/.moderne/build/*/build.log");
        }
    }
}
