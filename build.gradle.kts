import net.fabricmc.stitch.commands.tinyv2.*
import java.net.URL

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

enum class EnvType {
    CLIENT,
    SERVER,
    BOTH
}

val minecraftVersion = "b1.7.3"
val officialVersion = "b1.7.3"
val env = EnvType.CLIENT

version = if (System.getenv("BUILD_NUMBER") != null) {
    "$minecraftVersion.b${System.getenv("BUILD_NUMBER")}-${env.toString().toLowerCase()}"
} else {
    "$minecraftVersion-${env.toString().toLowerCase()}"
}

group = "org.duvetmc"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://maven.quiltmc.org/repository/snapshot/")
    maven("https://libraries.minecraft.net/")
    maven("https://maven.concern.i.ng/releases/")
}

val enigmaConfig by configurations.creating

dependencies {
    enigmaConfig("org.quiltmc:enigma-swing:1.5.1+local")
    enigmaConfig("org.quiltmc:enigma-server:1.5.1+local")
    enigmaConfig("org.quiltmc:quilt-enigma-plugin:1.2.1")
    enigmaConfig("net.fabricmc:name-proposal:0.1.4")
    listOf(
        "asm",
        "asm-tree",
        "asm-commons",
        "asm-util",
    ).forEach { enigmaConfig("org.ow2.asm:$it:9.4") }
}

fun download(path: String, output: File) {
    val url = URL(path)
    url.openStream().use { input ->
        output.outputStream().use { output ->
            input.copyTo(output)
        }
    }
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

        onlyIf { !outputFile.exists() }

        doLast {
            download("https://raw.githubusercontent.com/DuvetMC/old-intermediaries/master/intermediaries/$officialVersion.tiny", outputFile)
        }
    }

    val downloadMinecraft by registering {
        group = mappingsGroup
        val outputFile = file("$mcJarsDir/$minecraftVersion-official.jar")
        outputs.file(outputFile)

        onlyIf { !outputFile.exists() }

        doLast {
            val jsonUrl = URL("https://skyrising.github.io/mc-versions/version/$officialVersion.json")
            val json = jsonUrl.readText()
            // why parse json when you can just substring
            val clientUrl = if ("\"client\": {" in json) json.substringAfter("\"client\":").substringAfter("\"url\":").substringAfter("\"").substringBefore("\"") else null
            val serverUrl = if ("\"server\": {" in json) json.substringAfter("\"server\":").substringAfter("\"url\":").substringAfter("\"").substringBefore("\"") else null

            if (clientUrl != null && env == EnvType.CLIENT) {
                download(clientUrl, outputFile)
            } else if (serverUrl != null && env == EnvType.SERVER) {
                download(serverUrl, outputFile)
            } else if (clientUrl != null && serverUrl != null && env == EnvType.BOTH) {
                val clientFile = file("$mcJarsDir/$minecraftVersion-official-client.jar")
                val serverFile = file("$mcJarsDir/$minecraftVersion-official-server.jar")
                download(clientUrl, clientFile)
                download(serverUrl, serverFile)

                net.fabricmc.stitch.merge.JarMerger(clientFile, serverFile, outputFile).use {
                    it.merge()
                }
            } else {
                throw IllegalStateException("No client/server jar found for $minecraftVersion for env $env")
            }
        }
    }

    val genV2Intermediary by registering {
        group = mappingsGroup
        dependsOn(downloadIntermediary)

        val outputFile = file("$mappingsDir/$minecraftVersion-intermediary-v2.tiny")
        outputs.file(outputFile)

        onlyIf { !outputFile.exists() }

        doLast {
            val mappings = file("$mappingsDir/$minecraftVersion-intermediary.tiny")

            cuchaz.enigma.command.ConvertMappingsCommand.run(
                "tiny",
                mappings.toPath(),
                "tinyv2:official:intermediary",
                outputFile.toPath()
            )
        }
    }

    val genIntermediaryJar by registering {
        group = mappingsGroup
        dependsOn(downloadIntermediary, downloadMinecraft)

        val outputJar = file("$mcJarsDir/$minecraftVersion-intermediary.jar")
        outputs.file(outputJar)

        onlyIf { !outputJar.exists() }

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
    
    val enigmaCli by registering(JavaExec::class) {
        group = mappingsGroup
        dependsOn(genIntermediaryJar)
        
        val mappings = file("mappings/")
        
        classpath = enigmaConfig
        mainClass.set("cuchaz.enigma.network.DedicatedEnigmaServer")
        
        args(
            "-jar",
            genIntermediaryJar.get().outputs.files.singleFile.absolutePath,
            "-mappings",
            mappings.absolutePath,
            "-profile",
            "enigma.json",
            "-port",
            System.getenv("ENIGMA_PORT") ?: "34712",
            "-password",
            System.getenv("ENIGMA_PASSWORD") ?: "",
            "-log",
            System.getenv("ENIGMA_LOG_FILE") ?: "build/log.txt"
        )
    }

    val genMappings by registering {
        group = mappingsGroup

        val outputFile = file("$mappingsDir/$minecraftVersion-named.tiny")
        outputs.file(outputFile)

        onlyIf { !outputFile.exists() }

        doLast {
            val mappingsDir = file("mappings/")

            cuchaz.enigma.command.ConvertMappingsCommand.run(
                "enigma",
                mappingsDir.toPath(),
                "tinyv2:intermediary:named",
                outputFile.toPath()
            )
        }
    }

    val genV1Mappings by registering {
        group = mappingsGroup
        dependsOn(genMappings)

        val outputFile = file("$mappingsDir/$minecraftVersion-named-v1.tiny")
        outputs.file(outputFile)

        onlyIf { !outputFile.exists() }

        doLast {
            val mappings = file("$mappingsDir/$minecraftVersion-named.tiny")

            cuchaz.enigma.command.ConvertMappingsCommand.run(
                "tinyv2",
                mappings.toPath(),
                "tiny:intermediary:named",
                outputFile.toPath()
            )
        }
    }

    val genMappingsJar by registering(Jar::class) {
        group = mappingsGroup
        dependsOn(genMappings)

        archiveClassifier.set("named-v2")
        from(genMappings.get().outputs.files.singleFile) {
            rename { "mappings/mappings.tiny" }
        }
    }

    val genCombinedMappings by registering {
        group = mappingsGroup
        dependsOn(genMappings, genV2Intermediary)

        val outputFile = file("$mappingsDir/$minecraftVersion-combined.tiny")
        outputs.file(outputFile)

        onlyIf { !outputFile.exists() }

        doLast {
            val intermediaries = genV2Intermediary.get().outputs.files.singleFile
            val named = genMappings.get().outputs.files.singleFile

            val temp = file("$buildDir/temp")
            temp.mkdirs()

            val invertedIntermediaries = file("$temp/inverted-intermediaries.tiny")
            val unordered = file("$temp/unordered.tiny")

            CommandReorderTinyV2().run(arrayOf(
                intermediaries.absolutePath,
                invertedIntermediaries.absolutePath,
                "intermediary",
                "official",
            ))

            CommandMergeTinyV2().run(arrayOf(
                invertedIntermediaries.absolutePath,
                named.absolutePath,
                unordered.absolutePath,
            ))

            CommandReorderTinyV2().run(arrayOf(
                unordered.absolutePath,
                outputFile.absolutePath,
                "official",
                "intermediary",
                "named",
            ))
        }
    }

    val genCombinedMappingsJar by registering(Jar::class) {
        group = mappingsGroup
        dependsOn(genCombinedMappings)

        archiveClassifier.set("combined-v2")
        from(genCombinedMappings.get().outputs.files.singleFile) {
            rename { "mappings/mappings.tiny" }
        }
    }
    
    val genLoomIntermediaryJar by registering(Jar::class) {
        // Loom needs Intermediary to be in a jar using TinyV2, but Stitch pretty much can only
        // generate TinyV1 intermediaries. So we have to convert it to TinyV2.

        group = mappingsGroup
        dependsOn(genV2Intermediary)

        archiveClassifier.set("intermediary-v2")
        from(genV2Intermediary.get().outputs.files.singleFile) {
            rename { "mappings/mappings.tiny" }
        }
    }
    
    val genMappedJar by registering {
        group = mappingsGroup
        dependsOn(genMappings, genIntermediaryJar)

        val outputFile = file("$mcJarsDir/$minecraftVersion-named.jar")
        outputs.file(outputFile)

        onlyIf { !outputFile.exists() }

        doLast {
            val mappings = file("$mappingsDir/$minecraftVersion-named.tiny")
            val inputJar = file("$mcJarsDir/$minecraftVersion-intermediary.jar")

            cuchaz.enigma.command.DeobfuscateCommand.run(
                inputJar.toPath(),
                outputFile.toPath(),
                mappings.toPath(),
            )
        }
    }

    val genFakeNamedJar by registering(Jar::class) {
        group = mappingsGroup
        dependsOn(genV2Intermediary)

        archiveClassifier.set("fake-in-order-to-satisfy-loom")
        val inputFile = file("$mappingsDir/intermediary-as-named.tiny")
        from(inputFile) {
            rename { "mappings/mappings.tiny" }
        }

        doFirst { 
            val mappings = genV2Intermediary.get().outputs.files.singleFile
            inputFile.parentFile.mkdirs()
            inputFile.writeText(buildString {
                for (line in mappings.readLines()) {
                    if (isEmpty()) {
                        appendLine("tiny\t2\t0\tintermediary\tnamed")
                    } else {
                        appendLine(line)
                    }
                }
            })
        }
    }

    assemble {
        dependsOn(
            genIntermediaryJar,
            genMappingsJar,
            genCombinedMappingsJar,
            genLoomIntermediaryJar,
            genMappedJar,
            genFakeNamedJar,
        )
    }
    
    jar {
        enabled = false
    }
}

publishing {
    publications {
        create<MavenPublication>("mappings") {
            artifactId = "mappings"

            artifact(tasks["genMappingsJar"])
            artifact(tasks["genCombinedMappingsJar"])
        }

        create<MavenPublication>("intermediary") {
            artifactId = "intermediary"
            version = "$minecraftVersion-${env.toString().toLowerCase()}"

            artifact(tasks["genLoomIntermediaryJar"]) {
                classifier = "v2" // remove redundant "intermediary" part
            }
        }
    }

    repositories {
        mavenLocal()

        val url = System.getenv("MAVEN_URL")
        if (url != null) {
            maven(url = url) {
                if (url.startsWith("file://")) return@maven // no credentials for local repos
                credentials {
                    username = System.getenv("MAVEN_USERNAME") ?: System.getenv("MAVEN_USER")
                    password = System.getenv("MAVEN_PASSWORD") ?: System.getenv("MAVEN_PASS")
                }
            }
        }
    }
}

tasks.withType<PublishToMavenRepository> {
    onlyIf { System.getProperty("intermediary.publish") == "true" || "intermediary" !in it.name }
}
