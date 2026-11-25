rootProject.name = "CloudstreamAsian"

// Every source lives in its own folder under sources/ and is included automatically.
// To leave one out of the build, add its folder name to "disabled".

val disabled = listOf<String>()

File(rootDir, "sources").listFiles()
    ?.filter { it.isDirectory && it.name !in disabled && File(it, "build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
