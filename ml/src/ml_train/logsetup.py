"""構造化ログ（1 行 1 JSON、標準出力）。秘密・値の長い内容を出さない（呼び出し側が固定語彙と数値だけを渡す）。"""

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

_LOGGER_NAME = "ml_train"
_SENSITIVE = ("password", "secret", "token", "signing_key", "private")


class _JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
            "level": record.levelname,
            "event": record.getMessage(),
        }
        fields = getattr(record, "fields", None)
        if isinstance(fields, dict):
            for k, v in fields.items():
                payload[k] = "***" if any(s in k.lower() for s in _SENSITIVE) else v
        return json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)


def setup(stream: Any = None, level: int = logging.INFO) -> logging.Logger:
    logger = logging.getLogger(_LOGGER_NAME)
    logger.handlers.clear()
    handler = logging.StreamHandler(stream if stream is not None else sys.stdout)
    handler.setFormatter(_JsonFormatter())
    logger.addHandler(handler)
    logger.setLevel(level)
    logger.propagate = False
    return logger


def log(event: str, level: int = logging.INFO, **fields: Any) -> None:
    logging.getLogger(_LOGGER_NAME).log(level, event, extra={"fields": fields})
