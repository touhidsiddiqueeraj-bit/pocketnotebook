package com.pocketnotebook.app.core.llm

/**
 * GBNF grammars for grammar-constrained decoding (llama.cpp sampler chain).
 * All follow the same string/JSON primitives; only shapes differ.
 * Strings exclude quotes, backslashes and control chars — models are prompted
 * accordingly (single-line strings, no emojis).
 */
object Grammars {

    private const val STRING = """string ::= "\"" chars "\"" ws
chars ::= char*
char ::= [^"\\\x00-\x1F\x7F]
ws ::= [ \t\n]{0,16}"""

    /** {"cards":[{"front":"...","back":"..."}]} */
    val flashcards = """
root ::= "{" ws "\"cards\"" ws ":" ws "[" ws (card ("," ws card)*)? "]" ws "}" ws
card ::= "{" ws "\"front\"" ws ":" ws string "," ws "\"back\"" ws ":" ws string "}" ws
$STRING
""".trim()

    /** {"questions":[{"question":"...","options":["...","...","...","..."],"answer":0}]} */
    val quiz = """
root ::= "{" ws "\"questions\"" ws ":" ws "[" ws (question ("," ws question)*)? "]" ws "}" ws
question ::= "{" ws "\"question\"" ws ":" ws string "," ws "\"options\"" ws ":" ws "[" ws string "," ws string "," ws string "," ws string "]" ws "," ws "\"answer\"" ws ":" ws answer "}" ws
answer ::= [0-3]
$STRING
""".trim()

    /** {"title":"...","children":[{"title":"...","children":[...]}]} */
    val mindMap = """
root ::= node
node ::= "{" ws "\"title\"" ws ":" ws string ("," ws "\"children\"" ws ":" ws nodes)? "}" ws
nodes ::= "[" ws (node ("," ws node)*)? "]" ws
$STRING
""".trim()

    /** {"title":"...","segments":[{"speaker":"A","text":"..."}]} */
    val overviewScript = """
root ::= "{" ws "\"title\"" ws ":" ws string "," ws "\"segments\"" ws ":" ws "[" ws (segment ("," ws segment)*)? "]" ws "}" ws
segment ::= "{" ws "\"speaker\"" ws ":" ws ("\"A\"" | "\"B\"") "," ws "\"text\"" ws ":" ws string "}" ws
$STRING
""".trim()
}
