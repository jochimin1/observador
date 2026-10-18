package com.ubers.observador.domain.backup

/**
 * A small JSON reader and writer for the backup's two text files (1.31.0). Pure Kotlin so the
 * format is tested on the JVM, and strict on purpose: a restore reads a file the driver picked,
 * so anything unexpected is an error, never a guess. Maps keep their order; whole numbers read as
 * Long, the rest as Double; nesting is capped.
 */
object MiniJson {

    class ParseException(message: String) : Exception(message)

    private const val MAX_DEPTH = 32

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipSpace()
        val v = p.value(0)
        p.skipSpace()
        if (!p.atEnd()) throw ParseException("Texto sobrante en la posición ${p.pos}")
        return v
    }

    fun parseOrNull(text: String): Any? = try { parse(text) } catch (e: ParseException) { null }

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is Float -> append(sb, v.toDouble())
            is Double -> {
                require(v.isFinite()) { "JSON no admite NaN ni infinito" }
                sb.append(v.toString())
            }
            is String -> string(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    append(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, x)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Tipo no admitido en JSON: ${v::class.java.name}")
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipSpace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("Demasiado anidado")
            if (atEnd()) throw ParseException("Fin inesperado")
            return when (s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Valor no válido en la posición $pos")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipSpace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipSpace()
                if (peek() != '"') throw ParseException("Se esperaba un nombre en la posición $pos")
                val key = str()
                skipSpace()
                if (peek() != ':') throw ParseException("Falta ':' en la posición $pos")
                pos++
                skipSpace()
                if (out.containsKey(key)) throw ParseException("Nombre repetido: $key")
                out[key] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseException("Se esperaba ',' o '}' en la posición $pos")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipSpace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipSpace()
                out += value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseException("Se esperaba ',' o ']' en la posición $pos")
                }
            }
        }

        private fun peek(): Char = if (atEnd()) throw ParseException("Fin inesperado") else s[pos]

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Texto sin cerrar")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw ParseException("Texto sin cerrar")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Escape \\u incompleto")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: throw ParseException("Escape \\u no válido")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Escape no válido: \\$e")
                        }
                    }
                    c.code < 0x20 -> throw ParseException("Carácter de control sin escapar")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            while (pos < s.length && s[pos] in "+-0123456789.eE") pos++
            val t = s.substring(start, pos)
            if (t.isEmpty()) throw ParseException("Valor no válido en la posición $start")
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                val d = t.toDoubleOrNull() ?: throw ParseException("Número no válido: $t")
                if (!d.isFinite()) throw ParseException("Número fuera de rango: $t")
                d
            } else {
                t.toLongOrNull() ?: throw ParseException("Número no válido: $t")
            }
        }
    }
}
