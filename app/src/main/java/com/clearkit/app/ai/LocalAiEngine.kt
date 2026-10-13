package com.clearkit.app.ai

import android.content.Context
import android.net.Uri
import com.clearkit.app.models.ModelManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Offline vision pipeline:
 *  1. If a GGUF model was loaded natively (LlamaBridge.nativeLoaded), ask it first.
 *  2. Otherwise use on-device ML Kit OCR (fully offline after first Play-Services
 *     module download) + deterministic paperwork analysis:
 *     "What is this, what do I do by when?"
 */
class LocalAiEngine(
    private val context: Context,
    private val models: ModelManager
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun ocr(uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        return try {
            recognizer.process(image).await().text ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** What was read from photos, per chat — so follow-up questions work without re-sending the picture. */
    private val ocrMemory = LinkedHashMap<String, String>()

    suspend fun answer(userText: String, imageUris: List<Uri>, mode: String, chatId: String): String {
        val modelName = models.activeModel()?.displayName ?: "no model"
        val hasImage = imageUris.isNotEmpty()
        // Read the text first — it grounds the vision model and powers the fallback.
        val fresh = imageUris.map { ocr(it) }.joinToString("\n---\n")
        if (fresh.isNotBlank()) {
            val prev = ocrMemory[chatId].orEmpty()
            ocrMemory[chatId] = ((if (prev.isNotBlank()) prev + "\n---\n" else "") + fresh).take(12000)
            if (ocrMemory.size > 30) ocrMemory.remove(ocrMemory.keys.first())
        }
        val ocr = (if (fresh.isNotBlank()) fresh else ocrMemory[chatId].orEmpty()).take(6000)
        // 1) Real vision-language turn on-device — but ONLY with a photo to look at.
        //    A vision model with no image just confabulates, so imageless questions
        //    go straight to the text reader / "send a photo" reply below.
        if (LlamaBridge.nativeLoaded && hasImage) {
            val localPath = copyToCache(imageUris.firstOrNull())
            val prompt = nativePrompt(userText, mode, ocr.take(1500))
            LlamaBridge.generate(prompt, localPath)?.let {
                // Docs have a strong deterministic fallback, so demand substance there:
                // tiny models sometimes quit after 3 words ("When? ANSWER: When.").
                val substantive = mode == "general" || wordCount(it) >= 10
                // Parroted question or runaway loop? Fall through to the text reader instead.
                if (!isEcho(it, userText) && !isDegenerate(it) && substantive) {
                    return it + "\n\n— $modelName (on-device)"
                }
            }
        }
        // 2) OCR + rules fallback — deterministic, and excellent at bills/forms.
        if (ocr.isBlank()) {
            return if (hasImage) {
                "I couldn't read this photo clearly — try a sharper, straight-on shot with good light, then ask again." +
                    if (mode == "general") " If it's a landmark or object, get closer first." else "" +
                    "\n\n— on-device ($modelName)."
            } else {
                needPhoto(userText, modelName)
            }
        }
        // Question about the picture -> answer it directly instead of the full template.
        if (userText.isNotBlank() && (fresh.isBlank() || looksLikeQuestion(userText))) {
            if (wantsSummary(userText)) return analyze(userText, ocr, mode, modelName)
            return answerQuestion(userText, ocr, modelName)
        }
        return analyze(userText, ocr, mode, modelName)
    }

    private fun copyToCache(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            val out = File(context.cacheDir, "images/${System.currentTimeMillis()}.jpg")
            out.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { ins.copyTo(it) }
            }
            out.absolutePath
        } catch (_: Exception) { null }
    }

    private fun nativePrompt(userText: String, mode: String, ocrText: String): String {
        val ground = if (ocrText.isNotBlank()) {
            " The photo's text reads (may be imperfect): \"\"\"$ocrText\"\"\". Use these exact amounts, dates and names."
        } else ""
        val instruction = "If the image shows a question, homework, or any text asking something, " +
            "ANSWER it directly using what you see. Never reply by repeating the question or the prompt. "
        if (userText.isBlank()) {
            return instruction + if (mode == "scan") {
                "Describe this document in this exact shape:$ground\nWHAT IT IS: one line. WHAT TO DO: numbered steps. BY WHEN: the deadline."
            } else "Describe this image in detail.$ground"
        }
        return when (mode) {
            "scan" -> "You are ClearKit, an offline paperwork helper. $instruction" +
                "Look at the image and answer in this exact shape:$ground\n" +
                "WHAT IT IS: one line. WHAT TO DO: numbered steps with the amounts, dates and names. " +
                "BY WHEN: the deadline. Question: $userText"
            "med" -> "You are ClearKit. $instruction" + "Answer simply and briefly$ground: $userText"
            "proof" -> "You are ClearKit. $instruction" +
                "Answer briefly, noting item condition and visible details$ground: $userText"
            else -> instruction + userText + ground
        }
    }

    private fun wordCount(t: String): Int = t.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** The tiny on-device models sometimes just parrot the question — catch that and use the reader instead. */
    private fun isEcho(response: String, question: String): Boolean {
        if (question.isBlank()) return false
        val qw = question.lowercase().split(Regex("[^a-zäöüß0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }.distinct()
        if (qw.isEmpty()) return false
        val rw = response.lowercase().split(Regex("[^a-zäöüß0-9]+")).toSet()
        val overlap = qw.count { it in rw }
        return overlap * 1f / qw.size >= 0.7f && rw.size <= qw.size + 8
    }

    private fun analyze(userText: String, ocr: String, mode: String, modelName: String): String {
        if (ocr.isBlank() && userText.isBlank()) return "I couldn't read anything. Try a brighter, straight-on photo."
        if (ocr.isBlank()) return needPhoto(userText, modelName)

        val dates = DATE_RES.findAll(ocr).map { it.value }.distinct().take(5).toList()
        val amounts = AMOUNT_RES.findAll(ocr).map { it.value }.distinct().take(5).toList()
        val refs = REF_RES.findAll(ocr).map { it.value }.distinct().take(5).toList()
        val title = guessTitle(ocr)
        val deadline = dates.firstOrNull() ?: "no explicit deadline found — check the letter for dates"
        val urgency = if (URGENT_RES.containsMatchIn(ocr)) "This looks time-sensitive (words like 'urgent', 'final notice', 'overdue' detected)." else "No urgent penalty wording detected, but confirm the deadline above."

        val header = when (mode) {
            "scan" -> "📄 **$title**"
            "med" -> "💊 **Medication / appointment note**"
            "proof" -> "📸 **Photo-proof logged**"
            else -> "📄 **$title**"
        }
        return buildString {
            appendLine(header)
            appendLine()
            appendLine("**What is this?** ${oneLineSummary(ocr, title)}")
            appendLine()
            appendLine("**What do I do?**")
            buildSteps(ocr, mode).forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            appendLine()
            appendLine("**By when?** $deadline")
            appendLine(urgency)
            if (dates.size > 1) appendLine("Other dates seen: ${dates.drop(1).joinToString(", ")}")
            if (amounts.isNotEmpty()) appendLine("Amounts seen: ${amounts.joinToString(", ")}")
            if (refs.isNotEmpty()) appendLine("References: ${refs.joinToString(", ")}")
            if (userText.isNotBlank()) appendLine("\nYour note: $userText")
            appendLine("\n---\n OCR transcript (first 800 chars):\n${ocr.take(800)}")
            appendLine("\n— analyzed on-device ($modelName session active). Not legal/medical advice.")
        }
    }

    private fun needPhoto(userText: String, modelName: String): String {
        return if (userText.isBlank()) {
            "Send a photo of the letter, prescription, or receipt and I'll read it offline — then you can ask me anything about it.\n\n— on-device ($modelName)."
        } else {
            "I don't have a document in this chat yet, so I can't answer that. Send a photo of it first, then ask me anything — amounts, deadlines, who sent it, what to do.\n\n— on-device ($modelName)."
        }
    }

    private fun looksLikeQuestion(t: String): Boolean {
        if ("?" in t) return true
        val first = t.trim().lowercase().split(Regex("\\s+")).firstOrNull() ?: return false
        return first in QUESTION_WORDS
    }

    private fun wantsSummary(t: String): Boolean {
        val l = t.lowercase()
        return SUMMARY_TRIGGERS.any { it in l }
    }

    /** Answer a specific follow-up question using the OCR text of the chat's photos. */
    private fun answerQuestion(question: String, ocr: String, modelName: String): String {
        val q = question.lowercase()
        val lines = ocr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        // Things the OCR reader genuinely cannot see (no real vision model bundled yet).
        if (VISUAL_WORDS.any { it in q }) {
            return "I can only read the *text* in your photos right now (on-device OCR) — I can't see colors, stamps, signatures, or layout. What I did read:\n\n" +
                keyFacts(ocr) +
                "\n\nAsk me about the text content — amounts, dates, names, what to do — and I'll answer from that."
        }
        // Yes/no questions get a verdict first, then the evidence — in that order.
        if (isYesNo(question)) {
            return answerYesNo(q, lines, ocr, modelName)
        }
        if (hasAny(q, AMOUNT_WORDS)) {
            val amounts = AMOUNT_RES.findAll(ocr).map { it.value }.distinct().toList()
            val context = matchingLines(lines, listOf("zahl", "pay", "betrag", "amount", "total", "due", "fällig", "summe", "eur", "€", "$", "£"))
            return if (amounts.isEmpty() && context.isEmpty())
                "I couldn't find any amount in this document. If it's handwritten or blurry, try a sharper, straight-on photo."
            else "Amounts I found:\n" + (if (amounts.isNotEmpty()) amounts.joinToString("\n") { "• $it" } else "") +
                (if (context.isNotEmpty()) "\n" + context.joinToString("\n") { "• $it" } else "") +
                "\n\n— on-device ($modelName)."
        }
        if (hasAny(q, DATE_WORDS)) {
            val dates = DATE_RES.findAll(ocr).map { it.value }.distinct().toList()
            val context = matchingLines(lines, listOf("frist", "termin", "fällig", "stichtag", "deadline", "due", "appointment", "datum", "date", "bis zum", "spätestens"))
            return if (dates.isEmpty() && context.isEmpty())
                "I couldn't find a date in this document — check whether one is handwritten or on the back of the page."
            else "Dates I found:\n" + (dates.joinToString("\n") { "• $it" }) +
                (if (context.isNotEmpty()) "\n" + context.joinToString("\n") { "• $it" } else "") +
                "\n\n— on-device ($modelName)."
        }
        if (hasAny(q, SENDER_WORDS)) {
            val org = lines.filter { ORG_RES.containsMatchIn(it) }.distinct().take(3)
            val head = lines.take(4).filter { it !in org }.take(3)
            val found = (org + head).distinct().take(4)
            return if (found.isEmpty()) "I couldn't tell who sent this — the letterhead may be a logo or image, which I can't read yet."
            else "This looks like it's from:\n" + found.joinToString("\n") { "• $it" } + "\n\n— on-device ($modelName)."
        }
        if (hasAny(q, CONTACT_WORDS)) {
            val phones = PHONE_RES.findAll(ocr).map { it.value.trim() }.distinct().take(4).toList()
            val emails = EMAIL_RES.findAll(ocr).map { it.value }.distinct().take(4).toList()
            val context = matchingLines(lines, listOf("tel", "phone", "anruf", "e-mail", "email", "anschrift", "adresse", "address", "straße", "strasse", "@"))
            return if (phones.isEmpty() && emails.isEmpty() && context.isEmpty())
                "I couldn't find contact details in the text. They may be in a logo/image header — try photographing that part closely."
            else "Contact details I found:\n" +
                (phones.joinToString("\n") { "• $it" } + "\n") +
                (emails.joinToString("\n") { "• $it" } + "\n") +
                (if (context.isNotEmpty()) context.joinToString("\n") { "• $it" } else "") +
                "\n\n— on-device ($modelName)."
        }
        if (hasAny(q, REF_WORDS)) {
            val refs = REF_RES.findAll(ocr).map { it.value }.distinct().take(6).toList()
            return if (refs.isEmpty()) "I couldn't find a reference/account number in the text."
            else "Reference numbers I found:\n" + refs.joinToString("\n") { "• $it" } + "\n\n— on-device ($modelName)."
        }
        if (hasAny(q, DOSE_WORDS)) {
            val doses = DOSE_RES.findAll(ocr).map { it.value }.distinct().take(6).toList()
            val context = matchingLines(lines, listOf("einnahme", "dosier", "täglich", "morgens", "mittags", "abends", "dose", "daily", "morning", "evening", "tablet", "kapsel"))
            return if (doses.isEmpty() && context.isEmpty())
                "I couldn't find dosage info in the text. Never guess doses — check with your pharmacist.\n\n— on-device ($modelName)."
            else "Dosage info I found:\n" +
                (if (doses.isNotEmpty()) doses.joinToString("\n") { "• $it" } + "\n" else "") +
                (if (context.isNotEmpty()) context.joinToString("\n") { "• $it" } else "") +
                "\nConfirm this against your prescription — don't guess doses.\n\n— on-device ($modelName)."
        }
        // Generic keyword search across the document lines.
        val keywords = q.split(Regex("[^a-zäöüß0-9]+")).filter { it.length >= 4 && it !in STOPWORDS }.distinct()
        if (keywords.isNotEmpty()) {
            val hits = lines.filter { line -> keywords.any { it in line.lowercase() } }.distinct().take(5)
            if (hits.isNotEmpty()) {
                return "Here's what the document says about that:\n" + hits.joinToString("\n") { "• $it" } +
                    "\n\nAsk another follow-up, or say \"summarize\" for the full breakdown.\n\n— on-device ($modelName)."
            }
        }
        return "I couldn't find that specifically in the text I could read. What I did pick up:\n\n" + keyFacts(ocr) +
            "\n\nTry rephrasing, or send a sharper photo. Say \"summarize\" for the full breakdown."
    }

    private fun isYesNo(question: String): Boolean {
        val first = question.trim().lowercase().split(Regex("\\s+")).firstOrNull() ?: return false
        return first in YESNO_FIRST
    }

    /** Verdict-first answers for yes/no questions ("Is this for an appointment?"). */
    private fun answerYesNo(q: String, lines: List<String>, ocr: String, modelName: String): String {
        val foot = "\n\n— on-device ($modelName)."
        val what = oneLineSummary(ocr, guessTitle(ocr)).replaceFirstChar { it.lowercase() }
        if (hasAny(q, APPT_WORDS)) {
            val ev = (matchingLines(lines, APPT_WORDS) + matchingLines(lines, listOf("patient", "uhr", "uhrzeit"))).distinct().take(4)
            val dates = DATE_RES.findAll(ocr).map { it.value }.distinct().take(2).toList()
            return if (ev.isNotEmpty() || dates.isNotEmpty())
                "Yes — this is about an appointment:\n" +
                    (if (ev.isNotEmpty()) ev.joinToString("\n") { "• $it" } + "\n" else "") +
                    (if (dates.isNotEmpty()) "Dates mentioned: " + dates.joinToString(", ") else "") + foot
            else "Doesn't look like an appointment — this looks like $what\n\n" + keyFacts(ocr) + foot
        }
        if (hasAny(q, AMOUNT_WORDS) || "bill" in q || "rechnung" in q) {
            val amounts = AMOUNT_RES.findAll(ocr).map { it.value }.distinct().take(3).toList()
            val payLines = matchingLines(lines, listOf("zahl", "pay", "betrag", "fällig", "mahnung", "due", "invoice", "rechnung"))
            return if (amounts.isNotEmpty() || payLines.isNotEmpty())
                "Yes — money is asked for:\n" +
                    (if (amounts.isNotEmpty()) amounts.joinToString("\n") { "• $it" } + "\n" else "") +
                    payLines.take(3).joinToString("\n") { "• $it" } + foot
            else "No payment asked for as far as I can read — this looks like $what$foot"
        }
        if (hasAny(q, DOSE_WORDS) || "take" in q || "einnehm" in q) {
            val ev = matchingLines(lines, listOf("einnahme", "dosier", "täglich", "morgens", "mittags", "abends", "mg", "tablet", "kapsel", "dose", "daily", "take"))
            return if (ev.isNotEmpty()) "Yes — dosage instructions found:\n" + ev.take(4).joinToString("\n") { "• $it" } +
                "\nConfirm against your prescription — don't guess doses.$foot"
            else "I couldn't find dosage instructions in the text — don't guess, check with your pharmacist.$foot"
        }
        if (hasAny(q, CONTACT_WORDS)) {
            val phones = PHONE_RES.findAll(ocr).map { it.value.trim() }.distinct().take(3).toList()
            val emails = EMAIL_RES.findAll(ocr).map { it.value }.distinct().take(3).toList()
            return if (phones.isNotEmpty() || emails.isNotEmpty())
                "Yes:\n" + (phones + emails).joinToString("\n") { "• $it" } + foot
            else "No contact details found in the readable text — they may be in a logo or image header.$foot"
        }
        if (hasAny(q, SENDER_WORDS)) {
            val named = q.split(Regex("[^a-zäöüß0-9]+"))
                .filter { it.length >= 4 && it !in STOPWORDS && it !in SENDER_WORDS }.distinct()
            val who = (lines.filter { ORG_RES.containsMatchIn(it) }.distinct().take(3) + lines.take(4)).distinct().take(4)
            if (named.isNotEmpty() && who.isNotEmpty()) {
                return if (named.any { n -> who.any { n in it.lowercase() } })
                    "Yes — this is from:\n" + who.joinToString("\n") { "• $it" } + foot
                else "Doesn't look like it — this is from:\n" + who.joinToString("\n") { "• $it" } + foot
            }
            return if (who.isNotEmpty()) "This is from:\n" + who.joinToString("\n") { "• $it" } + foot
            else "I couldn't tell who sent this — the letterhead may be a logo or image.$foot"
        }
        // Generic yes/no: search the question's keywords in the text.
        val keywords = q.split(Regex("[^a-zäöüß0-9]+")).filter { it.length >= 4 && it !in STOPWORDS }.distinct()
        val hits = if (keywords.isNotEmpty()) {
            lines.filter { line -> keywords.any { it in line.lowercase() } }.distinct().take(4)
        } else emptyList()
        return if (hits.isNotEmpty())
            "Yes — the document says:\n" + hits.joinToString("\n") { "• $it" } + foot
        else "I couldn't find that in the text I could read. What I did pick up:\n\n" + keyFacts(ocr) +
            "\n\nTry rephrasing, or say \"summarize\" for the full breakdown."
    }

    private fun keyFacts(ocr: String): String {
        val amounts = AMOUNT_RES.findAll(ocr).map { it.value }.distinct().take(3).toList()
        val dates = DATE_RES.findAll(ocr).map { it.value }.distinct().take(3).toList()
        val refs = REF_RES.findAll(ocr).map { it.value }.distinct().take(3).toList()
        val sb = StringBuilder()
        if (amounts.isNotEmpty()) sb.appendLine("Amounts: " + amounts.joinToString(", "))
        if (dates.isNotEmpty()) sb.appendLine("Dates: " + dates.joinToString(", "))
        if (refs.isNotEmpty()) sb.appendLine("References: " + refs.joinToString(", "))
        if (sb.isEmpty()) sb.appendLine("(no amounts, dates, or reference numbers recognized)")
        return sb.toString().trim()
    }

    /** Catches runaway loops the sampler penalty didn't stop. */
    private fun isDegenerate(response: String): Boolean {
        val words = response.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 60) return false
        val seen = HashSet<String>()
        var repeats = 0
        for (i in 0..words.size - 6) {
            val gram = words.subList(i, i + 6).joinToString(" ")
            if (!seen.add(gram)) {
                repeats++
                if (repeats >= 5) return true
            }
        }
        return false
    }

    private fun matchingLines(lines: List<String>, needles: List<String>): List<String> {
        return lines.filter { line -> needles.any { it in line.lowercase() } }.distinct().take(4)
    }

    private fun hasAny(q: String, words: List<String>) = words.any { w -> Regex("\\b$w\\b").containsMatchIn(q) }

    private fun guessTitle(ocr: String): String {
        val first = ocr.lineSequence().map { it.trim() }.firstOrNull { it.length > 3 } ?: "Document"
        return first.take(80)
    }

    private fun oneLineSummary(ocr: String, title: String): String {
        val lower = ocr.lowercase()
        return when {
            "invoice" in lower || "amount due" in lower || "bill" in lower || "rechnung" in lower -> "A bill/invoice asking for payment."
            "appointment" in lower || "clinic" in lower || "doctor" in lower || "termin" in lower || "arzt" in lower || "praxis" in lower -> "A medical letter/appointment notice."
            "prescription" in lower || "dosage" in lower || "mg" in lower || "einnahme" in lower -> "Medication instructions."
            "lease" in lower || "landlord" in lower || "deposit" in lower || "miete" in lower || "kaution" in lower || "vermieter" in lower -> "A rental/lease-related document."
            "warranty" in lower || "receipt" in lower || "return" in lower || "garantie" in lower || "quittung" in lower -> "A receipt/warranty proof."
            "tax" in lower || "hmrc" in lower || "irs" in lower || "steuer" in lower || "finanzamt" in lower -> "A tax letter."
            "court" in lower || "fine" in lower || "penalty" in lower || "gericht" in lower || "bußgeld" in lower || "mahnung" in lower -> "An official notice, possibly a fine — check deadline carefully."
            else -> "Paperwork titled \"$title\". See transcript below."
        }
    }

    /** Concrete action steps quoted from the document itself — never generic filler. */
    private fun buildSteps(ocr: String, mode: String): List<String> {
        if (mode == "med") {
            val steps = mutableListOf<String>()
            val doses = DOSE_RES.findAll(ocr).map { it.value }.distinct().take(2).toList()
            val sched = matchingLines(ocrLines(ocr), listOf(
                "morgens", "mittags", "abends", "täglich", "wöchentlich",
                "vor dem", "nach dem", "morning", "evening", "daily", "nüchtern")).take(2)
            if (doses.isNotEmpty()) {
                steps += "Take exactly this: " + doses.joinToString(", ") +
                    (if (sched.isNotEmpty()) " — " + sched.joinToString("; ") else "") + "."
            }
            val appt = matchingLines(ocrLines(ocr), listOf("termin", "appointment", "uhr", "praxis", "arzt")).take(1)
            if (appt.isNotEmpty()) steps += "Go here: ${appt.first()} — bring insurance card + medication list."
            steps += "Add it to the Meds tab so you don't forget."
            steps += "Anything unclear? Ask your pharmacist — don't guess doses."
            return steps.take(4)
        }
        if (mode == "proof") {
            return listOf(
                "Kept with timestamp as rental/warranty evidence.",
                "Also save the original receipt/contract with it.",
                "Back it up (Files → ClearKit proofs folder)."
            )
        }
        val steps = mutableListOf<String>()
        val amounts = AMOUNT_RES.findAll(ocr).map { it.value }.distinct().take(2).toList()
        val dates = DATE_RES.findAll(ocr).map { it.value }.distinct().take(2).toList()
        if (amounts.isNotEmpty()) {
            val ibans = IBAN_RES.findAll(ocr).map { it.value.trim() }.distinct().take(1).toList()
            val orgs = ocrLines(ocr).filter { ORG_RES.containsMatchIn(it) }.distinct().take(1)
            val refs = REF_RES.findAll(ocr).map { it.value }.distinct().take(1).toList()
            var s = "Pay ${amounts.first()}"
            if (ibans.isNotEmpty()) s += " to ${ibans.first()}"
            else if (orgs.isNotEmpty()) s += " to ${orgs.first().take(60)}"
            if (dates.isNotEmpty()) s += ", no later than ${dates.first()}"
            if (refs.isNotEmpty()) s += " (use reference ${refs.first()})"
            steps += s + "."
        }
        val actions = ocrLines(ocr).filter { line ->
            ACTION_VERBS.any { v -> Regex("\\b$v\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) }
        }.distinct().take(2)
        actions.forEach { steps += "The letter says: \"${it.take(140)}\"" }
        val phones = PHONE_RES.findAll(ocr).map { it.value.trim() }.distinct().take(1).toList()
        if (phones.isNotEmpty()) steps += "Anything unclear? Call ${phones.first()} with your reference number ready."
        if (steps.isEmpty()) {
            steps += "Follow the instructions quoted in the transcript below, keep a photo copy."
        }
        steps += "File it: drawer → Photo-proof, or keep the paper 12 months."
        return steps.take(4)
    }

    private fun ocrLines(ocr: String): List<String> =
        ocr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    companion object {
        val DATE_RES = Regex("""\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b|\b\d{1,2}\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4}\b""", RegexOption.IGNORE_CASE)
        val AMOUNT_RES = Regex("""[£$€]\s?\d[\d,]*(\.\d{2})?|\b\d[\d.]*,\d{2}\s?(€|EUR|Euro)\b""")
        val REF_RES = Regex("""\b(ref|reference|account|customer|policy|invoice|kunden|vertrags|rechnungs|akten)\s*(no|number|nr|nummer|#)?\s*[:\-]?\s*[A-Z0-9\-/]{4,}\b""", RegexOption.IGNORE_CASE)
        val URGENT_RES = Regex("""urgent|final notice|overdue|immediately|penalty|eviction|cut off|deadline|mahnung|letzte|fristlos|vollstreck""", RegexOption.IGNORE_CASE)
        val ORG_RES = Regex("""gmbh|\bag\b| ug\b|ltd|inc|e\.v\.|amt\b|behörde|stadt\b|kasse|versicherung|bank\b|finanzamt|gericht|praxis|klinik""", RegexOption.IGNORE_CASE)
        val PHONE_RES = Regex("""\+?[\d][\d\s/().-]{5,}\d""")
        val IBAN_RES = Regex("""\b[A-Z]{2}\d{2} ?\d{4} ?\d{4} ?\d{4} ?\d{4} ?(\d{4} ?)?\b""")
        val ACTION_VERBS = listOf(
            "pay", "zahlen", "überweis", "call", "anruf", "rückruf", "rufen",
            "send", "senden", "schicken", "zusenden", "einreichen", "bringen",
            "mitbringen", "vorlegen", "sign", "unterschr", "ausfüllen",
            "erscheinen", "vorsprechen", "beantragen", "kündigen", "stornieren",
            "widerrufen", "bestätigen", "zurücksenden", "melden", "übermitteln"
        )
        val EMAIL_RES = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        val DOSE_RES = Regex("""\b\d+\s?(mg|g|ml|µg|ie|tablette|kapsel|tropfen|hub)\b""", RegexOption.IGNORE_CASE)

        val QUESTION_WORDS = setOf(
            "what", "when", "who", "whom", "whose", "how", "where", "why", "which",
            "is", "are", "was", "were", "do", "does", "did", "can", "could", "should",
            "was", "wann", "wer", "wen", "wie", "wo", "warum", "welche", "welcher", "welches",
            "ist", "sind", "war", "kann", "muss", "soll", "gibt", "steht", "wieviel", "wie viel"
        )
        val SUMMARY_TRIGGERS = listOf(
            "what is this", "was ist das", "summar", "zusammenfass", "overview", "überblick",
            "what do i do", "was muss ich", "explain", "erklär"
        )
        val VISUAL_WORDS = listOf(
            "color", "colour", "farbe", "look like", "sieht aus", "picture", "bild", "photo",
            "foto", "stamp", "stempel", "signature", "unterschrift", "logo", "handwriting",
            "handschrift", "layout", "添"
        )
        val AMOUNT_WORDS = listOf(
            "much", "amount", "pay", "cost", "price", "total", "owe", "charge",
            "viel", "betrag", "zahl", "kost", "preis", "summe", "gesamt", "schulden", "mahngeb"
        )
        val DATE_WORDS = listOf(
            "when", "date", "deadline", "due", "appointment", "frist", "termin",
            "fällig", "datum", "stichtag", "spätestens", "bis wann"
        )
        val SENDER_WORDS = listOf(
            "who", "from", "sender", "sent", "company", "wer", "von wem", "absender", "firma"
        )
        val CONTACT_WORDS = listOf(
            "contact", "phone", "call", "email", "address", "kontakt", "telefon", "anruf",
            "adresse", "anschrift", "nummer", "erreich"
        )
        val REF_WORDS = listOf(
            "reference", "account", "invoice", "number", "nummer", "referenz", "kunden",
            "vertrag", "rechnung", "aktenzeichen", "kundennummer"
        )
        val DOSE_WORDS = listOf(
            "dose", "dosage", "much", "often", "take", "dosis", "einnahme", "einnehmen",
            "häufig", "oft", "tablette", "medication", "medikament"
        )
        val YESNO_FIRST = setOf(
            "is", "are", "was", "were", "do", "does", "did", "can", "could",
            "will", "would", "should", "has", "have",
            "ist", "sind", "war", "kann", "muss", "soll", "geht", "handelt", "gibt", "brauche", "muss"
        )
        val APPT_WORDS = listOf(
            "termin", "appointment", "arzt", "praxis", "doktor", "doctor",
            "sprechstunde", "untersuchung", "check-up", "checkup", "klinik", "hospital"
        )
        val STOPWORDS = setOf(
            "what", "when", "who", "how", "where", "does", "this", "that", "with",
            "about", "please", "tell", "does", "there", "their", "from",
            "bitte", "dass", "diese", "dieser", "einen", "einem", "einer", "meine",
            "steht", "sagt", "sagen", "kannst", "geht", "mich", "noch", "oder", "und", "die", "der", "das"
        )
    }
}
