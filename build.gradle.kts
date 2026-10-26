import java.security.MessageDigest

plugins { kotlin("jvm") }

dependencies {
    implementation(project(":devline-deck-tools"))
    compileOnly(project(":devline-ide-extensions"))
    compileOnly("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(project(":devline-ide-extensions"))
    testImplementation("com.google.code.gson:gson:2.13.2")
}

tasks.processResources {
    from("src/extension/extension.json") { rename { "steamdeck-extension.json" } }
}

val panelInstall = tasks.register<Exec>("panelInstall") {
    dependsOn(":devline-ide-extensions:frontendInstall")
    workingDir(projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "extension", projectDir.absolutePath)
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/extension-devkit"))
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(panelInstall)
    dependsOn(":devline-desktop:webInstall")
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "panel", projectDir.absolutePath)
    inputs.dir("web")
    inputs.dir(rootProject.file("devline-deck-tools/shared"))
    inputs.dir(rootProject.file("devline-ui-web/src"))
    outputs.dir(layout.buildDirectory.dir("panel"))
}
val packPanel = tasks.register<Exec>("packPanel") {
    dependsOn(panelBuild)
    workingDir(rootProject.projectDir)
    commandLine("node", project.file("web/pack.mjs").absolutePath)
    inputs.dir(layout.buildDirectory.dir("panel"))
    outputs.dir(layout.buildDirectory.dir("extension-web"))
}
val extensionArchive = tasks.register<Zip>("extensionArchive") {
    group = "distribution"
    description = "Builds the independently installable Steam Deck extension."
    archiveFileName.set("devline-steamdeck-0.2.6.dlextension")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    from("src/extension/extension.json")
    from(packPanel) { into("web") }
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath) { exclude("kotlin-stdlib-*.jar", "annotations-*.jar") }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

configurations.create("bundledExtension") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add("bundledExtension", extensionArchive)

tasks.register("bundledExtensions") {
    group = "distribution"
    dependsOn(extensionArchive)
    val destination = layout.buildDirectory.dir("bundled-extensions")
    inputs.file(extensionArchive.flatMap { it.archiveFile })
    outputs.dir(destination)
    doLast {
        val archive = extensionArchive.get().archiveFile.get().asFile
        val checksum = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        val directory = destination.get().asFile.also { it.mkdirs() }
        archive.copyTo(directory.resolve(archive.name), overwrite = true)
        directory.resolve("catalog.json").writeText("""{"schemaVersion":1,"extensions":[{"id":"devline.steamdeck","archive":"${archive.name}","sha256":"$checksum","enabled":true}]}""", Charsets.UTF_8)
    }
}

val backendCheck = tasks.register<JavaExec>("steamDeckBackendCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.steamdeck.DeckRunBackendCheckKt")
    jvmArgs("-Djava.awt.headless=true")
}

tasks.register<JavaExec>("steamDeckCheck") {
    group = "verification"
    dependsOn(tasks.testClasses, extensionArchive, backendCheck)
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get()
    mainClass.set("devline.steamdeck.SteamDeckCheckKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
    systemProperty("devline.deck.archive", extensionArchive.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
