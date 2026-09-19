package me.rerere.rikkahub.sticker

/**
 * The one-shot prompt used when a sticker is imported.
 *
 * Separate from the OCR prompt on purpose, and not a variation of it. OCR is asked for a faithful
 * transcription of what is on screen so a text task can proceed; this is asked for *meaning* —
 * what the picture expresses and when somebody would reach for it. Sharing one prompt would force
 * one of the two to be wrong, and the failure would be invisible: a sticker described as "一张图，
 * 上面有文字：哈哈哈哈" is technically accurate and useless for choosing it later.
 *
 * The output contract is a small JSON object rather than prose because the result is stored as
 * searchable fields, and because prose has to be re-parsed by something less reliable than a
 * parser. [StickerVisionParser] still tolerates the ways models deviate from it.
 */
object StickerPrompt {
    const val SYSTEM: String =
        "你是一个表情包资料整理助手。你会看到一张表情包图片，需要为它写一份供以后检索和选用的说明。\n" +
            "\n" +
            "只输出一个 JSON 对象，不要输出 JSON 以外的任何内容：\n" +
            "{\"description\": \"...\", \"tags\": [\"...\", \"...\"]}\n" +
            "\n" +
            "description：一到两句中文。说明画面里是什么、表达什么情绪、适合在什么语境下使用。" +
            "写成\"以后如何理解这个表情\"的说明，例如：一只猫缩成一团，看起来委屈又有点生气，" +
            "适合表达\"我不开心但又想被哄\"。\n" +
            "tags：${StickerTags.MIN_PROMPTED_TAGS} 到 ${StickerTags.MAX_TAGS} 个中文短标签，" +
            "用于以后检索，例如：委屈、生气、猫猫、撒娇。每个标签是词或短语，不要写成整句，不要重复。\n" +
            "\n" +
            "只描述你确实看到的内容，不要虚构图片里不存在的文字、人物或情节。" +
            "如果图片里有文字，可以在 description 里概括它的意思，但不要逐字全量转写。" +
            "不要输出 markdown 代码块。"
}
