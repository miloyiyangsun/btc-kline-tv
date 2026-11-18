@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(17)
}

val copySources = tasks.register<Copy>("copySources") {
    from("../app/src/main/java/com/milolab/btctv/ui") {
        include("**/*.kt")
        include("board/layout/**/*.kt")
    }
    from("../app/src/main/java/com/milolab/btctv/data") {
        include("*.kt")
        exclude("InstrumentCatalog.kt")
    }
    from("../app/src/main/java/com/milolab/btctv/data/overview") {
        include("**/*.kt")
        exclude("OverviewAndroid.kt")
    }
    from("../app/src/main/java/com/milolab/btctv/data/hub") {
        include("**/*.kt")
    }
    from("../app/src/main/java") {
        include("com/milolab/btctv/data/indicators/**/*.kt")
    }
    into(layout.buildDirectory.dir("gen/kotlin"))
}

sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("gen/kotlin"))
tasks.named("compileKotlin") { dependsOn(copySources) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20240303")
    testImplementation(compose.uiTest)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = (project.findProperty("mainClass") as String?) ?: "PreviewKt"
    }
}
