plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Spec §3: this module stays pure JVM so it runs in fast unit tests, can be driven from a laptop
// main(), and can be cross-validated against Arcascope/circadian. Any Android import fails the build.
val verifyNoAndroidImports = tasks.register("verifyNoAndroidImports") {
    val sources = fileTree("src") { include("**/*.kt", "**/*.java") }
    val marker = layout.buildDirectory.file("verifyNoAndroidImports/ok.txt")
    inputs.files(sources)
    outputs.file(marker)
    doLast {
        val forbidden = Regex("""^\s*import\s+(android|androidx|com\.google\.android)\.""", RegexOption.MULTILINE)
        val offenders = sources.files.filter { forbidden.containsMatchIn(it.readText()) }
        if (offenders.isNotEmpty()) {
            throw GradleException(":core-model must not import Android APIs (spec §3):\n" + offenders.joinToString("\n"))
        }
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("no android imports\n")
        }
    }
}

tasks.named("compileKotlin") { dependsOn(verifyNoAndroidImports) }
tasks.named("compileTestKotlin") { dependsOn(verifyNoAndroidImports) }
