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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitlabTest {
    GitLab gitlab = new GitLab();

    @BeforeEach
    void setup() {
        gitlab.platform = "linux";
    }

    @Nested
    class Pipeline {
        @Test
        void createPipeline() throws IOException {
            gitlab.fromCsv = Path.of("src/test/csv/gitlab-repos.csv");
            gitlab.dockerImageBuildJob = "ruby:latest";
            GitLabYaml.Pipeline pipeline = gitlab.createPipeline();
            assertThat(pipeline.getJobs().keySet()).containsExactly("build-moderneinc/git-test", "build-moderneinc/moderne-gitlab-ingest");
            assertThat(pipeline.getJobs().values()).extracting(GitLabYaml.Job::getImage).containsExactly("ruby:latest", "ruby:latest");
            assertThat(pipeline.getStages()).containsExactly(GitLabYaml.Stage.BUILD_LST);
        }

        @Test
        void skipDownload() throws IOException {
            gitlab.fromCsv = Path.of("src/test/csv/gitlab-repos.csv");
            GitLabYaml.Pipeline pipeline = gitlab.createPipeline();
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
    class BuildStage {
        @Test
        void withoutJava() {
            assertBuildSteps(
                    "mod build $REPO_PATH --no-download",
                    "mod publish $REPO_PATH");
        }

        @Test
        void withoutDownload() {
            assertBuildSteps(
                    "mod build $REPO_PATH --no-download",
                    "mod publish $REPO_PATH");
        }

        @Test
        void windows() {
            gitlab.platform = "windows";
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            assertBuildSteps("mod.exe config artifacts artifactory edit --local=. --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "mod.exe build $REPO_PATH --no-download",
                    "mod.exe publish $REPO_PATH");
        }

        @Test
        void skipSsl() {
            gitlab.publishUrl = "https://my.artifactory/moderne-ingest";
            gitlab.publishPwdSecretName = "PUBLISH_SECRET";
            gitlab.publishUserSecretName = "PUBLISH_USER";
            gitlab.skipSSL = true;
            assertBuildSteps(
                    "mod config artifacts artifactory edit --local=. --skip-ssl --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "mod build $REPO_PATH --no-download",
                    "mod publish $REPO_PATH"
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
                    "mod config moderne --token=modToken https://app.moderne.io",
                    "mod config artifacts artifactory edit --local=. --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "mod build $REPO_PATH --no-download",
                    "mod publish $REPO_PATH"
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
                    "mod config moderne --token=${MODERNE_TOKEN} https://app.moderne.io",
                    "mod config artifacts artifactory edit --local=. --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "mod build $REPO_PATH --no-download",
                    "mod publish $REPO_PATH"
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
                    "mod config moderne --token=$SECRET https://app.moderne.io",
                    "mod config artifacts artifactory edit --local=. --user=$PUBLISH_USER --password=$PUBLISH_SECRET https://my.artifactory/moderne-ingest",
                    "mod build $REPO_PATH --no-download",
                    "mod publish $REPO_PATH"
            );
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
                            "mod build $REPO_PATH --no-download",
                            "mod publish $REPO_PATH"
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
            GitLabYaml.Job build = gitlab.createBuildLstJob("org/repo-path", "main");
            assertThat(build.getStage()).isEqualTo(GitLabYaml.Stage.BUILD_LST);
            assertThat(build.getCache()).isNull();
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
