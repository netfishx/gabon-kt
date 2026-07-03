package com.gabon.identity.internal

import java.util.Locale

/** username 规范化(spec §5.1 定案):注册与登录唯一入口,唯一约束落在 canonical 列。 */
object UsernameCanonicalizer {
    fun canonicalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
}
