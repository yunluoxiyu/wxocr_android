package com.yunluo.wxocr.knowledge

data class Question(
    val id: String = "",
    val question: String = "",
    val answer: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "_id" to mapOf("\$oid" to id),
        "question" to question,
        "answer" to answer,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any>): Question {
            val idMap = map["_id"] as? Map<*, *>
            val id = idMap?.get("\$oid") as? String ?: ""
            return Question(
                id = id,
                question = map["question"] as? String ?: "",
                answer = map["answer"] as? String ?: "",
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}
