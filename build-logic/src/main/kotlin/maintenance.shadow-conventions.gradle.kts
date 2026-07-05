import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

plugins {
    id("maintenance.base-conventions")
    id("com.gradleup.shadow")
}

tasks {
    named<Jar>("jar") {
        archiveClassifier.set("unshaded")
        from(project.rootProject.file("LICENSE.txt"))
    }
    val shadowJar = named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        configureRelocations()
    }
    named("build") {
        dependsOn(shadowJar)
    }
}

publishShadowJar()

fun ShadowJar.configureRelocations() {
    relocate("org.bstats", "eu.kennytv.maintenance.lib.bstats")

    // Proxy
    relocate("redis.client", "eu.kennytv.maintenance.lib.redis.client")
    relocate("org.json", "eu.kennytv.maintenance.lib.json")
    relocate("org.apache.commons.pool2", "eu.kennytv.maintenance.lib.apache.commons.pool2")

    // Discord bot (JDA) and its transitive dependencies.
    // org.slf4j is intentionally NOT relocated: Velocity injects a real org.slf4j.Logger into the plugin,
    // so relocating it would break that binding.
    relocate("net.dv8tion", "eu.kennytv.maintenance.lib.jda")
    relocate("com.neovisionaries", "eu.kennytv.maintenance.lib.neovisionaries")
    relocate("okhttp3", "eu.kennytv.maintenance.lib.okhttp3")
    relocate("okio", "eu.kennytv.maintenance.lib.okio")
    relocate("kotlin", "eu.kennytv.maintenance.lib.kotlin")
    relocate("gnu.trove", "eu.kennytv.maintenance.lib.trove")
    relocate("com.fasterxml.jackson", "eu.kennytv.maintenance.lib.jackson")
    relocate("org.apache.commons.collections4", "eu.kennytv.maintenance.lib.apache.commons.collections4")
}