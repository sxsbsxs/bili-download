package com.example.bilidl.common.content

/**
 * Minimal, bounds-checked decoder for Bilibili DmSegMobileReply protobuf data.
 * It reads only the fields needed for export and safely skips unknown fields so
 * new server fields do not break older app builds.
 */
object BiliDanmakuProtoParser {
    private class Reader(
        private val bytes: ByteArray,
        private var position: Int = 0,
        private val limit: Int = bytes.size
    ) {
        val exhausted: Boolean get() = position >= limit

        fun readVarint(): Long {
            var result = 0L
            for (index in 0 until 10) {
                if (position >= limit) throw IllegalArgumentException("protobuf varint 被截断")
                val value = bytes[position++].toInt() and 0xff
                if (index == 9 && value > 1) {
                    throw IllegalArgumentException("protobuf varint 超出 64 位范围")
                }
                result = result or ((value and 0x7f).toLong() shl (index * 7))
                if ((value and 0x80) == 0) return result
            }
            throw IllegalArgumentException("protobuf varint 长度超过 10 字节")
        }

        fun readBytes(): ByteArray {
            val length = readVarint()
            if (length < 0 || length > (limit - position).toLong() || length > Int.MAX_VALUE) {
                throw IllegalArgumentException("protobuf 字段长度越界")
            }
            val end = position + length.toInt()
            val result = bytes.copyOfRange(position, end)
            position = end
            return result
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> advance(8)
                2 -> {
                    val length = readVarint()
                    if (length < 0 || length > (limit - position).toLong()) {
                        throw IllegalArgumentException("protobuf 字段长度越界")
                    }
                    advance(length.toInt())
                }
                5 -> advance(4)
                else -> throw IllegalArgumentException("不支持的 protobuf wire type：$wireType")
            }
        }

        private fun advance(count: Int) {
            if (count < 0 || count > limit - position) {
                throw IllegalArgumentException("protobuf 固定长度字段被截断")
            }
            position += count
        }
    }

    fun parse(data: ByteArray): List<BiliDanmaku> {
        val reader = Reader(data)
        val result = ArrayList<BiliDanmaku>()
        while (!reader.exhausted) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 0) throw IllegalArgumentException("protobuf 字段编号不能为 0")
            if (field == 1 && wire == 2) {
                val item = parseElement(reader.readBytes())
                if (item != null) result += item
            } else {
                reader.skip(wire)
            }
        }
        return result
    }

    private fun parseElement(data: ByteArray): BiliDanmaku? {
        val reader = Reader(data)
        var id = 0L
        var progressMs = -1L
        var mode = 1
        var fontSize = 25
        var color = 0xffffffL
        var senderHash = ""
        var content = ""
        var sentAt = 0L
        var pool = 0

        while (!reader.exhausted) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 0) throw IllegalArgumentException("弹幕 protobuf 字段编号不能为 0")
            when {
                field == 1 && wire == 0 -> id = reader.readVarint()
                field == 2 && wire == 0 -> progressMs = reader.readVarint()
                field == 3 && wire == 0 -> mode = reader.readVarint().toInt()
                field == 4 && wire == 0 -> fontSize = reader.readVarint().toInt()
                field == 5 && wire == 0 -> color = reader.readVarint()
                field == 6 && wire == 2 -> senderHash = reader.readString()
                field == 7 && wire == 2 -> content = reader.readString()
                field == 8 && wire == 0 -> sentAt = reader.readVarint()
                field == 11 && wire == 0 -> pool = reader.readVarint().toInt()
                else -> reader.skip(wire)
            }
        }

        if (progressMs < 0 || content.isBlank()) return null
        return BiliDanmaku(
            id = id,
            progressMs = progressMs,
            mode = mode,
            fontSize = fontSize,
            color = color,
            senderHash = senderHash,
            content = content,
            sentAtEpochSeconds = sentAt,
            pool = pool
        )
    }
}
