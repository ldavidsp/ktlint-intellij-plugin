package com.nbadal.ktlint

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import com.pinterest.ktlint.core.KtLint
import com.pinterest.ktlint.core.LintError
import com.pinterest.ktlint.core.ParseException
import com.pinterest.ktlint.core.RuleSet
import com.pinterest.ktlint.core.RuleSetProvider
import com.pinterest.ktlint.internal.containsLintError
import com.pinterest.ktlint.internal.loadBaseline
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

internal fun doLint(
    file: PsiFile,
    config: KtlintConfigStorage,
    format: Boolean
): LintResult {
    val userData = listOfNotNull(
        "android" to config.androidMode.toString(),
        // Skip entry if empty, so we don't overwrite the .editorconfig
        config.disabledRules
            .let { if (it.isNotEmpty()) ("disabled_rules" to it.joinToString(",")) else null },
    ).toMap()

    var fileName = file.virtualFile.name
    // KtLint wants the full file path in order to search for .editorconfig files
    // Attempt to get the real file path:
    file.viewProvider.document?.let { doc ->
        FileDocumentManager.getInstance().getFile(doc)?.let { file ->
            fileName = file.path
        }
    }

    if (fileName == "/fragment.kt") {
        return emptyLintResult()
    }

    // Get relative path, if possible.
    var projectRelativePath = fileName
    file.project.basePath?.let { projectRelativePath = fileName.removePrefix(it).removePrefix("/") }
    val baselineErrors = config.baselinePath?.let { loadBaseline(it).baselineRules?.get(projectRelativePath) } ?: emptyList()

    val correctedErrors = mutableListOf<LintError>()
    val uncorrectedErrors = mutableListOf<LintError>()
    val ignoredErrors = mutableListOf<LintError>()

    val params = KtLint.Params(
        fileName = fileName,
        text = file.text,
        ruleSets = findRulesets(config.externalJarPaths, config.useExperimental),
        userData = userData,
        script = !fileName.endsWith(".kt", ignoreCase = true),
        editorConfigPath = config.editorConfigPath,
        debug = false,
        cb = { lintError, corrected ->
            if (corrected) {
                correctedErrors.add(lintError)
            } else if (!baselineErrors.containsLintError(lintError)) {
                uncorrectedErrors.add(lintError)
            } else {
                ignoredErrors.add(lintError)
            }
        },
    )

    // Clear editorconfig cache. (ideally, we could do this if .editorconfig files were changed)
    KtLint.trimMemory()

    try {
        if (format) {
            val results = KtLint.format(params)
            WriteCommandAction.runWriteCommandAction(file.project) {
                file.viewProvider.document?.setText(results)
            }
        } else {
            KtLint.lint(params)
        }
    } catch (pe: ParseException) {
        // TODO: report to rollbar?
        return emptyLintResult()
    }

    return LintResult(correctedErrors, uncorrectedErrors, ignoredErrors)
}

fun RuleSet.ids() = rules.map { rule -> if (id == "standard") rule.id else "$id:${rule.id}" }

fun getAllRules(config: KtlintConfigStorage) = findRulesets(config.externalJarPaths, config.useExperimental)
    .fold(mutableListOf<String>()) { allRules, ruleSet -> allRules.apply { addAll(ruleSet.ids()) } }
    .toList()

private fun findRulesets(paths: List<String>, experimental: Boolean) = ServiceLoader
    .load(
        RuleSetProvider::class.java,
        URLClassLoader(
            externalRulesetArray(paths),
            RuleSetProvider::class.java.classLoader
        )
    )
    .associateBy {
        val key = it.get().id
        if (key == "standard") "\u0000$key" else key
    }
    .filterKeys { experimental || it != "experimental" }
    .toSortedMap()
    .map { it.value.get() }

private fun externalRulesetArray(paths: List<String>) = paths
    .map { it.replaceFirst(Regex("^~"), System.getProperty("user.home")) }
    .map { File(it) }
    .filter { it.exists() }
    .map { it.toURI().toURL() }
    .toTypedArray()

data class LintResult(
    val correctedErrors: List<LintError>,
    val uncorrectedErrors: List<LintError>,
    val ignoredErrors: List<LintError>,
)

fun emptyLintResult() = LintResult(emptyList(), emptyList(), emptyList())
