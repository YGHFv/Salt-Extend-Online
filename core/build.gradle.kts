plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.register<JavaExec>("contractTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.yghfv.saltneri.core.CoreContractTest")
}

tasks.check {
    dependsOn("contractTest")
}
