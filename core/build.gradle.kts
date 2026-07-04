plugins {
    id("net.kyori.blossom")
    id("org.jetbrains.gradle.plugin.idea-ext")
}

sourceSets {
    main {
        blossom {
            javaSources {
                property("version", project.version.toString())
                property("impl_version", "git-Maintenance-${project.version}:${rootProject.latestCommitHash()}")
            }
        }
    }
}

dependencies {
    api(projects.maintenanceApi)
    compileOnlyApi(libs.adventureApi)
    compileOnlyApi(libs.adventureTextMinimessage)
    compileOnlyApi(libs.adventureTextSerializerPlain)
    compileOnlyApi(libs.adventureTextSerializerLegacy)

    // Built-in Discord bot (shared by all platforms). Exposed as `api` because MaintenancePlugin has a
    // protected DiscordBot field (DiscordBot extends JDA's ListenerAdapter), so subclasses in the platform
    // modules need JDA on their compile classpath. Audio (opus-java) and voice crypto (tink) are unused and
    // excluded to keep the jar small. slf4j is excluded here: platforms provide it (Velocity/Paper) or shade
    // it in separately (Bungee - see bungee/build.gradle.kts).
    api(libs.jda) {
        exclude("club.minnced", "opus-java")
        exclude("com.google.crypto.tink", "tink")
        exclude("org.slf4j", "slf4j-api")
    }

    compileOnly(libs.luckperms)
    compileOnly(libs.serverlistplus)
    compileOnly(libs.guava)
    compileOnly(libs.gson)
    compileOnly(libs.snakeyaml)
}

java {
    withJavadocJar()
}
