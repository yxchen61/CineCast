package com.cinecast.app.novel.engine

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URI

/**
 * 轻量级 Legado 书源规则求值引擎
 * 支持：
 * 1. JSONPath 与模板替换（$.data / $[*] / /api/book/{{$.book_id}}）
 * 2. Jsoup CSS 与 Legado 链式选择器（class.xx.0@tag.a.-1@href、id.list.0@tag.a、@css:...）
 * 3. 常见 XPath 语法转换（//meta[@property='og:novel:author']/@content 等）
 * 4. 逻辑组合（|| 回退、&& 拼接）与正则清洗（##pattern##replacement）
 */
object NovelRuleEngine {

    private val templateRegex = Regex("""\{\{([^}]+)\}\}""")

    // ==================== JSONPath 求值 ====================

    fun selectJsonList(root: Any?, rule: String): List<Any> {
        if (root == null || rule.isBlank()) return emptyList()
        val expr = rule.trim().substringBefore("##").trim()
        val value = evalJsonPath(root, expr)
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
            is List<*> -> value.filterNotNull()
            is JSONObject -> listOf(value)
            else -> emptyList()
        }
    }

    fun evalJsonString(node: Any?, rule: String): String {
        if (node == null || rule.isBlank()) return ""
        val (baseRule, regexPattern, replacement) = splitRegexSuffix(rule)
        val raw = if (baseRule.contains("{{")) {
            templateRegex.replace(baseRule) { m ->
                val inner = m.groupValues[1].trim()
                evalJsonPath(node, inner)?.toString().orEmpty()
            }
        } else {
            // Support || fallback
            val branches = baseRule.split("||")
            var hit = ""
            for (b in branches) {
                val parts = b.split("&&").map { evalJsonPath(node, it.trim())?.toString().orEmpty().trim() }
                    .filter { it.isNotBlank() }
                if (parts.isNotEmpty()) {
                    hit = parts.joinToString(" · ")
                    break
                }
            }
            hit
        }
        return applyRegex(raw, regexPattern, replacement).trim()
    }

    fun evalJsonPath(root: Any?, path: String): Any? {
        if (root == null) return null
        val p = path.trim()
        if (p == "$" || p == "$[*]" || p.isBlank()) return root
        val clean = p.removePrefix("$.").removePrefix("$").removePrefix(".")
        if (clean.isBlank() || clean == "[*]") return root

        var cur: Any? = root
        val segments = clean.split(".")
        for (seg in segments) {
            if (cur == null) return null
            val arrayMatch = Regex("""^([^\[]*)\[(\d+|\*)\]$""").find(seg)
            if (arrayMatch != null) {
                val key = arrayMatch.groupValues[1]
                val idxStr = arrayMatch.groupValues[2]
                if (key.isNotBlank()) {
                    cur = (cur as? JSONObject)?.opt(key)
                }
                if (cur is JSONArray) {
                    cur = if (idxStr == "*") cur else cur.opt(idxStr.toIntOrNull() ?: 0)
                }
            } else {
                cur = when (cur) {
                    is JSONObject -> cur.opt(seg)
                    is JSONArray -> cur.opt(seg.toIntOrNull() ?: 0)
                    else -> null
                }
            }
        }
        return if (cur == JSONObject.NULL) null else cur
    }

    // ==================== HTML / Legado CSS 求值 ====================

    fun selectElements(root: Element, rule: String): List<Element> {
        val trimmed = rule.trim()
        if (trimmed.isBlank()) return emptyList()

        // Support || fallback
        val branches = trimmed.split("||")
        for (branch in branches) {
            val res = selectElementsSingle(root, branch.trim())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun selectElementsSingle(root: Element, rule: String): List<Element> {
        if (rule.isBlank()) return emptyList()
        if (rule.startsWith("@css:")) {
            val css = rule.removePrefix("@css:").substringBefore("@").trim()
            return runCatching { root.select(css).toList() }.getOrDefault(emptyList())
        }
        if (rule.startsWith("//")) {
            return selectBySimpleXPath(root, rule)
        }

        val steps = rule.split("@").map { it.trim() }.filter { it.isNotBlank() }
        var current: List<Element> = listOf(root)

        for (step in steps) {
            if (current.isEmpty()) break
            // Stop if step is an attribute/text extractor
            if (isTerminalExtractor(step)) break

            val next = mutableListOf<Element>()
            for (el in current) {
                next.addAll(applyElementStep(el, step))
            }
            current = next
        }
        return current
    }

    fun evalHtmlString(root: Element, rule: String, baseUrl: String = ""): String {
        val trimmed = rule.trim()
        if (trimmed.isBlank()) return ""

        // Handle {{@@...}} template syntax used in some GitHub sources
        if (trimmed.contains("{{@@")) {
            val (baseTpl, regexPat, repl) = splitRegexSuffix(trimmed)
            val replaced = templateRegex.replace(baseTpl) { m ->
                val inner = m.groupValues[1].trim().removePrefix("@@").trim()
                if (inner.startsWith("'") && inner.endsWith("'")) ""
                else evalHtmlString(root, inner, baseUrl)
            }
            return applyRegex(replaced, regexPat, repl).trim()
        }

        val (baseRule, regexPattern, replacement) = splitRegexSuffix(trimmed)

        // Handle || fallback
        val orBranches = baseRule.split("||")
        for (branch in orBranches) {
            // Handle && concatenation
            val andParts = branch.split("&&")
                .map { evalHtmlSingle(root, it.trim(), baseUrl).trim() }
                .filter { it.isNotBlank() }
            if (andParts.isNotEmpty()) {
                val joined = andParts.joinToString(" · ")
                return applyRegex(joined, regexPattern, replacement).trim()
            }
        }
        return ""
    }

    private fun evalHtmlSingle(root: Element, rule: String, baseUrl: String): String {
        if (rule.isBlank()) return ""

        // XPath-style rules like //meta[@property='og:novel:author']/@content
        if (rule.startsWith("//")) {
            val attrSplit = rule.lastIndexOf("/@")
            val (xpathNode, attrName) = if (attrSplit > 0) {
                rule.substring(0, attrSplit) to rule.substring(attrSplit + 2)
            } else {
                rule to "text"
            }
            val els = selectBySimpleXPath(root, xpathNode)
            val first = els.firstOrNull() ?: return ""
            return extractFromElements(listOf(first), attrName, baseUrl)
        }

        // @css: selector with trailing @extractor
        if (rule.startsWith("@css:")) {
            val body = rule.removePrefix("@css:")
            val lastAt = body.lastIndexOf("@")
            val (css, ext) = if (lastAt > 0 && isTerminalExtractor(body.substring(lastAt + 1))) {
                body.substring(0, lastAt).trim() to body.substring(lastAt + 1).trim()
            } else {
                body.trim() to "text"
            }
            val els = runCatching { root.select(css).toList() }.getOrDefault(emptyList())
            return extractFromElements(els, ext, baseUrl)
        }

        // Pure terminal extractor directly on root (e.g., "text", "href", "src")
        if (isTerminalExtractor(rule)) {
            return extractFromElements(listOf(root), rule, baseUrl)
        }

        val steps = rule.split("@").map { it.trim() }.filter { it.isNotBlank() }
        if (steps.isEmpty()) return ""

        val lastIsExtractor = isTerminalExtractor(steps.last())
        val selectorSteps = if (lastIsExtractor) steps.dropLast(1) else steps
        val extractor = if (lastIsExtractor) steps.last() else "text"

        var current: List<Element> = listOf(root)
        for (step in selectorSteps) {
            if (current.isEmpty()) break
            val next = mutableListOf<Element>()
            for (el in current) {
                next.addAll(applyElementStep(el, step))
            }
            current = next
        }
        return extractFromElements(current, extractor, baseUrl)
    }

    private fun applyElementStep(el: Element, rawStep: String): List<Element> {
        var step = rawStep.trim()
        if (step.isBlank()) return listOf(el)

        // Handle slice suffix like [0:1] or [-1:0] (reverse)
        var sliceSpec: String? = null
        val sliceMatch = Regex("""\[(-?\d*:-?\d*)\]$""").find(step)
        if (sliceMatch != null) {
            sliceSpec = sliceMatch.groupValues[1]
            step = step.substring(0, sliceMatch.range.first)
        }

        // Handle exclude index like !0 or !-1 (e.g. tr!0 or tag.p.!-1)
        var excludeIndex: Int? = null
        val exclMatch = Regex("""\.?!(-?\d+)$""").find(step)
        if (exclMatch != null) {
            excludeIndex = exclMatch.groupValues[1].toIntOrNull()
            step = step.substring(0, exclMatch.range.first)
        }

        // Handle children selector
        if (step == "children") {
            return applySliceAndExclude(el.children().toList(), sliceSpec, excludeIndex)
        }

        // Handle Legado shorthand: class.foo.0 / id.bar.0 / tag.a.-1 / text.下一页.0
        val matched: List<Element> = when {
            step.startsWith("class.") -> {
                val parts = step.removePrefix("class.").split(".")
                val cls = parts[0].trim().replace(" ", ".")
                val idx = parts.getOrNull(1)?.toIntOrNull()
                val found = runCatching { el.select(".$cls").toList() }.getOrDefault(emptyList())
                pickIndex(found, idx)
            }
            step.startsWith("id.") -> {
                val parts = step.removePrefix("id.").split(".")
                val id = parts[0].trim()
                val idx = parts.getOrNull(1)?.toIntOrNull()
                val found = runCatching { el.select("#$id").toList() }.getOrDefault(emptyList())
                pickIndex(found, idx)
            }
            step.startsWith("tag.") -> {
                val parts = step.removePrefix("tag.").split(".")
                val tag = parts[0].trim()
                val idx = parts.getOrNull(1)?.toIntOrNull()
                val found = runCatching { el.select(tag).toList() }.getOrDefault(emptyList())
                pickIndex(found, idx)
            }
            step.startsWith("text.") -> {
                val rest = step.removePrefix("text.")
                val dotIdx = rest.lastIndexOf(".")
                val (needle, idx) = if (dotIdx > 0 && rest.substring(dotIdx + 1).toIntOrNull() != null) {
                    rest.substring(0, dotIdx) to rest.substring(dotIdx + 1).toIntOrNull()
                } else {
                    rest to null
                }
                val found = el.select("a, button, span, li").filter { it.text().contains(needle) }
                pickIndex(found, idx)
            }
            else -> {
                // Standard CSS with optional trailing numeric index (e.g. "a.0", ".odd.1", "div.card-other.1", "p.-1")
                val idxMatch = Regex("""^(.*)\.(-?\d+)$""").find(step)
                val (css, idx) = if (idxMatch != null) {
                    idxMatch.groupValues[1] to idxMatch.groupValues[2].toIntOrNull()
                } else {
                    step to null
                }
                val found = runCatching { el.select(css).toList() }.getOrDefault(emptyList())
                pickIndex(found, idx)
            }
        }

        return applySliceAndExclude(matched, sliceSpec, excludeIndex)
    }

    private fun pickIndex(list: List<Element>, idx: Int?): List<Element> {
        if (idx == null) return list
        if (list.isEmpty()) return emptyList()
        val actual = if (idx < 0) list.size + idx else idx
        return list.getOrNull(actual)?.let { listOf(it) } ?: emptyList()
    }

    private fun applySliceAndExclude(
        list: List<Element>,
        sliceSpec: String?,
        excludeIndex: Int?
    ): List<Element> {
        var res = list
        if (excludeIndex != null && res.isNotEmpty()) {
            val actual = if (excludeIndex < 0) res.size + excludeIndex else excludeIndex
            res = res.filterIndexed { index, _ -> index != actual }
        }
        if (sliceSpec != null && res.isNotEmpty()) {
            val parts = sliceSpec.split(":")
            val start = parts.getOrNull(0)?.toIntOrNull()
            val end = parts.getOrNull(1)?.toIntOrNull()
            if (start == -1 && end == 0) {
                return res.reversed()
            }
            val s = (start ?: 0).let { if (it < 0) (res.size + it).coerceAtLeast(0) else it.coerceAtMost(res.size) }
            val e = (end ?: (res.size - 1)).let { if (it < 0) (res.size + it) else it }.coerceAtMost(res.size - 1)
            if (s <= e && s in res.indices) {
                res = res.subList(s, e + 1)
            }
        }
        return res
    }

    private fun selectBySimpleXPath(root: Element, xpath: String): List<Element> {
        // Common meta pattern: //meta[@property='og:novel:author'] or //meta[@name='...']
        val metaMatch = Regex("""//meta\[@(property|name)=['"]([^'"]+)['"]\]""").find(xpath)
        if (metaMatch != null) {
            val attr = metaMatch.groupValues[1]
            val value = metaMatch.groupValues[2]
            return root.select("meta[$attr=$value]").toList()
        }
        // Pattern: //div[@id="list"]/dl/dt[last()]/following-sibling::dd/a
        val followingMatch = Regex("""//(\w+)\[@(id|class)=['"]([^'"]+)['"]\].*following-sibling::(\w+)(?:/(\w+))?""").find(xpath)
        if (followingMatch != null) {
            val attr = followingMatch.groupValues[2]
            val valStr = followingMatch.groupValues[3]
            val siblingTag = followingMatch.groupValues[4]
            val childTag = followingMatch.groupValues[5]
            val containerSel = if (attr == "id") "#$valStr" else ".$valStr"
            val container = root.selectFirst(containerSel) ?: return emptyList()
            val dts = container.select("dt")
            val lastDt = dts.lastOrNull()
            val collected = mutableListOf<Element>()
            if (lastDt != null) {
                var sib = lastDt.nextElementSibling()
                while (sib != null) {
                    if (sib.tagName().equals(siblingTag, ignoreCase = true)) {
                        if (childTag.isNotBlank()) collected.addAll(sib.select(childTag))
                        else collected.add(sib)
                    }
                    sib = sib.nextElementSibling()
                }
            }
            if (collected.isNotEmpty()) return collected
            return container.select(if (childTag.isNotBlank()) "$siblingTag $childTag" else siblingTag).toList()
        }
        // Pattern: //div[@class='prenext']/span/a[text()='下一页']
        val textEqMatch = Regex("""//\w+\[@(id|class)=['"]([^'"]+)['"]\].*text\(\)=['"]([^'"]+)['"]""").find(xpath)
        if (textEqMatch != null) {
            val attr = textEqMatch.groupValues[1]
            val valStr = textEqMatch.groupValues[2]
            val needle = textEqMatch.groupValues[3]
            val containerSel = if (attr == "id") "#$valStr" else ".$valStr"
            return root.select("$containerSel a").filter { it.text().trim() == needle }
        }
        // Generic fallback: //*[@class="MLlist"]/li/a
        val genericClass = Regex("""@class=['"]([^'"]+)['"]""").find(xpath)
        if (genericClass != null) {
            val cls = genericClass.groupValues[1]
            val tail = xpath.substringAfter("]").trim('/').replace("/", " ")
            return runCatching { root.select(".$cls $tail".trim()).toList() }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    private fun extractFromElements(elements: List<Element>, extractor: String, baseUrl: String): String {
        if (elements.isEmpty()) return ""
        return when (extractor.lowercase()) {
            "text" -> elements.map { it.text().trim() }.filter { it.isNotBlank() }.joinToString("\n")
            "owntext" -> elements.map { it.ownText().trim() }.filter { it.isNotBlank() }.joinToString("\n")
            "textnodes" -> elements.flatMap { el ->
                el.textNodes().map { it.text().trim() }.filter { it.isNotBlank() }
            }.joinToString("\n")
            "html" -> elements.joinToString("\n") { htmlToParagraphs(it.html()) }
            "href", "src", "data-src", "data-original" -> {
                val el = elements.first()
                val raw = el.attr(extractor).ifBlank {
                    if (extractor == "src") el.attr("data-src").ifBlank { el.attr("data-original") } else ""
                }
                resolveUrl(baseUrl, raw)
            }
            else -> elements.first().attr(extractor).ifBlank { elements.first().text() }
        }
    }

    private fun isTerminalExtractor(token: String): Boolean {
        return token.lowercase() in setOf(
            "text", "textnodes", "owntext", "html", "outerhtml",
            "href", "src", "data-src", "data-original", "content", "alt", "title", "value"
        )
    }

    fun htmlToParagraphs(html: String): String {
        if (html.isBlank()) return ""
        val cleaned = html
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</div>""", RegexOption.IGNORE_CASE), "\n")
        val doc: Document = Jsoup.parseBodyFragment(cleaned)
        doc.outputSettings(Document.OutputSettings().prettyPrint(false))
        val text = doc.body().wholeText()
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    fun splitRegexSuffix(rule: String): Triple<String, String?, String> {
        val idx = rule.indexOf("##")
        if (idx < 0) return Triple(rule, null, "")
        val base = rule.substring(0, idx).trim()
        val rest = rule.substring(idx + 2)
        val idx2 = rest.indexOf("##")
        return if (idx2 >= 0) {
            Triple(base, rest.substring(0, idx2), rest.substring(idx2 + 2))
        } else {
            Triple(base, rest, "")
        }
    }

    fun applyRegex(input: String, pattern: String?, replacement: String): String {
        if (pattern.isNullOrBlank()) return input
        return runCatching {
            Regex(pattern, setOf(RegexOption.MULTILINE)).replace(input, replacement)
        }.getOrDefault(input)
    }

    fun resolveUrl(baseUrl: String, relative: String): String {
        val rel = relative.trim()
        if (rel.isBlank()) return ""
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        if (rel.startsWith("//")) return "https:$rel"
        if (baseUrl.isBlank()) return rel
        return runCatching {
            URI(baseUrl).resolve(rel).toString()
        }.getOrElse {
            val base = baseUrl.trimEnd('/')
            if (rel.startsWith("/")) "$base$rel" else "$base/$rel"
        }
    }
}
