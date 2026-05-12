plugins {
    id("java")
}

val buildNumberFile = file("build.number")
val buildNumber = buildNumberFile.readText().trim().toInt()
version = "0.1.$buildNumber"
base.archivesName = "P2PMarkets"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
}

dependencies {
    // Hytale server jar (provides JavaPlugin, ECS, etc.)
    implementation(files("C:/Users/almig/AppData/Roaming/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("src/main/resources")

    filesMatching("manifest.json") {
        filter { it.replace(Regex(""""Version":\s*"[^"]*""""), """"Version": "$version"""") }
    }

    doLast {
        buildNumberFile.writeText("${buildNumber + 1}\n")
    }
}

val userModsDir = "C:/Users/almig/AppData/Roaming/Hytale/UserData/Saves/New World/mods"

tasks.register<Delete>("cleanMods") {
    delete(fileTree(userModsDir).matching { include("P2PMarkets-*.jar") })
}

tasks.register<Copy>("deploy") {
    dependsOn("cleanMods", "jar")
    mustRunAfter("cleanMods")
    from(tasks.jar.get().archiveFile)
    into(userModsDir)
}
