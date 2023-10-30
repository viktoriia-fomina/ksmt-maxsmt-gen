import org.gradle.jvm.tasks.Jar

plugins {
    id("io.ksmt.ksmt-base")
    id("java")
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://projects.itemis.de/nexus/content/repositories/OS/")
    }
}

dependencies {
    implementation("io.ksmt:ksmt-core:0.5.8")
    implementation("io.ksmt:ksmt-bitwuzla:0.5.8")
    implementation("io.ksmt:ksmt-runner:0.5.8")

    implementation("tools.aqua:z3-turnkey:4.12.2.1")

    // 4.8.8.1
    //implementation("com.microsoft.z3:java-jar:4.11.2")
    // implementation("com.microsoft.z3:libz3.java.win:4.8.8")
    //implementation("com.microsoft.z3:libz3.java.linux:4.11.2")
    // implementation("com.microsoft.z3:libz3.win:4.8.8")
    // implementation("com.microsoft.z3:libz3.linux:4.11.2")

    implementation(project(":ksmt-test"))
    implementation(project(":ksmt-maxsmt"))
    implementation(project(":ksmt-maxsmt-test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
}

val fatJar = task("fatJar", type = Jar::class) {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Implementation-Title"] = "Gradle Jar File Example"
        attributes["Implementation-Version"] = archiveVersion
        attributes["Main-Class"] = "MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get() as CopySpec)
}

tasks {
    "build" {
        dependsOn(fatJar)
    }
}
