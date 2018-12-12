/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("UnstableApiUsage")

package org.jetbrains.kotlin.gradle.scripting.internal

import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.transform.ArtifactTransform
import org.gradle.api.attributes.Attribute
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.gradle.internal.KaptGenerateStubsTask
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.scripting.ScriptingExtension
import org.jetbrains.kotlin.gradle.tasks.GradleMessageCollector
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptDefinitionsFromClasspathDiscoverySource
import java.io.File
import javax.inject.Inject

class ScriptingGradleSubplugin : Plugin<Project> {
    companion object {
        fun isEnabled(project: Project) = project.plugins.findPlugin(ScriptingGradleSubplugin::class.java) != null

        val MAIN_CONFIGURATION_NAME = "kotlinScriptDef"
        val RESULTS_CONFIGURATION_SUFFIX = "Extensions"

        fun getConfigurationName(sourceSetName: String): String = when (sourceSetName) {
            "main" -> MAIN_CONFIGURATION_NAME
            else -> "$sourceSetName${MAIN_CONFIGURATION_NAME.capitalize()}"
        }

        fun createDiscoveryConfigurationIfNeeded(project: Project, sourceSetName: String) {
            project.configurations.maybeCreate(getConfigurationName(sourceSetName))
        }
    }

    override fun apply(project: Project) {

        project.afterEvaluate {

            val javaPluginConvention = project.convention.findPlugin(JavaPluginConvention::class.java)
            if (javaPluginConvention?.sourceSets?.isEmpty() == false) {


                project.tasks.withType(KotlinCompile::class.java) { task ->

                    val discoveryConfigurationName =
                        project.configurations.findByName(getConfigurationName(task.sourceSetName))

                    if (discoveryConfigurationName != null && task !is KaptGenerateStubsTask) {
                        javaPluginConvention.sourceSets.findByName(task.sourceSetName)?.let { sourceSet ->

                            val discoveryResultsConfigurationName = getConfigurationName(task.sourceSetName) + RESULTS_CONFIGURATION_SUFFIX
                            project.configurations.create(discoveryResultsConfigurationName)
                            project.dependencies.apply {
                                add(
                                    discoveryResultsConfigurationName,
                                    project.withRegisteredDiscoverScriptExtensionsTransform {
                                        discoveryConfigurationName.resolveDiscoverScriptExtensionsFiles()
                                    }
                                )
                            }

                            val kotlinSourceSet = sourceSet.getConvention(KOTLIN_DSL_NAME) as? KotlinSourceSet
                            if (kotlinSourceSet == null) {
                                project.logger.warn("kotlin scripting plugin: kotlin source set not found: $project.$sourceSet")
                            } else {
                                val extensions by lazy {
                                    val cfg = project.configurations.findByName(discoveryResultsConfigurationName)
                                    if (cfg == null) {
                                        project.logger.warn("kotlin scripting plugin: discovery results not found: $project.$discoveryResultsConfigurationName")
                                        emptyList()
                                    } else {
                                        cfg.files.flatMap {
                                            it.readLines().map { ext -> ".$ext" }
                                        }.also {
                                            project.logger.info("kotlin scripting plugin: $project.$sourceSet: discovered script extensions: $it")
                                        }
                                    }
                                }
                                kotlinSourceSet.kotlin.filter.include { fte -> extensions.any { ext -> fte.name.endsWith(ext) } }
                            }
                        }
                    }
                }
            } else {
                project.logger.warn("kotlin scripting plugin: applied to a non-JVM project $project")
            }
        }
    }
}

internal class DiscoverScriptExtensionsTransform /*@Inject constructor(private val project: Project)*/ : ArtifactTransform() {

    override fun transform(input: File): List<File> {
        val definitions =
            ScriptDefinitionsFromClasspathDiscoverySource(
                listOf(input), emptyMap(),
//                GradleMessageCollector(project.logger)
                MessageCollector.NONE
            ).definitions
        val extensions = definitions.mapTo(arrayListOf()) { it.fileExtension }
        return if (extensions.isNotEmpty()) {
//            project.logger.info("kotlin scripting plugin: discovered script extensions: $extensions in the $input")
            val outputFile = outputDirectory.resolve("${input.nameWithoutExtension}.discoveredScriptsExtensions.txt")
            outputFile.writeText(extensions.joinToString("\n"))
            listOf(outputFile)
        } else emptyList()
    }
}

private
fun Project.registerDiscoverScriptExtensionsTransform() {
    dependencies.apply {
        registerTransform {
            with(it) {
                from.attribute(artifactType, "jar")
                to.attribute(artifactType, scriptFilesExtensions)
                artifactTransform(DiscoverScriptExtensionsTransform::class.java)
//                {
//                    it.params(project)
//                }
            }
        }
    }
}

private
fun <T> Project.withRegisteredDiscoverScriptExtensionsTransform(block: () -> T): T {
    if (!project.extensions.extraProperties.has("DiscoverScriptExtensionsTransform")) {
        registerDiscoverScriptExtensionsTransform()
        project.extensions.extraProperties["DiscoverScriptExtensionsTransform"] = true
    }
    return block()
}

private val artifactType = Attribute.of("artifactType", String::class.java)

private val scriptFilesExtensions = "script-files-extensions"

private
fun Configuration.resolveDiscoverScriptExtensionsFiles() =
    incoming.artifactView {
        attributes {
            it.attribute(artifactType, scriptFilesExtensions)
        }
    }.artifacts.artifactFiles


class ScriptingKotlinGradleSubplugin : KotlinGradleSubplugin<AbstractCompile> {
    companion object {
        const val SCRIPTING_ARTIFACT_NAME = "kotlin-scripting-compiler-embeddable"

        val SCRIPT_DEFINITIONS_OPTION = "script-definitions"
        val SCRIPT_DEFINITIONS_CLASSPATH_OPTION = "script-definitions-classpath"
        val DISABLE_SCRIPT_DEFINITIONS_FROM_CLSSPATH_OPTION = "disable-script-definitions-from-classpath"
        val LEGACY_SCRIPT_RESOLVER_ENVIRONMENT_OPTION = "script-resolver-environment"
    }

    override fun isApplicable(project: Project, task: AbstractCompile) =
        ScriptingGradleSubplugin.isEnabled(project)

    override fun apply(
        project: Project,
        kotlinCompile: AbstractCompile,
        javaCompile: AbstractCompile?,
        variantData: Any?,
        androidProjectHandler: Any?,
        kotlinCompilation: KotlinCompilation<*>?
    ): List<SubpluginOption> {
        if (!ScriptingGradleSubplugin.isEnabled(project)) return emptyList()

        val scriptingExtension = project.extensions.findByType(ScriptingExtension::class.java)
            ?: project.extensions.create("kotlinScripting", ScriptingExtension::class.java)

        val options = mutableListOf<SubpluginOption>()

        for (scriptDef in scriptingExtension.myScriptDefinitions) {
            options += SubpluginOption(SCRIPT_DEFINITIONS_OPTION, scriptDef)
        }
        for (path in scriptingExtension.myScriptDefinitionsClasspath) {
            options += SubpluginOption(SCRIPT_DEFINITIONS_CLASSPATH_OPTION, path)
        }
        if (scriptingExtension.myDisableScriptDefinitionsFromClasspath) {
            options += SubpluginOption(DISABLE_SCRIPT_DEFINITIONS_FROM_CLSSPATH_OPTION, "true")
        }
        for (pair in scriptingExtension.myScriptResolverEnvironment) {
            options += SubpluginOption(LEGACY_SCRIPT_RESOLVER_ENVIRONMENT_OPTION, "${pair.key}=${pair.value}")
        }

        return options
    }

    override fun getCompilerPluginId() = "kotlin.scripting"
    override fun getPluginArtifact(): SubpluginArtifact =
        JetBrainsSubpluginArtifact(artifactId = SCRIPTING_ARTIFACT_NAME)
}
