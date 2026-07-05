dependencies {
    implementation(projects.maintenanceCoreProxy)
    implementation(libs.bstatsBungee)
    compileOnly(libs.bungee)

    // BungeeCord does not provide slf4j-api, which JDA (the Discord bot) requires at runtime.
    // Velocity provides it, so this is only added here. It is left unrelocated so JDA can find it;
    // with no provider on the classpath, slf4j 2.x falls back to a no-op logger.
    implementation(libs.slf4jApi)
}

tasks {
    shadowJar {
        relocate("net.kyori", "eu.kennytv.maintenance.lib.kyori") {
            exclude("net.kyori", "adventure-bom")
            exclude("com.google.code.gson", "gson")
        }
    }
}

dependencies {
    api(libs.bundles.adventureBungee) {
        exclude("com.google.code.gson", "gson")
        exclude("org.slf4j", "slf4j-api")
        exclude("net.kyori", "adventure-bom")
    }
}
