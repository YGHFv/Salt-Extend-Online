plugins {
    `java-library`
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

val experimentalAdapter = providers.gradleProperty("experimentalHostAdapter").orNull == "true"
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

tasks.register<JavaExec>("transportContractTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.yghfv.saltneri.plugin.transport.TransportContractTest")
    jvmArgs("--limit-modules=java.base")
}

tasks.check {
    dependsOn("transportContractTest")
}

listOf(
    "nativeUiContractTest" to "io.github.yghfv.saltneri.plugin.ui.NativeUiContractTest",
    "saltPageContentBindingContractTest" to "io.github.yghfv.saltneri.plugin.ui.SaltPageContentBindingContractTest",
    "nativeHookTransformTest" to "io.github.yghfv.saltneri.plugin.ui.NativeHookTransformTest",
    "coverLoaderContractTest" to "io.github.yghfv.saltneri.plugin.ui.CoverLoaderContractTest",
    "nativeQueueContractTest" to "io.github.yghfv.saltneri.plugin.host.NativeQueueContractTest",
    "playbackCoverContractTest" to "io.github.yghfv.saltneri.plugin.host.PlaybackCoverContractTest"
).forEach { (taskName, testClass) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(testClass)
        val tempFiles = layout.buildDirectory.dir("native-ui-contract-tmp")
        doFirst { tempFiles.get().asFile.mkdirs() }
        jvmArgs("-Djava.io.tmpdir=" + tempFiles.get().asFile.absolutePath)
    }
    tasks.check { dependsOn(taskName) }
}

listOf(
    "pinnedHttpsContractTest" to "io.github.yghfv.saltneri.plugin.net.PinnedHttpsContractTest",
    "boundedJsonContractTest" to "io.github.yghfv.saltneri.plugin.net.BoundedJsonContractTest",
    "biliClientContractTest" to "io.github.yghfv.saltneri.plugin.bilibili.BiliClientContractTest",
    "biliResolutionContractTest" to "io.github.yghfv.saltneri.plugin.bilibili.BiliResolutionContractTest",
    "neteaseClientContractTest" to "io.github.yghfv.saltneri.plugin.netease.NeteaseClientContractTest",
    "neteaseResolutionContractTest" to "io.github.yghfv.saltneri.plugin.netease.NeteaseResolutionContractTest",
    "catalogContractTest" to "io.github.yghfv.saltneri.plugin.library.CatalogContractTest",
    "libraryWorkflowContractTest" to "io.github.yghfv.saltneri.plugin.library.LibraryWorkflowContractTest",
    "nativeDeskPlaybackContractTest" to "io.github.yghfv.saltneri.plugin.ui.NativeDeskPlaybackContractTest",
    "authProtocolContractTest" to "io.github.yghfv.saltneri.plugin.auth.AuthProtocolContractTest",
    "authLifecycleContractTest" to "io.github.yghfv.saltneri.plugin.auth.AuthLifecycleContractTest",
    "windowsDpapiContractTest" to "io.github.yghfv.saltneri.plugin.auth.WindowsDpapiContractTest"
).forEach { (taskName, testClass) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(testClass)
        jvmArgs("--limit-modules=java.base")
        val tempFiles = layout.buildDirectory.dir("contract-tmp")
        doFirst { tempFiles.get().asFile.mkdirs() }
        jvmArgs("-Djava.io.tmpdir=" + tempFiles.get().asFile.absolutePath)
    }
    tasks.check { dependsOn(taskName) }
}

tasks.register<Exec>("verifyQrVendor") {
    commandLine("python", rootProject.file("scripts/verify-vendor.py").absolutePath)
}
tasks.compileJava { dependsOn("verifyQrVendor") }

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
    PluginId = "io.github.yghfv.saltneri.online"
    PluginVersion = if (experimentalAdapter) "0.8.0-playlist-state" else project.version.toString()
    PluginProvider = "YGHFv"
    PluginName = "Salt Extend Online"
    PluginDescription = if (experimentalAdapter) "Native Salt sidebar/catalog/QR UI and explicit playback preview; unverified." else "Passive host preview; online playback is not connected yet."
    PluginPermissions = if (experimentalAdapter) listOf(com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM) else emptyList()
    PluginHasConfig = true
}
