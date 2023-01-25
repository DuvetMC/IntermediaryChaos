buildscript {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://maven.quiltmc.org/repository/snapshot/")
        mavenCentral()
    }
    dependencies {
        classpath("org.quiltmc:enigma-cli:1.5.0")
        classpath("net.fabricmc:stitch:0.6.2")
        classpath("de.undercouch:gradle-download-task:4.1.2")
        classpath("org.quiltmc.unpick:unpick:3.0.2")
        classpath("org.quiltmc.unpick:unpick-format-utils:3.0.2")
        classpath("org.quiltmc:quilt-enigma-plugin:1.2.1")
        classpath("net.fabricmc:name-proposal:0.1.4")
    }
}

plugins {
    java
    `maven-publish`
    id("de.undercouch.download") version "4.1.2"
}

import java.net.URL

val minecraftVersion = "b1.3"
val officialVersion = "b1.3-1750"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://maven.quiltmc.org/repository/snapshot/")
    maven("https://libraries.minecraft.net/")
}

val enigmaConfig by configurations.creating

dependencies {
    enigmaConfig("org.quiltmc:enigma-swing:1.5.0")
    enigmaConfig("org.quiltmc:quilt-enigma-plugin:1.2.1")
    enigmaConfig("net.fabricmc:name-proposal:0.1.4")
    listOf(
        "asm",
        "asm-tree",
        "asm-commons",
        "asm-util",
    ).forEach { enigmaConfig("org.ow2.asm:$it:9.4") }
}

tasks {
    val mappingsGroup = "mappings"
    val mappingsDir = file("$buildDir/mappings")
    val mcJarsDir = file("$buildDir/mc-jars")
    val workingDir = file("$buildDir/temp/mappings")

    val downloadIntermediary by registering {
        group = mappingsGroup

        val outputFile = file("$mappingsDir/$minecraftVersion-intermediary.tiny")
        outputs.file(outputFile)

        doLast {
            val url = URL("https://raw.githubusercontent.com/DuvetMC/old-intermediaries/master/intermediaries/$officialVersion.tiny")
            url.openStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    val downloadMinecraft by registering {
        group = mappingsGroup
        val outputFile = file("$mcJarsDir/$minecraftVersion-official.jar")
        outputs.file(outputFile)

        doLast {
            val jsonUrl = URL("https://skyrising.github.io/mc-versions/version/$officialVersion.json")
            val json = jsonUrl.readText()
            // why parse json when you can just substring
            val clientUrl = if ("\"client\": {" in json) json.substringAfter("\"client\":").substringAfter("\"url\":").substringAfter("\"").substringBefore("\"") else null
            val serverUrl = if ("\"server\": {" in json) json.substringAfter("\"server\":").substringAfter("\"url\":").substringAfter("\"").substringBefore("\"") else null

            if (clientUrl != null && serverUrl == null) {
                val url = URL(clientUrl)
                url.openStream().use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else if (clientUrl == null && serverUrl != null) {
                val url = URL(serverUrl)
                url.openStream().use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else if (clientUrl != null && serverUrl != null) {
                val clientUrl = URL(clientUrl)
                val serverUrl = URL(serverUrl)
                val clientFile = file("$mcJarsDir/$minecraftVersion-official-client.jar")
                val serverFile = file("$mcJarsDir/$minecraftVersion-official-server.jar")
                clientUrl.openStream().use { input ->
                    clientFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                serverUrl.openStream().use { input ->
                    serverFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                net.fabricmc.stitch.merge.JarMerger(clientFile, serverFile, outputFile).use {
                    it.merge()
                }
            } else {
                throw IllegalStateException("No client or server jar found for $minecraftVersion")
            }
        }
    }

    val genV2Intermediary by registering {
        group = mappingsGroup
        dependsOn(downloadIntermediary)

        doLast {
            val mappings = file("$mappingsDir/$minecraftVersion-intermediary.tiny")
            val v2Mappings = file("$mappingsDir/$minecraftVersion-intermediary-v2.tiny")

            cuchaz.enigma.command.ConvertMappingsCommand.run(
                "tiny",
                mappings.toPath(),
                "tinyv2:official:intermediary",
                v2Mappings.toPath()
            )
        }
    }

    val genIntermediaryJar by registering {
        group = mappingsGroup
        dependsOn(downloadIntermediary, downloadMinecraft)

        val outputJar = file("$mcJarsDir/$minecraftVersion-intermediary.jar")
        outputs.file(outputJar)

        doLast {
            val mappings = file("$mappingsDir/$minecraftVersion-intermediary.tiny")
            val inputJar = file("$mcJarsDir/$minecraftVersion-official.jar")

            cuchaz.enigma.command.DeobfuscateCommand.run(
                inputJar.toPath(),
                outputJar.toPath(),
                mappings.toPath(),
            )
        }
    }

    val enigma by registering(JavaExec::class) {
        group = mappingsGroup
        dependsOn(genIntermediaryJar)

        val mappings = file("mappings/")

        classpath = enigmaConfig
        mainClass.set("cuchaz.enigma.gui.Main")

        args(
            "-jar",
            genIntermediaryJar.get().outputs.files.singleFile.absolutePath,
            "-mappings",
            mappings.absolutePath,
            "-profile",
            "enigma.json",
        )

        jvmArgs(
            "-Xmx2G",
        )

        doFirst {
            mappings.mkdirs()
        }
    }
}
