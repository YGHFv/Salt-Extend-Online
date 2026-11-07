import java.util.Properties

plugins {
    `java-library`
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

val experimentalAdapter = providers.gradleProperty("experimentalHostAdapter").orNull == "true"
val pluginMetadata = Properties().apply {
    rootProject.file("plugin.properties").inputStream().use { load(it) }
}
sourceSets.main {
    resources.exclude("preference_config*.json")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    implementation(project(":core"))
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    testCompileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    annotationProcessor("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    // Compile-only metadata; the emitted bytecode is checked against the actual host by the local build.
    compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.10.3")
    compileOnly("androidx.compose.runtime:runtime-desktop:1.10.5")
    providers.gradleProperty("saltHostPackage").orNull?.let {
        compileOnly(files(it))
        testCompileOnly(files(it))
        testRuntimeOnly(files(it))
    }
}

kotlin { jvmToolchain(25) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    doFirst {
        require(providers.gradleProperty("saltHostPackage").isPresent) {
            "Native UI compilation needs -PsaltHostPackage=<actual Salt host classpath archive>; no host classes are bundled."
        }
    }
}

val contractMatrix = groovy.json.JsonSlurper().parse(rootProject.file("scripts/contract-tests.json")) as Map<*, *>
(contractMatrix["tests"] as List<*>).forEach { value ->
    val entry = value as Map<*, *>
    val testClass = entry["class"] as String
    val taskName = testClass.substringAfterLast('.').replaceFirstChar { it.lowercase() }
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(testClass)
        val tempFiles = layout.buildDirectory.dir("contract-tmp/$taskName")
        doFirst { tempFiles.get().asFile.mkdirs() }
        jvmArgs("-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + tempFiles.get().asFile.absolutePath)
        if (entry["baseOnly"] == true) jvmArgs("--limit-modules=java.base")
        else {
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            providers.gradleProperty("saltHostPackage").orNull?.let {
                jvmArgs("-Dskiko.library.path=" + file(it).parentFile.absolutePath)
            }
        }
    }
    tasks.check { dependsOn(taskName) }
}

tasks.register<Exec>("verifyQrVendor") {
    commandLine("python", rootProject.file("scripts/verify-vendor.py").absolutePath)
}
tasks.register<Exec>("verifyProject") {
    commandLine("python", rootProject.file("scripts/verify-project.py").absolutePath)
}
tasks.compileJava {
    dependsOn("verifyQrVendor", "verifyProject")
    // The checked-in extension index is shared with the offline local build.
    options.compilerArgs.add("-proc:none")
}

tasks.processResources {
    from("src/main/resources/" + if (experimentalAdapter) "preference_config_experimental.json" else "preference_config.json") {
        rename { "preference_config.json" }
    }
    from(rootProject.files("LICENSE", "THIRD_PARTY_NOTICES.md")) {
        into("META-INF/licenses")
    }
    from(rootProject.files("licenses/QR-Code-generator-MIT.txt")) { into("META-INF/licenses") }
    from(rootProject.files("licenses/Kotlin-Compose-Apache-2.0.txt")) { into("META-INF/licenses") }
    from(rootProject.files("licenses/Simple-Icons-CC0.txt")) { into("META-INF/licenses") }
}

spmod {
    PluginClass = "io.github.yghfv.saltneri.plugin." + if (experimentalAdapter) "ExperimentalPlugin" else "MainPlugin"
    PluginId = pluginMetadata.getProperty("id")
    PluginVersion = pluginMetadata.getProperty(if (experimentalAdapter) "version" else "passiveVersion")
    PluginProvider = pluginMetadata.getProperty("provider")
    PluginName = pluginMetadata.getProperty("name")
    PluginDescription = if (experimentalAdapter) "Native Salt sidebar/catalog/QR UI and explicit playback preview; unverified." else "Passive host preview; online playback is not connected yet."
    PluginPermissions = if (experimentalAdapter) listOf(com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM) else emptyList()
    PluginHasConfig = true
}
