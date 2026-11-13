import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.follow.clash.service"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Unit tests live under android/tests/ instead of each module's src/test.
        getByName("test").java.setSrcDirs(listOf("../tests/service"))
        getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("rootHelper/jniLibs").get().asFile)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))
    implementation(libs.gson)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Root mode's TUN helper: a static Go binary shipped as a native library so it is extracted and executable.
val rootHelperAbis =
    linkedMapOf(
        "android-arm" to Triple("armeabi-v7a", "arm", "7"),
        "android-arm64" to Triple("arm64-v8a", "arm64", ""),
        "android-x64" to Triple("x86_64", "amd64", ""),
    )
val rootHelperTargets =
    (rootProject.findProperty("target-platform") as String?)
        ?.split(",")
        ?.mapNotNull { rootHelperAbis[it.trim()] }
        ?: rootHelperAbis.values.toList()

val buildRootHelper by tasks.registering {
    val source = layout.projectDirectory.dir("roothelper")
    val output = layout.buildDirectory.dir("rootHelper/jniLibs")
    inputs.dir(source)
    inputs.property("targets", rootHelperTargets.map { it.first })
    outputs.dir(output)
    doLast {
        val go = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "go") }
            .firstOrNull { it.canExecute() }
            ?.path ?: "go"
        rootHelperTargets.forEach { (abi, arch, arm) ->
            val target = output.get().file("$abi/libflroot.so").asFile
            target.parentFile.mkdirs()
            val process = ProcessBuilder(go, "build", "-trimpath", "-ldflags", "-s -w", "-o", target.absolutePath, ".")
                .directory(source.asFile)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(
                        mapOf("CGO_ENABLED" to "0", "GOOS" to "linux", "GOARCH" to arch, "GOARM" to arm, "GOFLAGS" to "-mod=mod"),
                    )
                }
                .start()
            val log = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("Root helper build failed for $abi:\n$log")
        }
    }
}

tasks.named("preBuild") { dependsOn(buildRootHelper) }
