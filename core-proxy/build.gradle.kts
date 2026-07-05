dependencies {
    api(projects.maintenanceApiProxy)
    api(projects.maintenanceCore)

    // Try to reasonably minimize it...
    implementation(libs.jedis) {
        exclude("com.google.code.gson", "gson")
        exclude("org.slf4j", "slf4j-api")
    }

    // The built-in Discord bot (JDA) now lives in the shared core module, so it is inherited from there.

    compileOnly(libs.luckperms)
    compileOnly(libs.guava)
    compileOnly(libs.gson)
}

java {
    withJavadocJar()
}
