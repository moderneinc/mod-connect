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

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JenkinsTest {
    Jenkins jenkins = new Jenkins();

    @Nested
    class DownloadStage {
        @Test
        void download() {
            jenkins.downloadCLI = true;
            jenkins.platform = "linux";
            jenkins.cliVersion = "v0.4.4"; // TODO do we really want this hardcoded into the command option as a default value?

            assertDownloadSteps("""
                    sh "curl --request GET https://pkgs.dev.azure.com/moderneinc/moderne_public/_packaging/moderne/maven/v1/io/moderne/moderne-cli-linux/v0.4.4/moderne-cli-linux-v0.4.4 > mod"
                    sh "chmod 755 mod"
                    """);
        }

        @Test
        void customUrl() {
            jenkins.downloadCLIUrl = "https://acme.com/moderne-cli";
            assertDownloadSteps("""
                    sh "curl --request GET https://acme.com/moderne-cli > mod"
                    sh "chmod 755 mod"
                    """);
        }

        @Test
        void credentialsAndCustomUrl() {
            jenkins.downloadCLIUrl = "https://acme.com/moderne-cli";
            jenkins.downloadCLICreds = "downloadCreds";
            assertDownloadSteps("""
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'downloadCreds', usernameVariable: 'CLI_DOWNLOAD_CRED_USR', passwordVariable: 'CLI_DOWNLOAD_CRED_PWD']]) {
                        sh "curl --user ${CLI_DOWNLOAD_CRED_USR}:${CLI_DOWNLOAD_CRED_PWD} --request GET https://acme.com/moderne-cli > mod"
                        sh "chmod 755 mod"
                    }
                    """);
        }

        void assertDownloadSteps(@Language("groovy") String steps) {
            assertThat(jenkins.createStageDownload())
                    .isEqualToIgnoringWhitespace("stage('Download CLI') { steps { %s } }".formatted(steps));
        }
    }

    @Nested
    class PublishStage {
        @Test
        void withoutJava() {
            assertPublishSteps("""
                    sh 'mod build . --no-download'
                    sh 'mod publish .'
                    """);
        }

        @Test
        void windows() {
            jenkins.platform = "windows";
            jenkins.publishUrl = "https://my.artifactory/moderne-ingest";
            jenkins.publishCredsId = "artifactCreds";
            assertPublishSteps("""
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactCreds', usernameVariable: 'ARTIFACTS_PUBLISH_CRED_USR', passwordVariable: 'ARTIFACTS_PUBLISH_CRED_PWD']]) {
                        powershell 'mod.exe config artifacts https://my.artifactory/moderne-ingest --user $env:ARTIFACTS_PUBLISH_CRED_USR --password $env:ARTIFACTS_PUBLISH_CRED_PWD'
                    }
                    powershell 'mod.exe build . --no-download'
                    powershell 'mod.exe publish .'
                    """);
        }

        @Test
        void skipSsl() {
            jenkins.publishUrl = "https://my.artifactory/moderne-ingest";
            jenkins.publishCredsId = "artifactCreds";
            jenkins.skipSSL = true;
            assertPublishSteps("""
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactCreds', usernameVariable: 'ARTIFACTS_PUBLISH_CRED_USR', passwordVariable: 'ARTIFACTS_PUBLISH_CRED_PWD']]) {
                        sh 'mod config artifacts https://my.artifactory/moderne-ingest --user ${ARTIFACTS_PUBLISH_CRED_USR} --password ${ARTIFACTS_PUBLISH_CRED_PWD} --skipSSL'
                    }
                    sh 'mod build . --no-download'
                    sh 'mod publish .'
                    """);
        }

        @Test
        void modConfigModerne() {
            jenkins.tenant = new Jenkins.Tenant();
            jenkins.tenant.moderneUrl = "https://app.moderne.io";
            jenkins.tenant.moderneToken = "modToken";
            jenkins.publishUrl = "https://my.artifactory/moderne-ingest";
            jenkins.publishCredsId = "artifactCreds";
            assertPublishSteps("""
                    withCredentials([string(credentialsId: 'modToken', variable: 'MODERNE_TOKEN')]) {
                        sh 'mod config moderne https://app.moderne.io --token ${MODERNE_TOKEN}'
                    }
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactCreds', usernameVariable: 'ARTIFACTS_PUBLISH_CRED_USR', passwordVariable: 'ARTIFACTS_PUBLISH_CRED_PWD']]) {
                        sh 'mod config artifacts https://my.artifactory/moderne-ingest --user ${ARTIFACTS_PUBLISH_CRED_USR} --password ${ARTIFACTS_PUBLISH_CRED_PWD}'
                    }
                    sh 'mod build . --no-download'
                    sh 'mod publish .'
                    """);
        }

        @Test
        void submitJobWithMavenSettings() {
            jenkins.mavenSettingsConfigFileId = "maven-ingest-settings-credentials";
            assertPublishSteps("""
                    configFileProvider([configFile(fileId: 'maven-ingest-settings-credentials', variable: 'MODERNE_MVN_SETTINGS_XML')]) {
                        sh 'mod build . --no-download --maven-settings ${MODERNE_MVN_SETTINGS_XML}'
                    }
                    sh 'mod publish .'
                    """);
        }

        @Test
        void submitJobWithMavenPluginVersion() {
            jenkins.mvnPluginVersion = "5.0.2";
            assertPublishSteps("""
                    sh 'mod build . --no-download --maven-plugin-version 5.0.2'
                    sh 'mod publish .'
                    """);
        }

        @Test
        void submitJobWithGradlePluginVersion() {
            jenkins.gradlePluginVersion = "5.0.2";
            assertPublishSteps("""
                    sh 'mod build . --no-download --gradle-plugin-version 5.0.2'
                    sh 'mod publish .'
                    """);
        }

        @Test
        void withGradleTool() {
            assertThat(jenkins.createStagePublish("", "Gradle7", "", ""))
                    //language=groovy
                    .isEqualToIgnoringWhitespace("""
                            stage('Publish') {
                               tools {
                                  gradle 'Gradle7'
                               }
                               steps {
                                  sh 'mod build . --no-download'
                                  sh 'mod publish .'
                               }
                            }
                            """
                    );
        }

        void assertPublishSteps(@Language("groovy") String steps) {
            assertThat(jenkins.createStagePublish("", "", "", ""))
                    .isEqualToIgnoringWhitespace("stage('Publish') { steps { %s } }".formatted(steps));
        }
    }
}
