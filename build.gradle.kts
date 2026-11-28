import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "Latrixz/cloudstream-asian")
    }

    android {
        namespace = "com.cloudstreamasian"

        // Code shared by every source (backup links), compiled into each plugin.
        sourceSets.getByName("main").java.srcDir(rootProject.file("shared"))

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // These dependencies can include any of those which are added by the app,
        // but you don't need to include any of them if you don't need them.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // HTTP Lib
        implementation("org.jsoup:jsoup:1.18.3") // HTML Parser
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will
        // break compatibility on older Android devices.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON Parser
        // Provided by the app at runtime; the shared backup code needs it to compile.
        val compileOnly by configurations
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

        // Source watcher (./gradlew watch): runs the real provider code on the JVM.
        val testImplementation by configurations
        testImplementation(files(File(gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")))
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
        testImplementation("junit:junit:4.13.2")
        // Libraries the CloudStream app ships at runtime.
        testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        testImplementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        testImplementation("dev.whyoleg.cryptography:cryptography-core:0.4.0")
        testImplementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.4.0")
    }

    // Daily watcher wiring. The provider class is read from the Plugin's registerMainAPI(...) call.
    android {
        sourceSets.getByName("test").java.srcDir(rootProject.file("watcher/src"))
        testOptions.unitTests.isReturnDefaultValues = true
    }
    val providerClass: String? = fileTree("src/main/kotlin").files.firstNotNullOfOrNull { f ->
        val text = f.readText()
        val cls = Regex("""registerMainAPI\((\w+)\(""").find(text)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        if (pkg == null) cls else "$pkg.$cls"
    }
    val needsWebView = fileTree("src/main/kotlin").files.any { it.readText().contains(Regex("CloudflareKiller|WebViewResolver")) }
    tasks.withType<Test>().configureEach {
        onlyIf { providerClass != null }
        outputs.upToDateWhen { false }
        ignoreFailures = true
        filter { includeTestsMatching("com.csasian.watcher.*") }
        systemProperty("watch.module", project.name)
        systemProperty("watch.provider", providerClass ?: "")
        systemProperty("watch.webview", needsWebView.toString())
        systemProperty("watch.out", rootProject.file("build/watch/${project.name}.json").absolutePath)
        testLogging { showStandardStreams = true }
    }
}

// ./gradlew watch  -> build/watch/<Source>.json for every source (AsianHome has no site of its own).
tasks.register("watch") {
    dependsOn(subprojects.filter { it.name != "AsianHome" }.map { "${it.path}:testReleaseUnitTest" })
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
