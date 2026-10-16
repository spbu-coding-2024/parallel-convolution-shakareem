plugins {
    kotlin("jvm") version "2.4.21"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "24"
    targetCompatibility = "24"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation("org.knowm.xchart:xchart:3.8.2")
    implementation("com.twelvemonkeys.imageio:imageio-bmp:3.9.4")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.9.4")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

application {
    mainClass.set("AppKt")
}

tasks.register("fmt") {
    group = "formatting"
    description = "Format Kotlin code with ktlint"
    dependsOn("ktlintFormat")
}

tasks.test {
    useJUnitPlatform()
    // Deterministic AWT behavior regardless of the shell's DISPLAY (XChart/ImageIO
    // must not try to open an X11 connection; Wayland sessions often export a
    // DISPLAY that has no X authority).
    systemProperty("java.awt.headless", "true")
}

tasks.withType<JavaExec> {
    // Benchmark saves chart PNGs via XChart/AWT; run reads/writes images via ImageIO.
    // Headless mode renders offscreen without needing an X11/Wayland connection.
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("benchmark") {
    group = "benchmark"
    description = "Run benchmark. Use -Ptask=serial|parallel|pipeline to select scenario."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("BenchmarkKt")

    // Pass -Ptask=serial (or parallel/pipeline) from Gradle command line.
    // Optional: -Preps=N (measured rounds per config), -Pout=DIR (raw data dir).
    val benchTask = project.findProperty("task")?.toString() ?: "serial"
    args(benchTask)
    (project.findProperty("reps")?.toString())?.let { args("--reps", it) }
    (project.findProperty("out")?.toString())?.let { args("--out", it) }
}

tasks.register<JavaExec>("charts") {
    group = "benchmark"
    description = "Draw charts + stats CSVs from raw benchmark data. Use -Psrc=docs/bench/<run> to select."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ChartsKt")

    (project.findProperty("src")?.toString())?.let { args(it) }
}
