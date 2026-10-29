import java.security.MessageDigest

plugins { kotlin("jvm") }

dependencies {
    implementation(files("vendor/devline-deck-tools-0.1.1.jar"))
    compileOnly(project(":devline-ide-extensions"))
    compileOnly("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(project(":devline-ide-extensions"))
    testImplementation("com.google.code.gson:gson:2.13.2")
}

val deckToolsJar = layout.projectDirectory.file("vendor/devline-deck-tools-0.1.1.jar")
val verifyDeckTools = tasks.register("verifyDeckTools") {
    group = "verification"
    description = "Verifies the immutable Deck tools runtime before compilation and packaging."
    val expected = "610e5c253b09b603efa9b3cca279b0c81637fcf11025bbda0a068f7a5d1f7296"
    inputs.file(deckToolsJar)
    inputs.property("sha256", expected)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256").digest(deckToolsJar.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        require(actual == expected) { "Deck tools JAR checksum mismatch; restore the pinned vendor artifact." }
    }
}
tasks.named("compileKotlin") { dependsOn(verifyDeckTools) }
tasks.jar { dependsOn(verifyDeckTools) }

tasks.processResources {
    from("src/extension/extension.json") { rename { "steamdeck-extension.json" } }
}

val panelInstall = tasks.register<Exec>("panelInstall") {
    workingDir(projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "extension", projectDir.absolutePath)
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/extension-devkit"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/deck-build-inventory"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/ui-web"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/animgraph"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/game-render-web"))
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(panelInstall)
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "panel", projectDir.absolutePath)
    inputs.dir("web")
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
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
    archiveFileName.set("devline-steamdeck-0.2.7.dlextension")
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
