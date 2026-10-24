"""ログに秘密（トークン・署名・nonce・生データ）を出さないための整形とフィルタ。"""

from __future__ import annotations

import logging
import re

_PATTERNS = [
    (re.compile(r"jrat1_[A-Za-z0-9_\-]{8,}"), "jrat1_***"),
    (re.compile(r"(X-Signature['\"]?\s*[:=]\s*['\"]?)[0-9a-fA-F]{16,}", re.IGNORECASE), r"\1***"),
    (re.compile(r"(X-Nonce['\"]?\s*[:=]\s*['\"]?)[A-Za-z0-9_\-]{8,}", re.IGNORECASE), r"\1***"),
    (re.compile(r"(token['\"]?\s*[:=]\s*['\"]?)[A-Za-z0-9_\-+/=]{12,}", re.IGNORECASE), r"\1***"),
    (re.compile(r"(bytesB64['\"]?\s*[:=]\s*['\"]?)[A-Za-z0-9+/=]{16,}", re.IGNORECASE), r"\1***"),
]


def redact(text: str) -> str:
    for pattern, repl in _PATTERNS:
        text = pattern.sub(repl, text)
    return text


class RedactingFilter(logging.Filter):
    """最後の砦。呼び出し側が秘密を渡さないことが前提で、うっかり混入したときに伏せる。"""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:
            record.msg, record.args = "<unformattable log>", ()
            return True
        redacted = redact(message)
        if redacted != message:
            record.msg, record.args = redacted, ()
        if record.exc_info:
            # 例外メッセージに秘密が入り得るので、型名だけにする
            exc = record.exc_info[1]
            record.exc_info = None
            record.msg = f"{record.msg} [{type(exc).__name__}]"
        return True
