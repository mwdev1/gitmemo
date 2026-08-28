plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.gitmemo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("Git4Idea")
        // Optional at runtime (see plugin.xml), but needed on the compile classpath for the
        // Claude Code bridge, which writes into the Claude session's terminal.
        bundledPlugin("org.jetbrains.plugins.terminal")

        // Not a dependency of this plugin — nothing here compiles against it. It is here only so the
        // runIde sandbox has the same Claude Code session the bridge targets in a real IDE, which
        // otherwise cannot be tested at all.
        plugin("com.anthropic.code.plugin:0.1.14-beta")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        // Shown in the "What's new" section of the listing: a short summary of this version only.
        changeNotes = """
            <ul>
                <li>First release.</li>
                <li>View, add, edit and delete git notes from the commit details panel, a Git Notes
                    tool window and the Log context menu.</li>
                <li>Fetch and push the notes refs.</li>
                <li>Send a note to a Claude Code terminal session.</li>
                <li>Configurable notes namespace.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            // Verify against the IDE we compile against.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.2.4")
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
