@file:Suppress("GradlePackageUpdate")

import org.asciidoctor.gradle.jvm.AsciidoctorTask
import java.util.*

plugins {
    java
    application
    `maven-publish`
    id("nebula.release") version "17.1.0"
    id("org.owasp.dependencycheck") version "8.1.0"
    id("java-library-distribution")
    id("org.asciidoctor.jvm.convert") version "3.3.2"
    id("com.github.hierynomus.license") version "0.16.1"
}

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

license {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = project.rootProject.file("gradle/licenseHeader.txt")
    strictCheck = true
    include("**/*.java")
}

dependencyCheck {
    analyzers.assemblyEnabled = false
    failBuildOnCVSS = 9.0F
    suppressionFile = "suppressions.xml"
}

group = "io.moderne"

repositories {
    mavenLocal()
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    mavenCentral()
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases-local/")
    }
}

configurations {
    all {
        resolutionStrategy {
            cacheChangingModulesFor(0, TimeUnit.MINUTES)
            cacheDynamicVersionsFor(0, TimeUnit.MINUTES)
            exclude("ch.qos.logback")
        }
    }
}

val jacksonVersion = "2.15.1"
dependencies {
    implementation("info.picocli:picocli:latest.release")
    annotationProcessor("info.picocli:picocli-codegen:latest.release")

    runtimeOnly("org.slf4j:slf4j-nop:latest.release")
    implementation("org.apache.commons:commons-lang3:latest.release")
    implementation("com.konghq:unirest-java:latest.release")
    implementation ("commons-io:commons-io:latest.release")

    implementation(platform("com.fasterxml.jackson:jackson-bom:$jacksonVersion"))
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    // Resolve warning: unknown enum constant When.MAYBE
    compileOnly("com.google.code.findbugs:jsr305:latest.release")
    compileOnly("org.projectlombok:lombok:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")

    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testImplementation("org.junit-pioneer:junit-pioneer:latest.release")
    testImplementation("org.assertj:assertj-core:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")
    testImplementation("org.testcontainers:testcontainers:1.17.6")
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
    testImplementation("org.testcontainers:junit-jupiter:1.17.6")
    testImplementation("org.awaitility:awaitility:4.2.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}

application {
    mainClass.set("io.moderne.cli.commands.Mod")
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
    sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
        // Pass group & name to PicoCLI annotation processor as per https://picocli.info/#_using_build_tools
        options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
        // https://github.com/remkop/picocli/tree/main/picocli-codegen#222-other-options
        options.compilerArgs.add("-Aother.resource.patterns=.*")
    }

    compileTestJava {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
        // Pass group & name to PicoCLI annotation processor as per https://picocli.info/#_using_build_tools
        options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
        // https://github.com/remkop/picocli/tree/main/picocli-codegen#222-other-options
        options.compilerArgs.add("-Aother.resource.patterns=.*")

        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }

}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveVersion.set("")
}

tasks.withType<Tar> {
    compression = Compression.GZIP
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveExtension.set("tar.gz")
    archiveVersion.set("")
}

tasks.withType<CreateStartScripts>{
    doLast {
        //solves the windows problem with the input line being too long
        val windowsTemplate = windowsScript.readText()
        val classpath = windowsTemplate.lines().find { it.startsWith("set CLASSPATH=") }.orEmpty()
        windowsScript
                .writeText(windowsTemplate.replace(
                        classpath,
                        "set CLASSPATH=%APP_HOME%\\lib\\*"))
    }
}


distributions {
    main {
        distributionBaseName.set("moderne-connect")
    }
}

tasks.withType<JavaExec> {
    dependsOn("classes")
    group = "Documentation"
    description = "Generate AsciiDoc manpage"
    classpath(configurations.compileClasspath, configurations.annotationProcessor, "src/main/java")
    mainClass.set("picocli.codegen.docgen.manpage.ManPageGenerator")
    args("io.moderne.cli.commands.Connect", "--outdir=${buildDir}/asciidoc")
}

tasks.withType<AsciidoctorTask> {
    dependsOn("run")
    setSourceDir("${buildDir}/asciidoc")
    setOutputDir("${buildDir}/docs")
    outputOptions {
        backends("manpage", "html5")
    }
    attributes(
        mapOf(
            "revnumber" to project.version.toString()
        )
    )
}
